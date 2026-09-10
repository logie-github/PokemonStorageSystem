package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Generation I window.
 *
 * One tile of border on every side, drawn from the cartridge's own tiles (see
 * [drawGen1Border]), over a flat panel. Content is inset past the border, so a
 * caller never has to know how thick it is.
 */
@Composable
fun Gen1Frame(
    modifier: Modifier = Modifier,
    fill: Color = Gen1Palette.Panel,
    ink: Color = Gen1Palette.Ink,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val pixel = gen1PixelPx().toFloat()
    Column(
        modifier
            .background(fill)
            .drawBehind { drawGen1Border(ink, pixel) }
            .padding(gen1Dp(GEN1_TILE - 2))
            .padding(contentPadding),
        content = content,
    )
}

/** The same window as a Box, for screens that position their own children. */
@Composable
fun Gen1FrameBox(
    modifier: Modifier = Modifier,
    fill: Color = Gen1Palette.Panel,
    ink: Color = Gen1Palette.Ink,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    val pixel = gen1PixelPx().toFloat()
    Box(
        modifier
            .background(fill)
            .drawBehind { drawGen1Border(ink, pixel) }
            .padding(gen1Dp(GEN1_TILE - 2))
            .padding(contentPadding),
    ) { content() }
}

/**
 * The L-shaped rule the status screen draws around its header and trainer
 * blocks — closed on the right and bottom, open towards the sprite.
 */
@Composable
fun Gen1CornerRule(
    modifier: Modifier = Modifier,
    ink: Color = Gen1Palette.Ink,
    thickness: Dp = gen1Dp(1),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .drawBehind {
                val t = thickness.toPx()
                // Right edge, full height.
                drawRect(ink, Offset(size.width - t, 0f), Size(t, size.height))
                // Bottom edge, stopping short so the rule reads as an elbow.
                drawRect(ink, Offset(size.width * 0.12f, size.height - t), Size(size.width * 0.88f, t))
            }
            .padding(end = 12.dp, bottom = 10.dp),
        content = content,
    )
}

/** The solid selection triangle the cursor uses. */
@Composable
fun Gen1SelectionArrow(visible: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                if (!visible) return@drawBehind
                val height = size.height * 0.55f
                val width = height * 0.8f
                val top = (size.height - height) / 2f
                val left = size.width - width - 2.dp.toPx()
                // Stepped, like the tile: three stacked bars rather than a
                // smooth triangle, which is what the original glyph is.
                val steps = 5
                for (i in 0 until steps) {
                    val fraction = i / steps.toFloat()
                    val barHeight = height / steps
                    val barWidth = width * (1f - kotlin.math.abs(fraction - 0.5f) * 2f) + width * 0.15f
                    drawRect(
                        Gen1Palette.Ink,
                        Offset(left, top + i * barHeight),
                        Size(barWidth.coerceAtLeast(2.dp.toPx()), barHeight),
                    )
                }
            }
    )
}
