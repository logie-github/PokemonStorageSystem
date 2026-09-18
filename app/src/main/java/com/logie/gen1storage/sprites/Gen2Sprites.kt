package com.logie.gen1storage.sprites

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Generation II art, as pret's decompilations store it.
 *
 * Two things make it different from the Generation I sets, and both are in the
 * files rather than in how they are drawn:
 *
 * **The colours are already in the picture.** A Generation I sprite is four
 * greys this app tints; a Generation II sprite is an indexed PNG whose own
 * four-entry palette *is* what the Game Boy Color showed — white, the light
 * colour, the dark colour, black, in that order. The order is the point: the
 * light colour can be brighter or darker than the dark one depending on the
 * Pokémon, so it cannot be recovered by looking at the pixels afterwards.
 * [paletteOf] reads it straight out of the file's PLTE chunk while the bytes
 * are still on hand, and [Store] writes it down beside the sprite.
 *
 * **Crystal's sprites are a strip.** `front.png` there holds the still frame
 * and every animation frame under it, all one square wide; Gold and Silver
 * keep theirs as a single square. [firstFrame] takes the top square either
 * way.
 *
 * Shininess is a separate two-colour file per species, and it is the same
 * file in pokegold and pokecrystal — checked across a spread of twelve — so
 * one copy is fetched and all three sets read it.
 */
object Gen2Sprites {

    /**
     * Where a set's sprite for one species lives upstream.
     *
     * UNOWN is the exception both repositories make: its twenty-six forms are
     * twenty-six folders, `unown_a` through `unown_z`, and the plain `unown`
     * folder holds only the palettes they share, no sprite of its own. This
     * app asks for a particular letter by the synthetic id [unownFormId]
     * builds — `UNOWN_A` through `UNOWN_Z` — which [gen2SpriteFolder]'s
     * ordinary rule already turns into the right folder name; the bare
     * `UNOWN` id (no letter decided yet) falls back to `unown_a`, the same
     * representative drawing this app showed before a Pokémon's own DVs were
     * read for one. Neither repository gives Unown a per-version drawing
     * either, so the file is `front.png` in all three sets, for every letter.
     */
    fun url(set: SpriteSet, speciesId: String): String? = urls(set, speciesId).firstOrNull()

    /**
     * Every name that sprite might be under, best first.
     *
     * pokegold keeps Gold's and Silver's drawings side by side as
     * `front_gold.png` and `front_silver.png`, because the two games drew
     * most species separately. Not all of them: where one drawing served both
     * games the repository holds a single `front.png` and neither versioned
     * name exists at all. Eight species are like that, which was sixteen
     * files a download reported as lost every time it ran and sixteen
     * Pokemon with no Generation II art afterwards.
     *
     * So the versioned name is asked for first and the shared one is the
     * fallback, rather than either being assumed. Crystal redrew the lot and
     * names them all `front.png`, so it has only ever needed the one.
     */
    fun urls(set: SpriteSet, speciesId: String): List<String> {
        val repo = set.repo ?: return emptyList()
        val file = set.frontFile ?: return emptyList()
        val upper = speciesId.uppercase()
        // The egg is drawn once for all three games and the two repositories
        // file it differently: pokecrystal has it as an ordinary species
        // folder, pokegold as `egg/egg.png`, and neither has a versioned
        // name. One address for all three sets rather than a third fallback
        // name, because it is one picture.
        if (upper == EGG_ID) return listOf("$CRYSTAL_POKEMON_ROOT/egg/$SHARED_FRONT")
        val unown = upper == "UNOWN" || upper.startsWith("UNOWN_")
        val folder = if (upper == "UNOWN") "unown_a" else gen2SpriteFolder(speciesId)
        val root = "https://raw.githubusercontent.com/$repo/master/gfx/pokemon/$folder"
        // Unown was never given a per-version drawing by either repository.
        val names = if (unown || file == SHARED_FRONT) listOf(SHARED_FRONT)
        else listOf(file, SHARED_FRONT)
        return names.map { "$root/$it" }
    }

    /** What a species drawn once for both games is filed under. */
    private const val SHARED_FRONT = "front.png"

    /**
     * The synthetic species id an egg's picture is fetched and cached under.
     *
     * Not a real species: the cartridge has an `EGG` constant past the 251
     * and draws it in place of whatever is inside, which is the whole point
     * of an egg. See [com.logie.gen1storage.pokemon.Gen1Pokemon.isEgg].
     */
    const val EGG_ID = "EGG"

    private const val CRYSTAL_POKEMON_ROOT =
        "https://raw.githubusercontent.com/pret/pokecrystal/master/gfx/pokemon"

    /** Every one of Unown's 26 letter forms, as the synthetic id [unownFormId] builds. */
    val UNOWN_FORM_IDS: List<String> = ('A'..'Z').map { unownFormId(it) }

    /**
     * The species id this app stores and fetches one Unown letter form
     * under. Not a real pokecrystal constant — Gen1Recomp writes every Unown
     * as plain `UNOWN` regardless of letter — but [gen2SpriteFolder]'s
     * generic rule turns it into the right upstream folder without needing
     * to know Unown is special, and [SpriteStore] can cache the twenty-six
     * letters as twenty-six ordinary species rather than one it has to pick
     * a variant of at load time.
     */
    fun unownFormId(letter: Char): String = "UNOWN_${letter.uppercaseChar()}"

    /** The two middle colours of a species' shiny palette, from pokecrystal. */
    fun shinyUrl(speciesId: String): String =
        "https://raw.githubusercontent.com/pret/pokecrystal/master/gfx/pokemon/" +
            "${gen2SpriteFolder(speciesId)}/shiny.pal"

