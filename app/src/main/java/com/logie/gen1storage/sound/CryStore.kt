package com.logie.gen1storage.sound

import android.content.Context
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.download.fetchInParallel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The Pokémon cries on the device.
 *
 * They are the games' own recordings, so none of them ship with the app: each
 * is fetched from the PokéAPI archive and kept here. One file per dex number,
 * which is all the naming this needs — the archive is keyed the same way.
 *
 * Its `legacy` folder is the Game Boy recordings, Johto's among them, so the
 * same place answers for all 251.
 *
 * Both the player that sounds them and the bulk download go through this, so
 * there is one place that knows where a cry lives and where it comes from.
 */
class CryStore(private val directory: File) {

    constructor(context: Context) : this(File(context.filesDir, "cries"))

    fun file(dexNumber: Int): File = File(directory, "$dexNumber.ogg")

    fun has(dexNumber: Int): Boolean = file(dexNumber).let { it.isFile && it.length() > 0 }

    fun count(): Int = (1..LAST_CRY).count(::has)

    fun bytesOnDisk(): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        directory.deleteRecursively()
    }

    /**
     * Fetches one cry if it is not here yet. Returns the file, or null when it
     * could not be had — a missing cry is silence, never a failure.
     */
    fun fetch(dexNumber: Int): File? {
        if (dexNumber !in 1..LAST_CRY) return null
        val target = file(dexNumber)
        if (target.isFile && target.length() > 0) return target

        val connection = (URL("$BASE_URL/$dexNumber.ogg").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }
        // Deliberately not disconnected: that shuts the socket and throws
        // away the keep-alive, so every file after it pays for a fresh TCP
        // and TLS handshake. Reading the body to the end and closing the
        // stream hands the connection back to the pool for the next one.
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return null

        directory.mkdirs()
        // Staged and renamed, so an interrupted download cannot leave half a
        // cry behind that later looks like a complete one.
        val staged = File(directory, "$dexNumber.ogg.part")
        staged.writeBytes(bytes)
        if (!staged.renameTo(target)) {
            staged.delete()
            return null
        }
        return target
    }

    /**
     * Fetches every cry that is not already here, reporting after each.
     *
     * One that fails is counted and skipped rather than ending the run: a
     * missing recording should not cost the player the other hundred and fifty.
     */
    suspend fun downloadAll(onProgress: (DownloadProgress) -> Unit): DownloadProgress =
        withContext(Dispatchers.IO) {
            val total = LAST_CRY
            onProgress(DownloadProgress(0, total))
            val counted = fetchInParallel(1..total, total, onProgress) { fetch(it) }
            DownloadProgress(total, total, counted, finished = true).also(onProgress)
        }

    companion object {
        /**
         * As far as this app's Pokémon go.
         *
         * The archive's `legacy` folder is the Game Boy recordings and it runs
         * well past here — it has every generation's — so the cap is this
         * app's rather than the archive's: 251 is what a Gold, Silver or
         * Crystal save can hold.
         */
        const val LAST_CRY = 251
        private const val BASE_URL =
            "https://raw.githubusercontent.com/PokeAPI/cries/main/cries/pokemon/legacy"
    }
}
