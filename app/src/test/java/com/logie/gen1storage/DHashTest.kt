package com.logie.gen1storage

import com.logie.gen1storage.mods.DHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bit-twiddling every scan a mod goes through rests on.
 *
 * [EXPECTED_HEX] is not made up: it is what
 * `tools/generate_mod_scanner_hashes.py`'s own dHash, run against a
 * deterministic 20x18 RGBA test image with a punched-out alpha hole, comes
 * out to in Pillow. [LUMINANCE] is that same run's own resized,
 * white-composited, greyscale pixel grid, read back out mid-computation.
 * Feeding it to [DHash.fromResizedLuminance] and comparing hex strings is
 * what proves this class's bit ordering is the Python generator's own, not
 * just internally consistent with itself.
 */
class DHashTest {

    private val luminance = intArrayOf(
        207, 54, 91, 120, 230, 193, 57, 77, 200, 194, 172, 168, 145, 209, 133, 179, 220,
        43, 73, 105, 219, 201, 158, 61, 186, 195, 151, 190, 137, 207, 120, 163, 198,
        215, 62, 87, 213, 184, 140, 43, 172, 195, 134, 177, 234, 212, 105, 149, 178,
        255, 60, 69, 205, 172, 184, 56, 153, 193, 120, 160, 222, 200, 90, 133, 166,
        255, 179, 68, 202, 169, 149, 115, 161, 186, 106, 146, 211, 201, 81, 117, 152,
        239, 162, 40, 49, 160, 124, 170, 189, 167, 95, 130, 189, 197, 71, 101, 138,
        226, 234, 110, 19, 198, 113, 147, 220, 188, 78, 117, 162, 255, 134, 71, 125,
        225, 210, 151, 27, 183, 172, 131, 202, 184, 59, 104, 148, 249, 159, 68, 105,
        223, 185, 207, 122, 142, 174, 99, 193, 204, 43, 90, 134, 232, 195, 146, 70,
        218, 180, 184, 155, 136, 175, 83, 130, 236, 133, 61, 120, 227, 188, 173, 53,
        208, 179, 153, 194, 255, 158, 69, 115, 168, 172, 41, 104, 222, 170, 175, 138,
        189, 177, 137, 183, 245, 154, 54, 100, 157, 233, 121, 81, 217, 162, 161, 188,
        171, 174, 121, 168, 216, 153, 42, 84, 141, 228, 191, 49, 210, 155, 143, 183,
        248, 168, 106, 153, 204, 255, 41, 66, 125, 226, 168, 119, 214, 147, 128, 166,
        240, 151, 91, 136, 191, 249, 115, 54, 107, 225, 151, 193, 60, 133, 115, 150,
        230, 151, 77, 122, 179, 233, 230, 66, 76, 224, 141, 172, 100, 199, 103, 133,
        217, 156, 60, 109, 161, 233, 211, 110, 56, 224, 135, 143, 181, 224, 136,
    )

    private val expectedHex = "730be357c6cead9c1b396779ce629cd6b8a531336a674ace99dc339a6735ce2e"

    @Test
    fun `matches the Python generator's own hash bit for bit`() {
        val hash = DHash.fromResizedLuminance(luminance)
        assertEquals(expectedHex, DHash.toHex(hash))
    }

    @Test
    fun `a hash round trips through hex`() {
        val hash = DHash.fromResizedLuminance(luminance)
        val roundTripped = DHash.fromHex(DHash.toHex(hash))
        assertEquals(hash.toList(), roundTripped?.toList())
    }

    @Test
    fun `fromHex rejects anything that is not 64 hex characters`() {
        assertNull(DHash.fromHex(""))
        assertNull(DHash.fromHex("abc"))
        assertNull(DHash.fromHex("g".repeat(64)))
        assertNull(DHash.fromHex("a".repeat(63)))
        assertNull(DHash.fromHex("a".repeat(65)))
    }

    @Test
    fun `identical hashes are zero bits apart`() {
        val hash = DHash.fromResizedLuminance(luminance)
        assertEquals(0, DHash.hammingDistance(hash, hash))
    }

    @Test
    fun `flipping every bit is 256 apart, the whole hash`() {
        val hash = DHash.fromResizedLuminance(luminance)
        val inverted = LongArray(4) { hash[it].inv() }
        assertEquals(256, DHash.hammingDistance(hash, inverted))
    }

    @Test
    fun `a solid image compares as identical to another solid image`() {
        val flat = IntArray(17 * 16) { 128 }
        val a = DHash.fromResizedLuminance(flat)
        val b = DHash.fromResizedLuminance(flat)
        assertEquals(0, DHash.hammingDistance(a, b))
    }

    @Test
    fun `verdict thresholds match the reference scanner's own`() {
        assertEquals(DHash.Verdict.REJECT, DHash.verdictFor(0))
        assertEquals(DHash.Verdict.REJECT, DHash.verdictFor(14))
        assertEquals(DHash.Verdict.FLAGGED, DHash.verdictFor(15))
        assertEquals(DHash.Verdict.FLAGGED, DHash.verdictFor(30))
        assertEquals(DHash.Verdict.CLEAN, DHash.verdictFor(31))
        assertEquals(DHash.Verdict.CLEAN, DHash.verdictFor(256))
    }

    @Test
    fun `fromResizedLuminance refuses the wrong grid size`() {
        val threw = runCatching { DHash.fromResizedLuminance(IntArray(10)) }.isFailure
        assertTrue("a grid that isn't 17x16 should be refused, not silently misread", threw)
    }
}