    /**
     * The four colours a PNG's own palette holds, in index order.
     *
     * Read from the bytes rather than from a decoded bitmap because decoding
     * throws the indexes away, and index order is the whole of what this is
     * for. Null if the file is not an indexed PNG with at least four entries,
     * which is not something pret's art ever is.
     */
    fun paletteOf(bytes: ByteArray): IntArray? {
        var at = PNG_HEADER
        while (at + 8 <= bytes.size) {
            val length = readInt(bytes, at)
            if (length < 0) return null
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            if (type == "PLTE") {
                if (length < 12) return null
                val start = at + 8
                return IntArray(4) { i ->
                    argb(
                        bytes[start + i * 3].toInt() and 0xFF,
                        bytes[start + i * 3 + 1].toInt() and 0xFF,
                        bytes[start + i * 3 + 2].toInt() and 0xFF,
                    )
                }
            }
            at += 12 + length
        }
        return null
    }

    /**
     * `RGB r, g, b` twice over: the light colour and the dark one, five bits
     * each as the Game Boy Color stored them, widened to eight.
     */
    fun parsePal(text: String): IntArray? {
        val found = PAL_LINE.findAll(text).map { match ->
            val (r, g, b) = match.destructured
            argb(widen(r.toInt()), widen(g.toInt()), widen(b.toInt()))
        }.take(2).toList()
        return if (found.size == 2) found.toIntArray() else null
    }

    /** The top square of a sprite strip, or the sprite itself where it is one. */
    fun firstFrame(bytes: ByteArray): ByteArray? {
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null
        if (decoded.width <= 0 || decoded.height <= 0) return null
        if (decoded.height <= decoded.width) return bytes
        val square = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.width)
        val out = ByteArrayOutputStream()
        val written = square.compress(Bitmap.CompressFormat.PNG, 100, out)
        square.recycle()
        decoded.recycle()
        return if (written) out.toByteArray() else null
    }

    /**
     * The colours read off the art, kept beside it.
     *
     * One file per set for the Pokémon's own four, one file for the shiny
     * pair every set shares. Plain text, a line per species, because it is
     * read once per session and a line that can be looked at with a text
     * editor is worth more here than a byte saved.
     */
    class Store(private val directory: File) {

        /**
         * Every read of these goes through [lock].
         *
         * They are read by six download workers at once and by whatever is
         * drawing a sprite, and a plain map shared like that does not merely
         * risk a wrong answer: a HashMap resized from two threads at once can
         * spin forever, which is an app that has stopped rather than an app
         * that has thrown. Loading a table is a handful of lines of text, so
         * holding a lock across it costs nothing worth measuring.
         */
        private val lock = Any()
        private val normal = mutableMapOf<String, MutableMap<String, IntArray>>()
        private var shiny: MutableMap<String, IntArray>? = null

        fun normalFile(set: SpriteSet): File = File(File(directory, FOLDER), "${set.id}.txt")

        fun shinyFile(): File = File(File(directory, FOLDER), "shiny.txt")

        /** A species' own four colours in that set, lightest entry first. */
        fun normalOf(set: SpriteSet, speciesId: String): IntArray? =
            synchronized(lock) { table(set)[spriteFileName(speciesId)] }

        /** Its shiny light and dark, which every Generation II set shares. */
        fun shinyOf(speciesId: String): IntArray? = synchronized(lock) {
            val loaded = shiny ?: read(shinyFile()).also { shiny = it }
            loaded[spriteFileName(speciesId)]
        }

        fun write(set: SpriteSet, colours: Map<String, IntArray>) = synchronized(lock) {
            write(normalFile(set), colours)
            normal.remove(set.id)
            Unit
        }

        fun writeShiny(colours: Map<String, IntArray>) = synchronized(lock) {
            write(shinyFile(), colours)
            shiny = null
        }

        private fun table(set: SpriteSet): Map<String, IntArray> =
            normal.getOrPut(set.id) { read(normalFile(set)) }

        private fun write(file: File, colours: Map<String, IntArray>) {
            if (colours.isEmpty()) return
            file.parentFile?.mkdirs()
            // Merged with whatever is already there: a download that was
            // interrupted and run again should add to the file rather than
            // leave it holding only the species of the second run.
            val merged = read(file).toMutableMap()
            colours.forEach { (species, values) -> merged[species] = values }
            val text = merged.entries.sortedBy { it.key }.joinToString("\n") { (species, values) ->
                "$species " + values.joinToString(",") { "%06x".format(it and 0xFFFFFF) }
            }
            runCatching { file.writeText(text) }
        }

        private fun read(file: File): MutableMap<String, IntArray> {
            val out = mutableMapOf<String, IntArray>()
            if (!file.isFile) return out
            runCatching {
                file.forEachLine { line ->
                    val space = line.indexOf(' ')
                    if (space <= 0) return@forEachLine
                    val values = line.substring(space + 1).split(',').mapNotNull {
                        it.trim().takeIf { piece -> piece.isNotEmpty() }
                            ?.toIntOrNull(16)?.let { rgb -> rgb or (0xFF shl 24) }
                    }
                    if (values.isNotEmpty()) out[line.substring(0, space)] = values.toIntArray()
                }
            }
            return out
        }
    }

    private const val FOLDER = "gen2"
    private const val PNG_HEADER = 8
    private val PAL_LINE = Regex("""RGB\s+(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""")

    /** Five bits to eight, the way the hardware's colours widen. */
    private fun widen(value: Int): Int = (value shl 3) or (value shr 2)

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun readInt(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return -1
        return ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
    }
}
