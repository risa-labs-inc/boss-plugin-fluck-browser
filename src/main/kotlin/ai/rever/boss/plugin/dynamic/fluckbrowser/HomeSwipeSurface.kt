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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.awt.event.MouseEvent
import java.util.logging.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val homeSwipeLog = Logger.getLogger("HomeSwipeSurface")

/** These values are read by event handlers and effects, never during composition. */
private class HomeSwipeRuntime {
    var reportedTimestampFailure = false
    var reportedUnavailable = false
    var lastNativeEventNanos = System.nanoTime()
}

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
 * On macOS, current hosts publish [SWIPE_PHASE_KEY], including `unavailable`, and only a
 * matching native Ended phase commits. Hosts from before that property existed are still within
 * this plugin's supported Boss range; absence alone selects the former quiet-gap detector for
 * compatibility. Other platforms always retain that detector. On macOS `unavailable` never
 * does, because that would weaken release semantics on a host which attempted native observation and could not provide it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HomeSwipeSurface(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onNavigate: (HomeSwipeDirection) -> Unit,
    content: @Composable () -> Unit,
) {
    val phaseSource = remember { HomeSwipePhaseSource() }
    val contactGuard = HomeSwipeContacts.guard
    val runtime = remember { HomeSwipeRuntime() }
    var gesture by remember { mutableStateOf(HomeSwipeGesture()) }
    var shown by remember { mutableStateOf<HomeSwipeDirection?>(null) }
    var progress by remember { mutableStateOf(0f) }
    // Used only by hosts predating the native phase property. Updated hosts never idle-commit.
    var legacyEventTick by remember { mutableStateOf(0L) }

    // Drops the gesture and its affordance without deciding anything. The abandon path.
    fun cancelGesture() {
        contactGuard.cancel(gesture)
        gesture = HomeSwipeGesture()
        shown = null
        progress = 0f
    }

    // Runs legacy and watchdog completions through the decision and navigates if earned. Retained
    // releases are decided by prepareHomeSwipeScroll and dispatched directly by the pointer handler.
    // homeSwipeEnabled() is read for each pointer event or asynchronous completion, not cached:
    // the host republishes the key when the setting changes, so a relaunch is unnecessary.
    fun decide(finished: HomeSwipeGesture, enabled: Boolean = homeSwipeEnabled()): Boolean {
        val direction = endHomeSwipe(finished)
        if (direction != null && enabled) {
            onNavigate(direction)
            return true
        }
        return false
    }

    // Ends the CURRENT gesture: decide, then clear.
    fun endGesture(phase: HomeSwipeNativePhase): Boolean {
        val finished = homeSwipeWithNativeFinal(gesture, phase)
        cancelGesture()
        return decide(finished)
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
        if (phaseSource.support() != HomeSwipePhaseSupport.LEGACY) {
            return@LaunchedEffect
        }
        if (gesture.events > 0) {
            delay(GESTURE_GAP_MS + 60)
            if (phaseSource.support() == HomeSwipePhaseSupport.LEGACY) endLegacyGesture()
        }
    }

    // The host observes the real macOS contact phase. Polling a process-local property avoids an
    // API dependency between host and plugin while preserving the one fact wheel events omit:
    // whether fingers are still down. Quiet time never commits and momentum never extends a swipe.
    LaunchedEffect(gesture.nativeGestureId) {
        val ownedId = gesture.nativeGestureId ?: return@LaunchedEffect
        while (isActive) {
            delay(16)
            val phase = phaseSource.phase(ownedId)
            val idleMs = (System.nanoTime() - runtime.lastNativeEventNanos) / 1_000_000
            val action = homeSwipeOwnedWatchdogAction(
                ownedId, gesture, phase, idleMs, reliableLifecycle = phaseSource.hasTerminalHistory(),
            ) ?: break
            when (action) {
                HomeSwipePhaseAction.DECIDE -> {
                    endGesture(phase)
                    break
                }
                HomeSwipePhaseAction.CANCEL -> {
                    cancelGesture()
                    break
                }
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
                    val rawPhase = phaseSource.raw()
                    val enabled = homeSwipeEnabled()
                    val nativeWhen = (event.nativeEvent as? MouseEvent)?.`when`
                    val prepared = prepareHomeSwipeScroll(
                        gesture, rawPhase, phaseSource::terminalHistory, nativeWhen, contactGuard,
                        phaseSource.isMac, enabled,
                    )
                    gesture = prepared.gesture
                    if (prepared.clearAffordance) {
                        shown = null
                        progress = 0f
                    }
                    prepared.navigate?.let {
                        onNavigate(it)
                        return@onPointerEvent
                    }
                    val gate = prepared.gate
                    if (rawPhase == "unavailable" && enabled && !runtime.reportedUnavailable) {
                        runtime.reportedUnavailable = true
                        homeSwipeLog.warning(
                            "Native home swipe unavailable. Check BOSS trackpad settings for release-detection status.",
                        )
                    }
                    if (!gate.accept) {
                        if (gate.timestampRejected && !runtime.reportedTimestampFailure) {
                            runtime.reportedTimestampFailure = true
                            homeSwipeLog.warning(
                                "Native home swipe ignored: AWT event timestamp is missing or predates " +
                                    "the host contact beyond the clock tolerance. Check host/AWT clock synchronization.",
                            )
                        }
                        return@onPointerEvent
                    }
                    if (gate.legacy) {
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
                        step.ended?.let { decide(it, enabled) }
                        gesture = step.gesture
                        legacyEventTick++
                        shown = step.direction.takeIf { enabled }
                        progress = step.progress
                        return@onPointerEvent
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
                            nativeGestureId = gate.nativeId,
                        )
                    runtime.lastNativeEventNanos = System.nanoTime()
                    gesture = step.gesture
                    shown = step.direction.takeIf { enabled }
                    progress = step.progress
                }
                // A pointer that leaves the surface CANCELS the gesture; it does not end it.
                // Exit is not a release: a macOS two-finger scroll moves no cursor, so the events
                // that actually raise Exit mid-swipe are the cursor drifting off the home surface
                // (onto the toolbar, under an overlay) - none of which mean the user let go.
                // Native Ended is the only event that decides. Exit also latches cancellation
                // process-wide, so moving to a sibling home surface cannot revive this contact.
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
    val (hiddenPx, travelPx) = with(LocalDensity.current) { PUCK_HIDDEN_DP.toPx() to PUCK_TRAVEL_DP.toPx() }
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
