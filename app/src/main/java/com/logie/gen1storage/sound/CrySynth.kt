package com.logie.gen1storage.sound

import com.logie.gen1storage.pokemon.Gen1Data
import kotlin.math.min

/**
 * A Pokémon's cry, synthesised through the sound hardware it was written for.
 *
 * A cry was never a recording. It is three channels of note commands, two
 * pulses and the noise generator, plus one pitch and one length per species
 * that bend the base cry into that Pokémon's own: thirty-eight base cries
 * cover Generation I's hundred and fifty-one, sixty-eight cover Generation
 * II's two hundred and fifty-one. So the honest way to have them is the way
 * this app already has the species tables — the data, out of pret, played
 * through a model of the hardware — rather than a download of somebody's
 * recordings of an emulator.
 *
 * What that buys, beyond being right: nothing to fetch. The cries were 251
 * files and the largest download this app had after the sprites, and they are
 * now about 40 KB of tables in the APK. See `tools/generate_cries.py`, which
 * reads pokered and pokecrystal and writes [Gen1CryTable] and [Gen2CryTable].
 *
 * The hardware, as the registers define it rather than as it sounds:
 *
 *  - **frequency** `f = 131072 / (2048 - x)`, x being the 11-bit value a
 *    `square_note` carries. A cry's pitch is added to x before it is played,
 *    which is why the same base cry is a Nidoran and a Nidoking.
 *  - **duty** NR11 bits 6-7, the fraction of the period the wave is high.
 *    `duty_cycle_pattern` is four of them in one byte, rotated two bits every
 *    frame, so the timbre changes sixty times a second.
 *  - **envelope** NR12. The volume steps by one every `fade / 64` seconds,
 *    down for a positive fade and up for a negative one; zero holds it.
 *  - **sweep** NR10. Every `period / 128` seconds, `x += x >> shift`, or minus
 *    for a downward sweep. x passing 2047 silences the channel.
 *  - **noise** NR43. A linear-feedback shift register clocked at
 *    `4194304 / (divisor << shift)`, seven bits wide or fifteen.
 *  - **length** a note's own, times the cry's, over 256, with the remainder
 *    carried into the next note exactly as `SetNoteDuration` carries it.
 */
object CrySynth {

    /** Rendered at CD rate: the arithmetic is cheap and a cry is a second. */
    const val RATE = 44100

    /**
     * How many points are taken per output sample before they are averaged.
     *
     * The hardware's edges land wherever they land in a period, and sampling
     * that at 44.1 kHz alone puts the error back as aliasing — a ringing on
     * top of the note that is not in the cartridge. Eight points and a box
     * average is enough to put it under the noise floor, and eight times
     * nothing is still nothing.
     */
    private const val OVERSAMPLE = 8

    private const val FRAMES_PER_SECOND = 60.0
    private const val CPU_HZ = 4_194_304.0

    /**
     * Longer than any cry, and the stop against a table that says otherwise.
     *
     * The longest real one is Jynx at about three and a half seconds: cry $0D,
     * which loops its first channel twice over, at the slowest length a cry
     * can carry.
     */
    private const val MAX_SECONDS = 6.0

    /** NR43's divisor codes, in the order the three low bits name them. */
    private val NOISE_DIVISORS = intArrayOf(8, 16, 32, 48, 64, 80, 96, 112)

    /** The four duties, as the fraction of a period the wave is high. */
    private val DUTIES = doubleArrayOf(0.125, 0.25, 0.5, 0.75)

    // Opcodes, matching tools/generate_cries.py.
    private const val OP_DUTY_PATTERN = 0
    private const val OP_DUTY = 1
    private const val OP_PITCH_SWEEP = 2
    private const val OP_PITCH_OFFSET = 3
    private const val OP_SQUARE = 4
    private const val OP_NOISE = 5

    /**
     * Generation I's, by Pokédex number.
     *
     * The cry table is indexed by internal index rather than by dex number —
     * `GetCryData` is handed `wCurPartySpecies`, which is the cartridge's own
     * numbering with Rhydon at 1 — so the dex number is translated first.
     */
    fun gen1(dexNumber: Int): ShortArray? {
        val species = Gen1Data.species.firstOrNull { it.dexNumber == dexNumber } ?: return null
        val entry = Gen1CryTable.entry(species.internalIndex - 1) ?: return null
        val cry = Gen1CryTable.channels(entry[0]) ?: return null
        // `Audio1_SetSfxTempo`: the cry's length byte is added to $80 as a
        // sixteen-bit value, so the tempo runs from half speed to half again.
        return render(cry, pitch = entry[1], tempo = 0x80 + entry[2])
    }

