package com.logie.gen1storage.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** The Game Boy buttons the gesture layer can produce. */
enum class GbButton { UP, DOWN, LEFT, RIGHT, A, B, START, SELECT }

/**
 * Whether swipes are driving the app.
 *
 * Read by every list in it: with swipes on, a list does not scroll under a
 * finger, because the same drag cannot be both a scroll and a step. The lists
 * still follow the cursor, so nothing goes out of reach — see
 * [LazyListState.scrollToRow].
 */
val LocalGen1Swipe = staticCompositionLocalOf { false }

/**
 * How the app is driven, alongside tapping.
 *
 * Off by default, and off it is not there at all: the whole layer is left out
 * of the modifier chain, so the app is tapped and scrolled and nothing reads
 * a gesture over it. The hold is part of this and goes with it — back is the
 * BACK row and the phone's own back gesture, as it is in any app.
 *
 * On, the vocabulary is the TM35 Metronome mod's, and its thresholds, so
 * muscle memory carries over from the game:
 *
 *  - swipe up / down / left / right  -> the D-pad, moving the cursor
 *  - tap                             -> A, taking whatever the cursor is on
 *  - tap and hold                    -> B, going back
 *  - double tap                      -> START, opening OPTIONS
 *
 * And a swipe is read **wherever it lands**: over a window, over a box, over
 * a list. That is the point of the setting — a player who cannot reach the
 * far corner of a screen should not have to. The swipe is consumed, so the
 * list under it does not scroll as well; scrolling by finger is off entirely
 * while this is on ([LocalGen1Swipe]), because a list that both steps and
 * slides does neither predictably.
 *
 * A tap still belongs to whatever it lands on. It is only in the empty space
 * beside the windows that a tap is A and two taps are START.
 *
 * Thresholds scale with the short side of the screen exactly as the mod's do,
 * so the feel is the same on a small phone and on an unfolded one.
 */
