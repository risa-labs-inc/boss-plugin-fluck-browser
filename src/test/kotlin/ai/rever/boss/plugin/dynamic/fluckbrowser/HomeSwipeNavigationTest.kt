package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The home surface's two-finger swipe.
 *
 * Every number in [advanceHomeSwipe] is one that looks reasonable while being wrong, so these
 * drive it the way a trackpad would - a stream of small deltas - rather than asserting on the
 * constants. The host's page-side detector is covered the same way, by running it
 * (`scripts/test/test-swipe-nav.js` in BossConsole); this is that suite's counterpart for the one
 * surface with no page.
 */
class HomeSwipeNavigationTest {
    /** One scroll event, as the surface would see it. */
    private data class Wheel(
        val dx: Float,
        val dy: Float = 0f,
        val consumed: Boolean = false,
        val atMs: Long = 0,
    )

    /** Fold a stream and report every navigation it produced, plus where it ended up. */
    private fun run(
        events: List<Wheel>,
        canGoBack: Boolean = true,
        canGoForward: Boolean = true,
    ): Pair<List<HomeSwipeDirection>, HomeSwipeStep> {
        var gesture = HomeSwipeGesture()
        val navigated = mutableListOf<HomeSwipeDirection>()
        var last = HomeSwipeStep(gesture)
        events.forEachIndexed { index, event ->
            last =
                advanceHomeSwipe(
                    gesture = gesture,
                    deltaX = event.dx,
                    deltaY = event.dy,
                    // Events one millisecond apart unless the case says otherwise, so a stream is
                    // one gesture without every case having to say so.
                    nowMs = if (event.atMs != 0L) event.atMs else 1_000L + index,
                    consumed = event.consumed,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                )
            gesture = last.gesture
            last.navigate?.let { navigated += it }
        }
        return navigated to last
    }

    private fun swipe(
        count: Int,
        dx: Float,
        dy: Float = 0f,
        consumed: Boolean = false,
        startMs: Long = 1_000L,
    ) = (0 until count).map { Wheel(dx, dy, consumed, startMs + it) }

    // --- gestures that navigate ---------------------------------------------------------------

    @Test
    fun `a real swipe goes back once`() {
        val (navigated, _) = run(swipe(12, -1f))
        assertEquals(listOf(HomeSwipeDirection.BACK), navigated)
    }

    @Test
    fun `the other direction goes forward`() {
        val (navigated, _) = run(swipe(12, 1f))
        assertEquals(listOf(HomeSwipeDirection.FORWARD), navigated)
    }

    @Test
    fun `one continuous swipe navigates exactly once`() {
        val (navigated, _) = run(swipe(40, -1f))
        assertEquals(listOf(HomeSwipeDirection.BACK), navigated)
    }

    @Test
    fun `two swipes across a gap navigate twice`() {
        val first = swipe(12, -1f, startMs = 1_000L)
        val second = swipe(12, -1f, startMs = 1_000L + 12 + GESTURE_GAP_MS + 40)
        val (navigated, _) = run(first + second)
        assertEquals(listOf(HomeSwipeDirection.BACK, HomeSwipeDirection.BACK), navigated)
    }

    // --- gestures that must not ---------------------------------------------------------------

    @Test
    fun `abandoned short of the threshold`() {
        val (navigated, last) = run(swipe(5, -1f))
        assertTrue(navigated.isEmpty())
        assertEquals(HomeSwipeDirection.BACK, last.direction, "the affordance should still be up")
        assertTrue(last.progress > 0f && last.progress < 1f, "progress ${last.progress}")
    }

    /**
     * The load-bearing one. Home's tool row scrolls horizontally, and Compose consumes the wheel
     * at the Main pass only when a scroller could actually use it - so a consumed event means the
     * page wanted it and this must not also navigate.
     */
    @Test
    fun `an event a scroller already took`() {
        val (navigated, last) = run(swipe(12, -1f, consumed = true))
        assertTrue(navigated.isEmpty())
        assertNull(last.direction, "no affordance over something the page is scrolling")
    }

    /**
     * A scroller that took the START of the gesture keeps the whole of it, even after it runs out
     * of room and stops consuming. Latching matches the page detector, which decides its scroll
     * chain once per gesture for the same reason: the alternative is that scrolling a row to its
     * end silently turns into leaving the page.
     */
    @Test
    fun `a scroller that took the start of a gesture keeps it`() {
        val (navigated, _) = run(swipe(4, -1f, consumed = true) + swipe(20, -1f, startMs = 1_004L))
        assertTrue(navigated.isEmpty())
    }

    /**
     * The other half of that, or the guard above would be indistinguishable from "never navigate
     * near a scrollable area again": lifting and swiping afresh starts a gesture that is free.
     */
    @Test
    fun `and the next gesture is free`() {
        val first = swipe(4, -1f, consumed = true, startMs = 1_000L)
        val second = swipe(12, -1f, startMs = 1_004L + GESTURE_GAP_MS + 40)
        val (navigated, _) = run(first + second)
        assertEquals(listOf(HomeSwipeDirection.BACK), navigated)
    }

    @Test
    fun `a diagonal drag`() {
        val (navigated, _) = run(swipe(12, -1f, dy = -4f))
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `a vertical scroll that curls into a horizontal one`() {
        val vertical = swipe(5, 0f, dy = -5f, startMs = 1_000L)
        val (navigated, _) = run(vertical + swipe(12, -1f, startMs = 1_005L))
        assertTrue(navigated.isEmpty())
    }

    /**
     * Far enough the other way that, without the reversal guard, the running total alone would
     * cross the commit point. A shorter reversal proves nothing: it fails to navigate whether the
     * guard is there or not.
     */
    @Test
    fun `a swipe reversed halfway`() {
        val (navigated, _) = run(swipe(3, -1f, startMs = 1_000L) + swipe(14, 1f, startMs = 1_003L))
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `a direction with no history entry`() {
        val (navigated, last) = run(swipe(12, -1f), canGoBack = false)
        assertTrue(navigated.isEmpty())
        assertNull(last.direction, "no affordance for a direction that cannot be taken")
    }

    @Test
    fun `the unavailable direction does not block the other one`() {
        val (navigated, _) = run(swipe(12, 1f), canGoBack = false)
        assertEquals(listOf(HomeSwipeDirection.FORWARD), navigated)
    }

    // --- the affordance -----------------------------------------------------------------------

    @Test
    fun `nothing is drawn until the gesture looks real`() {
        val (_, last) = run(swipe(MIN_EVENTS - 1, -1f))
        assertNull(last.direction)
        assertEquals(0f, last.progress)
    }

    @Test
    fun `progress ramps to one at the commit`() {
        val (_, half) = run(swipe(5, -1f))
        assertTrue(half.progress in 0.4f..0.7f, "half way was ${half.progress}")
        val (navigated, committing) = run(swipe(9, -1f))
        assertEquals(listOf(HomeSwipeDirection.BACK), navigated)
        assertEquals(1f, committing.progress, "the committing step draws the puck filled in")
    }

    /**
     * And it goes away as the navigation starts, rather than sitting over the page that replaces
     * home. The page detector hides its own chevron at the same moment for the same reason.
     */
    @Test
    fun `and the affordance clears once it has fired`() {
        val (_, after) = run(swipe(20, -1f))
        assertNull(after.direction)
        assertEquals(0f, after.progress)
    }
}