    /** Generation II's, which are indexed by dex number and say so. */
    fun gen2(dexNumber: Int): ShortArray? {
        val entry = Gen2CryTable.entry(dexNumber - 1) ?: return null
        val cry = Gen2CryTable.channels(entry[0]) ?: return null
        // `_PlayCry` puts the length straight into the channel's tempo, whose
        // own default is $100. The pitch goes into its pitch offset.
        return render(cry, pitch = entry[1], tempo = entry[2])
    }

    /** One base cry, bent by a pitch and a tempo, as signed 16-bit mono. */
    fun render(cry: Array<IntArray>, pitch: Int, tempo: Int): ShortArray {
        val channels = listOf(
            Voice(cry.getOrNull(0) ?: IntArray(0), noise = false, pitch = pitch, tempo = tempo),
            Voice(cry.getOrNull(1) ?: IntArray(0), noise = false, pitch = pitch, tempo = tempo),
            // "No tempo for channel 4": the noise channel runs at the cry's
            // own speed whatever the length says, in both engines.
            Voice(cry.getOrNull(2) ?: IntArray(0), noise = true, pitch = 0, tempo = 0x100),
        )

        val stepSeconds = 1.0 / (RATE * OVERSAMPLE)
        val samplesPerFrame = (RATE * OVERSAMPLE / FRAMES_PER_SECOND).toInt()
        val limit = (RATE * MAX_SECONDS).toInt()

        val out = ArrayList<Short>(RATE / 2)
        var accumulated = 0.0
        var taken = 0
        var sinceFrame = samplesPerFrame

        while (out.size < limit) {
            if (sinceFrame >= samplesPerFrame) {
                sinceFrame = 0
                var alive = false
                channels.forEach { if (it.frame()) alive = true }
                if (!alive) break
            }
            var level = 0.0
            channels.forEach { level += it.sample(stepSeconds) }
            accumulated += level
            sinceFrame++
            if (++taken == OVERSAMPLE) {
                // Three channels at fifteen apiece is the loudest this can be,
                // and the headroom below full scale is where the cartridge's
                // own master volume sat.
                val value = (accumulated / OVERSAMPLE / 45.0 * 0.8 * Short.MAX_VALUE)
                out.add(value.coerceIn(-32768.0, 32767.0).toInt().toShort())
                accumulated = 0.0
                taken = 0
            }
        }
        return ShortArray(out.size) { out[it] }
    }

