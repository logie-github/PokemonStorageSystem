package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/**
 * A card's own corner, cut in a staircase rather than a curve.
 *
 * The games have nothing rounder than a diagonal line of pixels to draw a
 * curve with, so "rounded" here is the same trick every sprite on the
 * cartridge uses for one: a step in from each straight edge, one Game Boy
 * pixel at a time, [corner] steps deep. It reads as a corner being taken off
 * without ever pretending to be a circle.
 */
internal fun steppedCardOutline(size: Size, pixel: Float, corner: Int): Path {
    val w = size.width
    val h = size.height
    val r = corner.coerceAtMost((minOf(w, h) / (2 * pixel)).toInt()).coerceAtLeast(0)
    return Path().apply {
        if (r == 0) {
            addRect(Rect(0f, 0f, w, h))
            return@apply
        }
        moveTo(r * pixel, 0f)
        lineTo(w - r * pixel, 0f)
        for (i in 0 until r) {
            val x = w - (r - i - 1) * pixel
            lineTo(x, i * pixel)
            lineTo(x, (i + 1) * pixel)
        }
        lineTo(w, h - r * pixel)
        for (i in 0 until r) {
            val y = h - (r - i - 1) * pixel
            lineTo(w - i * pixel, y)
            lineTo(w - (i + 1) * pixel, y)
        }
        lineTo(r * pixel, h)
        for (i in 0 until r) {
            val x = (r - i - 1) * pixel
            lineTo(x, h - i * pixel)
            lineTo(x, h - (i + 1) * pixel)
        }
        lineTo(0f, r * pixel)
        for (i in 0 until r) {
            val y = (r - i - 1) * pixel
            lineTo(i * pixel, y)
            lineTo((i + 1) * pixel, y)
        }
        close()
    }
}

/**
 * A trainer card's frame: a plain panel with its corners stepped off, and the
 * game's name down a coloured band on the left.
 *
 * Deliberately not [Gen1Frame]. That frame is the cartridge's own window,
 * transcribed tile for tile, and it belongs to the things that are windows —
 * menus, message boxes, lists. A trainer card is an object rather than a
 * window onto something, so it is drawn as one: a flat panel, a stepped edge,
 * and a spine.
 *
 * The spine is what a card is found by when every game's cards are in one
 * list. It carries the game's name set downward a letter to a line, ruled off
 * above and below, in the second darkest of that game's own four shades —
 * dark enough to hold light letters, light enough not to read as the border.
 */
@Composable
fun Gen1SpineCard(
    modifier: Modifier = Modifier,
    fill: Color,
    ink: Color,
    /** The game's name down the left edge, or null for no spine at all. */
    spine: String? = null,
    spineFill: Color = ink,
    spineInk: Color = fill,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pixel = gen1PixelPx().toFloat()
    val spineWidth = gen1Dp(SPINE_PIXELS)
    Box(
        modifier
            .gen1MaxWidth()
            .gen1WindowBounds()
            .drawWithContent {
                val outline = steppedCardOutline(size, pixel, CARD_CORNER_STEPS)
                clipPath(outline) {
                    drawRect(fill)
                    if (spine != null) {
                        drawRect(
                            spineFill,
                            Offset.Zero,
                            Size(SPINE_PIXELS * pixel, size.height),
                        )
                    }
                    this@drawWithContent.drawContent()
                }
                drawPath(outline, ink, style = Stroke(pixel))
            },
    ) {
        // The content settles the card's height, and the spine then matches
        // whatever that turned out to be. The other way round — a spine that
        // asked for a height of its own — would have the letters deciding how
        // tall a trainer card is.
        Column(
            Modifier
                .padding(start = if (spine == null) 0.dp else spineWidth)
                .padding(gen1Dp(CARD_INSET_PIXELS)),
            content = content,
        )
        if (spine != null) {
            Box(Modifier.matchParentSize()) {
                Spine(spine, spineWidth, spineInk)
            }
        }
    }
}

/** The game's name down the band, ruled off top and bottom as a label is. */
@Composable
private fun Spine(word: String, width: androidx.compose.ui.unit.Dp, ink: Color) {
    Column(
        Modifier.width(width).fillMaxHeight().padding(vertical = gen1Dp(2)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Rule(ink)
        Spacer(Modifier.height(gen1Dp(2)))
        // A letter to a line, and the longer names set smaller so CRYSTAL
        // still fits down the side of a card only as tall as its own four
        // lines of text. The card's height is the card's contents' to decide;
        // the spine fits itself to whatever that came to.
        val style = if (word.length > SHORT_SPINE_LETTERS) Gen1TextTiny else Gen1TextSmall
        word.forEach { letter ->
            GbText(letter.toString(), style = style.copy(color = ink), maxLines = 1)
        }
        Spacer(Modifier.height(gen1Dp(2)))
        Rule(ink)
    }
}

@Composable
private fun Rule(ink: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = gen1Dp(2))
            .height(gen1Dp(1))
            .background(ink),
    )
}

/** How wide the spine is, in game pixels: a character and air either side. */
private const val SPINE_PIXELS = 14

/** Up to this many letters the spine is set at the ordinary small size. */
private const val SHORT_SPINE_LETTERS = 5

/** How far the card's own contents sit in from its edge, in game pixels. */
private const val CARD_INSET_PIXELS = 4

/** How many pixels each corner steps in — chunky enough to read as cut. */
private const val CARD_CORNER_STEPS = 3
