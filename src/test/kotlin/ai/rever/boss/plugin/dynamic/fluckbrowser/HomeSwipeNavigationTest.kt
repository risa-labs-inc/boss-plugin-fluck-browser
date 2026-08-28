package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
     * The case the suite was missing, and the one that broke on real hardware: nothing pinned what
     * must still be ACCEPTED, only what must be refused.
     *
     * Vertical at 60% of horizontal is an ordinary slightly-sloped swipe. The half-of-horizontal
     * rule that used to be here refused it; Chrome takes it, and so does this now - its rule 2
     * accepts until vertical reaches about 0.77 of horizontal.
     */
    @Test
    fun `an honest swipe with slope still commits`() {
        val (navigated, _) = run(swipe(14, -1f, dySteps = 0.6f))
        assertEquals(listOf(HomeSwipeDirection.BACK), navigated)
    }

    /**
     * Chrome's rule 1, and correct rather than a regression: if the first thing your fingers do is
     * move vertically, you are scrolling. Chrome refuses this too - its `_gestureTotalY`
     * accumulates from the moment the fingers land, placement wobble included.
     */
    @Test
    fun `a gesture that starts vertically is refused`() {
        val opening = listOf(Wheel(-0.2f * step, 1.5f * step, false, 1_000L))
        val (navigated, _) = run(opening + swipe(12, -1f, startMs = 1_001L))
        assertTrue(navigated.isEmpty())
    }

    /**
     * The case only Chrome's rule 1 catches.
     *
     * Rule 2 needs vertical past an absolute floor before it will refuse anything, so the very
     * first flick of a gesture - too small to reach that floor but already going the wrong way -
     * is rule 1's alone. Without it, a gesture that opens vertically and then straightens out is
     * taken, which is a scroll being read as a swipe.
     */
    @Test
    fun `a tiny opening flick the wrong way is refused`() {
        val flick = listOf(Wheel(-0.01f * step, 0.05f * step, false, 1_000L))
        val (navigated, _) = run(flick + swipe(14, -1f, startMs = 1_001L))
        assertTrue(navigated.isEmpty())
    }

    /**
     * The path-length asymmetry, and the point of measuring vertical the way Chrome does. This
     * wobble nets out to about zero, so a rule reading the NET vertical total would take it; as a
     * path length it accumulates and rule 2 refuses it.
     */
    @Test
    fun `vertical wobble that nets to zero is still refused`() {
        val events = (0 until 20).map { Wheel(-1f * step, (if (it % 2 == 0) 0.8f else -0.8f) * step, false, 1_000L + it) }
        val (navigated, _) = run(events)
        assertTrue(navigated.isEmpty())
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

    // --- the switch the host publishes ----------------------------------------------------------

    @Test
    fun `the gesture follows the key the host publishes`() {
        assertTrue(homeSwipeEnabled(env = null, property = "true"))
        assertFalse(homeSwipeEnabled(env = null, property = "false"))
    }

    /**
     * An exported variable outranks the setting everywhere else in this app, so home must not
     * disagree with the pages beside it.
     */
    @Test
    fun `the environment wins over the property`() {
        assertFalse(homeSwipeEnabled(env = "off", property = "true"))
    }

    /**
     * A host older than the setting publishes nothing. Defaulting to on matches what that host's
     * own pages do; defaulting to off would silently remove the gesture.
     */
    @Test
    fun `an unset key leaves it on`() {
        assertTrue(homeSwipeEnabled(env = null, property = null))
        assertTrue(homeSwipeEnabled(env = null, property = "nonsense"))
    }

    /**
     * The two repos hand-match this key across a system property. A drift is silent - home simply
     * stops agreeing with every page - so it is pinned to the literal the host publishes.
     */
    @Test
    fun `the key matches the one the host publishes`() {
        assertEquals("BOSS_BROWSER_SWIPE_NAV", SWIPE_ENABLED_KEY)
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