    /**
     * One hardware channel running one channel program.
     *
     * The program is walked a note at a time; everything else it carries — the
     * duty pattern, a sweep, a pitch offset — is state the notes after it are
     * played through, which is how the engine holds them too.
     */
    private class Voice(
        private val program: IntArray,
        private val noise: Boolean,
        private val pitch: Int,
        private val tempo: Int,
    ) {
        private var pc = 0
        private var finished = program.isEmpty()

        private var dutyPattern = 0
        private var duty = 2
        private var sweepPeriod = 0
        private var sweepShift = 0
        private var sweepDown = false
        private var pitchOffset = 0

        private var framesLeft = 0
        private var durationModifier = 0
        private var volume = 0
        private var fade = 0
        private var frequency = 0
        private var silenced = false

        private var phase = 0.0
        private var envelopeSeconds = 0.0
        private var sweepSeconds = 0.0

        /** The noise generator's shift register, which starts all ones. */
        private var lfsr = 0x7FFF
        private var lfsrWide = true
        private var noisePeriod = 0.0
        private var noiseSeconds = 0.0

        /** A frame of the engine. Returns whether this channel still has work. */
        fun frame(): Boolean {
            if (framesLeft > 0) framesLeft--
            while (framesLeft <= 0 && !finished) readNext()
            if (!noise) {
                // `Audio1_ApplyDutyCyclePattern`: rotate left by two and take
                // the top two bits, every frame, so a four-duty pattern is a
                // timbre that moves rather than a chord.
                if (dutyPattern != 0) {
                    dutyPattern = ((dutyPattern shl 2) or (dutyPattern ushr 6)) and 0xFF
                    duty = (dutyPattern ushr 6) and 0x3
                }
                if (sweepPeriod > 0 && !silenced) stepSweep()
            }
            if (framesLeft > 0) stepEnvelopeFrame()
            return framesLeft > 0 || !finished
        }

        /** One oversampled point, as this channel's contribution to the mix. */
        fun sample(stepSeconds: Double): Double {
            if (framesLeft <= 0 || silenced || volume <= 0) return 0.0
            if (noise) {
                if (noisePeriod <= 0.0) return 0.0
                noiseSeconds += stepSeconds
                while (noiseSeconds >= noisePeriod) {
                    noiseSeconds -= noisePeriod
                    clockLfsr()
                }
                return if (lfsr and 1 == 0) volume.toDouble() else -volume.toDouble()
            }
            val divisor = 2048 - frequency
            if (divisor <= 0) return 0.0
            val hz = 131072.0 / divisor
            phase += hz * stepSeconds
            if (phase >= 1.0) phase -= phase.toInt().toDouble()
            return if (phase < DUTIES[duty]) volume.toDouble() else -volume.toDouble()
        }

        private fun clockLfsr() {
            val bit = (lfsr xor (lfsr shr 1)) and 1
            lfsr = (lfsr shr 1) or (bit shl 14)
            if (!lfsrWide) lfsr = (lfsr and 0x7FBF) or (bit shl 6)
        }

        /** NR12, counted in the 64 Hz steps the frame sequencer gives it. */
        private fun stepEnvelopeFrame() {
            if (fade == 0) return
            val period = kotlin.math.abs(fade) / 64.0
            envelopeSeconds += 1.0 / FRAMES_PER_SECOND
            while (envelopeSeconds >= period) {
                envelopeSeconds -= period
                volume = (volume + if (fade > 0) -1 else 1).coerceIn(0, 15)
            }
        }

        /** NR10, in its own 128 Hz steps. */
        private fun stepSweep() {
            val period = sweepPeriod / 128.0
            sweepSeconds += 1.0 / FRAMES_PER_SECOND
            while (sweepSeconds >= period) {
                sweepSeconds -= period
                val delta = frequency shr sweepShift
                frequency += if (sweepDown) -delta else delta
                if (frequency > 2047) {
                    // Overflow stops the channel, which is the cut-off at the
                    // end of the sweeps that have one.
                    silenced = true
                    return
                }
                if (frequency < 0) frequency = 0
            }
        }

        private fun readNext() {
            if (pc >= program.size) {
                finished = true
                return
            }
            when (program[pc]) {
                OP_DUTY_PATTERN -> {
                    dutyPattern = program[pc + 1]
                    duty = (dutyPattern ushr 6) and 0x3
                    pc += 2
                }
                OP_DUTY -> {
                    dutyPattern = 0
                    duty = program[pc + 1] and 0x3
                    pc += 2
                }
                OP_PITCH_SWEEP -> {
                    sweepPeriod = program[pc + 1]
                    val shift = program[pc + 2]
                    sweepDown = shift < 0
                    sweepShift = kotlin.math.abs(shift)
                    pc += 3
                }
                OP_PITCH_OFFSET -> {
                    pitchOffset = program[pc + 1]
                    pc += 2
                }
                OP_SQUARE -> {
                    startNote(program[pc + 1], program[pc + 2], program[pc + 3])
                    frequency = (program[pc + 4] + pitch + pitchOffset).coerceIn(0, 2047)
                    silenced = false
                    phase = 0.0
                    pc += 5
                }
                OP_NOISE -> {
                    startNote(program[pc + 1], program[pc + 2], program[pc + 3])
                    setNoise(program[pc + 4])
                    pc += 5
                }
                else -> finished = true
            }
        }

        /**
         * `SetNoteDuration`: the note's own length times the cry's tempo, over
         * 256, with what is left over kept for the next note. Dropping the
         * remainder would shorten a long cry by a frame or two, which is
         * audible as a clipped tail.
         */
        private fun startNote(length: Int, noteVolume: Int, noteFade: Int) {
            val product = (length + 1) * tempo + durationModifier
            durationModifier = product and 0xFF
            framesLeft = product shr 8
            volume = noteVolume.coerceIn(0, 15)
            fade = noteFade
            envelopeSeconds = 0.0
            sweepSeconds = 0.0
        }

        private fun setNoise(parameter: Int) {
            val shift = (parameter ushr 4) and 0xF
            lfsrWide = (parameter and 0x8) == 0
            val divisor = NOISE_DIVISORS[parameter and 0x7]
            noisePeriod = if (shift >= 14) 0.0 else (divisor.toDouble() * (1 shl shift)) / CPU_HZ
            noiseSeconds = 0.0
            lfsr = 0x7FFF
            silenced = false
        }
    }

    /** How long a rendered cry runs, for whatever has to wait on it. */
    fun millisOf(samples: ShortArray): Int = min(samples.size * 1000L / RATE, Int.MAX_VALUE.toLong()).toInt()
}
