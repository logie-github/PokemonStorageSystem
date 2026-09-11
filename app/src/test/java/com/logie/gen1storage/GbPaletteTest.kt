package com.logie.gen1storage

import androidx.compose.ui.graphics.Color
import com.logie.gen1storage.ui.GbPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palettes themselves. The recolour that uses them needs a real Bitmap and
 * so belongs to an instrumented test, but the ramp's shape and ordering are
 * plain data and are exactly what a wrong index would get wrong.
 */
class GbPaletteTest {

    @Test
    fun `every palette has a unique id`() {
        val ids = GbPalette.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the requested palettes are all present`() {
        val ids = GbPalette.ALL.map { it.id }.toSet()
        assertEquals(
            setOf("original", "red", "blue", "green", "yellow", "pastel", "route"),
            ids,
        )
    }

    /**
     * The route palette is transcribed from the disassembly, so it is worth
     * pinning: the middle two are deliberately swapped from the order the SGB
     * table lists them in, and a "correction" back to that order would step
     * the background ramp backwards.
     */
    @Test
    fun `the route palette is the Super Game Boy one, ordered by brightness`() {
        assertEquals(Color(0xFFFFEFFF), GbPalette.ROUTE.lightest)
        assertEquals(Color(0xFFA5D6FF), GbPalette.ROUTE.light)
        assertEquals(Color(0xFFADE75A), GbPalette.ROUTE.dark)
        assertEquals(Color(0xFF181010), GbPalette.ROUTE.darkest)
    }

    @Test
    fun `an unknown or missing id falls back to the untinted look`() {
        assertEquals(GbPalette.ORIGINAL, GbPalette.fromId(null))
        assertEquals(GbPalette.ORIGINAL, GbPalette.fromId(""))
        assertEquals(GbPalette.ORIGINAL, GbPalette.fromId("gameboy-micro"))
        assertEquals(GbPalette.RED, GbPalette.fromId("red"))
    }

    /**
     * The recolour indexes this list by brightness bucket, darkest bucket
     * first. If the order ever flips, every sprite comes out inverted.
     */
    @Test
    fun `the ramp runs darkest to lightest`() {
        GbPalette.ALL.forEach { palette ->
            assertEquals(4, palette.ramp.size)
            assertEquals(palette.darkest, palette.ramp[0])
            assertEquals(palette.dark, palette.ramp[1])
            assertEquals(palette.light, palette.ramp[2])
            assertEquals(palette.lightest, palette.ramp[3])
            val luma = palette.ramp.map { luminance(it) }
            assertTrue(
                "${palette.id} ramp is not monotonic: $luma",
                luma.zipWithNext().all { (a, b) -> a < b },
            )
        }
    }

    @Test
    fun `only the untinted palette leaves sprites alone`() {
        assertFalse(GbPalette.ORIGINAL.tintsSprites)
        GbPalette.ALL.filter { it != GbPalette.ORIGINAL }
            .forEach { assertTrue(it.id, it.tintsSprites) }
    }

    @Test
    fun `every palette names itself`() {
        GbPalette.ALL.forEach {
            assertNotNull(it.label)
            assertTrue(it.label.isNotBlank())
        }
    }

    private fun luminance(color: Color): Int {
        val argb = color.value shr 32
        val r = ((argb shr 16) and 0xFFu).toInt()
        val g = ((argb shr 8) and 0xFFu).toInt()
        val b = (argb and 0xFFu).toInt()
        return (r * 77 + g * 151 + b * 28) shr 8
    }
}
