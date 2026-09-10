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
        return SpriteSet.entries.firstOrNull { has(it, speciesId) }
    }

    fun load(speciesId: String, gameVersionId: String?): ImageBitmap? {
        val set = resolve(speciesId, gameVersionId) ?: return null
        return load(set, speciesId)
    }

    fun load(set: SpriteSet, speciesId: String): ImageBitmap? {
        val key = "${set.id}/${spriteFileName(speciesId)}"
        memory[key]?.let { return it }
        val file = fileFor(set, speciesId)
        if (!file.isFile) return null

        val full = runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null

        val reduced = runCatching { pointSample(full) }.getOrNull()
        if (reduced !== full) full.recycle()
        val image = (reduced ?: return null).asImageBitmap()
        memory[key] = image
        return image
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
