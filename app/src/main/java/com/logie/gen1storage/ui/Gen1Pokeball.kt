package com.logie.gen1storage.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The ball turning behind everything.
 *
 * Its centre is the screen's bottom right corner and it grows from there until
 * its rim reaches the top left corner, so the whole screen is inside it and
 * what shows is one quarter of a very large Poké Ball: the button's arc in the
 * near corner, the seam sweeping out of it, and the rim itself grazing the far
 * corner it was grown to.
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

    private var key: String = ""
    private var frame: Frame? = null

    /**
     * The ball at [step], covering a window [widthPx] by [heightPx].
     *
     * Cached on everything that shapes it, because every piece of ground on
     * screen asks for the same one in the same frame and only the first of them
     * should pay for it.
     */
    @Synchronized
    fun frame(widthPx: Int, heightPx: Int, unit: Int, colour: Int, step: Int): Frame? {
        if (widthPx <= 0 || heightPx <= 0 || unit <= 0) return null
        val wanted = "$widthPx/$heightPx/$unit/$colour/$step"
        frame?.let { if (key == wanted) return it }
        val built = build(widthPx / unit, heightPx / unit, colour, step) ?: return null
        key = wanted
        frame = built
        return built
    }

    private fun build(wide: Int, high: Int, colour: Int, step: Int): Frame? {
        if (wide < MIN_CELLS || high < MIN_CELLS) return null
        val pixels = IntArray(wide * high)

        // The centre is the bottom right corner of the window and the rim
        // reaches the opposite one, so the radius is the screen's diagonal.
        val radius = hypot(wide.toFloat(), high.toFloat())
        val button = radius * BUTTON
        val angle = step.toFloat() / STEPS * 2f * PI.toFloat()
        val alongX = cos(angle)
        val alongY = sin(angle)

        for (cellY in 0 until high) {
            val y = cellY + 0.5f - high
            val row = cellY * wide
            for (cellX in 0 until wide) {
                val x = cellX + 0.5f - wide
                val distance = hypot(x, y)
                val on = when {
                    // The rim, sitting inside the ball's full reach.
                    distance <= radius && distance >= radius - WEIGHT -> true
                    // The button.
                    abs(distance - button) <= WEIGHT / 2f -> true
                    // The seam, from the button out to the rim. Turned by the
                    // step; the rings are not, having nothing to turn.
                    else -> abs(y * alongX - x * alongY) <= WEIGHT / 2f &&
                        distance <= radius - WEIGHT &&
                        distance >= button + WEIGHT / 2f
                }
                pixels[row + cellX] = if (on) colour else 0
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, wide, high, Bitmap.Config.ARGB_8888)
        return Frame(bitmap.asImageBitmap(), wide, high)
    }

    /** Below this there is no room for a shape at all. */
    private const val MIN_CELLS = 8

    /**
     * Line weight in cells, held rather than scaled.
     *
     * A real Poké Ball's outline is a few per cent of its width, and this ball
     * is several screens wide — that fraction would draw a slab. Three of the
     * ground's own pixels is what reads as a drawn line at the size the screen
     * actually shows.
     */
    private const val WEIGHT = 3f

    /** The button's radius, as a fraction of the ball's. */
    private const val BUTTON = 0.21f
}
