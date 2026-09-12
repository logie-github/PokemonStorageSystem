package com.logie.gen1storage.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The ball turning behind everything.
 *
 * Its centre is the screen's bottom right corner and it grows from there to
 * half the screen's width, so what shows is one quarter of a large Poké Ball
 * sitting in that corner: the rim arcing from the bottom edge round to the
 * right one, the button near the corner, and the seam sweeping between them.
 *
 * Drawn a pixel at a time rather than as a circle. A stroked path would give
 * smooth, anti-aliased edges — a modern picture of a Poké Ball, which is the
 * one thing this interface never shows. Every cell here is either the palette's
 * lightest colour or nothing at all, on the same grid the dither uses, so the
 * ball is made of the same pixels as the ground it lies on.
 *
 * Only the seam actually turns: a ring is the same ring at every angle, so what
 * the eye reads as rotation is the seam sweeping round the corner. The turn is
 * taken in [STEPS] fixed positions rather than continuously, which is both what
 * keeps the edges on the grid and what makes it read as animation cels instead
 * of a tweened object.
 */
object Gen1Pokeball {

    /** How many distinct positions one turn is taken in. */
    const val STEPS = 120

    /** How long one full turn takes. Slow: it is scenery, not an event. */
    const val PERIOD_MS = 12_000L

    /** Milliseconds each position holds for. */
    const val STEP_MS = PERIOD_MS / STEPS

    /** A rasterised position, in cells, and the cell size it was drawn at. */
    class Frame(val image: ImageBitmap, val cellsWide: Int, val cellsHigh: Int)

    /**
     * The position on screen now, ready to draw.
     *
     * Snapshot state written from a background coroutine and read inside a
     * draw lambda, so a turn costs one blit and nothing else. It used to be
     * rasterised inside the draw pass itself — eight thousand cells and a
     * bitmap allocation, on the main thread, ten times a second, in the middle
     * of laying out whatever else was on screen. That is a frame lost every
     * hundred milliseconds, which is exactly what a laggy touch is.
     */
    var current by mutableStateOf<Frame?>(null)
        private set

    /** Whether somebody is already turning it. */
    private var busy = false

    /** What [current] was built from, so a handover does not rebuild it. */
    private var builtFrom: String? = null

    /**
     * Which of the [STEPS] positions the ball is at, read off the clock.
     *
     * Taken from elapsed time rather than counted up by whoever happens to be
     * driving. Every screen carries its own piece of ground, several are in
     * composition at once while one is replacing another, and each of them
     * asks for the ball: with a counter, a driver leaving and another taking
     * over restarted the turn from zero, so the seam jumped back to the bottom
     * edge every time a screen changed. Off the clock, whoever is driving
     * computes the same position, and a handover cannot be seen at all.
     */
    private fun phase(): Int =
        ((SystemClock.elapsedRealtime() / STEP_MS) % STEPS).toInt()

    /**
     * Turns the ball for as long as the caller is on screen.
     *
     * One driver at a time; the rest wait a step and try again rather than
     * giving up, because the one that claimed it is usually the screen being
     * navigated away from. Giving up left nothing turning the ball once that
     * screen went — the corner simply froze on whatever position it had
     * reached — and clearing the claim on the way out let two coroutines
     * write positions over each other, which is a seam flickering between two
     * angles ten times a second.
     */
    suspend fun drive(widthPx: Int, heightPx: Int, unit: Int, colour: Int, turning: Boolean) {
        while (true) {
            val claimed = synchronized(this) { if (busy) false else { busy = true; true } }
            if (!claimed) {
                delay(STEP_MS)
                continue
            }
            try {
                while (true) {
                    val step = if (turning) phase() else 0
                    val from = "$widthPx/$heightPx/$unit/$colour/$step"
                    if (from != builtFrom) {
                        val frame = withContext(Dispatchers.Default) {
                            build(widthPx / unit, heightPx / unit, colour, step)
                        }
                        if (frame != null) {
                            current = frame
                            builtFrom = from
                        }
                    }
                    // Held still: one position, drawn, and nothing further to do.
                    if (!turning) return
                    delay(STEP_MS)
                }
            } finally {
                synchronized(this) { busy = false }
            }
        }
    }

    private fun build(wide: Int, high: Int, colour: Int, step: Int): Frame? {
        // Half the window across, measured from the corner it is centred on.
        val radius = wide / 2f
        // Only the quarter of the ball that is on screen is drawn, and only
        // out to its own rim: the rest is off two edges and would be a bitmap
        // several times the size for nothing.
        val side = minOf(ceil(radius).toInt(), wide, high)
        if (side < MIN_CELLS) return null

        val pixels = IntArray(side * side)
        val weight = (radius * 2f * LINE).coerceAtLeast(2f)
        val button = radius * BUTTON
        val angle = step.toFloat() / STEPS * 2f * PI.toFloat()
        val alongX = cos(angle)
        val alongY = sin(angle)

        for (cellY in 0 until side) {
            // The bitmap's bottom right cell is the ball's centre.
            val y = cellY + 0.5f - side
            val row = cellY * side
            for (cellX in 0 until side) {
                val x = cellX + 0.5f - side
                val distance = hypot(x, y)
                val on = when {
                    // The rim, sitting inside the ball's full reach.
                    distance <= radius && distance >= radius - weight -> true
                    // The button.
                    abs(distance - button) <= weight / 2f -> true
                    // The seam, from the button out to the rim. Turned by the
                    // step; the rings are not, having nothing to turn.
                    //
                    // Two of them, at right angles. A line through the centre
                    // crosses the quarter of the ball that is on screen for
                    // only half of every turn: with one seam the corner showed
                    // it sweep to the edge, vanish for three seconds, then
                    // appear at the other edge — a blink rather than a
                    // rotation. The second seam reaches the corner exactly as
                    // the first one leaves it, so at any moment one of the two
                    // is crossing what is on screen and the sweep is unbroken.
                    else -> distance <= radius - weight &&
                        distance >= button + weight / 2f &&
                        (
                            abs(y * alongX - x * alongY) <= weight / 2f ||
                                abs(y * alongY + x * alongX) <= weight / 2f
                            )
                }
                pixels[row + cellX] = if (on) colour else 0
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        return Frame(bitmap.asImageBitmap(), side, side)
    }

    /** Below this there is no room for a shape at all. */
    private const val MIN_CELLS = 8

    /** Line weight as a fraction of the ball's width, as the real one is drawn. */
    private const val LINE = 0.028f

    /** The button's radius, as a fraction of the ball's. */
    private const val BUTTON = 0.21f
}
