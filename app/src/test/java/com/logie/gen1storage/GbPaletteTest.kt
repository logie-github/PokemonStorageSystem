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
            setOf(
                "original", "red", "blue", "green", "yellow", "pastel", "route", "purple_rain",
                "gold", "ho_oh", "lugia", "silver", "crystal", "suicune",
            ),
            ids,
        )
    }

    /**
     * The palettes that are ramps, as against sets of four named colours.
     *
     * A Game Boy palette is four slots a tile's shade indexes into, and
     * nothing says slot 2 has to be brighter than slot 1 — the Super Game Boy
     * tables are full of pairs that are not. The ones listed here were built
     * as ramps, though, so a swapped pair in one of them would be a mistake
     * rather than a choice, and that is what [the ramp runs darkest to
     * lightest] is guarding.
     */
    private val brightnessRamps = setOf(
        "original", "red", "blue", "green", "yellow", "pastel", "route", "purple_rain",
    )

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

    /**
     * Named shades rather than transcribed ones, so what is worth pinning is
     * that they are in the order the ramp reads them: the magenta is the
     * second-darkest of the four and belongs between the purple and the lilac.
     */
    @Test
    fun `the purple rain palette is ordered darkest to lightest`() {
        assertEquals(Color(0xFF68006A), GbPalette.PURPLE_RAIN.darkest)
        assertEquals(Color(0xFFFF0084), GbPalette.PURPLE_RAIN.dark)
        assertEquals(Color(0xFF8570B2), GbPalette.PURPLE_RAIN.light)
        assertEquals(Color(0xFFADFFFC), GbPalette.PURPLE_RAIN.lightest)
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
            if (palette.id !in brightnessRamps) return@forEach
            val luma = palette.ramp.map { luminance(it) }
            assertTrue(
                "${palette.id} ramp is not monotonic: $luma",
                luma.zipWithNext().all { (a, b) -> a < b },
            )
        }
    }

    /**
     * The six named sets, pinned in the order they were given: bottom of the
     * screen first, top last. The ground is drawn from the middle two and the
     * lightest ([Modifier.gen1Ground]), so a pair swapped here turns the
     * screen upside down.
     */
    @Test
    fun `the named colour sets are in the order they were given`() {
        assertEquals(Color(0xFFB7404A), GbPalette.GOLD.darkest)
        assertEquals(Color(0xFFD8CFA9), GbPalette.GOLD.lightest)
        assertEquals(Color(0xFFED8047), GbPalette.HO_OH.darkest)
        assertEquals(Color(0xFFFCFFE0), GbPalette.HO_OH.lightest)
        assertEquals(Color(0xFF31408E), GbPalette.LUGIA.darkest)
        assertEquals(Color(0xFFFEFEFE), GbPalette.LUGIA.lightest)
        assertEquals(Color(0xFF96147C), GbPalette.SILVER.darkest)
        assertEquals(Color(0xFFAEC2D4), GbPalette.SILVER.lightest)
        assertEquals(Color(0xFF4277A3), GbPalette.CRYSTAL.darkest)
        assertEquals(Color(0xFFEDF2F6), GbPalette.CRYSTAL.lightest)
        assertEquals(Color(0xFF9676B5), GbPalette.SUICUNE.darkest)
        assertEquals(Color(0xFF73B8C7), GbPalette.SUICUNE.lightest)
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
