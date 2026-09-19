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
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.sprites.TrainerStore
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.ui.GbPalette

/**
 * What the printer is being asked to print.
 *
 * The Game Boy Printer never printed "a Pokémon". It printed one of a small
 * number of set pages, each laid out by the game rather than by whoever
 * pressed print, and the only choice was which page. These are the ones that
 * are about a single Pokémon; the others the real thing could do — a box
 * list, the trainer card, mail, the diploma — belong to the screens those
 * things live on rather than to this one.
 */
enum class PrintKind(val label: String) {
    /** The page in every photograph of one of these, and the one everybody printed. */
    DEX("DEX ENTRY"),
    STATS("STATS"),
    MOVES("MOVES"),
    /** The picture on its own, blown up, which is what a box print came out as. */
    SPRITE("SPRITE"),
}

/**
 * What sits around the print.
 *
 * The real printer had a margin setting — how much paper to feed before and
 * after the image — so a print could come off the roll with a band of blank
 * paper around it or butted straight up against the one before. That is
 * [PAPER]. [FRAME] is a different thing: the ruled box the games drew
 * *inside* the image, which is why it survives being torn off.
 */
enum class PrintBorder(val label: String) {
    /** No feed and no rule: the print, edge to edge. */
    NONE("NONE"),
    /** Fed before and after, and torn off the roll. */
    PAPER("PAPER"),
    /** The games' own ruled box, drawn in the print itself. */
    FRAME("FRAME"),
    PAPER_AND_FRAME("BOTH");

    val feedsPaper: Boolean get() = this == PAPER || this == PAPER_AND_FRAME
    val rules: Boolean get() = this == FRAME || this == PAPER_AND_FRAME
}

/**
 * A Pokémon drawn as a picture somebody can send to somebody else.
 *
 * A Game Boy screen's worth of print — 160 by 144 of the same pixels the app
 * is drawn in — scaled up by a whole number so it stays square-edged wherever
 * it lands. Nothing here is anti-aliased and nothing is scaled by a fraction:
 * the point of sharing a Pokémon from this app rather than screenshotting it
 * is that the result looks like it came off a Game Boy.
 */
object PokemonCardImage {

    /** A Game Boy screen, which is the shape a printed one came out in. */
    const val WIDTH = 160
    const val HEIGHT = 144

    /** Whole pixels per Game Boy pixel. Six puts it near a phone's own width. */
    const val SCALE = 6

    /** How much roll is fed before and after a print, in Game Boy pixels. */
    private const val FEED = 12

    /** The left edge everything starts at, and the right edge it stops at. */
    private const val INSET = 6

    /** How many characters fit across the print at the ordinary size. */
    private const val COLUMNS = (WIDTH - INSET * 2) / 8

    /** How far apart a DEX entry's own lines sit, and how much room growing the roll by one more of them buys. */
    private const val DEX_LINE_HEIGHT = 11

    /** Where a DEX entry's own text starts, under the beaded rule. */
    private const val DEX_TEXT_TOP = 98

    /**
     * How much clear air sits between a DEX entry's last line and the
     * credit line under it — generous on purpose: FRAME draws its own rule
     * right where the credit line sits, and a gap any tighter than this
     * read as the entry's own text running into it once an entry actually
     * used the room [DEX_LINE_HEIGHT] buys it.
     */
    private const val DEX_CREDIT_GAP = 20

