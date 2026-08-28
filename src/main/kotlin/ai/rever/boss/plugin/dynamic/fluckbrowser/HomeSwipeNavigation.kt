package ai.rever.boss.plugin.dynamic.fluckbrowser

/**
 * Two-finger swipe navigation on the home surface.
 *
 * The host detects this gesture inside the page, which is the only place a wheel is observable
 * while Chromium owns a native surface over the window. Home is the one surface with no page:
 * [BrowserSurface.DASHBOARD] renders the host's home screen *instead of* the browser view, so
 * nothing is injected there and the swipe used to die at the one place a user most wants it -
 * you could swipe back to home and then not swipe forward again.
 *
 * With no native surface in the way, Compose sees the scroll here, so home gets its own detector.
 * It is deliberately a separate, simpler one rather than a port of the page script:
 *
 *  - **Overscroll comes free.** Compose 1.11's `MouseWheelScrollingLogic` consumes a wheel event
 *    at the Main pass only when the scroller can actually use the delta, so a horizontally
 *    scrolling row that still has room consumes and one at its edge does not. Reading
 *    `isConsumed` from a parent at Main is therefore the same question the page script has to
 *    answer by walking the scroll chain itself. Home has such a row - the tool grid - so this is
 *    load-bearing, not theoretical.
 *  - **The units are not the page's.** Compose's `scrollDelta` carries AWT's
 *    `preciseWheelRotation`, which on macOS is the legacy `NSEvent.deltaX`; for a precise device
 *    AppKit reports that as roughly a tenth of `scrollingDeltaX`, which is what a DOM `wheel`
 *    event carries. So [COMMIT_UNITS] is the page detector's 90 pixels expressed in this space,
 *    not a second opinion about how far a swipe should be.
 *
 * Pure, so the thresholds are pinned by tests rather than by trying to swipe carefully by hand.
 */
internal enum class HomeSwipeDirection { BACK, FORWARD }

/**
 * The key the host publishes the gesture's on/off state on.
 *
 * A system property is the only channel the two halves of this gesture share. Home is drawn by
 * this plugin and web pages by the host, in different repos; `PluginContext.settingsProvider` only
 * opens the Settings window and reads nothing, so there is no api route for a value. The host runs
 * this plugin in its own process and republishes the key whenever the setting changes, which is
 * why it is read per gesture rather than cached - the user must not have to relaunch.
 */
internal const val SWIPE_ENABLED_KEY = "BOSS_BROWSER_SWIPE_NAV"

/**
 * Whether the gesture is on, according to the host.
 *
 * The environment is consulted first for the same reason the host does it: an exported variable
 * outranks a setting everywhere else in this app, and someone debugging one session by exporting
 * it should not find home disagreeing with every page.
 *
 * A host older than the setting publishes nothing, and the fallback is ON: that is what such a
 * host's own pages do, and defaulting to off would silently remove a gesture. Anything
 * unrecognised is "no opinion" for the same reason.
 */
internal fun homeSwipeEnabled(
    env: String? = System.getenv(SWIPE_ENABLED_KEY),
    property: String? = System.getProperty(SWIPE_ENABLED_KEY),
): Boolean = parseHomeSwipeEnabled(env) ?: parseHomeSwipeEnabled(property) ?: true

internal fun parseHomeSwipeEnabled(raw: String?): Boolean? =
    when (raw?.trim()?.lowercase()) {
        "off", "false", "0", "no" -> false
        "on", "true", "1", "yes", "chevron" -> true
        else -> null
    }

/**
 * Travel that commits, in Compose wheel-rotation units.
 *
 * **Tuned on hardware, not derived.** It started at 9.0f, being the page detector's 90 pixels
 * converted at the 1:10 ratio AppKit is said to apply to the legacy delta, and on a real trackpad
 * that was much too long a swipe. The lesson is in the class note: the conversion between what a
 * DOM `wheel` event reports and what AWT hands Compose is not a clean ratio worth reasoning from,
 * because Chromium scales its own deltas on the way to the page. So this number answers to how the
 * gesture feels next to the same one on an ordinary page, and nothing else.
 *
 * Erring short is deliberate. A gesture that fires a little early is a visible, recoverable
 * mistake; one that fires late reads as the feature not working at all, which is what this
 * whole change exists to stop.
 */
internal const val COMMIT_UNITS = 3.5f

/** No scroll event for this long ends the gesture. Matches the page detector. */
internal const val GESTURE_GAP_MS = 120L

/**
 * Events before anything commits or is drawn.
 *
 * A trackpad swipe crossing [COMMIT_UNITS] emits dozens of events; this only rules out the
 * single stray delta. The page detector's companion guard - refusing large individual deltas as
 * mouse-wheel-shaped - does NOT transfer: in this space a mouse notch is 1.0 and a trackpad
 * event is a fraction of one, so a size bound would reject the trackpad and keep the mouse.
 */
internal const val MIN_EVENTS = 3

