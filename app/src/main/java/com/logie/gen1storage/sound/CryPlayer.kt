package com.logie.gen1storage.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * Pokémon cries, played once when a Pokémon is looked at.
 *
 * The recordings are **not** shipped with the app. They are lifted from the
 * games, and this app distributes none of that: the first time a species is
 * looked at its cry is fetched from the PokéAPI cries archive and kept on the
 * device, exactly as the sprites are. The first cry for a species therefore
 * arrives a moment late and every one after it is immediate, which is the
 * price of the APK carrying nothing it has no right to.
 *
 * [SoundPool] rather than MediaPlayer because these are short one-shots — it
 * keeps decoded samples in memory and starts them immediately. Loading is
 * asynchronous, so a cry asked for before its sample is ready is remembered and
 * played from the load callback rather than dropped.
 */
class CryPlayer(context: Context) {

    private val directory = File(context.filesDir, "cries")

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Dex number to the pool's sample id, once the file has been loaded. */
    private val samples = Collections.synchronizedMap(HashMap<Int, Int>())

    /** The sample still loading that should play the moment it is ready. */
    @Volatile private var pending: Int? = null

    /** When the cry now sounding should be finished, by the clock. */
    @Volatile private var busyUntil = 0L

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            if (pending == sampleId) {
                pending = null
                play(sampleId)
            }
        }
    }

    /**
     * Plays [dexNumber]'s cry once.
     *
     * Everything that can go wrong here — a number out of range, no network, a
     * file that will not decode — ends in silence. A cry is decoration, and
     * nothing in this app should fail because a sound did.
     */
    fun cry(dexNumber: Int?) {
        if (dexNumber == null || dexNumber !in 1..LAST_GEN1) return
        samples[dexNumber]?.let {
            play(it)
            return
        }
        scope.launch {
            val file = runCatching { fetch(dexNumber) }.getOrNull() ?: return@launch
            val id = runCatching { pool.load(file.path, 1) }.getOrNull() ?: return@launch
            samples[dexNumber] = id
            pending = id
        }
    }

    /** The file for [dexNumber], downloading it once if it is not here yet. */
    private fun fetch(dexNumber: Int): File? {
        val target = File(directory, "$dexNumber.ogg")
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
     * Plays a sample, waiting out whatever is still sounding first.
     *
     * [SoundPool] will not say how long a sample runs, so this holds a fixed
     * window instead — every Generation I cry is well under it. Waiting rather
     * than cutting the previous one off is what makes tapping a sprite twice
     * sound like two cries instead of one interrupted one.
     */
    private fun play(sampleId: Int) {
        scope.launch {
            val wait = busyUntil - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            busyUntil = System.currentTimeMillis() + CRY_LENGTH_MILLIS
            runCatching { pool.play(sampleId, 1f, 1f, 1, 0, 1f) }
        }
    }

    fun release() {
        scope.cancel()
        runCatching { pool.release() }
        samples.clear()
    }

    private companion object {
        const val LAST_GEN1 = 151
        const val BASE_URL = "https://raw.githubusercontent.com/PokeAPI/cries/main/cries/pokemon/legacy"

        /** Longer than any Generation I cry, which is all this has to be. */
        const val CRY_LENGTH_MILLIS = 1_100L
    }
}

val LocalCryPlayer = staticCompositionLocalOf<CryPlayer?> { null }

/** One player for the app, released with the composition that owns it. */
@Composable
fun rememberCryPlayer(): CryPlayer {
    val context = LocalContext.current.applicationContext
    val player = remember { CryPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}