    fun render(
        context: Context,
        pokemon: Gen1Pokemon,
        sprite: Bitmap?,
        provenance: Provenance?,
        palette: GbPalette,
        kind: PrintKind = PrintKind.DEX,
        border: PrintBorder = PrintBorder.PAPER,
        /**
         * The Pokédex screen's own tiles, by index into
         * `gfx/pokedex/pokedex.png` — the `No.` and the ball on the rule.
         * Null before DOWNLOADS has fetched the sheet, and every use of it
         * falls back to something drawn here, so a print is never blocked on
         * a download.
         */
        dexTile: (Int) -> Bitmap? = { null },
    ): Bitmap {
        // The roll is exactly as wide as the print — a Game Boy Printer never
        // left a margin down the sides, only the feed above and below. Margin
        // here is a height only, never a width.
        val margin = if (border.feedsPaper) FEED else 0
        val species = (pokemon.species?.displayName ?: pokemon.speciesId.orEmpty()).uppercase()
        val number = pokemon.species?.let { "No.%03d".format(it.dexNumber) } ?: "No.???"
        val name = pokemon.displayName.uppercase()

        // The DEX page's own entry, read once here rather than inside the
        // layout below: how tall this print needs to be depends on how many
        // lines it takes, and the bitmap has to be that tall before anything
        // is drawn on it.
        val dexEntry = if (kind == PrintKind.DEX) pokemon.dexPage(provenance?.gameVersion) else null
        val dexLines = if (kind == PrintKind.DEX) {
            dexEntry?.lines?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: wrap(dexEntry?.flowing ?: species, COLUMNS)
        } else emptyList()

        // A real Game Boy Printer fed however much paper a job needed —
        // every other page here is a fixed screen's worth, but a long dex
        // entry is the one thing this app draws that would otherwise cut
        // off mid-sentence at four lines. Sized from where the entry's own
        // last line actually lands rather than from a fixed per-line step,
        // so the credit line's own gap is the same [DEX_CREDIT_GAP]
        // whether the entry is four lines or fourteen.
        val dexDescriptionBottom = DEX_TEXT_TOP + (dexLines.size - 1).coerceAtLeast(0) * DEX_LINE_HEIGHT
        val printHeight = if (kind == PrintKind.DEX) {
            maxOf(HEIGHT, dexDescriptionBottom + DEX_CREDIT_GAP + 3)
        } else HEIGHT

        val bitmap = Bitmap.createBitmap(
            WIDTH * SCALE,
            (printHeight + margin * 2) * SCALE,
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

        if (border.feedsPaper) {
            fill.color = paper
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), fill)
            drawTornEdge(canvas, bitmap.width, 0, ink, downwards = true)
            drawTornEdge(canvas, bitmap.width, bitmap.height, ink, downwards = false)
        }

        rect(0, 0, WIDTH, printHeight, paper)
        if (border.rules) {
            // A box a pixel wide, inset the way every window in the app is,
            // with a lighter rule inside it — which is what the games' own
            // printed pages were boxed in.
            rect(2, 2, WIDTH - 4, 1, ink)
            rect(2, printHeight - 3, WIDTH - 4, 1, ink)
            rect(2, 2, 1, printHeight - 4, ink)
            rect(WIDTH - 3, 2, 1, printHeight - 4, ink)
            rect(4, 4, WIDTH - 8, 1, mid)
            rect(4, printHeight - 5, WIDTH - 8, 1, mid)
            rect(4, 4, 1, printHeight - 8, mid)
            rect(WIDTH - 5, 4, 1, printHeight - 8, mid)
        }

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
        val small = Paint(text).apply { textSize = (6 * SCALE).toFloat(); color = mid }

        fun write(x: Int, baseline: Int, value: String, paint: Paint = text) {
            canvas.drawText(
                value,
                (x * SCALE).toFloat(),
                ((baseline + margin) * SCALE).toFloat(),
                paint,
            )
        }

        /** One tile of the cartridge's own sheet, at this print's scale. */
        fun drawTile(index: Int, left: Int, top: Int): Boolean {
            val tile = dexTile(index) ?: return false
            canvas.drawBitmap(
                tile,
                Rect(0, 0, tile.width, tile.height),
                Rect(
                    left * SCALE,
                    (top + margin) * SCALE,
                    (left + 8) * SCALE,
                    (top + margin + 8) * SCALE,
                ),
                Paint().apply { isFilterBitmap = false; isAntiAlias = false },
            )
            return true
        }

        /**
         * A line set a character at a time, so the marks the cartridge has
         * its own tiles for are drawn as those tiles rather than as whatever
         * the text face has under the same key. The face is monospace at an
         * eight pixel em, which is why walking it a character at a time lands
         * every glyph exactly where writing the whole string would have.
         */
        fun writeGlyphs(x: Int, baseline: Int, value: String, paint: Paint = text) {
            value.forEachIndexed { index, ch ->
                val left = x + index * 8
                val tile = when (ch) {
                    '\'' -> TrainerStore.DEX_TILE_FEET
                    '"' -> TrainerStore.DEX_TILE_INCHES
                    else -> null
                }
                if (tile == null || !drawTile(tile, left, baseline - 8)) {
                    write(left, baseline, ch.toString(), paint)
                }
            }
        }

        fun drawSprite(left: Int, top: Int, side: Int) {
            val art = sprite ?: return
            canvas.drawBitmap(
                art,
                Rect(0, 0, art.width, art.height),
                Rect(
                    left * SCALE,
                    (top + margin) * SCALE,
                    (left + side) * SCALE,
                    (top + margin + side) * SCALE,
                ),
                Paint().apply { isFilterBitmap = false; isAntiAlias = false },
            )
        }

        /**
         * The games' own divider: a rule with beads strung along it, which is
         * what separates the head of a printed page from its text.
         */
        fun beadedRule(y: Int) {
            rect(INSET, y, WIDTH - INSET * 2, 1, mid)
            var x = INSET
            while (x <= WIDTH - INSET - 8) {
                // The cartridge's own ball where the sheet is on the device,
                // and a drawn one where it is not.
                if (!drawTile(TrainerStore.DEX_TILE_BALL, x, y - 4)) {
                    rect(x + 2, y - 2, 4, 4, ink)
                    rect(x + 3, y - 1, 2, 2, paper)
                }
                x += 16
            }
        }

