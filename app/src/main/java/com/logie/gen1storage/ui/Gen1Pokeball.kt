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
 * It grows out of the bottom right corner until it reaches the far side, so it
 * is as wide as the screen and sits along the bottom, where the dithered ground
 * is at its darkest and a light outline has something to read against.
 *
 * Drawn a pixel at a time rather than as a circle. A stroked path would give
 * smooth, anti-aliased edges — a modern picture of a Poké Ball, which is the one
 * thing this interface never shows. Every cell here is either the palette's
 * lightest colour or nothing at all, on the same grid the dither uses, so the
 * ball is made of the same pixels as the ground it lies on.
 *
 * Only the band actually turns: a ring is the same ring at every angle, so what
 * the eye reads as rotation is the seam and the button's stem sweeping round.
 * The turn is taken in [STEPS] fixed positions rather than continuously, which
 * is both what keeps the edges on the grid and what makes it read as animation
 * cels instead of a tweened object.
 */
object Gen1Pokeball {

    /** How many distinct positions one turn is taken in. */
    const val STEPS = 120

    /** How long one full turn takes. Slow: it is scenery, not an event. */
    const val PERIOD_MS = 12_000L

    /** Milliseconds each position holds for. */
    const val STEP_MS = PERIOD_MS / STEPS

    /** A rasterised position, and the side it must be drawn at to stay square. */
    class Frame(val image: ImageBitmap, val sidePx: Int)

    private var key: String = ""
    private var frame: Frame? = null

    /**
     * The ball at [step], as wide as [widthPx].
     *
     * Cached on everything that shapes it, because every piece of ground on
     * screen asks for the same one in the same frame and only the first of them
     * should pay for it.
     */
    @Synchronized
    fun frame(widthPx: Int, unit: Int, colour: Int, step: Int): Frame? {
        if (widthPx <= 0 || unit <= 0) return null
        val wanted = "$widthPx/$unit/$colour/$step"
        frame?.let { if (key == wanted) return it }
        val built = build(widthPx / unit, colour, step) ?: return null
        key = wanted
        frame = built
        return built
    }

    private fun build(side: Int, colour: Int, step: Int): Frame? {
        if (side < MIN_CELLS) return null
        val pixels = IntArray(side * side)

        val radius = side / 2f
        // Everything below is a fraction of the ball itself, so the shape holds
        // whatever size the screen makes it.
        val weight = (side * LINE).coerceAtLeast(1f)
        val button = side * BUTTON
        val angle = step.toFloat() / STEPS * 2f * PI.toFloat()
        val alongX = cos(angle)
        val alongY = sin(angle)

        for (cellY in 0 until side) {
            val y = cellY + 0.5f - radius
            val row = cellY * side
            for (cellX in 0 until side) {
                val x = cellX + 0.5f - radius
                val distance = hypot(x, y)
                val on = when {
                    // The rim, sitting inside the ball's full width.
                    distance <= radius && distance >= radius - weight -> true
                    // The button.
                    abs(distance - button) <= weight / 2f -> true
                    // The seam, from the button out to the rim on both sides.
                    // Turned by the step; the rings are not, having nothing to
                    // turn.
                    else -> abs(y * alongX - x * alongY) <= weight / 2f &&
                        distance <= radius - weight &&
                        distance >= button + weight / 2f
                }
                pixels[row + cellX] = if (on) colour else 0
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        return Frame(bitmap.asImageBitmap(), side)
    }

    /** Below this the shape stops being a Poké Ball and becomes a smudge. */
    private const val MIN_CELLS = 24

    /** Line weight, as a fraction of the ball's width. */
    private const val LINE = 0.028f

    /** The button's radius, as a fraction of the ball's width. */
    private const val BUTTON = 0.105f
}
