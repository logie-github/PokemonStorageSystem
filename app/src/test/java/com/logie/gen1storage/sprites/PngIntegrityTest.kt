package com.logie.gen1storage.sprites

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PngIntegrityTest {

    /** A real, complete PNG's own bytes — a plain 4x4 RGBA square. */
    private val completePng = intArrayOf(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x04, 0x08, 0x06, 0x00, 0x00, 0x00, 0xA9, 0xF1, 0x9E,
        0x7E, 0x00, 0x00, 0x00, 0x15, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xFC, 0xCF, 0xC0, 0xF0,
        0x9F, 0x01, 0x09, 0x30, 0x31, 0xA0, 0x01, 0xC2, 0x02, 0x00, 0x83, 0xD1, 0x02, 0x06, 0x02, 0x90,
        0xEF, 0x58, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
    ).map { it.toByte() }.toByteArray()

    @Test
    fun `a real, complete PNG passes`() {
        assertTrue(isCompletePng(completePng))
    }

    @Test
    fun `truncated mid-transfer fails`() {
        assertFalse(isCompletePng(completePng.copyOf(completePng.size - 20)))
    }

    @Test
    fun `cut off right before the CRC fails`() {
        // IHDR would still decode bounds fine off bytes this short, which is
        // exactly the truncated-but-decodes case this check exists to catch.
        assertFalse(isCompletePng(completePng.copyOf(completePng.size - 4)))
    }

    @Test
    fun `too short to hold an IEND chunk at all fails`() {
        assertFalse(isCompletePng(ByteArray(4)))
    }

    /**
     * The gap the tail-only check had: same length, same closing IEND, one
     * byte wrong in the middle. A dropped-and-resent packet or a proxy
     * rewriting a chunk boundary leaves exactly this shape, and it is what
     * decoded to a clean top and static further down.
     */
    @Test
    fun `a byte corrupted in the middle of a chunk fails, even with a valid tail`() {
        val corrupted = completePng.copyOf()
        corrupted[45] = (corrupted[45] + 1).toByte()
        assertFalse(isCompletePng(corrupted))
    }

    @Test
    fun `a byte corrupted in IHDR fails too`() {
        val corrupted = completePng.copyOf()
        corrupted[20] = (corrupted[20] + 1).toByte()
        assertFalse(isCompletePng(corrupted))
    }

    @Test
    fun `a declared chunk length past the end of the file fails`() {
        val corrupted = completePng.copyOf()
        // IDAT's own length field, at offset 33..36.
        corrupted[36] = 0x7F
        assertFalse(isCompletePng(corrupted))
    }
}
