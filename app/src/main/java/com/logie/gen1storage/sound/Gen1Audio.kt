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
import java.util.Collections

/**
 * Everything the app plays: the Pokémon cries and the interface's own sounds.
 *
 * Both are this project's own work and ship with it. The cries used to be
 * downloaded recordings; they are now synthesised here out of pret's note
 * data through a model of the Game Boy's sound channels, so nothing is
 * fetched and nothing of anybody's recordings is distributed. See [CrySynth].
 *
 * [SoundPool] rather than MediaPlayer because these are short one-shots: it
 * keeps decoded samples in memory and starts them immediately. Loading is
 * asynchronous, so a sound asked for before its sample is ready is remembered
 * and played from the load callback rather than dropped.
 */
class Gen1Audio(private val context: Context) {

    private val cries = CryStore(context)

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** File name to the pool's sample id, once it has been loaded. */
    private val samples = Collections.synchronizedMap(HashMap<String, Int>())

    /** Samples still loading that should play the moment they are ready. */
    private val pending = Collections.synchronizedSet(HashSet<Int>())

    /** Whether each effect may be heard. Set from the player's settings. */
    @Volatile var allowed: (SoundEffect) -> Boolean = { true }

    /** When the cry now sounding should be finished, by the clock. */
    @Volatile private var cryBusyUntil = 0L

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && pending.remove(sampleId)) {
                runCatching { pool.play(sampleId, 1f, 1f, 1, 0, 1f) }
            }
        }
    }

    /** Sound effects are bundled, so each is loaded once and kept. */
    private val effects = Collections.synchronizedMap(HashMap<SoundEffect, Int>())

    /**
     * Plays an interface sound, if the player has left it on.
     *
     * Deliberately not queued behind anything: these are short, they punctuate
     * an action, and one arriving late would be worse than one overlapping.
     */
    fun play(effect: SoundEffect) {
        if (!allowed(effect)) return
        effects[effect]?.let {
            runCatching { pool.play(it, 1f, 1f, 1, 0, 1f) }
            return
        }
        val resourceId = context.resources.getIdentifier(
            effect.resourceName,
            "raw",
            context.packageName,
        )
        if (resourceId == 0) return
        val id = runCatching { pool.load(context, resourceId, 1) }.getOrNull() ?: return
        effects[effect] = id
        pending.add(id)
    }

    /**
     * Plays [dexNumber]'s cry once, waiting out whatever cry is still sounding.
     *
     * [SoundPool] will not say how long a sample runs, so this holds a fixed
     * window instead — every Generation I cry is well under it. Waiting rather
     * than cutting the previous one off is what makes tapping a sprite twice
     * sound like two cries instead of one interrupted one.
     */
    fun cry(dexNumber: Int?, generation: Int = 1) {
        if (dexNumber == null) return
        // A Pokemon out of a Generation II save cries its own game's cry, and
        // the first hundred and fifty-one have one in each: same dex number,
        // different note data.
        val key = "$generation/$dexNumber"
        samples[key]?.let { sample ->
            scope.launch { soundAfterTheLast(sample) }
            return
        }
        scope.launch {
            val file = runCatching { cries.render(dexNumber, generation) }.getOrNull()
                ?: return@launch
            val id = runCatching { pool.load(file.path, 1) }.getOrNull() ?: return@launch
            samples[key] = id
            pending.add(id)
        }
    }

    private suspend fun soundAfterTheLast(sampleId: Int) {
        val wait = cryBusyUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        cryBusyUntil = System.currentTimeMillis() + CRY_LENGTH_MILLIS
        runCatching { pool.play(sampleId, 1f, 1f, 1, 0, 1f) }
    }

    fun release() {
        scope.cancel()
        runCatching { pool.release() }
        samples.clear()
        effects.clear()
    }

    private companion object {

        /** Longer than any Generation I cry, which is all this has to be. */
        const val CRY_LENGTH_MILLIS = 1_100L

    }
}

val LocalGen1Audio = staticCompositionLocalOf<Gen1Audio?> { null }

/** One player for the app, released with the composition that owns it. */
@Composable
fun rememberGen1Audio(): Gen1Audio {
    val context = LocalContext.current.applicationContext
    val audio = remember { Gen1Audio(context) }
    DisposableEffect(audio) { onDispose { audio.release() } }
    return audio
}
