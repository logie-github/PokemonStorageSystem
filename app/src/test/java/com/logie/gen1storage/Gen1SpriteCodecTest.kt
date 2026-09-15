package com.logie.gen1storage

import com.logie.gen1storage.rom.Gen1SpriteCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Both fixtures here are synthetic test patterns — not any Pokémon's actual
 * sprite — compressed with pret/pokered's own `tools/pkmncompress` (built
 * from `home/uncompress.asm`'s source, at the commit this project pins) and
 * confirmed byte-for-byte round-trippable through that same tool's own
 * uncompress path before being pinned here. That is the closest thing to a
 * real cartridge this app can check itself against without one: no ROM was
 * available to validate against an actual game sprite, and a device test
 * with a real, legally owned ROM is still worth doing before trusting this
 * for a species this table has never seen decompressed.
 */
class Gen1SpriteCodecTest {

    @Test
    fun `a single all-zero tile decompresses to sixty-four zero pixels`() {
        val compressed = byteArrayOf(17, -68, 19, -63)
        val decoded = Gen1SpriteCodec.decompress(compressed, offset = 0)

        assertEquals(8, decoded.widthPx)
        assertEquals(8, decoded.heightPx)
        assertArrayEquals(IntArray(64), decoded.pixels)
    }

    @Test
    fun `a two-by-two tile pattern decompresses pixel-exact`() {
        val compressed = byteArrayOf(
            34, 35, 119, 52, -1, -51, 63, -13, 79, -4, -59, -87, 66, -99, 13, 33,
            -31, 102, -10, -34, 23, -74, 111, 97, 120, 91, 102, -28, -68, 107, -64, -26,
            -81, 26, -16, 57, -107, 80, 83, 109, -75, 65, 85, 109, -73, 5, 80, 91,
            109, -75, 65, 83, 109, -80,
        )
        val expected = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 1, 3, 1, 0, 1, 3,
            0, 0, 0, 0, 0, 0, 0, 0, 1, 3, 1, 3, 0, 3, 1, 3,
            0, 0, 0, 0, 0, 0, 0, 0, 1, 3, 1, 0, 1, 3, 1, 3,
            3, 0, 1, 2, 3, 0, 1, 2, 1, 3, 0, 3, 1, 3, 1, 0,
            0, 1, 2, 3, 0, 1, 2, 3, 1, 0, 1, 3, 1, 3, 0, 3,
            1, 2, 3, 0, 1, 2, 3, 0, 0, 3, 1, 3, 1, 0, 1, 3,
            2, 3, 0, 1, 2, 3, 0, 1, 1, 3, 1, 3, 0, 3, 1, 3,
            3, 0, 1, 2, 3, 0, 1, 2, 1, 3, 1, 0, 1, 3, 1, 3,
            0, 0, 0, 0, 0, 0, 0, 0, 3, 1, 2, 0, 1, 2, 0, 1,
            0, 0, 0, 0, 0, 0, 0, 0, 2, 3, 1, 2, 0, 1, 2, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 1, 2, 0, 1, 2,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 1, 2, 0, 1,
            0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1, 2, 3, 1, 2, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 0, 1, 2, 3, 1, 2,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 0, 1, 2, 3, 1,
            0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1, 2, 0, 1, 2, 3,
        )

        val decoded = Gen1SpriteCodec.decompress(compressed, offset = 0)

        assertEquals(16, decoded.widthPx)
        assertEquals(16, decoded.heightPx)
        assertArrayEquals(expected, decoded.pixels)
    }

    @Test
    fun `decompressing at a nonzero offset reads only from there`() {
        val compressed = byteArrayOf(17, -68, 19, -63)
        val padded = byteArrayOf(9, 9, 9) + compressed
        val decoded = Gen1SpriteCodec.decompress(padded, offset = 3)

        assertArrayEquals(IntArray(64), decoded.pixels)
    }

    @Test(expected = Gen1SpriteCodec.MalformedSpriteException::class)
    fun `a size byte claiming an implausible dimension is refused`() {
        Gen1SpriteCodec.decompress(byteArrayOf(-1, 0, 0), offset = 0)
    }
}
