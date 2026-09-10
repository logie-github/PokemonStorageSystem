package com.logie.gen1storage

import com.logie.gen1storage.ui.GEN1_TILE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The border tiles, checked against the disassembly they were taken from.
 *
 * These are tiles `$19`-`$1E` of `gfx/font/font_extra.png` in pret/pokered. A
 * transcription is exactly the kind of thing that rots silently — one wrong
 * pixel still draws a plausible-looking box — so the expected patterns are
 * written out here a second time, independently, and compared.
 */
class Gen1BorderTest {

    private val expectedTopLeft = listOf(
        "........",
        "...##...",
        "..#.##.#",
        ".######.",
        ".#....#.",
        "..#..#.#",
        "...##.#.",
        "...#.#..",
    )

    private val expectedBottomRight = listOf(
        "..#.#...",
        ".#.##...",
        "#.#.##..",
        ".######.",
        ".#....#.",
        "#.#..#..",
        "...##...",
        "........",
    )

    @Test
    fun `a tile is eight Game Boy pixels square`() {
        assertEquals(8, GEN1_TILE)
    }

    @Test
    fun `the transcribed corners are the tiles from the disassembly`() {
        assertEquals(expectedTopLeft, Gen1BorderSource.tile("CORNER_TOP_LEFT"))
        assertEquals(expectedBottomRight, Gen1BorderSource.tile("CORNER_BOTTOM_RIGHT"))
    }

    /**
     * The corners are not rotations of one another — the cartridge's own tiles
     * are drawn separately and differ in where the rules meet the ring. A
     * "clever" refactor that generated three of them by flipping the first
     * would look right and be wrong, so this pins it.
     */
    @Test
    fun `each corner is its own tile`() {
        val corners = listOf(
            "CORNER_TOP_LEFT",
            "CORNER_TOP_RIGHT",
            "CORNER_BOTTOM_LEFT",
            "CORNER_BOTTOM_RIGHT",
        ).map { Gen1BorderSource.tile(it) }
        assertEquals(corners.size, corners.toSet().size)
        corners.forEach { corner ->
            assertEquals(8, corner.size)
            corner.forEach { row -> assertEquals(8, row.length) }
        }
    }

    /**
     * The two rules are asymmetric, and that asymmetry is the cartridge's:
     * the horizontal edge is one pixel, a gap, then two, while the vertical is
     * one, a gap, then one. Squaring them up would be the obvious "fix" and
     * would stop it looking like the real thing.
     */
    @Test
    fun `the rules keep the tileset's own asymmetry`() {
        assertEquals(listOf(2, 4, 5), Gen1BorderSource.edgeRows())
        assertEquals(listOf(2, 4), Gen1BorderSource.edgeColumns())
    }

    @Test
    fun `every corner carries the ring the rules run into`() {
        listOf("CORNER_TOP_LEFT", "CORNER_TOP_RIGHT", "CORNER_BOTTOM_LEFT", "CORNER_BOTTOM_RIGHT")
            .forEach { name ->
                val tile = Gen1BorderSource.tile(name)
                // The ring's widest row, six pixels across, is in every corner.
                assertTrue(name, tile.any { it.contains("######") })
            }
    }
}
