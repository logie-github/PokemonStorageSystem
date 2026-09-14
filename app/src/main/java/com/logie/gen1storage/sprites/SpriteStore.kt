package com.logie.gen1storage.sprites

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.Collections

/**
 * Sprites on disk, and which set each species is shown in.
 *
 * The files are the archive's enlarged art, untouched. Reducing them for the
 * screen happens here, and it is deliberately **point sampling**: every
 * ordinary downscale — `inSampleSize`, `createScaledBitmap` with filtering,
 * Skia's default — averages neighbouring pixels, which is exactly what turns a
 * hard pixel edge into a soft one. Taking one source pixel per destination
 * pixel keeps every edge as sharp as the original upscale.
 *
 * A full-size decode is around 16 MB, so it happens once per sprite, is
 * reduced immediately, and the large bitmap is recycled before returning. Only
 * the reduced copies are cached.
 */
class SpriteStore(
    private val directory: File,
    private val prefs: SharedPreferences,
) {

    constructor(context: Context) : this(
        File(context.filesDir, "sprites"),
        context.getSharedPreferences("gen1storage-sprites", Context.MODE_PRIVATE),
    )

    /**
     * Reduced sprites, kept by least-recently-used. Each is a few hundred
     * kilobytes of heap rather than the source's sixteen megabytes, so a
     * generous cache still costs little.
     */
    private val memory: MutableMap<String, ImageBitmap> =
        Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(48, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 60
        })

    /**
     * The four shades the art is mapped onto, darkest first, or null to leave
     * it alone. Set from the palette the player picked in OPTIONS.
     */
    private var tintId: String = "original"
    private var tintRamp: IntArray? = null

    /** The colours Generation II art carries, read off the files as they land. */
    val gen2Colours = Gen2Sprites.Store(directory)

    /**
     * Whether Generation II art takes the chosen palette instead of the
     * colours the Game Boy Color gave it.
     *
     * Off, a Gold sprite is the colours in its own file and a shiny one is
     * the two the cartridge swapped in. On, it is tinted like everything else
     * — and a shiny then reads the palette backwards, which is the same idea
     * the cartridge had: the same drawing, lit the other way round.
     */
    var gbcFollowsPalette: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            memory.clear()
        }

    /**
     * Points every sprite at a palette. [id] takes part in the cache key, so a
     * change repaints from the files rather than handing back the old colours.
     */
    fun setTint(id: String, ramp: IntArray?) {
        if (id == tintId) return
        tintId = id
        tintRamp = ramp
        memory.clear()
    }

    fun fileFor(set: SpriteSet, speciesId: String): File =
        File(File(directory, set.id), "${spriteFileName(speciesId)}.png")

    fun has(set: SpriteSet, speciesId: String): Boolean = fileFor(set, speciesId).isFile

    /** Every set that has at least one sprite on disk, for the picker. */
    fun installedSets(): List<SpriteSet> = SpriteSet.entries.filter { set ->
        File(directory, set.id).listFiles().orEmpty().any { it.isFile }
    }

    fun countIn(set: SpriteSet): Int =
        File(directory, set.id).listFiles().orEmpty().count { it.isFile }

    val isEmpty: Boolean get() = installedSets().isEmpty()

    /** Total bytes the downloaded sprites occupy, for the options screen. */
    fun bytesOnDisk(): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ------- per-species overrides

    /** The set a species is pinned to, whichever game it is being viewed from. */
    fun overrideFor(speciesId: String): SpriteSet? =
        SpriteSet.fromId(prefs.getString(KEY_PREFIX + speciesId, null))

    fun setOverride(speciesId: String, set: SpriteSet?) {
        prefs.edit().apply {
            if (set == null) remove(KEY_PREFIX + speciesId) else putString(KEY_PREFIX + speciesId, set.id)
        }.apply()
        memory.clear()
    }

    /**
     * Which set to actually draw: the player's choice for this species, else
     * the art of the game it came from, else any set that happens to have it.
     */
    fun resolve(speciesId: String, gameVersionId: String?): SpriteSet? {
        overrideFor(speciesId)?.takeIf { has(it, speciesId) }?.let { return it }
        val preferred = SpriteSet.forGameId(gameVersionId)
        if (has(preferred, speciesId)) return preferred
        // Within the generation it came from, and no further. A Pidgey out of
        // a Red save falls back to Yellow's drawing of it, never to Gold's:
        // they are different drawings and a box that mixed them would read as
        // two games at once. Only the player picking a set for a species
        // crosses that line.
        return SpriteSet.entries.firstOrNull {
            it.generation == preferred.generation && has(it, speciesId)
        }
    }

    fun load(
        speciesId: String,
        gameVersionId: String?,
        cutout: Boolean = false,
        shiny: Boolean = false,
    ): ImageBitmap? {
        val set = resolve(speciesId, gameVersionId) ?: return null
        return load(set, speciesId, cutout, shiny)
    }

    /**
     * [cutout] drops the sprite's white field so it sits on whatever is behind
     * it — see [recolourToRamp]. Kept under its own key, because the two are
     * different pictures and a screen asking for one must not be handed the
     * other.
     */
    fun load(
        set: SpriteSet,
        speciesId: String,
        cutout: Boolean = false,
        shiny: Boolean = false,
    ): ImageBitmap? {
        val key = buildString {
            append(tintId).append('/').append(set.id).append('/')
            if (cutout) append("cut/")
            if (shiny) append("shiny/")
            if (set.generation == 2 && gbcFollowsPalette) append("tinted/")
            append(spriteFileName(speciesId))
        }
        memory[key]?.let { return it }
        val file = fileFor(set, speciesId)
        if (!file.isFile) return null

        val full = runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null

        val reduced = runCatching { pointSample(full) }.getOrNull()
        if (reduced == null) {
            full.recycle()
            return null
        }
        val shown = runCatching {
            if (set.generation == 2) gen2(reduced, set, speciesId, cutout, shiny)
            else {
                val ramp = tintRamp
                when {
                    ramp != null -> recolourToRamp(reduced, ramp, cutout)
                    cutout -> cutOutLightest(reduced)
                    else -> reduced
                }
            }
        }.getOrNull() ?: reduced

        // Recycle every intermediate that is not the bitmap being kept.
        if (full !== shown) full.recycle()
        if (reduced !== shown && reduced !== full) reduced.recycle()

        val image = shown.asImageBitmap()
        memory[key] = image
        return image
    }

    /**
     * Generation II art, which arrives already coloured.
     *
     * The file's own four entries are white, the light colour, the dark
     * colour and black, in that order, and the pixels are those colours
     * exactly — so the work is a swap of one for another rather than a tint
     * of a grey. Which four they are swapped for is the whole of what varies:
     *
     *  - shown as the cartridge did, a shiny takes the pair out of its
     *    `shiny.pal` and keeps white and black
     *  - told to follow the palette, all four come from the palette, and a
     *    shiny takes it backwards — the dark end where the light was
     *
     * A species whose colours were never written down is left exactly as the
     * file has it, which is the normal picture.
     */
    private fun gen2(
        source: Bitmap,
        set: SpriteSet,
        speciesId: String,
        cutout: Boolean,
        shiny: Boolean,
    ): Bitmap {
        val own = gen2Colours.normalOf(set, speciesId)?.takeIf { it.size >= 4 }
            ?: return if (cutout) cutOutLightest(source) else source
        val palette = tintRamp
        val wanted = when {
            gbcFollowsPalette && palette != null && palette.size >= 4 ->
                // The ramp runs darkest first; the file's runs lightest
                // first, so a straight read of one into the other is already
                // the right way round for a shiny.
                if (shiny) intArrayOf(palette[0], palette[1], palette[2], palette[3])
                else intArrayOf(palette[3], palette[2], palette[1], palette[0])

            shiny -> gen2Colours.shinyOf(speciesId)?.takeIf { it.size >= 2 }
                ?.let { intArrayOf(own[0], it[0], it[1], own[3]) }

            else -> null
        } ?: return if (cutout) cutOutLightest(source) else source

        return swapColours(source, own, wanted, cutout)
    }

    /**
     * Reduces by taking one source pixel per destination pixel, never a blend.
     *
     * The divisor is a whole number, so each destination pixel lands on a
     * consistent position within the source's upscale blocks and the result is
     * the same art at a smaller size rather than a smeared version of it.
     * `createScaledBitmap` is called with filtering off, which is Android's
     * nearest-neighbour path.
     */
    private fun pointSample(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= DISPLAY_PIXELS) return source
        val divisor = longest / DISPLAY_PIXELS
        if (divisor <= 1) return source
        val width = (source.width / divisor).coerceAtLeast(1)
        val height = (source.height / divisor).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, false)
    }

    fun clear() {
        directory.deleteRecursively()
        prefs.edit().clear().apply()
        memory.clear()
    }

    private companion object {
        const val KEY_PREFIX = "set-for-"

        /**
         * The longest edge kept in memory. A sprite slot is about 96dp — 288
         * pixels on a 3x screen — so this holds well above what any display
         * needs while staying far below the source's two thousand.
         */
        const val DISPLAY_PIXELS = 480
    }
}