fun Modifier.gen1Gestures(
    /** Whether the swipe vocabulary is on at all. */
    swipes: Boolean,
    isFreeSpace: (Offset) -> Boolean,
    isHoldClaimed: (Offset) -> Boolean,
    /** Whether something under a drag's start reads it directly. See [Gen1WindowBounds.isSwipeClaimed]. */
    isSwipeClaimed: (Offset) -> Boolean = { false },
    onButton: (GbButton) -> Unit,
): Modifier = if (!swipes) this else this.then(
    Modifier.pointerInput(Unit) {
        val shortSide = minOf(size.width, size.height).toFloat()
        val swipeThreshold = maxOf(MIN_SWIPE_PX, shortSide * SWIPE_RATIO)
        val tapSlop = maxOf(MIN_TAP_SLOP_PX, shortSide * TAP_SLOP_RATIO)
        val doubleTapDistance = maxOf(MIN_DOUBLE_TAP_DISTANCE_PX, shortSide * DOUBLE_TAP_DISTANCE_RATIO)
        val systemEdge = EDGE_DP * density

        var lastTapAtMillis = 0L
        var lastTapPosition = Offset.Zero

        awaitEachGesture {
            // The Initial pass reaches an ancestor before its children, which
            // is the only way to see a gesture that starts on a scrolling list
            // at all. What is done with it still depends on where it started.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            // The side strips belong to the system's back gesture. A swipe
            // there is taken away mid-flight, and what was left of it used to
            // arrive here looking like a tap — which is a confirm, so swiping
            // back took whatever the cursor was sitting on.
            if (start.x <= systemEdge || start.x >= size.width - systemEdge) {
                return@awaitEachGesture
            }
            val free = isFreeSpace(start)
            // Read by a card the wallet is dragging directly: past this point,
            // anything beyond a plain release is left alone, unconsumed, for
            // that window's own gesture detector rather than turned into a
            // D-pad direction here.
            val claimed = isSwipeClaimed(start)
            var travelled = Offset.Zero

            /** Follows the pointer to its end, returning how far it ever got. */
            suspend fun AwaitPointerEventScope.drain(from: Offset, consume: Boolean): Offset {
                var furthest = from
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return furthest
                    if (consume) change.consume()
                    val delta = change.position - start
                    if (delta.getDistance() > furthest.getDistance()) furthest = delta
                    if (!change.pressed) return furthest
                }
            }

            val outcome = withTimeoutOrNull(HOLD_DELAY_MILLIS) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // The pointer vanishing is not a release: something else
                    // took the gesture. Whatever it was, it was not a tap.
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull GestureOutcome.CANCELLED
                    travelled = change.position - start
                    if (abs(travelled.x) > swipeThreshold || abs(travelled.y) > swipeThreshold) {
                        return@withTimeoutOrNull GestureOutcome.SWIPED
                    }
                    if (!change.pressed) return@withTimeoutOrNull GestureOutcome.RELEASED
                }
                @Suppress("UNREACHABLE_CODE")
                GestureOutcome.RELEASED
            }

            // Held still past the delay: B, wherever the finger is. Consumed
            // and drained so the row underneath does not fire on release too.
            if (outcome == null) {
                // Generous about drift: a finger held still for half a second
                // is never perfectly still, and cancelling the hold over a few
                // pixels is why it sometimes did nothing at all.
                val held = abs(travelled.x) < swipeThreshold && abs(travelled.y) < swipeThreshold
                if (held && !isHoldClaimed(start) && !claimed) {
                    onButton(GbButton.B)
                    drain(travelled, consume = true)
                    return@awaitEachGesture
                }
            }

            if (outcome == GestureOutcome.CANCELLED) return@awaitEachGesture

            // A swipe is the D-pad wherever it starts, and it is consumed so
            // the list under it does not also move. Nowhere is exempt: a list
            // that kept its own drags was one rectangle of the screen where
            // up and down meant something else, and one exception is what
            // makes the rest read as arbitrary rather than as a rule.
            //
            // A tap over a window belongs to the window, which hands it to
            // [gen1Tap] and so to the cursor. Only a tap on the screen itself
            // reaches the A below.
            if (!free) {
                if (outcome == GestureOutcome.RELEASED) return@awaitEachGesture
                if (claimed) return@awaitEachGesture
                direction(drain(travelled, consume = true), swipeThreshold)?.let(onButton)
                return@awaitEachGesture
            }

            val furthest = when (outcome) {
                // Up before the hold delay, so the gesture is already over and
                // `travelled` is the whole of it. Draining here would block on
                // the next gesture's events and report this one a touch late.
                GestureOutcome.RELEASED -> travelled
                else -> if (claimed) return@awaitEachGesture else drain(travelled, consume = true)
            }

            val horizontal = abs(furthest.x)
            val vertical = abs(furthest.y)
            val step = direction(furthest, swipeThreshold)
            when {
                step != null -> onButton(step)

                horizontal <= tapSlop && vertical <= tapSlop -> {
                    val now = System.currentTimeMillis()
                    val nearLastTap = (start - lastTapPosition).getDistance() <= doubleTapDistance
                    if (now - lastTapAtMillis <= DOUBLE_TAP_MILLIS && nearLastTap) {
                        onButton(GbButton.START)
                        lastTapAtMillis = 0L
                    } else {
                        onButton(GbButton.A)
                        lastTapAtMillis = now
                        lastTapPosition = start
                    }
                }
            }
        }
    }
)

/** Which way a drag went, or null if it never went far enough to be one. */
private fun direction(travelled: Offset, threshold: Float): GbButton? {
    val horizontal = abs(travelled.x)
    val vertical = abs(travelled.y)
    return when {
        horizontal >= threshold && horizontal >= vertical ->
            if (travelled.x > 0) GbButton.RIGHT else GbButton.LEFT

        vertical >= threshold -> if (travelled.y > 0) GbButton.DOWN else GbButton.UP
        else -> null
    }
}

private enum class GestureOutcome { SWIPED, RELEASED, CANCELLED }

/** As wide as the system's own back-gesture strip down each side. */
private const val EDGE_DP = 24f

// TM35 Metronome main.lua, verbatim.
private const val SWIPE_RATIO = 0.055f
private const val MIN_SWIPE_PX = 22f
private const val TAP_SLOP_RATIO = 0.025f
private const val MIN_TAP_SLOP_PX = 10f
private const val DOUBLE_TAP_DISTANCE_RATIO = 0.08f
private const val MIN_DOUBLE_TAP_DISTANCE_PX = 28f
/** Long enough to be a deliberate double tap rather than the mod's 180ms. */
private const val DOUBLE_TAP_MILLIS = 280L
private const val HOLD_DELAY_MILLIS = 500L