/**
 * Chrome's own cancellation rules, ported from `history_swiper.mm`
 * (`shouldCancelHorizontalSwipeWithCurrentPoint`). Three tiers rather than one ratio:
 *
 * ```
 * if (yDelta > 2 * xDelta)                        cancel
 * if (yDelta * 1.3 > xDelta && yDelta > 0.01)     cancel
 * if (yDelta > 0.24)                              cancel
 * ```
 *
 * The second is the binding one in practice, and it is LOOSER than the half-of-horizontal rule
 * that used to be here: Chrome accepts a swipe until vertical reaches about 0.77 of horizontal, so
 * gestures it would have taken were being refused.
 *
 * **Chrome's numbers are fractions of the trackpad**, read from `NSTouch.normalizedPosition`, which
 * nothing in this process can see. The two ratios carry over unchanged; the two absolute limits are
 * carried over as the same fractions of the commit distance that Chrome's are of its own - 0.01/0.08
 * and 0.24/0.08 - which is the closest honest translation between the two spaces.
 */
private const val CANCEL_STRONG_RATIO = 2f
private const val CANCEL_MIXED_RATIO = 1.3f
private val CANCEL_VERTICAL_LOW get() = COMMIT_UNITS * 0.125f
private val CANCEL_VERTICAL_HIGH get() = COMMIT_UNITS * 3f

/** One gesture in progress. Immutable; [advanceHomeSwipe] returns the next one. */
internal data class HomeSwipeGesture(
    val accumX: Float = 0f,
    /**
     * The vertical PATH length, the sum of every `|dy|` - not a net total.
     *
     * Chrome's asymmetry, and the point of it: horizontal counts net progress, so a reversal spends
     * it, while vertical counts distance travelled, so wobble accumulates and counts against the
     * gesture instead of cancelling itself out.
     */
    val verticalPath: Float = 0f,
    val events: Int = 0,
    val lastEventAtMs: Long = 0,
    /** Ruled out; stays ruled out until the fingers lift, so a rejected swipe cannot come back. */
    val rejected: Boolean = false,
    /** Already navigated, so one continuous swipe navigates exactly once. */
    val committed: Boolean = false,
    val direction: HomeSwipeDirection? = null,
)

/**
 * What one scroll event did: the gesture that follows it, whether to navigate now, and how far
 * along the affordance should be drawn (0 when there is nothing to draw).
 */
internal data class HomeSwipeStep(
    val gesture: HomeSwipeGesture,
    val navigate: HomeSwipeDirection? = null,
    val direction: HomeSwipeDirection? = null,
    val progress: Float = 0f,
)

/**
 * Fold one scroll event into [gesture].
 *
 * [consumed] is whether a child scroller took the event; a consumed event is the page scrolling
 * something, never a navigation. [canGoBack] and [canGoForward] gate the direction, so an
 * unavailable one shows no affordance rather than an affordance that does nothing.
 */
internal fun advanceHomeSwipe(
    gesture: HomeSwipeGesture,
    deltaX: Float,
    deltaY: Float,
    nowMs: Long,
    consumed: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
): HomeSwipeStep {
    // A gap in the stream is the end of the previous gesture. Unlike the page detector there is
    // no timer companion to this: a Compose surface can also be told the pointer left, and the
    // caller ends the gesture on exit, which covers the lifted finger that never sends another
    // event.
    val continuing = gesture.lastEventAtMs != 0L && nowMs - gesture.lastEventAtMs <= GESTURE_GAP_MS
    val base = if (continuing) gesture else HomeSwipeGesture()
    val stamped = base.copy(lastEventAtMs = nowMs)

    if (stamped.committed || stamped.rejected) return HomeSwipeStep(stamped)
    // Something under the pointer scrolled. That is what the event was for.
    if (consumed) return HomeSwipeStep(stamped.copy(rejected = true))

    // Vertical travel counts from the first event of the gesture, including events with no
    // horizontal component, or a plain vertical scroll that curls sideways at the end would
    // arrive here looking like a fresh clean swipe.
    val withY = stamped.copy(verticalPath = stamped.verticalPath + kotlin.math.abs(deltaY))
    if (deltaX == 0f) return HomeSwipeStep(withY)

    val moved = withY.copy(accumX = withY.accumX + deltaX, events = withY.events + 1)

    // Chrome's three tiers, in its order.
    val yDelta = moved.verticalPath
    val xDelta = kotlin.math.abs(moved.accumX)
    val cancelled =
        yDelta > CANCEL_STRONG_RATIO * xDelta ||
            (yDelta * CANCEL_MIXED_RATIO > xDelta && yDelta > CANCEL_VERTICAL_LOW) ||
            yDelta > CANCEL_VERTICAL_HIGH
    if (cancelled) return HomeSwipeStep(moved.copy(rejected = true))

    val heading = if (moved.accumX < 0f) HomeSwipeDirection.BACK else HomeSwipeDirection.FORWARD
    val settled =
        when {
            // Decided once per gesture and latched: which way it goes, and whether that way exists.
            moved.direction == null -> {
                val available = if (heading == HomeSwipeDirection.BACK) canGoBack else canGoForward
                if (!available) return HomeSwipeStep(moved.copy(rejected = true))
                moved.copy(direction = heading)
            }
            // Reversed mid-swipe. An abandon, rather than flipping the navigation under the user.
            moved.direction != heading -> return HomeSwipeStep(moved.copy(rejected = true))
            else -> moved
        }

    if (settled.events < MIN_EVENTS) return HomeSwipeStep(settled)

    val progress = (kotlin.math.abs(settled.accumX) / COMMIT_UNITS).coerceAtMost(1f)
    if (progress < 1f) {
        return HomeSwipeStep(settled, direction = settled.direction, progress = progress)
    }
    return HomeSwipeStep(
        settled.copy(committed = true),
        navigate = settled.direction,
        direction = settled.direction,
        progress = 1f,
    )
}
