package com.logie.gen1storage

import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.sound.CrySynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The cries, rendered through the hardware model rather than fetched.
 *
 * What can be asserted without ears: that every species has one, that the
 * lengths are the lengths the cry tables ask for, and that the pitch a note
 * comes out at is the one `f = 131072 / (2048 - x)` gives for the frequency
 * register the note data carries plus the species' own pitch.
 */
class CrySynthTest {

    @Test
    fun `every Generation I species has a cry, and it is not silence`() {
        Gen1Data.species.forEach { species ->
            val samples = CrySynth.gen1(species.dexNumber)
            assertNotNull("${species.id} has no cry", samples)
            assertTrue("${species.id} is silent", samples!!.any { it.toInt() != 0 })
            // A cry is around a second, and Jynx's is three and a half:
            // cry $0D loops, and Jynx plays it at the slowest length there is.
            // Anything outside this is a table being read wrong rather than an
            // unusual Pokemon.
            val millis = samples.size * 1000 / CrySynth.RATE
            assertTrue("${species.id} runs ${millis}ms", millis in 50..4000)
        }
    }

    @Test
    fun `every Generation II species has one too`() {
        (1..251).forEach { dex ->
            val samples = CrySynth.gen2(dex)
            assertNotNull("#$dex has no cry", samples)
            assertTrue("#$dex is silent", samples!!.any { it.toInt() != 0 })
        }
    }

    @Test
    fun `a species neither generation has is silence rather than a crash`() {
        assertNull(CrySynth.gen1(0))
        assertNull(CrySynth.gen1(999))
        assertNull(CrySynth.gen2(0))
        assertNull(CrySynth.gen2(999))
    }

    @Test
    fun `the pitch a note sounds at is the one its frequency register gives`() {
        // One square note, held long enough to count cycles in: x = 1792 puts
        // f = 131072 / 256 = 512 Hz.
        val program = arrayOf(
            intArrayOf(1, 2, 4, 15, 15, 0, 1792),
            IntArray(0),
            IntArray(0),
        )
        val samples = CrySynth.render(program, pitch = 0, tempo = 0x100)
        assertEquals(512.0, dominantHz(samples), 6.0)

        // The pitch modifier is added to the register, not to the frequency:
        // x = 1792 + 128 is 131072 / 128, an octave up rather than a little
        // sharp. Getting that backwards is the difference between a Nidoran
        // and a Nidoking.
        val raised = CrySynth.render(program, pitch = 128, tempo = 0x100)
        assertEquals(1024.0, dominantHz(raised), 12.0)
    }

    @Test
    fun `a cry's length scales the notes, carrying the remainder`() {
        val program = arrayOf(
            intArrayOf(4, 15, 15, 0, 1792),
            IntArray(0),
            IntArray(0),
        )
        // Sixteen frames at the default tempo: (15 + 1) * 256 / 256.
        val normal = CrySynth.render(program, pitch = 0, tempo = 0x100)
        assertEquals(16 * CrySynth.RATE / 60, normal.size, CrySynth.RATE / 60)

        // Half the tempo is half the frames, which is what a length of $01
        // does to a Generation I cry.
        val quick = CrySynth.render(program, pitch = 0, tempo = 0x80)
        assertEquals(8 * CrySynth.RATE / 60, quick.size, CrySynth.RATE / 60)
    }

    @Test
    fun `a rising envelope gets louder and a falling one fades out`() {
        fun peakOfLastTenth(fade: Int): Int {
            val program = arrayOf(
                intArrayOf(4, 15, if (fade > 0) 15 else 1, fade, 1792),
                IntArray(0),
                IntArray(0),
            )
            val samples = CrySynth.render(program, pitch = 0, tempo = 0x100)
            val from = samples.size * 9 / 10
            return (from until samples.size).maxOf { abs(samples[it].toInt()) }
        }
        // Positive fade is a decrease, which is the engine's own convention
        // and the opposite of what the word suggests.
        assertTrue(peakOfLastTenth(fade = 2) < peakOfLastTenth(fade = -2))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected +/- $tolerance, was $actual", abs(expected - actual) <= tolerance)
    }

    /** The strongest frequency in the middle of a rendered note. */
    private fun dominantHz(samples: ShortArray): Double {
        // Zero crossings on the rising edge, over the steady middle of the
        // note, which is enough for a square wave and needs no transform.
        val from = samples.size / 4
        val to = samples.size * 3 / 4
        var crossings = 0
        for (at in from + 1 until to) {
            if (samples[at - 1] < 0 && samples[at] >= 0) crossings++
        }
        val seconds = (to - from).toDouble() / CrySynth.RATE
        return crossings / seconds
    }
}
