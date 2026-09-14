package com.logie.gen1storage.sprites

import android.graphics.BitmapFactory
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.download.fetchInParallel
import com.logie.gen1storage.pokemon.Gen1Data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.net.URL

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
    /** One file to fetch: a species' sprite in a set, or its shiny colours. */
    private sealed interface Job {
        data class Sprite(val set: SpriteSet, val speciesId: String) : Job
        data class Shiny(val speciesId: String) : Job
    }

    suspend fun download(
        sets: List<SpriteSet> = SpriteSet.downloadable,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadProgress = withContext(Dispatchers.IO) {
        val species = Gen1Data.species.map { it.id }
        // Every file in the run, across every set, as one flat list. Going set
        // by set would have each set's tail waiting on its own last few files
        // while the line sat idle.
        val wanted: List<Job> = buildList {
            sets.forEach { set -> species.forEach { add(Job.Sprite(set, it)) } }
            // One shiny palette per species, whichever Generation II sets are
            // being fetched: pokegold and pokecrystal hold the same file, so
            // all three sets read the one copy.
            if (sets.any { it.generation == 2 }) species.forEach { add(Job.Shiny(it)) }
        }
        val total = wanted.size

        onProgress(DownloadProgress(0, total))
        sets.forEach { set -> File(store.fileFor(set, species.first()).parent!!).mkdirs() }

        // The colours read out of each file as it lands. Written once at the
        // end rather than per sprite: a hundred and fifty workers appending to
        // one text file is a race, and what they each learn is four numbers.
        val normals = ConcurrentHashMap<String, ConcurrentHashMap<String, IntArray>>()
        val shinies = ConcurrentHashMap<String, IntArray>()

        // Hundreds of small files: the time goes on round trips, not on bytes,
        // so they go a handful at a time rather than one after another. The
        // same treatment the followers and the cries already had.
        val failed = fetchInParallel(wanted, total, onProgress) { job ->
            when (job) {
                is Job.Sprite -> {
                    val target = store.fileFor(job.set, job.speciesId)
                    val known = job.set.generation == 1 ||
                        store.gen2Colours.normalOf(job.set, job.speciesId) != null
                    // Already here is already done. TRUE stands for "nothing
                    // to do" because the helper reads null as a failure.
                    if (target.isFile && known) true
                    else true.takeIf { fetch(job.set, job.speciesId, target, normals) }
                }

                is Job.Shiny -> {
                    if (store.gen2Colours.shinyOf(job.speciesId) != null) true
                    else true.takeIf { fetchShiny(job.speciesId, shinies) }
                }
            }
        }

        normals.forEach { (setId, colours) ->
            SpriteSet.entries.firstOrNull { it.id == setId }?.let {
                store.gen2Colours.write(it, colours)
            }
        }
        if (shinies.isNotEmpty()) store.gen2Colours.writeShiny(shinies)

        DownloadProgress(total, total, failed, finished = true).also(onProgress)
    }

    /**
     * A species' shiny pair, which is two lines of text in the decompilation.
     */
    private fun fetchShiny(
        speciesId: String,
        into: MutableMap<String, IntArray>,
    ): Boolean {
        val bytes = read(Gen2Sprites.shinyUrl(speciesId)) ?: return false
        val colours = Gen2Sprites.parsePal(String(bytes, Charsets.US_ASCII)) ?: return false
        into[spriteFileName(speciesId)] = colours
        return true
    }

    private fun fetch(
        set: SpriteSet,
        speciesId: String,
        target: File,
        normals: MutableMap<String, ConcurrentHashMap<String, IntArray>>,
    ): Boolean {
        val address =
            if (set.generation == 2) Gen2Sprites.url(set, speciesId) ?: return false
            else "$BASE_URL/${set.remotePath}/${spriteFileName(speciesId)}.png"
        var bytes = read(address) ?: return false

        if (set.generation == 2) {
            // The colours the cartridge showed are the file's own palette, in
            // index order, and decoding throws that order away — so it is
            // read here, out of the bytes, before anything else happens to
            // them.
            val palette = Gen2Sprites.paletteOf(bytes) ?: return false
            normals.getOrPut(set.id) { ConcurrentHashMap() }[spriteFileName(speciesId)] = palette
            // Crystal keeps a sprite's animation frames under the still one,
            // all in the same file.
            bytes = Gen2Sprites.firstFrame(bytes) ?: return false
        }

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

    /** The bytes at a URL, or null for anything that is not a plain 200. */
    private fun read(address: String): ByteArray? {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        // Deliberately not disconnected: that closes the pooled socket, and
        // with hundreds of files to fetch from one host it meant hundreds of
        // fresh handshakes. Closing the stream hands the connection back to
        // the pool for the next one.
        val bytes =
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            else connection.inputStream.use { it.readBytes() }
        return bytes.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val BASE_URL =
            "https://raw.githubusercontent.com/ShiraTheMogul/rby-sprites-project/main"


    }
}