        when (kind) {
            PrintKind.DEX -> {
                // The cartridge's own page, in its own order: the picture top
                // left, what the species is beside it, the number under the
                // picture, the beaded rule, and the entry's own words below.
                val entry = dexEntry
                drawSprite(INSET + 2, 10, 56)
                write(72, 20, name.take(10))
                entry?.let { write(72, 32, it.category.take(10)) }
                entry?.let { writeGlyphs(72, 44, "HT ${it.heightText}") }
                entry?.let { writeGlyphs(72, 56, "WT ${it.weightText}") }
                // `No.` is a tile of the dex sheet, not three letters of the
                // text face — the cartridge draws it as one glyph and so does
                // this, falling back to the letters where the sheet is absent.
                // `No.` is two tiles of the dex sheet — the letters and the
                // stop — not three characters of the text face. Both or
                // neither: half of it drawn and half written would be worse
                // than either.
                val digits = pokemon.species?.let { "%03d".format(it.dexNumber) } ?: "???"
                val drawn = drawTile(TrainerStore.DEX_TILE_NUMBER, INSET, 66) &&
                    drawTile(TrainerStore.DEX_TILE_STOP, INSET + 8, 66)
                if (drawn) write(INSET + 16, 74, digits) else write(INSET, 74, number)
                beadedRule(82)
                // The entry's own line breaks where it has them — they are the
                // cartridge's, and it broke its lines where it meant to. Every
                // one of them, not just the first four: see printHeight above.
                dexLines.forEachIndexed { index, line ->
                    write(INSET, DEX_TEXT_TOP + index * DEX_LINE_HEIGHT, line.take(COLUMNS))
                }
            }

            PrintKind.STATS -> {
                write(INSET, 16, name.take(11))
                write(WIDTH - INSET - 5 * 8, 16, ":L%-3d".format(pokemon.level))
                write(INSET, 28, number)
                beadedRule(36)
                write(INSET, 52, "HP")
                write(
                    WIDTH - INSET - 7 * 8,
                    52,
                    "%3d/%3d".format(pokemon.currentHp, pokemon.maxHp ?: 0),
                )
                pokemon.battleStats.take(5).forEachIndexed { index, line ->
                    val y = 66 + index * 12
                    write(INSET, y, line.label.uppercase().take(9))
                    write(WIDTH - INSET - 3 * 8, y, "%3d".format(line.value ?: 0))
                }
                write(INSET, HEIGHT - 10, "OT/${pokemon.otName ?: "-----"}", small)
            }

            PrintKind.MOVES -> {
                write(INSET, 16, name.take(11))
                write(WIDTH - INSET - 5 * 8, 16, ":L%-3d".format(pokemon.level))
                beadedRule(26)
                val moves = pokemon.moves
                if (moves.isEmpty()) {
                    write(INSET, 64, "NO MOVES.")
                } else {
                    moves.take(4).forEachIndexed { index, slot ->
                        val top = 44 + index * 24
                        write(INSET, top, slot.displayName.uppercase().take(COLUMNS))
                        val max = slot.maxPp
                        write(
                            INSET + 8,
                            top + 11,
                            "PP ${slot.pp}${max?.let { "/$it" } ?: ""}",
                            small,
                        )
                    }
                }
            }

            PrintKind.SPRITE -> {
                // The picture at a whole multiple — twice, at this size — and
                // its name under it. Nothing else: this is the one somebody
                // prints to put on a wall.
                //
                // Sized to leave the rule's own beads clear of the name row
                // below them: a rule any closer to the bottom, or a sprite
                // any bigger, and the two started overlapping instead of
                // stacking.
                drawSprite((WIDTH - 104) / 2, 6, 104)
                beadedRule(120)
                write(INSET, HEIGHT - 8, name.take(10))
                write(WIDTH - INSET - number.length * 8, HEIGHT - 8, number)
            }
        }

        if (kind != PrintKind.SPRITE) {
            val game = GameVersion.fromId(provenance?.gameVersion)?.label
            write(INSET, printHeight - 3, game?.let { "FROM $it" } ?: "POKéMON STORAGE SYSTEM", small)
        }

        return bitmap
    }

    /** Greedy wrap at [columns] characters, which is what the face measures in. */
    private fun wrap(text: String, columns: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
            val room = if (line.isEmpty()) columns else columns - line.length - 1
            if (word.length > room && line.isNotEmpty()) {
                lines += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
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
