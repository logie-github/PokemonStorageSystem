package com.logie.gen1storage.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
 * Swipe controls, off by default.
 *
 * The vocabulary is the TM35 Metronome mod's, and its thresholds, so muscle
 * memory carries over from the game:
 *
 *  - swipe up / down / left / right  -> the D-pad, moving the cursor
 *  - tap                             -> A, taking whatever the cursor is on
 *  - tap and hold                    -> B, going back
 *  - double tap                      -> START, opening OPTIONS
 *
 * Everything but the hold is read **only in empty space** — off the windows,
 * on the screen itself. That is what lets both ways of driving the app exist at
 * once: a tap on a menu row is that row's, a tap on the screen beside it is the
 * cursor's, and neither has to guess. The hold is the exception and works
 * anywhere, because going back should not depend on where a finger happens to
 * be; it consumes the gesture so the row underneath does not also fire.
 *
 * Thresholds scale with the short side of the screen exactly as the mod's do,
 * so the feel is the same on a small phone and on an unfolded one.
 */
fun Modifier.gen1Gestures(
    enabled: Boolean,
    isFreeSpace: (Offset) -> Boolean,
    onButton: (GbButton) -> Unit,
): Modifier = if (!enabled) this else this.then(
    Modifier.pointerInput(enabled) {
        val shortSide = minOf(size.width, size.height).toFloat()
        val swipeThreshold = maxOf(MIN_SWIPE_PX, shortSide * SWIPE_RATIO)
        val tapSlop = maxOf(MIN_TAP_SLOP_PX, shortSide * TAP_SLOP_RATIO)
        val doubleTapDistance = maxOf(MIN_DOUBLE_TAP_DISTANCE_PX, shortSide * DOUBLE_TAP_DISTANCE_RATIO)

        var lastTapAtMillis = 0L
        var lastTapPosition = Offset.Zero

        awaitEachGesture {
            // The Initial pass reaches an ancestor before its children, which
            // is the only way to see a gesture that starts on a scrolling list
            // at all. What is done with it still depends on where it started.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            val free = isFreeSpace(start)
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
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull GestureOutcome.RELEASED
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
                val withinSlop = abs(travelled.x) <= tapSlop && abs(travelled.y) <= tapSlop
                if (withinSlop) {
                    onButton(GbButton.B)
                    drain(travelled, consume = true)
                    return@awaitEachGesture
                }
            }

            // Everything else belongs to whatever was touched unless the touch
            // began on the screen itself.
            if (!free) {
                if (outcome != GestureOutcome.RELEASED) drain(travelled, consume = false)
                return@awaitEachGesture
            }

            val furthest = when (outcome) {
                // Up before the hold delay, so the gesture is already over and
                // `travelled` is the whole of it. Draining here would block on
                // the next gesture's events and report this one a touch late.
                GestureOutcome.RELEASED -> travelled
                else -> drain(travelled, consume = true)
            }

            val horizontal = abs(furthest.x)
            val vertical = abs(furthest.y)
            when {
                horizontal >= swipeThreshold && horizontal >= vertical ->
                    onButton(if (furthest.x > 0) GbButton.RIGHT else GbButton.LEFT)

                vertical >= swipeThreshold ->
                    onButton(if (furthest.y > 0) GbButton.DOWN else GbButton.UP)

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

private enum class GestureOutcome { SWIPED, RELEASED }

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

