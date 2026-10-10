package app.rev.core.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How far the content under a swipe has been dragged; it springs back when released. */
@Stable
class SwipeState {
    var offsetX by mutableFloatStateOf(0f)
        internal set
    var offsetY by mutableFloatStateOf(0f)
        internal set
}

@Composable
fun rememberSwipeState() = remember { SwipeState() }

/** Moves the content with an in-progress swipe, damped so it hints rather than slides away. */
fun Modifier.followSwipe(state: SwipeState, damping: Float = 0.5f): Modifier = graphicsLayer {
    translationX = state.offsetX * damping
    translationY = state.offsetY * damping
}

/**
 * Swipe gestures: a drag past a short distance, or a quick fling, in one direction calls that
 * direction's handler. Only directions with a handler are claimed, so a vertical swipe still
 * scrolls a list when only left/right are handled, and nested scrolling content (rows, pagers,
 * sliders) keeps its own drags.
 */
@Composable
fun Modifier.swipeGestures(
    state: SwipeState? = null,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
): Modifier {
    val left by rememberUpdatedState(onSwipeLeft)
    val right by rememberUpdatedState(onSwipeRight)
    val up by rememberUpdatedState(onSwipeUp)
    val down by rememberUpdatedState(onSwipeDown)
    val haptics = LocalHapticFeedback.current
    val horizontal = onSwipeLeft != null || onSwipeRight != null
    val vertical = onSwipeUp != null || onSwipeDown != null
    if (!horizontal && !vertical) return this

    return pointerInput(horizontal, vertical, state) {
        val distance = SWIPE_DISTANCE.toPx()
        val flingVelocity = FLING_VELOCITY.toPx()
        coroutineScope {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                var alongX = false
                // Claim the drag only once it clearly goes along an axis we handle.
                val start = awaitTouchSlopOrCancellation(first.id) { change, over ->
                    val isHorizontal = abs(over.x) > abs(over.y)
                    if (if (isHorizontal) horizontal else vertical) {
                        alongX = isHorizontal
                        change.consume()
                    }
                } ?: return@awaitEachGesture

                val tracker = VelocityTracker()
                tracker.addPosition(start.uptimeMillis, start.position)
                var total = 0f
                drag(start.id) { change ->
                    val delta = change.positionChange()
                    total += if (alongX) delta.x else delta.y
                    tracker.addPosition(change.uptimeMillis, change.position)
                    state?.let { if (alongX) it.offsetX = total else it.offsetY = total }
                    change.consume()
                }
                val velocity = tracker.calculateVelocity().let { if (alongX) it.x else it.y }
                val forward = total > distance || velocity > flingVelocity
                val backward = total < -distance || velocity < -flingVelocity
                val handler = when {
                    alongX && backward && !forward -> left
                    alongX && forward && !backward -> right
                    !alongX && backward && !forward -> up
                    !alongX && forward && !backward -> down
                    else -> null
                }
                if (handler != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    handler()
                }
                state?.let { s ->
                    launch {
                        animate(total, 0f, animationSpec = spring()) { value, _ ->
                            if (alongX) s.offsetX = value else s.offsetY = value
                        }
                    }
                }
            }
        }
    }
}

private val SWIPE_DISTANCE = 72.dp
/** Per second. */
private val FLING_VELOCITY = 900.dp
