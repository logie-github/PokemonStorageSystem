package com.logie.gen1storage.sprites

import android.graphics.BitmapFactory
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.download.fetchInParallel
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen2Data
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
        /**
         * Only these species, or every species the sets have when null.
         *
         * A handful named here is a run that finishes in seconds rather than
         * minutes, which is what the first launch fetches before it starts on
         * the whole archive: the dozen or so the introduction is about to put
         * on screen, so the art is already there when it is wanted. See
         * [com.logie.gen1storage.ui.StorageViewModel.downloadFirstRun].
         */
        species: Collection<String>? = null,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadProgress = withContext(Dispatchers.IO) {
        // Each set is fetched for the species its own generation has: the
        // Generation I sets for the original 151, Gold, Silver and Crystal for
        // all 251. Johto had no art in the Generation I sets to ask for, and
        // asking anyway is a hundred round trips that can only 404.
        val gen1Species = Gen1Data.species.map { it.id }
        val gen2Species = Gen2Data.speciesIds
        // UNOWN alone draws as one of twenty-six letters rather than one
        // picture, so its sprite job is twenty-six — one per synthetic id
        // Gen2Sprites.unownFormId builds — on top of the bare UNOWN id this
        // app already showed before a Pokémon's own DVs picked a letter.
        val gen2SpriteIds = gen2Species.flatMap { id ->
            if (id.equals("UNOWN", ignoreCase = true)) listOf(id) + Gen2Sprites.UNOWN_FORM_IDS
            else listOf(id)
            // The egg, which is not a species and is drawn instead of one.
        } + Gen2Sprites.EGG_ID
        val only = species?.map { it.uppercase() }?.toSet()
        fun speciesFor(set: SpriteSet): List<String> {
            val all = if (set.generation == 2) gen2SpriteIds else gen1Species
            return if (only == null) all else all.filter { it.uppercase() in only }
        }
        // Every file in the run, across every set, as one flat list. Going set
        // by set would have each set's tail waiting on its own last few files
        // while the line sat idle.
        val wanted: List<Job> = buildList {
            sets.forEach { set -> speciesFor(set).forEach { add(Job.Sprite(set, it)) } }
            // One shiny palette per species, whichever Generation II sets are
            // being fetched: pokegold and pokecrystal hold the same file, so
            // all three sets read the one copy.
            // Left out of a named run: a shiny palette is two lines of text
            // for a colour nothing in a first launch is about to draw, and
            // two hundred and fifty of them would be most of the run.
            if (only == null && sets.any { it.generation == 2 }) {
                gen2Species.forEach { add(Job.Shiny(it)) }
            }
        }
        val total = wanted.size

        onProgress(DownloadProgress(0, total))
        // A named run can ask for species a set has none of, and a set with
        // nothing to fetch has no folder to make.
        sets.forEach { set ->
            speciesFor(set).firstOrNull()?.let { File(store.fileFor(set, it).parent!!).mkdirs() }
        }

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
        val addresses =
            if (set.generation == 2) Gen2Sprites.urls(set, speciesId).ifEmpty { return false }
            else listOf("$BASE_URL/${set.remotePath}/${spriteFileName(speciesId)}.png")
        // Each name in turn until one answers. Generation II has two for most
        // species and one for the handful drawn once for both games; see
        // [Gen2Sprites.urls].
        var bytes = addresses.firstNotNullOfOrNull { read(it) } ?: return false
        // Checked on the raw download, before Gen2Sprites.firstFrame below:
        // that function re-encodes whatever pixels it is handed into a fresh,
        // structurally valid PNG, so a truncated source would still pass a
        // check made after it ran.
        if (!isCompletePng(bytes)) return false

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
            else runCatching { connection.inputStream.use { it.readBytes() } }.getOrNull()
                ?: return null
        // A connection dropped mid-transfer does not always surface as a
        // thrown IOException — some paths hand back whatever arrived before
        // the socket closed and call it EOF. That used to land in the sprite
        // folder looking downloaded: a real file, a real name, the first few
        // rows of a real picture, and noise or blank space for the rest of
        // it, because a truncated PNG can still decode as far as it goes.
        // The server's own Content-Length is the one thing that says how
        // much there was supposed to be, so a short read against it is
        // refused here rather than trusted to bounds-only decoding later.
        val expected = connection.contentLengthLong
        if (expected > 0 && bytes.size.toLong() != expected) return null
        return bytes.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val BASE_URL =
            "https://raw.githubusercontent.com/ShiraTheMogul/rby-sprites-project/main"


    }
}
