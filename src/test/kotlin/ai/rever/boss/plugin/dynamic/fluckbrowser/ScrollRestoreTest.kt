package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the parse/serialize half of scroll restore - the pure functions, not the
 * capture-before-dispose ordering or the settle-and-reapply timing, which need a live
 * [ai.rever.boss.plugin.browser.BrowserHandle] to exercise and are covered by manual QA instead.
 */
class ScrollRestoreTest {

    @Test
    fun `parses a plain x,y pair`() {
        assertEquals(ScrollRestore.Position(120, 4500), ScrollRestore.parseCapture("120,4500"))
    }

    @Test
    fun `tolerates the same quoting variance busyStateFromScriptResult already documents`() {
        assertEquals(ScrollRestore.Position(0, 0), ScrollRestore.parseCapture("\"0,0\""))
        assertEquals(ScrollRestore.Position(10, 20), ScrollRestore.parseCapture("  10,20  "))
    }

    @Test
    fun `negative offsets parse (RTL pages can report negative scrollX)`() {
        assertEquals(ScrollRestore.Position(-50, 0), ScrollRestore.parseCapture("-50,0"))
    }

    @Test
    fun `malformed capture returns null rather than a wrong position`() {
        assertNull(ScrollRestore.parseCapture(null))
        assertNull(ScrollRestore.parseCapture(""))
        assertNull(ScrollRestore.parseCapture("not-a-number,5"))
        assertNull(ScrollRestore.parseCapture("only-one-part"))
        assertNull(ScrollRestore.parseCapture("1,2,3"))
    }

    @Test
    fun `isOrigin is true only at exactly 0,0`() {
        assertTrue(ScrollRestore.Position(0, 0).isOrigin)
        assertTrue(!ScrollRestore.Position(0, 1).isOrigin)
        assertTrue(!ScrollRestore.Position(1, 0).isOrigin)
    }

    @Test
    fun `restoreJs applies exactly the captured position`() {
        assertEquals("window.scrollTo(120, 4500)", ScrollRestore.restoreJs(ScrollRestore.Position(120, 4500)))
    }

    @Test
    fun `CAPTURE_JS degrades to the origin rather than throwing on a NaN scroll read`() {
        // Guards against a regression that drops the `||0` fallback, which would make a capture
        // on a sandboxed or about: page throw and lose the whole hibernation event's scroll data,
        // not just fail to capture a position.
        assertTrue("scrollX||0" in ScrollRestore.CAPTURE_JS)
        assertTrue("scrollY||0" in ScrollRestore.CAPTURE_JS)
    }

    // region awaitSettleAndApply — the loop this class exists to make testable without a browser

    private val TARGET = ScrollRestore.Position(0, 4500)
    private val EXPECTED_URL = "https://example.com/page"

    /** Most tests below aren't about navigation timing - the real page is already there. */
    private val alreadyNavigated: suspend () -> String? = { EXPECTED_URL }

