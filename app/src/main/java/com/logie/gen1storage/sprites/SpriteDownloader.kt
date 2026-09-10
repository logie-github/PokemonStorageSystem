package com.logie.gen1storage.sprites

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.logie.gen1storage.pokemon.Gen1Data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** How far a sprite download has got. */
data class SpriteProgress(
    val done: Int,
    val total: Int,
    val failed: Int = 0,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val percent: Int get() = if (total <= 0) 0 else (done * 100) / total
}

/**
 * Fetches the Generation I front sprites and stores a small copy of each.
 *
 * The archive's images are around 2000 pixels square. Kept at that size they
 * would be roughly 24 MB on disk and 16 MB of heap every time one was decoded,
 * for a slot a hundred pixels across. Each is therefore subsampled as it is
 * decoded — `inSampleSize` is applied during decode, so the full-size bitmap is
 * never allocated at all — and re-encoded at a size the screen can actually
 * use. Subsampling by a power of two and drawing unfiltered keeps the pixel
 * edges hard.
 */
class SpriteDownloader(private val store: SpriteStore) {

    /**
     * Downloads every species in [sets], reporting after each file.
     *
     * A sprite that fails is counted and skipped rather than aborting the run:
     * one missing species should not cost the player the other 150.
     */
    suspend fun download(
        sets: List<SpriteSet> = SpriteSet.downloadable,
        onProgress: (SpriteProgress) -> Unit,
    ): SpriteProgress = withContext(Dispatchers.IO) {
        val species = Gen1Data.species.map { it.id }
        val total = sets.size * species.size
        var done = 0
        var failed = 0

        onProgress(SpriteProgress(0, total))

        for (set in sets) {
            File(store.fileFor(set, species.first()).parent!!).mkdirs()
            for (id in species) {
                coroutineContext.ensureActive()
                val target = store.fileFor(set, id)
                if (!target.isFile) {
                    val ok = runCatching { fetchAndShrink(set, id, target) }.getOrDefault(false)
                    if (!ok) failed++
                }
                done++
                onProgress(SpriteProgress(done, total, failed))
            }
        }
        SpriteProgress(done, total, failed, finished = true).also(onProgress)
    }

    private fun fetchAndShrink(set: SpriteSet, speciesId: String, target: File): Boolean {
        val url = URL("$BASE_URL/${set.remotePath}/${spriteFileName(speciesId)}.png")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        val bytes = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        if (bytes.isEmpty()) return false

        // Measure first, so the decode never allocates the full-size bitmap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PIXELS &&
            bounds.outHeight / (sample * 2) >= TARGET_PIXELS
        ) {
            sample *= 2
        }

        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return false

        target.parentFile?.mkdirs()
        val written = try {
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        if (!written) {
            target.delete()
            return false
        }
        return true
    }

    private companion object {
        const val BASE_URL =
            "https://raw.githubusercontent.com/ShiraTheMogul/rby-sprites-project/main"

        /**
         * The smallest stored edge. A sprite slot is around 96dp, which is 288
         * pixels at 3x, so this leaves headroom without keeping anything near
         * the source's two thousand.
         */
        const val TARGET_PIXELS = 320
    }
}
