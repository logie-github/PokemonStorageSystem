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
 * The archive's images are large — around 2000 pixels square, which is roughly
 * 16 MB once decoded — so nothing here ever decodes one at full size. The
 * download step subsamples on the way in (see [SpriteDownloader]) and this only
 * ever reads the small copies it left behind.
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
     * Decoded sprites, capped so a long list cannot grow without bound. The
     * small stored copies are a few tens of kilobytes each, so this is a
     * modest ceiling in practice.
     */
    private val memory: MutableMap<String, ImageBitmap> =
        Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 180
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
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                // The stored copies are already small; ARGB_8888 keeps the
                // palette's hard edges rather than dithering them.
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null
        val image = bitmap.asImageBitmap()
        memory[key] = image
        return image
    }

    fun clear() {
        directory.deleteRecursively()
        prefs.edit().clear().apply()
        memory.clear()
    }

    private companion object {
        const val KEY_PREFIX = "set-for-"
    }
}
