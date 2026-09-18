package com.logie.gen1storage

import com.logie.gen1storage.rom.Gen2SpriteCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fixtures compressed by pret/pokecrystal's own `tools/lzcompress.c` in
 * `--matching` mode, which — round-tripped through that same tool's own
 * `-u` decompressor first, to be sure it means what it appears to — is
 * confirmed to exercise every one of lz3's command types: literal runs,
 * zero runs, a repeated byte, an alternating pair, and (the part no
 * synthetic fixture can skip and still call itself a real test) a
 * self-referential repeat back into output this decompression has already
 * produced. None of these bytes are game data — they're patterns picked to
 * cover the format, run through the reference tool this app never ships.
 */
class Gen2SpriteCodecTest {

    private fun bytesOf(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

    // 400 zero bytes with two literal runs planted in them (offsets 50-69 and
    // 200-209) — lz3's LZ_ZERO and LZ_LITERAL commands.
    private val patternA = ByteArray(400).also { a ->
        for (i in 50 until 70) a[i] = ((i * 3 + 7) % 256).toByte()
        for (i in 200 until 210) a[i] = 0xAB.toByte()
    }

    private val patternACompressed = bytesOf(
        -20, 49, 19, -99, -96, -93, -90, -87, -84, -81, -78, -75, -72, -69, -66, -63, -60, -57, -54,
        -51, -48, -45, -42, -20, -127, 36, -85, -8, -122, -128, -20, 59, -1,
    )

    // 100 bytes: 40 of one repeated byte, then 60 alternating between two —
    // LZ_ITERATE followed by LZ_ALTERNATE, both in their long (10-bit
    // length) form.
    private val patternB = ByteArray(100).also { b ->
        for (i in 0 until 40) b[i] = 0x55
        for (i in 40 until 100) b[i] = if ((i - 40) % 2 == 0) 0x12 else 0x34
    }

    private val patternBCompressed = bytesOf(-28, 39, 85, -24, 59, 18, 52, -1)

    // 784 bytes: the same 16-byte tile shape 49 times over — forces lz3's
    // rewrite family (a self-referential copy back into this same
    // decompression's own output) rather than only literal encodings.
    private val patternC = ByteArray(784).also { c ->
        val tile = bytesOf(-127, 66, 36, 24, 24, 36, 66, -127, -127, 66, 36, 24, 24, 36, 66, -127)
        for (t in 0 until 49) tile.copyInto(c, t * 16)
    }

    private val patternCCompressed = bytesOf(3, -127, 66, 36, 24, -61, -128, -13, 7, -121, -1)

    @Test
    fun `zero runs and literal runs decompress byte for byte`() {
        val decompressed = Gen2SpriteCodec.decompressRaw(patternACompressed, 0, 4096)
        assertArrayEquals(patternA.copyOfRange(0, 400), decompressed)
    }

    @Test
    fun `a repeated byte and an alternating pair decompress byte for byte`() {
        val decompressed = Gen2SpriteCodec.decompressRaw(patternBCompressed, 0, 4096)
        assertArrayEquals(patternB, decompressed)
    }

    @Test
    fun `a self-referential repeat decompresses byte for byte`() {
        val decompressed = Gen2SpriteCodec.decompressRaw(patternCCompressed, 0, 4096)
        assertArrayEquals(patternC, decompressed)
    }

    @Test
    fun `a 7x7 sprite reshapes into a 56x56 picture`() {
        val sprite = Gen2SpriteCodec.decompressFrontpic(patternCCompressed, 0, 7)
        assertEquals(56, sprite.widthPx)
        assertEquals(56, sprite.heightPx)
    }

    @Test
    fun `a stream too short for the frame it was promised is refused`() {
        // Pattern B decompresses to 100 bytes, nowhere near the 400 a five
        // tile square needs, so it cannot be the pic the table said it was.
        assertThrows(Gen2SpriteCodec.MalformedSpriteException::class.java) {
            Gen2SpriteCodec.decompressFrontpic(patternBCompressed, 0, 5)
        }
    }

    @Test
    fun `a pic size no Generation II sprite ever has is refused`() {
        assertThrows(Gen2SpriteCodec.MalformedSpriteException::class.java) {
            Gen2SpriteCodec.decompressFrontpic(patternACompressed, 0, 4)
        }
    }

    @Test
    fun `a 5x5 sprite reshapes into a 40x40 picture`() {
        val sprite = Gen2SpriteCodec.decompressFrontpic(patternACompressed, 0, 5)
        assertEquals(40, sprite.widthPx)
        assertEquals(40, sprite.heightPx)
    }

    // 25 tiles, all zero but the row-major top-right one (row 0, col 4),
    // stored the way `tools/pokemon_animation_graphics.c`'s own
    // `transpose_tiles` leaves an animated pic's resting frame -- column-
    // major, i.e. this fixture's *storage* position 20 (col 4, row 0) is the
    // marked tile, not row-major position 4. Compressed literally (no
    // matching) so it is legible as bytes, then confirmed against
    // lzcompress's own -u decompressor.
    private val cornerTileCompressed = bytesOf(-19, 63, 47, -1, 127, 127, -1)

    @Test
    fun `a frontpic's tiles are put back in row-major order`() {
        // The one that matters, and the one Gold and Silver were not getting:
        // read straight out, the marked tile lands at row 0 column 4's
        // neighbour instead of the top right, and a real sprite comes out as
        // a grid of shuffled fragments at exactly the right size.
        val sprite = Gen2SpriteCodec.decompressFrontpic(cornerTileCompressed, 0, 5)
        assertEquals(40, sprite.widthPx)
        for (row in 0 until 40) {
            for (col in 0 until 40) {
                val expected = if (row < 8 && col in 32 until 40) 3 else 0
                assertEquals("row=$row col=$col", expected, sprite.pixels[row * 40 + col])
            }
        }
    }
}
