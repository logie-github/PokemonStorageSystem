package com.logie.gen1storage.sound

import android.content.Context
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
 * The Pokémon cries on the device.
 *
 * They are the games' own recordings, so none of them ship with the app: each
 * is fetched from the PokéAPI archive and kept here. One file per dex number,
 * which is all the naming this needs — the archive is keyed the same way.
 *
 * Both the player that sounds them and the bulk download go through this, so
 * there is one place that knows where a cry lives and where it comes from.
 */
class CryStore(private val directory: File) {

    constructor(context: Context) : this(File(context.filesDir, "cries"))

    fun file(dexNumber: Int): File = File(directory, "$dexNumber.ogg")

    fun has(dexNumber: Int): Boolean = file(dexNumber).let { it.isFile && it.length() > 0 }

    fun count(): Int = (1..LAST_GEN1).count(::has)

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
        if (dexNumber !in 1..LAST_GEN1) return null
        val target = file(dexNumber)
        if (target.isFile && target.length() > 0) return target

        val connection = (URL("$BASE_URL/$dexNumber.ogg").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }
        val bytes = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
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
            val total = Gen1Data.species.size
            var done = 0
            var failed = 0
            onProgress(DownloadProgress(0, total))
            for (dex in 1..total) {
                coroutineContext.ensureActive()
                if (!has(dex) && runCatching { fetch(dex) }.getOrNull() == null) failed++
                done++
                onProgress(DownloadProgress(done, total, failed))
            }
            DownloadProgress(done, total, failed, finished = true).also(onProgress)
        }

    private companion object {
        const val LAST_GEN1 = 151
        const val BASE_URL =
            "https://raw.githubusercontent.com/PokeAPI/cries/main/cries/pokemon/legacy"
    }
}
