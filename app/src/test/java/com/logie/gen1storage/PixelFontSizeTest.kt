package com.logie.gen1storage

import com.logie.gen1storage.ui.snapFontPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The font is vector art drawing square pixels, and it is only crisp when one
 * design pixel covers a whole number of device pixels. Its em is eight design
 * pixels, so every size the app asks for has to be a multiple of eight — this
 * is the check on that.
 */
class PixelFontSizeTest {

    private val grid = 8
    private val steps = listOf(-1, 0, 2)

    /** The base em per step of the text scale, and the four scales offered. */
    private val baseEmDp = 6.5f
    private val scales = listOf(1, 2, 3, 4)

    /** The densities Android actually ships, plus the odd ones in between. */
    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)

    @Test
    fun `every size lands on the eight-pixel grid`() {
        densities.forEach { density ->
            scales.forEach { scale ->
            steps.forEach { step ->
                val (size, leading) = snapFontPixels(baseEmDp * scale * density, step)
                assertEquals("size at $density/$step", 0, size % grid)
                assertEquals("leading at $density/$step", 0, leading % grid)
            }
            }
        }
    }

    @Test
    fun `leading always clears the face's own line box`() {
        // hhea ascender 400 + descender 40 over a 320 unit em.
        val natural = 440f / 320f
        densities.forEach { density ->
            scales.forEach { scale ->
            steps.forEach { step ->
                val (size, leading) = snapFontPixels(baseEmDp * scale * density, step)
                assertTrue(
                    "leading $leading is under $natural em of $size at $density/$step",
                    leading >= size * natural,
                )
            }
            }
        }
    }

    @Test
    fun `the three sizes stay distinct and ordered`() {
        densities.forEach { density ->
            scales.forEach { scale ->
            val small = snapFontPixels(baseEmDp * scale * density, -1).first
            val body = snapFontPixels(baseEmDp * scale * density, 0).first
            val large = snapFontPixels(baseEmDp * scale * density, 2).first
            assertTrue("$small < $body at $density", small < body)
            assertTrue("$body < $large at $density", body < large)
            }
        }
    }

    @Test
    fun `a tiny density still yields a legible size rather than nothing`() {
        val (size, leading) = snapFontPixels(1f, -1)
        assertTrue(size >= grid * 2)
        assertTrue(leading > size)
    }
}
