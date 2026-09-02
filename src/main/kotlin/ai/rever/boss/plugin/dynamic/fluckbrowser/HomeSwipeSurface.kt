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
    // Bumped on every scroll event so the clear below re-arms rather than accumulating.
    var lastEventTick by remember { mutableStateOf(0L) }

    // Ends the gesture and fires the navigation IF it earned one - the only two places that
    // happen, so onNavigate is called from neither the Scroll nor the Exit handler directly.
    // Read per gesture, not cached: the host republishes the key the moment the setting changes,
    // and a relaunch to pick that up would be a poor answer.
    fun endGesture() {
        val direction = endHomeSwipe(gesture)
        gesture = HomeSwipeGesture()
        shown = null
        progress = 0f
        if (direction != null && homeSwipeEnabled()) onNavigate(direction)
    }

    // The affordance's own end-of-gesture timer, and the ONLY place a swipe held past the commit
    // distance actually navigates - see endHomeSwipe's KDoc for why that decision waits for here
    // rather than firing the moment progress reaches 1 inside the Scroll handler below. The gap
    // check inside advanceHomeSwipe cannot do this alone: it only runs when a NEXT event arrives,
    // so a swipe held or abandoned would never end on its own. Keyed on the tick, so each event
    // cancels the pending end and starts a new one.
    LaunchedEffect(lastEventTick) {
        if (shown != null) {
            delay(GESTURE_GAP_MS + 60)
            endGesture()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
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
                    gesture = step.gesture
                    lastEventTick++
                    val enabled = homeSwipeEnabled()
                    shown = step.direction.takeIf { enabled }
                    progress = step.progress
                }
                // A pointer that leaves the surface ends the gesture outright - Compose does
                // report this one, unlike the page detector's world, so it does not have to be
                // inferred from silence. Still routed through endGesture(): lifting past the
                // commit distance is exactly as much a release as the timeout is.
                .onPointerEvent(PointerEventType.Exit) { endGesture() },
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
