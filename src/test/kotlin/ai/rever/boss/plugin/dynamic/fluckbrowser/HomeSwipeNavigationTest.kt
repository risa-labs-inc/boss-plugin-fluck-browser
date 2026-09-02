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
 *
 * [advanceHomeSwipe] itself cannot navigate any more - [HomeSwipeStep] has no `navigate` field, so
 * a mid-gesture step firing early is a compile error, not something a test has to catch. Only
 * [endHomeSwipe], called once a gesture ends, decides - which is why every test below folds a
 * whole physical gesture and then ends it, mirroring what [HomeSwipeSurface] actually does.
 */
class HomeSwipeNavigationTest {
    /** One scroll event, as the surface would see it. */
    private data class Wheel(
        val dx: Float,
        val dy: Float = 0f,
        val consumed: Boolean = false,
        val atMs: Long = 0,
    )

    /** What one physical gesture produced: whatever fired at its end, and where it left off. */
    private data class Run(
        val navigated: HomeSwipeDirection?,
        val last: HomeSwipeStep,
    )

    /** Fold one continuous physical gesture and end it, exactly as [HomeSwipeSurface] would on a timeout or pointer exit. */
    private fun run(
        events: List<Wheel>,
        canGoBack: Boolean = true,
        canGoForward: Boolean = true,
    ): Run {
        var gesture = HomeSwipeGesture()
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
        }
        return Run(endHomeSwipe(gesture), last)
    }

    /**
     * Like [run], but modelling [HomeSwipeSurface]'s own end-of-gesture GATE as well as the
     * decision - the surface does not call [endHomeSwipe] unconditionally, it arms a timer that
     * fires only under a condition, and that condition is the part [run] cannot see.
     *
     * Worth a second harness because the gate is where a whole gesture can be lost without
     * [endHomeSwipe] ever being asked: the gate used to be "something is drawn", which an event
     * with no horizontal component switched off mid-swipe. [run] stays unconditional deliberately,
     * so the tests that are about [endHomeSwipe]'s own rules keep exercising it directly rather
     * than being satisfied by a gate that refused to call it.
     */
    private fun runGated(
        events: List<Wheel>,
        gate: (HomeSwipeGesture, HomeSwipeStep) -> Boolean = ARMED_FROM_GESTURE,
        canGoBack: Boolean = true,
        canGoForward: Boolean = true,
    ): Run {
        var gesture = HomeSwipeGesture()
        var last = HomeSwipeStep(gesture)
        events.forEachIndexed { index, event ->
            last =
                advanceHomeSwipe(
                    gesture = gesture,
                    deltaX = event.dx,
                    deltaY = event.dy,
                    nowMs = if (event.atMs != 0L) event.atMs else 1_000L + index,
                    consumed = event.consumed,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                )
            gesture = last.gesture
        }
        return Run(if (gate(gesture, last)) endHomeSwipe(gesture) else null, last)
    }

    private companion object {
        /** What [HomeSwipeSurface]'s `LaunchedEffect` arms on today. */
        val ARMED_FROM_GESTURE: (HomeSwipeGesture, HomeSwipeStep) -> Boolean = { gesture, _ -> gesture.events > 0 }

        /**
         * What it used to arm on - "something is drawn". Kept as a second gate rather than
         * deleted, because a gesture asserted to navigate under BOTH survives either half of the
         * vertical-only fix regressing on its own: this gate is the one [advanceHomeSwipe]
         * carrying its state through the `dx == 0` branch is what satisfies, and
         * [ARMED_FROM_GESTURE] is the one that holds even if it stops.
         *
         * Neither gate is production code, so no test here can pin which one the surface actually
         * uses - that is a Compose-level fact. What they pin is that the gesture reaches
         * [endHomeSwipe] intact under either reading.
         */
        val ARMED_FROM_AFFORDANCE: (HomeSwipeGesture, HomeSwipeStep) -> Boolean = { _, step -> step.direction != null }
    }

    /**
     * Models [HomeSwipeSurface]'s Scroll handler end to end: fold each event, decide any gesture
     * the event's own lateness retired, and apply the end-of-gesture gate once the stream stops.
     * Returns every navigation in order.
     *
     * The difference from [runGestures] is the whole point. That harness ends each gesture itself
     * before feeding the next one's events, i.e. it models the surface's timer always winning the
     * race against the next event. This one never ends a gesture by hand, so a gesture retired
     * inside the `GESTURE_GAP_MS`..`GESTURE_GAP_MS + 60` window - where the next event cancels the
     * pending timer AND `advanceHomeSwipe` starts fresh - is lost here unless the retired gesture
     * is actually handed back and decided.
     */
    private fun runSurface(
        events: List<Wheel>,
        canGoBack: Boolean = true,
        canGoForward: Boolean = true,
    ): List<HomeSwipeDirection> {
        var gesture = HomeSwipeGesture()
        val navigated = mutableListOf<HomeSwipeDirection>()
        events.forEachIndexed { index, event ->
            val step =
                advanceHomeSwipe(
                    gesture = gesture,
                    deltaX = event.dx,
                    deltaY = event.dy,
                    nowMs = if (event.atMs != 0L) event.atMs else 1_000L + index,
                    consumed = event.consumed,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                )
            step.ended?.let { retired -> endHomeSwipe(retired)?.let(navigated::add) }
            gesture = step.gesture
        }
        if (gesture.events > 0) endHomeSwipe(gesture)?.let(navigated::add)
        return navigated
    }

    /**
     * Fold several SEPARATE physical gestures through one continuous state and end each in turn -
     * for the cases that are about the gap between two swipes, not one swipe's own mechanics.
     * Unlike [run], state is NOT reset to a fresh [HomeSwipeGesture] between calls: the point is
     * to prove [advanceHomeSwipe]'s own gap check (`continuing`) recognises the second swipe's
     * first event arrives after [GESTURE_GAP_MS] and starts it clean on its own, the same way
     * production relies on it rather than on [HomeSwipeSurface] having reset first.
     */
    private fun runGestures(vararg physicalGestures: List<Wheel>): List<HomeSwipeDirection> {
        var gesture = HomeSwipeGesture()
        val navigated = mutableListOf<HomeSwipeDirection>()
        physicalGestures.forEach { events ->
            events.forEach { event ->
                val step =
                    advanceHomeSwipe(
                        gesture = gesture,
                        deltaX = event.dx,
                        deltaY = event.dy,
                        nowMs = event.atMs,
                        consumed = event.consumed,
                        canGoBack = true,
                        canGoForward = true,
                    )
                gesture = step.gesture
            }
            endHomeSwipe(gesture)?.let { navigated += it }
        }
        return navigated
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
        assertEquals(HomeSwipeDirection.BACK, run(swipe(12, -1f)).navigated)
    }

    @Test
    fun `the other direction goes forward`() {
        assertEquals(HomeSwipeDirection.FORWARD, run(swipe(12, 1f)).navigated)
    }

    @Test
    fun `one continuous swipe navigates exactly once`() {
        // There is only one decision point per physical gesture now - endHomeSwipe, called once,
        // at the end. Overshooting the commit distance by a lot does not create extra decisions.
        assertEquals(HomeSwipeDirection.BACK, run(swipe(40, -1f)).navigated)
    }

    @Test
    fun `two swipes across a gap navigate twice`() {
        val first = swipe(12, -1f, startMs = 1_000L)
        val second = swipe(12, -1f, startMs = 1_000L + 12 + GESTURE_GAP_MS + 40)
        assertEquals(listOf(HomeSwipeDirection.BACK, HomeSwipeDirection.BACK), runGestures(first, second))
    }

    // --- gestures that must not -----------------------------------------------------------------

    @Test
    fun `abandoned short of the threshold`() {
        val out = run(swipe(5, -1f))
        assertNull(out.navigated)
        assertEquals(HomeSwipeDirection.BACK, out.last.direction, "the affordance should still be up")
        assertTrue(out.last.progress > 0f && out.last.progress < 1f, "progress ${out.last.progress}")
    }

    /**
     * The heart of the fix this suite exists to pin: reaching the commit distance is not the same
     * as navigating. Same direction throughout - accumX never crosses back through zero, so this
     * is NOT the reversal guard below - just letting go before release while short of the line.
     */
    @Test
    fun `easing back below the commit distance before release does not navigate`() {
        val out = run(swipe(10, -1f) + swipe(4, 1f, startMs = 1_010L))
        assertNull(out.navigated, "progress dropped back under 1 by the time the gesture ended")
    }

    /**
     * The other half of that: reaching the commit distance and STAYING there through release
     * must navigate - the guard above must not have overcorrected into never firing at all.
     */
    @Test
    fun `reaching the commit distance and holding it through release does navigate`() {
        val out = run(swipe(10, -1f) + swipe(1, -1f, startMs = 1_010L))
        assertEquals(HomeSwipeDirection.BACK, out.navigated)
    }

    /**
     * The load-bearing one. Home's tool row scrolls horizontally, and Compose consumes the wheel
     * at the Main pass only when a scroller could actually use it - so a consumed event means the
     * page wanted it and this must not also navigate.
     */
    @Test
    fun `an event a scroller already took`() {
        val out = run(swipe(12, -1f, consumed = true))
        assertNull(out.navigated)
        assertNull(out.last.direction, "no affordance over something the page is scrolling")
    }

    /**
     * A scroller that took the START of the gesture keeps the whole of it, even after it runs out
     * of room and stops consuming. Latching matches the page detector, which decides its scroll
     * chain once per gesture for the same reason: the alternative is that scrolling a row to its
     * end silently turns into leaving the page.
     */
    @Test
    fun `a scroller that took the start of a gesture keeps it`() {
        val out = run(swipe(4, -1f, consumed = true) + swipe(20, -1f, startMs = 1_004L))
        assertNull(out.navigated)
    }

    /**
     * The other half of that, or the guard above would be indistinguishable from "never navigate
     * near a scrollable area again": lifting and swiping afresh starts a gesture that is free.
     */
    @Test
    fun `and the next gesture is free`() {
        val first = swipe(4, -1f, consumed = true, startMs = 1_000L)
        val second = swipe(12, -1f, startMs = 1_004L + GESTURE_GAP_MS + 40)
        assertEquals(listOf(HomeSwipeDirection.BACK), runGestures(first, second))
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
        assertEquals(HomeSwipeDirection.BACK, run(swipe(14, -1f, dySteps = 0.6f)).navigated)
    }

    /**
     * Chrome's rule 1, and correct rather than a regression: if the first thing your fingers do is
     * move vertically, you are scrolling. Chrome refuses this too - its `_gestureTotalY`
     * accumulates from the moment the fingers land, placement wobble included.
     */
    @Test
    fun `a gesture that starts vertically is refused`() {
        val opening = listOf(Wheel(-0.2f * step, 1.5f * step, false, 1_000L))
        assertNull(run(opening + swipe(12, -1f, startMs = 1_001L)).navigated)
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
        assertNull(run(flick + swipe(14, -1f, startMs = 1_001L)).navigated)
    }

    /**
     * The path-length asymmetry, and the point of measuring vertical the way Chrome does. This
     * wobble nets out to about zero, so a rule reading the NET vertical total would take it; as a
     * path length it accumulates and rule 2 refuses it.
     */
    @Test
    fun `vertical wobble that nets to zero is still refused`() {
        val events = (0 until 20).map { Wheel(-1f * step, (if (it % 2 == 0) 0.8f else -0.8f) * step, false, 1_000L + it) }
        assertNull(run(events).navigated)
    }

    @Test
    fun `a diagonal drag`() {
        assertNull(run(swipe(12, -1f, dySteps = -4f)).navigated)
    }

    @Test
    fun `a vertical scroll that curls into a horizontal one`() {
        val vertical = swipe(5, 0f, dySteps = -5f, startMs = 1_000L)
        assertNull(run(vertical + swipe(12, -1f, startMs = 1_005L)).navigated)
    }

    /**
     * Far enough the other way that, without the reversal guard, the running total alone would
     * cross the commit point. A shorter reversal proves nothing: it fails to navigate whether the
     * guard is there or not.
     */
    @Test
    fun `a swipe reversed halfway`() {
        val out = run(swipe(3, -1f, startMs = 1_000L) + swipe(14, 1f, startMs = 1_003L))
        assertNull(out.navigated)
    }

    @Test
    fun `a direction with no history entry`() {
        val out = run(swipe(12, -1f), canGoBack = false)
        assertNull(out.navigated)
        assertNull(out.last.direction, "no affordance for a direction that cannot be taken")
    }

    @Test
    fun `the unavailable direction does not block the other one`() {
        assertEquals(HomeSwipeDirection.FORWARD, run(swipe(12, 1f), canGoBack = false).navigated)
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
        val out = run(swipe(MIN_EVENTS - 1, -1f))
        assertNull(out.last.direction)
        assertEquals(0f, out.last.progress)
    }

    /**
     * [MIN_EVENTS] gates COMMITTING, not just drawing - and the two are no longer the same code
     * path. While the commit decision lived inside [advanceHomeSwipe] it sat after that
     * function's own `events < MIN_EVENTS` early return and inherited the guard for free; with
     * the decision moved to [endHomeSwipe], a two-event flick carrying enough distance to clear
     * [COMMIT_UNITS] navigates unless [endHomeSwipe] restates it.
     *
     * The distance here is deliberately past the commit point (2 events x 5 steps ~= 3.9 units
     * against a 3.5 threshold), so nothing but the event count can be what refuses it. Asserted
     * on `navigated`, which the drawing test above does not look at.
     */
    @Test
    fun `a flick with too few events does not navigate, however far it travelled`() {
        val out = run(swipe(MIN_EVENTS - 1, -5f))
        assertTrue(
            kotlin.math.abs(out.last.gesture.accumX) / COMMIT_UNITS >= 1f,
            "the setup must actually clear the commit distance, or this proves nothing",
        )
        assertNull(out.navigated, "fewer than MIN_EVENTS is a stray delta, not a swipe")
        assertNull(out.last.direction, "and nothing is drawn for it either")
    }

    /** The same distance over enough events IS a swipe - so the guard above is a floor, not a wall. */
    @Test
    fun `the same travel spread over enough events does navigate`() {
        assertEquals(HomeSwipeDirection.BACK, run(swipe(MIN_EVENTS, -5f)).navigated)
    }

    // --- the window between advanceHomeSwipe retiring a gesture and the timer firing -----------

    /**
     * `advanceHomeSwipe` retires a gesture at [GESTURE_GAP_MS]; the surface's timer fires 60ms
     * later. An event arriving in between cancels the pending timer and starts a fresh gesture, so
     * a swipe that had already earned its navigation used to vanish.
     *
     * Not a two-swipe edge case: a trackpad emits nothing at all while the fingers are stationary,
     * so this is swipe past the threshold, hold ~150ms, nudge once before letting go.
     */
    @Test
    fun `a swipe retired by a late event still navigates`() {
        val heldThenNudged = swipe(12, -1f) + listOf(Wheel(dx = -1f * step, atMs = 1_011L + 150L))
        assertEquals(listOf(HomeSwipeDirection.BACK), runSurface(heldThenNudged))
    }

    /** The whole window, not just its midpoint - every delay from just-past-the-gap to past the timer. */
    @Test
    fun `the whole retire-to-timer window is covered`() {
        for (gap in listOf(GESTURE_GAP_MS + 1, 150L, GESTURE_GAP_MS + 60, GESTURE_GAP_MS + 500)) {
            val events = swipe(12, -1f) + listOf(Wheel(dx = -1f * step, atMs = 1_011L + gap))
            assertEquals(
                listOf(HomeSwipeDirection.BACK),
                runSurface(events),
                "a swipe retired ${'$'}gap ms later must still navigate",
            )
        }
    }

    /** An event still INSIDE the gap continues the gesture, so there is nothing to retire and one navigation. */
    @Test
    fun `an event inside the gap continues the swipe rather than retiring it`() {
        val events = swipe(12, -1f) + listOf(Wheel(dx = -1f * step, atMs = 1_011L + GESTURE_GAP_MS))
        assertEquals(listOf(HomeSwipeDirection.BACK), runSurface(events), "one gesture, one navigation")
    }

    /** Two full swipes separated by a real gap navigate twice - retiring must not swallow the second. */
    @Test
    fun `two separated swipes navigate once each`() {
        val events = swipe(12, -1f) + swipe(12, -1f, startMs = 1_500L)
        assertEquals(listOf(HomeSwipeDirection.BACK, HomeSwipeDirection.BACK), runSurface(events))
    }

    /** A retired gesture that never earned anything must not navigate just because it was retired. */
    @Test
    fun `retiring a gesture that never reached the commit distance navigates nothing`() {
        val events = swipe(3, -0.1f) + listOf(Wheel(dx = -1f * step, atMs = 1_003L + 150L))
        assertTrue(runSurface(events).isEmpty())
    }

    // --- the vertical-only event, which AWT delivers as its own MouseWheelEvent ----------------

    /**
     * macOS/AWT delivers horizontal and vertical wheel deltas as SEPARATE events, so in any
     * slightly sloped two-finger swipe roughly half the stream carries `dx == 0` - often
     * including the last event of the gesture. A bare step there reported no direction and zero
     * progress, which blanked the affordance and (because the surface's end-of-gesture timer
     * only armed while something was shown) dropped the whole gesture without ever asking
     * whether it had earned a navigation.
     *
     * Run through [runGated] specifically: [run] would pass this even while broken, because it
     * calls [endHomeSwipe] whether or not the surface would have.
     */
    @Test
    fun `a swipe whose last event carries only vertical still navigates`() {
        val swipeThenVertical = swipe(12, -1f) + listOf(Wheel(dx = 0f, dy = 0.1f * step, atMs = 1_012L))
        assertEquals(
            HomeSwipeDirection.BACK,
            runGated(swipeThenVertical, gate = ARMED_FROM_GESTURE).navigated,
            "the surface arms its end-of-gesture timer from the gesture",
        )
        assertEquals(
            HomeSwipeDirection.BACK,
            runGated(swipeThenVertical, gate = ARMED_FROM_AFFORDANCE).navigated,
            "and would still reach a decision even armed from the affordance, since the step carries it through",
        )
    }

    /** The same event mid-swipe must not blank the puck it has no opinion about. */
    @Test
    fun `a vertical-only event carries the affordance through instead of resetting it`() {
        val out = run(swipe(12, -1f) + listOf(Wheel(dx = 0f, dy = 0.1f * step, atMs = 1_012L)))
        assertEquals(HomeSwipeDirection.BACK, out.last.direction, "the puck must not vanish mid-swipe")
        assertEquals(1f, out.last.progress, "nor snap back to empty")
    }

    /**
     * It carries state through; it does not manufacture any. A vertical-only event arriving
     * before the gesture looks real must still draw nothing, exactly as the horizontal path
     * would at that point.
     */
    @Test
    fun `a vertical-only event before MIN_EVENTS still draws nothing`() {
        val out = run(swipe(MIN_EVENTS - 1, -1f) + listOf(Wheel(dx = 0f, dy = 0.1f * step, atMs = 1_009L)))
        assertNull(out.last.direction)
        assertEquals(0f, out.last.progress)
    }

    /** A gesture that never had a horizontal component is not a swipe, and the gate must not end one. */
    @Test
    fun `a purely vertical scroll never navigates`() {
        assertNull(runGated((0 until 12).map { Wheel(dx = 0f, dy = -1f * step, atMs = 1_000L + it) }).navigated)
    }

    @Test
    fun `progress ramps to one at the commit`() {
        val half = run(swipe(5, -1f)).last
        assertTrue(half.progress in 0.4f..0.7f, "half way was ${half.progress}")
        val full = run(swipe(10, -1f))
        assertEquals(HomeSwipeDirection.BACK, full.navigated)
        assertEquals(1f, full.last.progress, "the puck is fully filled in by the time the gesture ends")
    }
}
