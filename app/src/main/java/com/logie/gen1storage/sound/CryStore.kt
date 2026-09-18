package com.logie.gen1storage.sound

import android.content.Context
import java.io.File

/**
 * The Pokémon cries, rendered on this device and kept as WAV.
 *
 * They used to be downloaded: two hundred and fifty-one recordings from an
 * archive, the largest fetch this app had after the sprites. They are not
 * recordings any more. A cry is note data and a pitch, played through the
 * Game Boy's own sound channels, so [CrySynth] makes one out of tables that
 * ship in the APK and nothing is fetched at all. See `tools/generate_cries.py`.
 *
 * Kept on disk rather than in memory because [android.media.SoundPool] loads
 * from a file, and because rendering is a few milliseconds that only the first
 * tap of each species should pay. The file is the app's own output, so the
 * cache is thrown away and rebuilt whenever the synthesiser changes: see
 * [VERSION].
 */
class CryStore(private val directory: File) {

    constructor(context: Context) : this(File(context.cacheDir, "cries"))

    fun file(dexNumber: Int, generation: Int): File =
        File(directory, "$VERSION-$generation-$dexNumber.wav")

    /**
     * The cry for a species, rendered if this is the first time it is asked
     * for. Null only for a dex number neither generation has.
     */
    fun render(dexNumber: Int, generation: Int): File? {
        val target = file(dexNumber, generation)
        if (target.isFile && target.length() > 0) return target

        val samples = (if (generation >= 2) CrySynth.gen2(dexNumber) else CrySynth.gen1(dexNumber))
            ?: return null
        if (samples.isEmpty()) return null

        directory.mkdirs()
        // Written beside the target and renamed, so a render interrupted
        // halfway cannot leave a file that later looks finished.
        val staged = File(directory, "${target.name}.part")
        return runCatching {
            staged.writeBytes(wav(samples))
            if (!staged.renameTo(target)) {
                staged.delete()
                null
            } else target
        }.getOrNull()
    }

    fun bytesOnDisk(): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        directory.deleteRecursively()
    }

    /** A 16-bit mono WAV around the samples, which is all a header is. */
    private fun wav(samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = java.io.ByteArrayOutputStream(44 + dataBytes)
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        fun int16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        ascii("RIFF"); int32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(1)
        int32(CrySynth.RATE); int32(CrySynth.RATE * 2); int16(2); int16(16)
        ascii("data"); int32(dataBytes)
        samples.forEach { int16(it.toInt()) }
        return out.toByteArray()
    }

    companion object {
        /**
         * Bumped whenever the synthesiser's output changes, so an install
         * that has cached the old sound renders the new one rather than going
         * on playing what it already had.
         */
        const val VERSION = 1

        /** Every species either generation has a cry for. */
        const val LAST_CRY = 251
    }
}
