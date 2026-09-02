package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlinx.coroutines.CancellationException

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
     * A captured position, bundled with the URL of the document it was captured from.
     *
     * The URL is not optional decoration - a PR review caught that a prior revision compared
     * the *restoring* handle's current URL against itself (both reads happened at restore time,
     * on the freshly created handle, so the comparison was close to tautological and did not
     * verify anything about the ORIGINAL page). The URL that matters is the one the OLD handle
     * was on at capture time, which is what this bundles: [captureScrollOrNull] reads it off the
     * still-live handle in the same call that reads the position, and [awaitSettleAndApply]
     * waits for the NEW handle to actually reach this URL before ever touching height or scroll.
     */
    data class SavedScroll(val position: Position, val url: String)

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
     * Whether restoring [target] captured from [capturedUrl] is worth attempting at all.
     *
     * The origin is skipped ONLY when the URL has no fragment. A fragment URL (`#section`)
     * auto-scrolls there on load by the browser's own default behaviour - if the user had
     * manually scrolled back to the true top before hibernating, (0,0) is a real captured
     * position that must override that default, not a value indistinguishable from "nothing was
     * captured". Pulled out as its own pure function - see [awaitSettleAndApply]'s KDoc for why
     * that function no longer makes this call itself.
     *
     * Takes the CAPTURED url deliberately, not whatever the restoring handle currently reports -
     * see [SavedScroll]'s KDoc for why comparing a handle's URL against itself at restore time
     * verifies nothing.
     */
    internal fun shouldAttemptRestore(
        target: Position,
        capturedUrl: String,
    ): Boolean = !target.isOrigin || capturedUrl.contains('#')

    /**
     * Runs a bounded poll loop until [check] returns true, or the cap is hit. Extracted once
     * three separate phases in [awaitSettleAndApply] needed the identical shape (poll, check,
     * delay, bounded) - the navigation wait, the height-settle wait, and (implicitly) the reapply
     * count. A real `for`/`break`, never `repeat(N) { ...; return@repeat }` - see this file's own
     * history for why that distinction gets a whole paragraph: `return@repeat` is `continue`, and
     * a loop that "exits early" via `continue` silently runs every remaining iteration anyway.
     */
    private suspend fun pollUntil(
        maxPolls: Int,
        pollDelayMs: Long,
        delay: suspend (Long) -> Unit,
        check: suspend () -> Boolean,
    ): Boolean {
        for (poll in 0 until maxPolls) {
            // onFailure rethrows CancellationException instead of swallowing it - runCatching is
            // Throwable-wide by default, and a cancellation caught here and NOT rethrown lets this
            // loop keep polling past the point its own coroutine was told to stop, which a PR
            // review correctly flagged: it recovered only because the injected `delay` used in
            // production is itself cancellable and rethrows - a fact the tests' `delay = {}` does
            // not share, so the real guarantee was never actually exercised.
            val result =
                runCatching { check() }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(false)
            if (result) return true
            delay(pollDelayMs)
        }
        return false
    }

    /**
     * Waits for the restoring handle to actually reach [expectedUrl], then for the page to stop
     * resizing, then applies [target], verifying and reapplying up to [reapplyAttempts] times.
     *
     * Every browser operation is injected ([readUrl]/[readHeight]/[applyScroll]/[readPosition]/
     * [delay]) rather than taking a `BrowserHandle` directly, mirroring
     * [TabHibernation.awaitQuiet]'s `probe`/`onWait` shape - for the same reason: the retry and
     * early-exit LOGIC is what a regression is most likely to break, and that logic is what this
     * makes testable without a live renderer.
     *
     * **The navigation-wait phase is not optional colour - it is the fix for a bug a PR review
     * caught.** A freshly (re)created `BrowserHandle` starts on `about:blank`/empty while its own
     * `createBrowser` navigation is still in flight. Skipping straight to the height-settle loop
     * on THAT document reports `settled: true` within one or two polls - long before the real
     * page exists - and every subsequent `applyScroll` then lands on the wrong document (or is
     * wrongly skipped once the real navigation finally does commit, if the caller was comparing
     * a URL read at that same moment against itself). Waiting for `readUrl() == expectedUrl`
     * FIRST is what makes every later phase actually operate on the page whose scroll was
     * captured, not on whatever the handle happened to report when this was called.
     *
     * @return true if the navigation wait AND the height-settle both completed within their caps;
     *   false if either was hit first. A navigation-wait timeout means nothing here is safe to
     *   restore onto and the function returns immediately, without attempting height-settle or
     *   any apply at all - unlike an unsettled height (still worth one attempt on whatever the
     *   page is now), landing on the wrong document is not worth attempting anything against.
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
        expectedUrl: String,
        readUrl: suspend () -> String?,
        readHeight: suspend () -> String?,
        applyScroll: suspend () -> Unit,
        readPosition: suspend () -> Position?,
        delay: suspend (Long) -> Unit,
        maxNavigationWaitPolls: Int = 20,
        navigationWaitPollMs: Long = 150L,
        maxSettlePolls: Int = 20,
        settlePollMs: Long = 150L,
        reapplyAttempts: Int = 4,
        reapplyDelayMs: Long = 300L,
    ): Boolean {
        val navigated = pollUntil(maxNavigationWaitPolls, navigationWaitPollMs, delay) { readUrl() == expectedUrl }
        if (!navigated) return false

        var previousHeight: String? = null
        val settled =
            pollUntil(maxSettlePolls, settlePollMs, delay) {
                val height = readHeight()
                val stable = height != null && height == previousHeight
                previousHeight = height
                stable
            }

        repeat(reapplyAttempts) { attempt ->
            // Re-verified on every attempt, not just once before the loop: a redirect landing
            // AFTER the navigation-wait phase already passed (the page that loaded, then bounced
            // elsewhere - a login/session check is a real example on an EMR-class site) must not
            // have the original document's position applied to whatever replaced it.
            val stillOnExpectedPage =
                runCatching { readUrl() }.onFailure { if (it is CancellationException) throw it }.getOrNull() == expectedUrl
            if (stillOnExpectedPage) {
                runCatching { applyScroll() }.onFailure { if (it is CancellationException) throw it }
            }
            delay(reapplyDelayMs)
            val landed = runCatching { readPosition() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            // A bare `return` here is a non-local return out of awaitSettleAndApply - repeat()
            // is inline, so this exits the whole function, not just this iteration. That is the
            // "break", correctly this time; `return@repeat` would be the same mistake again.
            if (landed == target || attempt == reapplyAttempts - 1) return settled
        }
        return settled
    }
}
