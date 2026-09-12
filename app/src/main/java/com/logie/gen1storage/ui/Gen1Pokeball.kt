package com.logie.gen1storage.ui

import android.graphics.Bitmap
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

    /** Who is already turning it, so several grounds do not each do the work. */
    private var driving: String? = null

    /**
     * Turns the ball for as long as the caller is on screen.
     *
     * The first ground to ask for a given shape drives it and the rest are
     * handed the same [current]; they are all the same window's ball, drawn in
     * the same place, so a second coroutine rasterising it in parallel would
     * be the same picture computed twice.
     */
    suspend fun drive(widthPx: Int, heightPx: Int, unit: Int, colour: Int, turning: Boolean) {
        val key = "$widthPx/$heightPx/$unit/$colour/$turning"
        synchronized(this) {
            if (driving != null && driving != key) return
            driving = key
        }
        try {
            var step = 0
            while (true) {
                val frame = withContext(Dispatchers.Default) {
                    build(widthPx / unit, heightPx / unit, colour, step)
                }
                if (frame != null) current = frame
                // Held still: one position, drawn, and nothing further to do.
                if (!turning) return
                delay(STEP_MS)
                step = (step + 1) % STEPS
            }
        } finally {
            synchronized(this) { if (driving == key) driving = null }
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
                    else -> abs(y * alongX - x * alongY) <= weight / 2f &&
                        distance <= radius - weight &&
                        distance >= button + weight / 2f
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
