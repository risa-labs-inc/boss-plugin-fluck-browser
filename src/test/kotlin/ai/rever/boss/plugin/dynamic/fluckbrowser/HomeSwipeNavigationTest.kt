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

    /** What a folded stream produced: every navigation, the last step, and the one that fired. */
    private data class Run(
        val navigated: List<HomeSwipeDirection>,
        val last: HomeSwipeStep,
        /** The step that navigated. Not the last one - the affordance clears on the next event. */
        val committing: HomeSwipeStep?,
    )

    /** Fold a stream and report every navigation it produced, plus where it ended up. */
    private fun run(
        events: List<Wheel>,
        canGoBack: Boolean = true,
        canGoForward: Boolean = true,
    ): Run {
        var gesture = HomeSwipeGesture()
        val navigated = mutableListOf<HomeSwipeDirection>()
        var committing: HomeSwipeStep? = null
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
            last.navigate?.let {
                navigated += it
                committing = last
            }
        }
        return Run(navigated, last, committing)
    }

    /**
     * One event's worth of travel, as a fraction of the commit distance rather than a number.
     *
     * Every case below counts events, so nine of them is exactly one commit whatever
     * [COMMIT_UNITS] is set to. Written as literals once, tuning the gesture silently turned
     * "abandoned short of the threshold" into a swipe that commits, and the suite would have gone
     * red for a reason that had nothing to do with the behaviour it was describing.
     */
    private val step = COMMIT_UNITS / 9f

    private fun swipe(
        count: Int,
        steps: Float,
        dySteps: Float = 0f,
        consumed: Boolean = false,
        startMs: Long = 1_000L,
    ) = (0 until count).map { Wheel(steps * step, dySteps * step, consumed, startMs + it) }

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

    /**
     * The case the suite was missing, and the one that broke on real hardware.
     *
     * Every other drift case here asks what must be REJECTED, so nothing pinned what must still be
     * accepted - and shortening the commit distance silently cut the drift allowed at the start of
     * a gesture below the noise of putting two fingers down. Both halves matter: a first event that
     * is mostly vertical (finger placement, not direction) and honest drift for the rest of it.
     */
    @Test
    fun `an honest swipe that starts noisy still commits`() {
        // A first event that is mostly vertical, then an honest swipe with a tenth of drift.
        val placement = listOf(Wheel(-0.2f * step, 1.5f * step, false, 1_000L))
        val rest = swipe(12, -1f, dySteps = 0.1f, startMs = 1_001L)
        val (navigated, _) = run(placement + rest)
        assertEquals(listOf(HomeSwipeDirection.BACK), navigated)
    }

    @Test
    fun `a diagonal drag`() {
        val (navigated, _) = run(swipe(12, -1f, dySteps = -4f))
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `a vertical scroll that curls into a horizontal one`() {
        val vertical = swipe(5, 0f, dySteps = -5f, startMs = 1_000L)
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

    // --- the style the host publishes ----------------------------------------------------------

    @Test
    fun `the style comes from the property the host publishes`() {
        assertEquals(HomeSwipeStyle.SLIDE, homeSwipeStyle(env = null, property = "slide"))
        assertEquals(HomeSwipeStyle.OFF, homeSwipeStyle(env = null, property = "off"))
        assertEquals(HomeSwipeStyle.CHEVRON, homeSwipeStyle(env = null, property = "chevron"))
    }

    /**
     * An exported variable outranks the setting everywhere else in this app, so home must not
     * disagree with the pages beside it.
     */
    @Test
    fun `the environment wins over the property`() {
        assertEquals(HomeSwipeStyle.OFF, homeSwipeStyle(env = "off", property = "slide"))
    }

    /**
     * A host older than the setting publishes nothing. Falling back to the chevron matches what
     * that host's own pages do; falling back to off would silently remove the gesture.
     */
    @Test
    fun `an unset key is the chevron, not off`() {
        assertEquals(HomeSwipeStyle.CHEVRON, homeSwipeStyle(env = null, property = null))
        assertEquals(HomeSwipeStyle.CHEVRON, homeSwipeStyle(env = null, property = "nonsense"))
    }

    /** The key shipped as a boolean before it grew a third state; someone has that exported. */
    @Test
    fun `legacy boolean spellings still parse`() {
        assertEquals(HomeSwipeStyle.OFF, parseHomeSwipeStyle("false"))
        assertEquals(HomeSwipeStyle.CHEVRON, parseHomeSwipeStyle("true"))
    }

    /**
     * The two repos hand-match this key across a system property. A drift is silent - home simply
     * stops agreeing with every page - so it is pinned to the literal the host publishes.
     */
    @Test
    fun `the key matches the one the host publishes`() {
        assertEquals("BOSS_BROWSER_SWIPE_NAV", SWIPE_STYLE_KEY)
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
        val half = run(swipe(5, -1f)).last
        assertTrue(half.progress in 0.4f..0.7f, "half way was ${half.progress}")
        val full = run(swipe(10, -1f))
        assertEquals(listOf(HomeSwipeDirection.BACK), full.navigated)
        assertEquals(1f, full.committing?.progress, "the committing step draws the puck filled in")
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