    /**
     * awaitSettleAndApply itself does NOT special-case the origin any more - a codex red-team
     * finding on an earlier revision caught that (0,0) is not always the natural landing
     * position (a fragment URL auto-scrolls elsewhere by default; a user who scrolled back to
     * the true top before hibernating has a real (0,0) to restore). That decision now lives one
     * layer up, in shouldAttemptRestore, which HAS the URL this function does not. So this
     * function must actually attempt an origin target when asked - the opposite of what this
     * test used to assert.
     */
    @Test
    fun `awaitSettleAndApply does not special-case the origin - that decision lives in shouldAttemptRestore now`() = runTest {
        var heightReads = 0
        var applies = 0
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = ScrollRestore.Position(0, 0),
                expectedUrl = EXPECTED_URL,
                readUrl = alreadyNavigated,
                readHeight = { heightReads++; "1000" },
                applyScroll = { applies++ },
                readPosition = { ScrollRestore.Position(0, 0) },
                delay = {},
            )
        assertTrue(settled)
        assertTrue(heightReads >= 1, "must actually poll for an origin target, not skip straight to settled")
        assertTrue(applies >= 1, "must actually attempt to apply (0,0) - the caller decided this was worth attempting")
    }

    @Test
    fun `shouldAttemptRestore skips the origin only when the URL has no fragment`() {
        val origin = ScrollRestore.Position(0, 0)
        val nonOrigin = ScrollRestore.Position(0, 500)
        assertFalse(ScrollRestore.shouldAttemptRestore(origin, "https://example.com/page"))
        assertTrue(ScrollRestore.shouldAttemptRestore(origin, "https://example.com/page#section"))
        // A non-origin target is always worth attempting, fragment or not.
        assertTrue(ScrollRestore.shouldAttemptRestore(nonOrigin, "https://example.com/page"))
        assertTrue(ScrollRestore.shouldAttemptRestore(nonOrigin, "https://example.com/page#section"))
    }

    // region navigation-wait — the phase a PR review added: fixes comparing a freshly created
    // handle's URL against itself instead of against the ORIGINAL document's URL

    /**
     * The actual bug a PR review caught, reproduced directly: a freshly (re)created handle
     * starts on `about:blank` while its own navigation is still in flight. Height-settle and
     * apply must not run until the real page has actually loaded - this asserts zero calls to
     * either while the URL doesn't match yet, not just the eventual outcome.
     */
    @Test
    fun `height-settle and apply do not start until the handle actually reaches the expected URL`() = runTest {
        var urlReads = 0
        var heightReads = 0
        var applies = 0
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = TARGET,
                expectedUrl = EXPECTED_URL,
                // "about:blank" for the first three reads (still navigating), matching what the
                // reviewer identified: getCurrentUrl() on a just-created handle.
                readUrl = { urlReads++; if (urlReads <= 3) "about:blank" else EXPECTED_URL },
                readHeight = { heightReads++; "1000" },
                applyScroll = { applies++ },
                readPosition = { TARGET },
                delay = {},
            )
        assertTrue(settled)
        assertTrue(urlReads > 3, "must keep polling the URL past the still-navigating reads")
        assertTrue(heightReads >= 1, "must eventually reach the height-settle phase once navigation lands")
        assertTrue(applies >= 1, "must eventually attempt the apply once navigation lands")
    }

    /**
     * If the handle never reaches the expected URL at all (navigation failed, or landed
     * somewhere else entirely), nothing is safe to restore onto - not even one attempt. This is
     * the one case where an unsettled outcome does NOT get its usual "still worth trying" apply,
     * because there is no reason to believe any page it might apply to is the right one.
     */
    @Test
    fun `a navigation that never reaches the expected URL aborts entirely - no height poll, no apply`() = runTest {
        var heightReads = 0
        var applies = 0
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = TARGET,
                expectedUrl = EXPECTED_URL,
                readUrl = { "https://example.com/somewhere-else" },
                readHeight = { heightReads++; "1000" },
                applyScroll = { applies++ },
                readPosition = { TARGET },
                delay = {},
                maxNavigationWaitPolls = 5,
            )
        assertFalse(settled)
        assertEquals(0, heightReads, "landing on the wrong page is not worth polling height for")
        assertEquals(0, applies, "landing on the wrong page is not worth an apply attempt")
    }

    /**
     * A redirect landing AFTER the navigation-wait phase already passed (the page loaded, then
     * bounced elsewhere - a login/session check is a real example) must not have the ORIGINAL
     * document's position applied to whatever replaced it. Re-checked on every reapply attempt,
     * not just once up front.
     *
     * Also pins that a detected redirect stops the loop outright rather than spending its
     * remaining attempts' delay and readPosition on a document this deliberately refuses to
     * touch - `positionReads` would be 3 (one per attempt) under the earlier version of this loop,
     * which kept looping after `stillOnExpectedPage` went false and could even exit via
     * `landed == target` by coincidence on the wrong document.
     */
    @Test
    fun `reapply skips the actual scroll application once the page has navigated away mid-restore`() = runTest {
        var urlReads = 0
        var applies = 0
        var positionReads = 0
        ScrollRestore.awaitSettleAndApply(
            target = TARGET,
            expectedUrl = EXPECTED_URL,
            // read #1 is the navigation-wait's own check (must see the expected page to proceed
            // past that phase at all); every read from the reapply loop onward (#2+) sees the
            // redirect, so the very first reapply attempt's own URL check already fails.
            readUrl = { urlReads++; if (urlReads == 1) EXPECTED_URL else "https://example.com/login" },
            readHeight = { "1000" },
            applyScroll = { applies++ },
            readPosition = { positionReads++; ScrollRestore.Position(0, 0) },
            delay = {},
            reapplyAttempts = 3,
        )
        assertEquals(0, applies, "the redirect happens before the first reapply's URL check - apply must never run")
        assertEquals(0, positionReads, "a redirect must stop the loop immediately, not burn the remaining attempts")
    }

    // endregion

    /**
     * The exact regression this suite exists to catch. A `repeat(N) { ...; return@repeat }`
     * version of the settle loop cannot pass this: `return@repeat` only skips that iteration's
     * remaining body, so it would keep calling [readHeight] for all `maxSettlePolls` iterations
     * regardless of when the height actually stabilised - this asserts the call count, not just
     * the end result, specifically so a reintroduction of that bug fails here rather than only
     * showing up as "scroll didn't restore" in the field again.
     */
    @Test
    fun `settle polling stops the instant height stabilises, not after the full cap`() = runTest {
        var heightReads = 0
        val heights = listOf("1000", "1000") // stable on the SECOND read
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = TARGET,
                expectedUrl = EXPECTED_URL,
                readUrl = alreadyNavigated,
                readHeight = { heights[heightReads++.coerceAtMost(heights.size - 1)] },
                applyScroll = {},
                readPosition = { TARGET },
                delay = {},
                maxSettlePolls = 20,
            )
        assertTrue(settled)
        // Two reads to observe the same value twice, not twenty.
        assertEquals(2, heightReads)
    }

    @Test
    fun `an unsettled height still attempts a restore, and reports success once the position lands`() = runTest {
        var heightReads = 0
        var applies = 0
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = TARGET,
                expectedUrl = EXPECTED_URL,
                readUrl = alreadyNavigated,
                readHeight = { heightReads++.toString() }, // always different from the previous read
                applyScroll = { applies++ },
                readPosition = { TARGET },
                delay = {},
                maxSettlePolls = 5,
            )
        assertTrue(settled, "the position landed - that is what this reports now, independent of height ever settling")
        assertEquals(5, heightReads, "the settle gate still runs to its cap even though it never stabilised")
        assertEquals(1, applies, "lands on the very first reapply attempt")
    }

    /**
     * The bug a second review round caught: an earlier revision returned the height-settle
     * result, which reports `true` here even though every apply attempt below silently lands
     * short of [TARGET] - exactly what `window.scrollTo` clamping to a shorter, still-loading
     * document looks like from this function's side. A page can look height-stable for one poll
     * cycle in the middle of a lazy load; that must not be reported as the restore having worked.
     */
    @Test
    fun `a clamped scrollTo reports failure even though the height looked settled`() = runTest {
        var applies = 0
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = TARGET,
                expectedUrl = EXPECTED_URL,
                readUrl = alreadyNavigated,
                readHeight = { "1000" }, // stable immediately - looks settled
                applyScroll = { applies++ },
                readPosition = { ScrollRestore.Position(0, 1200) }, // clamped short of TARGET.y=4500, every time
                delay = {},
                reapplyAttempts = 4,
            )
        assertFalse(settled, "a height that looked stable must not be reported as the restore having landed")
        assertEquals(4, applies, "must still exhaust every reapply attempt on a page that might yet grow")
    }

    @Test
    fun `reapply stops the instant the position lands, not after every attempt`() = runTest {
        var applies = 0
        ScrollRestore.awaitSettleAndApply(
            target = TARGET,
            expectedUrl = EXPECTED_URL,
            readUrl = alreadyNavigated,
            readHeight = { "1000" },
            applyScroll = { applies++ },
            // Lands on the second application - the first read (right after the first apply)
            // still reports the stale pre-restore position, matching a real SPA reasserting its
            // own scroll once before settling on the restored one.
            readPosition = { if (applies == 1) ScrollRestore.Position(0, 0) else TARGET },
            delay = {},
            reapplyAttempts = 4,
        )
        assertEquals(2, applies, "must not keep reapplying once the position already matches")
    }

    @Test
    fun `reapply gives up after the configured attempt count when the position never lands`() = runTest {
        var applies = 0
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = TARGET,
                expectedUrl = EXPECTED_URL,
                readUrl = alreadyNavigated,
                readHeight = { "1000" },
                applyScroll = { applies++ },
                readPosition = { ScrollRestore.Position(0, 0) }, // never matches TARGET
                delay = {},
                reapplyAttempts = 4,
            )
        assertEquals(4, applies, "must stop at the configured cap, not loop forever on a page that never lands")
        assertFalse(settled, "a position that never lands must report failure, however healthy the height-settle looked")
    }

    /**
     * The prior tests only exercise readHeight succeeding or "never stabilises" - never throwing.
     * `runCatching` around each injected call is meant to degrade a page that answers garbage (or
     * throws) to "not settled" rather than crash the whole hibernation-wake coroutine; this is
     * what actually proves that, instead of taking it on faith from reading the code. Paired with
     * a position that never lands, so the final `settled` result is pinned too, not just the poll
     * count and the absence of a crash.
     */
    @Test
    fun `a throwing readHeight does not crash the settle loop, and a restore that never lands still reports failure`() = runTest {
        var heightReads = 0
        var applies = 0
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = TARGET,
                expectedUrl = EXPECTED_URL,
                readUrl = alreadyNavigated,
                readHeight = { heightReads++; throw RuntimeException("DOM read failed") },
                applyScroll = { applies++ },
                readPosition = { ScrollRestore.Position(0, 0) }, // never matches TARGET
                delay = {},
                maxSettlePolls = 5,
                reapplyAttempts = 4,
            )
        assertFalse(settled, "a restore that never lands must report failure, throwing height read or not")
        assertEquals(5, heightReads, "must keep polling the settle gate up to its cap, not abort early on the first throw")
        assertEquals(4, applies, "an unsettled page still deserves every configured restore attempt")
    }

    // endregion
}
