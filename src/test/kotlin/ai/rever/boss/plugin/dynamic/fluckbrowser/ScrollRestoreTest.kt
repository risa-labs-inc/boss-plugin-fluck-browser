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

    @Test
    fun `restoring to the origin is a no-op — no height poll, no apply`() = runTest {
        var heightReads = 0
        var applies = 0
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = ScrollRestore.Position(0, 0),
                readHeight = { heightReads++; "1000" },
                applyScroll = { applies++ },
                readPosition = { ScrollRestore.Position(0, 0) },
                delay = {},
            )
        assertTrue(settled)
        assertEquals(0, heightReads)
        assertEquals(0, applies)
    }

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
    fun `a height that never stabilises hits the cap and reports unsettled, but still attempts a restore`() = runTest {
        var heightReads = 0
        var applies = 0
        val settled =
            ScrollRestore.awaitSettleAndApply(
                target = TARGET,
                readHeight = { heightReads++.toString() }, // always different from the previous read
                applyScroll = { applies++ },
                readPosition = { TARGET },
                delay = {},
                maxSettlePolls = 5,
            )
        assertFalse(settled, "height never repeated, so this must report unsettled")
        assertEquals(5, heightReads)
        assertTrue(applies >= 1, "an unsettled page still deserves one restore attempt, not silence")
    }

    @Test
    fun `reapply stops the instant the position lands, not after every attempt`() = runTest {
        var applies = 0
        ScrollRestore.awaitSettleAndApply(
            target = TARGET,
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
                readHeight = { "1000" },
                applyScroll = { applies++ },
                readPosition = { ScrollRestore.Position(0, 0) }, // never matches TARGET
                delay = {},
                reapplyAttempts = 4,
            )
        assertEquals(4, applies, "must stop at the configured cap, not loop forever on a page that never lands")
        // Settling (height) and landing (position) are independent outcomes - the height poll
        // succeeded above, so this is still reported settled even though the position never took.
        assertTrue(settled)
    }

    // endregion
}
