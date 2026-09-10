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
 * Swipe controls, off by default, and working anywhere on the screen when on.
 *
 * The gesture vocabulary and its thresholds come from the TM35 Metronome mod
 * (`main.lua`), so muscle memory carries over from the game:
 *
 *  - swipe up / down / left / right  -> the D-pad
 *  - tap                             -> A
 *  - tap and hold                    -> B
 *  - double tap                      -> START
 *  - tap, release, then tap and hold -> SELECT
 *
 * The mod's long-hold fast-forward has no meaning here and is not implemented.
 *
 * Thresholds scale with the short side of the screen exactly as the mod's do,
 * so the feel is the same on a small phone and a tablet.
 */
fun Modifier.gen1Gestures(
    enabled: Boolean,
    onButton: (GbButton) -> Unit,
): Modifier = if (!enabled) this else this.then(
    Modifier.pointerInput(enabled) {
        val shortSide = minOf(size.width, size.height).toFloat()
        val swipeThreshold = maxOf(MIN_SWIPE_PX, shortSide * SWIPE_RATIO)
        val tapSlop = maxOf(MIN_TAP_SLOP_PX, shortSide * TAP_SLOP_RATIO)
        val doubleTapDistance = maxOf(MIN_DOUBLE_TAP_DISTANCE_PX, shortSide * DOUBLE_TAP_DISTANCE_RATIO)

        // "Tap, release, then tap and hold" is SELECT, so a hold has to know
        // whether a tap preceded it. This is that memory.
        var lastTapAtMillis = 0L
        var lastTapPosition = Offset.Zero
        var lastGestureWasTap = false

        awaitEachGesture {
            // Everything below watches the Initial pass, which reaches an
            // ancestor before its children rather than after. That is what
            // makes a swipe work over a menu row or a scrolling list: on the
            // Main pass the row's own click detector and the list's scroll
            // would have taken the gesture first and this would only ever fire
            // on the bare background.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            var travelled = Offset.Zero

            /** Follows the pointer to its end, returning how far it ever got. */
            suspend fun AwaitPointerEventScope.drain(from: Offset): Offset {
                var furthest = from
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return furthest
                    // Consumed, so whatever is underneath stops treating this
                    // as a scroll or a press. Taps are never consumed, which is
                    // why ordinary tapping still works with this switched on.
                    change.consume()
                    val delta = change.position - start
                    if (delta.getDistance() > furthest.getDistance()) furthest = delta
                    if (!change.pressed) return furthest
                }
            }

            // A hold only counts while the finger stays put, so watch for
            // movement up to the hold delay before deciding.
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

            val furthest = when (outcome) {
                // The finger stayed down past the hold delay. Decide now, then
                // follow the rest of the gesture so the press underneath is
                // cancelled rather than firing on release as well.
                null -> {
                    val withinSlop = abs(travelled.x) <= tapSlop && abs(travelled.y) <= tapSlop
                    if (withinSlop) {
                        val sinceTap = System.currentTimeMillis() - lastTapAtMillis
                        val nearLastTap = (start - lastTapPosition).getDistance() <= doubleTapDistance
                        onButton(
                            if (lastGestureWasTap && sinceTap <= SELECT_WINDOW_MILLIS && nearLastTap) {
                                GbButton.SELECT
                            } else {
                                GbButton.B
                            }
                        )
                        lastGestureWasTap = false
                        drain(travelled)
                        return@awaitEachGesture
                    }
                    drain(travelled)
                }

                // Already past the swipe threshold: take the gesture.
                GestureOutcome.SWIPED -> drain(travelled)

                // Up before the hold delay, so the gesture is already over and
                // `travelled` is the whole of it. Draining here would block on
                // the next gesture's events and report this one a touch late.
                GestureOutcome.RELEASED -> travelled
            }

            val horizontal = abs(furthest.x)
            val vertical = abs(furthest.y)
            when {
                horizontal >= swipeThreshold && horizontal >= vertical -> {
                    onButton(if (furthest.x > 0) GbButton.RIGHT else GbButton.LEFT)
                    lastGestureWasTap = false
                }
                vertical >= swipeThreshold -> {
                    onButton(if (furthest.y > 0) GbButton.DOWN else GbButton.UP)
                    lastGestureWasTap = false
                }
                horizontal <= tapSlop && vertical <= tapSlop -> {
                    val now = System.currentTimeMillis()
                    val sinceTap = now - lastTapAtMillis
                    val nearLastTap = (start - lastTapPosition).getDistance() <= doubleTapDistance
                    if (lastGestureWasTap && sinceTap <= DOUBLE_TAP_MILLIS && nearLastTap) {
                        onButton(GbButton.START)
                        lastGestureWasTap = false
                    } else {
                        onButton(GbButton.A)
                        lastGestureWasTap = true
                        lastTapAtMillis = now
                        lastTapPosition = start
                    }
                }
                else -> lastGestureWasTap = false
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
private const val DOUBLE_TAP_MILLIS = 180L
private const val HOLD_DELAY_MILLIS = 500L

/**
 * The mod's SELECT is a tap followed by a hold, so its window has to outlast
 * the hold delay itself; the double-tap window is far too short for it.
 */
private const val SELECT_WINDOW_MILLIS = 900L
