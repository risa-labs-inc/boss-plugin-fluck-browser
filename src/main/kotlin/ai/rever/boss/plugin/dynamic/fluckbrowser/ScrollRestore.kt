package ai.rever.boss.plugin.dynamic.fluckbrowser

/**
 * Captures and restores window scroll position across a hibernation wake.
 *
 * Zoom already survives hibernation independently, through [ai.rever.boss.plugin.api.ZoomSettingsProvider]'s
 * per-domain persistence - unrelated to this feature entirely. The navigation-entry list already
 * survives too: it lives on [FluckBrowserTabData], not on the disposed [BrowserHandle]. Scroll
 * position is the one piece of "where you left off" that a hibernate/wake cycle actually drops,
 * and this is the whole fix for it.
 *
 * Deliberately NOT JSON. [TabHibernation.busyStateFromScriptResult] already documents the
 * quoting inconsistency across `executeJavaScript` implementations for a plain string; wrapping
 * two integers in a JSON object over that same uncertain marshalling buys nothing and adds an
 * escaping failure mode for free. `"x,y"` has no character that needs escaping and splits with
 * no parser.
 */
internal object ScrollRestore {
    /**
     * Reads the window's current scroll offset. `|| 0` guards a page where `scrollX`/`scrollY`
     * are `NaN` or undefined (some sandboxed or about: pages), so a capture failure reads as the
     * origin rather than throwing and losing the whole capture.
     */
    const val CAPTURE_JS: String =
        "(function(){try{" +
            "return Math.round(window.scrollX||0)+','+Math.round(window.scrollY||0);" +
            "}catch(e){return null;}})()"

    /** One window scroll offset. */
    data class Position(val x: Int, val y: Int) {
        /** The default for a freshly loaded page - restoring to it is a wasted round trip. */
        val isOrigin: Boolean get() = x == 0 && y == 0
    }

    /**
     * Parses [CAPTURE_JS]'s return value. [normalizeJsStringResult] handles the same quoting
     * variance [TabHibernation.busyStateFromScriptResult] and [CredentialSuggestions] already
     * cope with (some hosts wrap a returned string in `"..."`, some do not) - shared rather than
     * a third independent copy. Returns null for anything that is not exactly two integers -
     * a malformed capture must not silently restore to some OTHER position.
     */
    internal fun parseCapture(result: Any?): Position? {
        val raw = normalizeJsStringResult(result) ?: return null
        val parts = raw.split(",")
        if (parts.size != 2) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        return Position(x, y)
    }

    /** The script that applies a captured position to the live page. */
    internal fun restoreJs(position: Position): String = "window.scrollTo(${position.x}, ${position.y})"

    /**
     * Whether restoring [target] on [currentUrl] is worth attempting at all.
     *
     * The origin is skipped ONLY when the URL has no fragment. A fragment URL (`#section`)
     * auto-scrolls there on load by the browser's own default behaviour - if the user had
     * manually scrolled back to the true top before hibernating, (0,0) is a real captured
     * position that must override that default, not a value indistinguishable from "nothing was
     * captured". Pulled out as its own pure function - see [awaitSettleAndApply]'s KDoc for why
     * that function no longer makes this call itself.
     */
    internal fun shouldAttemptRestore(
        target: Position,
        currentUrl: String,
    ): Boolean = !target.isOrigin || currentUrl.contains('#')

    /**
     * Waits for the page to stop resizing, then applies [target], verifying and reapplying up
     * to [reapplyAttempts] times.
     *
     * Every browser operation is injected ([readHeight]/[applyScroll]/[readPosition]/[delay])
     * rather than taking a `BrowserHandle` directly, mirroring [TabHibernation.awaitQuiet]'s
     * `probe`/`onWait` shape - for the same reason: the retry and early-exit LOGIC is what a
     * regression is most likely to break, and that logic is what this makes testable without a
     * live renderer. A prior version of this loop used `repeat(N) { ...; return@repeat }` to try
     * to exit early, which is `continue`, not `break` - the loop silently ran all N iterations
     * regardless of whether the condition it was checking had already been satisfied. That bug
     * shipped past a manual read of the code; `ScrollRestoreTest`'s call-count assertions on this
     * function are what a bug of that exact shape cannot pass unnoticed.
     *
     * @return true if height settled within [maxSettlePolls]; false if the cap was hit first
     *   (still worth attempting a restore on whatever the page is now, so this does not itself
     *   abort - the caller decides whether an unsettled restore is worth logging).
     *
     * **Known gap this cannot close:** height stability is a proxy for "the page stopped
     * changing", and it false-positives on a page whose content changes WITHOUT changing
     * document height - a virtualized list, a fixed-height app shell. There, the settle loop
     * reports done on the first or second poll, [applyScroll] runs immediately, and a route
     * change's own scroll-to-top (a common SPA pattern, fired on mount) can overwrite it a
     * moment later with no signal that it happened - this function still reports `settled: true`
     * for that outcome, because the height genuinely never moved. Closing this needs a second
     * proxy (a `MutationObserver` count, say) and was judged not worth the complexity for what
     * degrades, in the failure case, to a wrong scroll offset rather than lost data.
     */
    internal suspend fun awaitSettleAndApply(
        target: Position,
        readHeight: suspend () -> String?,
        applyScroll: suspend () -> Unit,
        readPosition: suspend () -> Position?,
        delay: suspend (Long) -> Unit,
        maxSettlePolls: Int = 20,
        settlePollMs: Long = 150L,
        reapplyAttempts: Int = 4,
        reapplyDelayMs: Long = 300L,
    ): Boolean {
        // No isOrigin shortcut here any more - a codex red-team finding on an earlier revision
        // caught that (0,0) is not always the natural landing position. A page loaded from a
        // fragment URL (#section) auto-scrolls to that element on its own; if the user had
        // manually scrolled back to the true top before hibernating, (0,0) is then a REAL
        // captured position that must be applied, not a default worth skipping. This function
        // has no URL, so it cannot make that call - see shouldAttemptRestore, which the caller
        // (restoreScrollOnSettle) checks before ever reaching here.
        var previousHeight: String? = null
        var settled = false
        for (poll in 0 until maxSettlePolls) {
            val height = runCatching { readHeight() }.getOrNull()
            if (height != null && height == previousHeight) {
                settled = true
                break
            }
            previousHeight = height
            delay(settlePollMs)
        }

        repeat(reapplyAttempts) { attempt ->
            runCatching { applyScroll() }
            delay(reapplyDelayMs)
            val landed = runCatching { readPosition() }.getOrNull()
            // A bare `return` here is a non-local return out of awaitSettleAndApply - repeat()
            // is inline, so this exits the whole function, not just this iteration. That is the
            // "break", correctly this time; `return@repeat` would be the same mistake again.
            if (landed == target || attempt == reapplyAttempts - 1) return settled
        }
        return settled
    }
}
