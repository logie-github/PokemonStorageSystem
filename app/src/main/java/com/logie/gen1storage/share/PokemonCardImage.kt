package com.logie.gen1storage.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.logie.gen1storage.R
import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.ui.GbPalette

/**
 * A Pokémon drawn as a picture somebody can send to somebody else.
 *
 * A Game Boy screen's worth of card — 160 by 144 of the same pixels the app is
 * drawn in — scaled up by a whole number so it stays square-edged wherever it
 * lands. Nothing here is anti-aliased and nothing is scaled by a fraction: the
 * point of sharing a Pokémon from this app rather than screenshotting it is
 * that the result looks like it came off a Game Boy.
 *
 * With the printer border on it is set on a sheet of paper with a torn edge top
 * and bottom, which is what a Game Boy Printer handed you: the print itself was
 * this size, and the paper around it was the frame. That border is this app's
 * own drawing of what printed output looked like rather than art taken from
 * anywhere.
 */
object PokemonCardImage {

    /** A Game Boy screen, which is the shape a printed one came out in. */
    const val WIDTH = 160
    const val HEIGHT = 144

    /** Whole pixels per Game Boy pixel. Six puts it near a phone's own width. */
    const val SCALE = 6

    /** The paper around the print, in Game Boy pixels. */
    private const val PAPER = 12

    fun render(
        context: Context,
        pokemon: Gen1Pokemon,
        sprite: Bitmap?,
        provenance: Provenance?,
        palette: GbPalette,
        printerBorder: Boolean,
    ): Bitmap {
        // The roll is exactly as wide as the print — a Game Boy Printer never
        // left a margin down the sides, only the tear above and below where
        // it came off the roll. Margin here is a height only, never a width.
        val margin = if (printerBorder) PAPER else 0
        val bitmap = Bitmap.createBitmap(
            WIDTH * SCALE,
            (HEIGHT + margin * 2) * SCALE,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val ink = palette.darkest.toArgb()
        val paper = palette.lightest.toArgb()
        val mid = palette.light.toArgb()

        val fill = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

        fun rect(x: Int, y: Int, w: Int, h: Int, colour: Int) {
            fill.color = colour
            canvas.drawRect(
                (x * SCALE).toFloat(),
                ((y + margin) * SCALE).toFloat(),
                ((x + w) * SCALE).toFloat(),
                ((y + margin + h) * SCALE).toFloat(),
                fill,
            )
        }

        if (printerBorder) {
            // The paper, then the print sitting on it.
            fill.color = paper
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), fill)
            drawTornEdge(canvas, bitmap.width, 0, ink, downwards = true)
            drawTornEdge(canvas, bitmap.width, bitmap.height, ink, downwards = false)
        }

        rect(0, 0, WIDTH, HEIGHT, paper)
        // The window's own rule, a pixel wide and inset the way every box in
        // the app is.
        rect(2, 2, WIDTH - 4, 1, ink)
        rect(2, HEIGHT - 3, WIDTH - 4, 1, ink)
        rect(2, 2, 1, HEIGHT - 4, ink)
        rect(WIDTH - 3, 2, 1, HEIGHT - 4, ink)

        val face = runCatching { ResourcesCompat.getFont(context, R.font.pokemon_font) }
            .getOrNull() ?: Typeface.MONOSPACE
        val text = Paint().apply {
            isAntiAlias = false
            typeface = face
            color = ink
            // The face's em is eight design pixels, so a size that is a whole
            // multiple of eight puts every glyph edge on a pixel boundary.
            textSize = (8 * SCALE).toFloat()
        }

        fun write(x: Int, baseline: Int, value: String, paint: Paint = text) {
            canvas.drawText(
                value,
                (x * SCALE).toFloat(),
                ((baseline + margin) * SCALE).toFloat(),
                paint,
            )
        }

        sprite?.let {
            val left = 8 * SCALE
            val top = (12 + margin) * SCALE
            val side = 56 * SCALE
            canvas.drawBitmap(
                it,
                Rect(0, 0, it.width, it.height),
                Rect(left, top, left + side, top + side),
                Paint().apply { isFilterBitmap = false; isAntiAlias = false },
            )
        }

        // Out of the Pokémon's own generation's table; see [Gen1Pokemon.species].
        val species = (pokemon.species?.displayName ?: pokemon.speciesId.orEmpty()).uppercase()
        // In the words of the cartridge it was deposited from, where the
        // card knows which that was.
        val entry = pokemon.dexPage(provenance?.gameVersion)
        write(72, 22, pokemon.displayName.uppercase())
        write(72, 34, ":L${pokemon.level}")
        write(72, 46, pokemon.species?.let { "No.%03d".format(it.dexNumber) } ?: "No.???")
        entry?.let { write(72, 58, it.category) }

        // A rule under the picture, then the facts that are about this one
        // Pokémon rather than about its species.
        rect(8, 76, WIDTH - 16, 1, mid)
        write(8, 90, "OT/${pokemon.otName ?: "-----"}")
        write(8, 102, "IDNo/${pokemon.otId?.let { "%05d".format(it) } ?: "-----"}")
        if (pokemon.displayName.uppercase() != species) write(8, 114, species)

        val game = GameVersion.fromId(provenance?.gameVersion)?.label
        val small = Paint(text).apply { textSize = (6 * SCALE).toFloat(); color = mid }
        write(8, HEIGHT - 10, game?.let { "FROM $it" } ?: "POKéMON STORAGE SYSTEM", small)

        return bitmap
    }

    /**
     * The ragged edge a print was torn off the roll at.
     *
     * Drawn as a run of columns of differing depth rather than a sawtooth: a
     * tear is uneven, and a repeating triangle reads as decoration.
     */
    private fun drawTornEdge(canvas: Canvas, width: Int, at: Int, ink: Int, downwards: Boolean) {
        val paint = Paint().apply { isAntiAlias = false; color = ink }
        val step = SCALE * 2
        var x = 0
        var seed = 1
        while (x < width) {
            // A small, repeatable wobble; the same card always tears the same.
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val depth = (seed % 3 + 1) * SCALE
            val top = if (downwards) at.toFloat() else (at - depth).toFloat()
            canvas.drawRect(x.toFloat(), top, (x + step).toFloat(), top + depth, paint)
            x += step
        }
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
        android.graphics.Color.argb(
            (alpha * 255).toInt(),
            (red * 255).toInt(),
            (green * 255).toInt(),
            (blue * 255).toInt(),
        )
}
