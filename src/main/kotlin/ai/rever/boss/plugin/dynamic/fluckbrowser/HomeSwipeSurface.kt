package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
    // The slide in flight, when that style is chosen. Home slides out and the navigation happens
    // at the end, rather than at the start: navigating first flips this surface to the browser
    // almost immediately and the animation is never seen.
    var slidingOut by remember { mutableStateOf<HomeSwipeDirection?>(null) }
    val slide = remember { Animatable(0f) }

    LaunchedEffect(slidingOut) {
        val direction = slidingOut ?: return@LaunchedEffect
        slide.snapTo(0f)
        slide.animateTo(1f, tween(HOME_SLIDE_MS, easing = FastOutSlowInEasing))
        onNavigate(direction)
        slidingOut = null
        slide.snapTo(0f)
    }

    // The affordance's own end-of-gesture timer. The gap check inside advanceHomeSwipe cannot do
    // this alone: it only runs when a NEXT event arrives, so a swipe abandoned halfway would park
    // the puck on screen until the user happened to scroll again. Keyed on the tick, so each event
    // cancels the pending clear and starts a new one.
    LaunchedEffect(lastEventTick) {
        if (shown != null) {
            delay(GESTURE_GAP_MS + 60)
            shown = null
            progress = 0f
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
                    // Read per gesture, not cached: the host republishes the property the moment
                    // the setting changes, and a relaunch to pick that up would be a poor answer.
                    val style = homeSwipeStyle()
                    // The chevron is this style's affordance; the slide is its own.
                    shown = step.direction.takeIf { style == HomeSwipeStyle.CHEVRON }
                    progress = step.progress
                    step.navigate?.let { direction ->
                        when (style) {
                            HomeSwipeStyle.OFF -> Unit
                            HomeSwipeStyle.CHEVRON -> onNavigate(direction)
                            HomeSwipeStyle.SLIDE -> if (slidingOut == null) slidingOut = direction
                        }
                    }
                }
                // A pointer that leaves the surface ends the gesture outright. Compose does report
                // this one, unlike the page detector's world, so it does not have to be inferred
                // from silence.
                .onPointerEvent(PointerEventType.Exit) {
                    gesture = HomeSwipeGesture()
                    shown = null
                    progress = 0f
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Home slides the way the finger went; the page it reveals is drawn by
                        // the host once the navigation lands, so there is nothing to parallax
                        // behind it here.
                        val sign = if (slidingOut == HomeSwipeDirection.BACK) 1f else -1f
                        translationX = sign * size.width * slide.value
                    },
        ) {
            content()
        }
        shown?.let { direction -> HomeSwipeAffordance(direction, progress) }
    }
}

/** Matches the host's own slide, so the two surfaces do not animate at visibly different speeds. */
private const val HOME_SLIDE_MS = 220

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
