package com.logie.gen1storage.ui

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps a sprite square: enlarge by whole pixels only.
 *
 * The sizes here are the real ones. pokered drew every Pokémon 56 pixels
 * square; pokegold and pokecrystal draw each species at the size it needs,
 * and the three Generation II starters are all 40.
 */
class Gen1WholePixelsTest {

    private fun scaleFor(art: Float, box: Float): Float =
        Gen1WholePixels.computeScaleFactor(Size(art, art), Size(box, box)).scaleX

    @Test
    fun `a sprite that fits exactly is left at its own scale`() {
        assertEquals(1f, scaleFor(56f, 56f), 0f)
        assertEquals(3f, scaleFor(56f, 168f), 0f)
    }

    @Test
    fun `a forty-pixel sprite in a box measured in fifty-sixes is not stretched by a fraction`() {
        // What Chikorita was getting: 56 / 40 = 1.4, so some of its pixels
        // came out a device pixel wider than their neighbours.
        assertEquals(1f, scaleFor(40f, 56f), 0f)
        assertEquals(2f, scaleFor(40f, 112f), 0f)
        assertEquals(4f, scaleFor(48f, 224f), 0f)
    }

    @Test
    fun `every whole scale is a whole number for every size Generation II uses`() {
        for (art in listOf(40f, 48f, 56f)) {
            for (box in 20..400) {
                val scale = scaleFor(art, box.toFloat())
                if (scale >= 1f) {
                    assertEquals("$art in $box", scale, kotlin.math.floor(scale), 0f)
                }
            }
        }
    }

    @Test
    fun `a sprite is never drawn bigger than the space it was given`() {
        for (art in listOf(40f, 48f, 56f)) {
            for (box in 4..400) {
                val drawn = art * scaleFor(art, box.toFloat())
                assertTrue("$art in $box drew $drawn", drawn <= box + 0.001f)
            }
        }
    }

    @Test
    fun `too small to draw at all shrinks rather than overflowing`() {
        // No whole multiple to snap to under 1:1, and a sprite spilling out
        // of its slot would be worse than a soft edge.
        assertTrue(scaleFor(56f, 28f) < 1f)
        assertEquals(0.5f, scaleFor(56f, 28f), 0.0001f)
    }

    @Test
    fun `a sprite with no size does not divide by zero`() {
        assertEquals(1f, Gen1WholePixels.computeScaleFactor(Size(0f, 0f), Size(56f, 56f)).scaleX, 0f)
    }
}
