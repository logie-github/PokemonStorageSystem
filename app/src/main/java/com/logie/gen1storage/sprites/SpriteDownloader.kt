package com.logie.gen1storage.sprites

import android.graphics.BitmapFactory
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.pokemon.Gen1Data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Fetches the Generation I front sprites and stores them exactly as downloaded.
 *
 * The enlarged art is kept at full size on purpose. It is a nearest-neighbour
 * upscale of a small sprite, so re-encoding it smaller would have to resample
 * it, and every general-purpose downscale averages neighbouring pixels — which
 * is precisely what softens pixel art. Keeping the file intact leaves the
 * decision to display time, where [com.logie.gen1storage.sprites.SpriteStore]
 * reduces it by point sampling instead and nothing is ever averaged.
 *
 * The cost is disk: roughly 12 MB per set. That is the price of crisp edges,
 * and the sprites screen reports it.
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
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadProgress = withContext(Dispatchers.IO) {
        val species = Gen1Data.species.map { it.id }
        val total = sets.size * species.size
        var done = 0
        var failed = 0

        onProgress(DownloadProgress(0, total))

        for (set in sets) {
            File(store.fileFor(set, species.first()).parent!!).mkdirs()
            for (id in species) {
                coroutineContext.ensureActive()
                val target = store.fileFor(set, id)
                if (!target.isFile) {
                    val ok = runCatching { fetch(set, id, target) }.getOrDefault(false)
                    if (!ok) failed++
                }
                done++
                onProgress(DownloadProgress(done, total, failed))
            }
        }
        DownloadProgress(done, total, failed, finished = true).also(onProgress)
    }

    private fun fetch(set: SpriteSet, speciesId: String, target: File): Boolean {
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

        // Confirm it really is an image before it lands in the sprite folder,
        // so a proxy's error page cannot masquerade as a Pokémon.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        target.parentFile?.mkdirs()
        // Written beside the target and renamed, so an interrupted download
        // cannot leave a half a sprite that later looks downloaded.
        val staged = File(target.parentFile, "${target.name}.part")
        staged.writeBytes(bytes)
        if (!staged.renameTo(target)) {
            staged.delete()
            return false
        }
        return true
    }

    private companion object {
        const val BASE_URL =
            "https://raw.githubusercontent.com/ShiraTheMogul/rby-sprites-project/main"


    }
}
