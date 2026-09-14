package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** How far the puck travels as it slides in from behind its edge. */
private val PUCK_TRAVEL_DP = 66.dp

/** Where it starts, behind the edge. */
private val PUCK_HIDDEN_DP = (-58).dp

private val PUCK_SIZE = 52.dp

/**
 * Wraps the home surface with the two-finger back/forward gesture and its affordance.
 *
 * The decision is [advanceHomeSwipe]'s; this owns only the Compose parts of it - where the
 * handler sits in the pass order, when the affordance is cleared, and what it looks like.
 *
 * **The handler is on the Main pass, on a parent of [content], and that is the whole overscroll
 * story.** Compose dispatches Main inner-to-outer, and `MouseWheelScrollingLogic` consumes a
 * wheel event there only when the scroller can actually use the delta. So the home screen's own
 * horizontally scrolling tool row consumes while it has room and stops consuming at its edge,
 * and this sees an unconsumed event exactly when nothing on the page wanted it. Moving this to
 * the Initial pass, or onto the same node as the scroller, would take the row's scrolling away.
 *
 * Current hosts publish [SWIPE_PHASE_KEY], including the explicit `unavailable` state, and only a
 * matching native Ended phase commits. Hosts from before that property existed are still within
 * this plugin's supported Boss range; absence alone selects the former quiet-gap detector for
 * compatibility. `unavailable` never does, because that would weaken release semantics on a host
 * which attempted native observation and could not provide it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HomeSwipeSurface(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onNavigate: (HomeSwipeDirection) -> Unit,
    content: @Composable () -> Unit,
) {
    var gesture by remember { mutableStateOf(HomeSwipeGesture()) }
    var shown by remember { mutableStateOf<HomeSwipeDirection?>(null) }
    var progress by remember { mutableStateOf(0f) }
    // Used only by hosts predating the native phase property. Updated hosts never idle-commit.
    var legacyEventTick by remember { mutableStateOf(0L) }

    // Drops the gesture and its affordance without deciding anything. The abandon path.
    fun cancelGesture() {
        gesture = HomeSwipeGesture()
        shown = null
        progress = 0f
    }

    // Runs one finished gesture through the decision and navigates if it earned it. The ONE
    // place onNavigate is called, so neither pointer handler below calls it directly.
    // homeSwipeEnabled() is read per gesture, not cached: the host republishes the key the moment
    // the setting changes, and a relaunch to pick that up would be a poor answer.
    fun decide(finished: HomeSwipeGesture) {
        val direction = endHomeSwipe(finished)
        if (direction != null && homeSwipeEnabled()) onNavigate(direction)
    }

    // Ends the CURRENT gesture: decide, then clear.
    fun endGesture(phase: HomeSwipeNativePhase) {
        val finished = homeSwipeWithNativeFinal(gesture, phase)
        cancelGesture()
        decide(finished)
    }

    fun endLegacyGesture() {
        val finished = gesture
        cancelGesture()
        decide(finished)
    }

    // Compatibility for supported older hosts which publish no phase property at all. The
    // explicit `unavailable` value belongs to an updated host and must remain fail-closed: using
    // quiet time there would reintroduce commits before a real finger release.
    LaunchedEffect(legacyEventTick) {
        if (homeSwipePhaseSupport(System.getProperty(SWIPE_PHASE_KEY)) != HomeSwipePhaseSupport.LEGACY) {
            return@LaunchedEffect
        }
        if (gesture.events > 0) {
            delay(GESTURE_GAP_MS + 60)
            if (System.getProperty(SWIPE_PHASE_KEY) == null) endLegacyGesture()
        }
    }

    // The host observes the real macOS contact phase. Polling a process-local property avoids an
    // API dependency between host and plugin while preserving the one fact wheel events omit:
    // whether fingers are still down. Quiet time never commits and momentum never extends a swipe.
    LaunchedEffect(gesture.nativeGestureId) {
        if (gesture.nativeGestureId == null) return@LaunchedEffect
        while (isActive) {
            delay(16)
            val phase = parseHomeSwipeNativePhase(System.getProperty(SWIPE_PHASE_KEY))
            when (homeSwipePhaseAction(gesture, phase)) {
                HomeSwipePhaseAction.DECIDE -> endGesture(phase)
                HomeSwipePhaseAction.CANCEL -> cancelGesture()
                HomeSwipePhaseAction.WAIT -> Unit
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val rawPhase = System.getProperty(SWIPE_PHASE_KEY)
                    if (homeSwipePhaseSupport(rawPhase) == HomeSwipePhaseSupport.LEGACY) {
                        if (gesture.nativeGestureId != null) cancelGesture()
                        val step =
                            advanceHomeSwipe(
                                gesture = gesture,
                                deltaX = change.scrollDelta.x,
                                deltaY = change.scrollDelta.y,
                                nowMs = System.currentTimeMillis(),
                                consumed = change.isConsumed,
                                canGoBack = canGoBack,
                                canGoForward = canGoForward,
                            )
                        step.ended?.let(::decide)
                        gesture = step.gesture
                        legacyEventTick++
                        val enabled = homeSwipeEnabled()
                        shown = step.direction.takeIf { enabled }
                        progress = step.progress
                        return@onPointerEvent
                    }
                    // Never carry progress across a capability transition. This is mostly startup
                    // hardening: an updated host initializes the property before plugins load.
                    if (gesture.nativeGestureId == null && gesture.events > 0) cancelGesture()
                    val phase = parseHomeSwipeNativePhase(rawPhase)
                    if (phase.state != HomeSwipeNativeState.ACTIVE || phase.id == null) {
                        return@onPointerEvent
                    }
                    val nativeWhen = (event.nativeEvent as? java.awt.event.MouseWheelEvent)?.`when`
                    if (!homeSwipeEventBelongsToPhase(nativeWhen, phase)) {
                        return@onPointerEvent
                    }
                    if (gesture.nativeGestureId != null && gesture.nativeGestureId != phase.id) {
                        cancelGesture()
                    }
                    val step =
                        advanceHomeSwipe(
                            gesture = gesture,
                            deltaX = change.scrollDelta.x,
                            deltaY = change.scrollDelta.y,
                            nowMs = System.currentTimeMillis(),
                            consumed = change.isConsumed,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            nativeGestureId = phase.id,
                        )
                    gesture = step.gesture
                    val enabled = homeSwipeEnabled()
                    shown = step.direction.takeIf { enabled }
                    progress = step.progress
                }
                // A pointer that leaves the surface CANCELS the gesture; it does not end it.
                // Exit is not a release: a macOS two-finger scroll moves no cursor, so the events
                // that actually raise Exit mid-swipe are the cursor drifting off the home surface
                // (onto the toolbar, under an overlay) - none of which mean the user let go.
                // Native Ended is the only event that decides; Exit only removes stale UI.
                .onPointerEvent(PointerEventType.Exit) { cancelGesture() },
    ) {
        content()
        shown?.let { direction -> HomeSwipeAffordance(direction, progress) }
    }
}

/**
 * The puck: a chevron that slides in from the edge it would navigate toward and firms up as the
 * swipe passes the commit point.
 *
 * Colours come from [BossThemeColors] rather than being written here, so it follows the app's
 * theme instead of needing a light and a dark spelling of its own.
 */
@Composable
private fun HomeSwipeAffordance(
    direction: HomeSwipeDirection,
    progress: Float,
) {
    val eased by animateFloatAsState(progress, label = "homeSwipeProgress")
    val committed = eased >= 1f
    val hiddenPx = with(androidx.compose.ui.platform.LocalDensity.current) { PUCK_HIDDEN_DP.toPx() }
    val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { PUCK_TRAVEL_DP.toPx() }
    val sign = if (direction == HomeSwipeDirection.BACK) 1f else -1f

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment =
            if (direction == HomeSwipeDirection.BACK) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Box(
            modifier =
                Modifier
                    .graphicsLayer {
                        translationX = sign * (hiddenPx + travelPx * eased)
                        alpha = 0.25f + 0.75f * eased
                        val scale = if (committed) 1.08f else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .size(PUCK_SIZE)
                    .shadow(6.dp, CircleShape)
                    .background(BossThemeColors.SurfaceColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector =
                    if (direction == HomeSwipeDirection.BACK) {
                        Icons.AutoMirrored.Filled.ArrowBack
                    } else {
                        Icons.AutoMirrored.Filled.ArrowForward
                    },
                contentDescription = null,
                tint = BossThemeColors.TextPrimary,
            )
        }
    }
}
