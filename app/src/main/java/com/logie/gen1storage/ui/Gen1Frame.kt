package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    /**
     * Whether this window is one that opens. The games draw a text box by
     * running its border outward over about three frames, so a window that
     * arrives in answer to something opens; one that is simply part of a
     * screen's furniture does not, or every screen would flinch on arrival.
     */
    opening: Boolean = false,
    /**
     * Whether this window *is* the screen rather than a window on it.
     *
     * Two things follow from that and they are the same thought. It is not
     * held to a readable width, because the cap would leave a band of ground
     * down one side of something meant to fill. And it does not register as
     * furniture, because the gesture layer reads a swipe as the cursor's only
     * where it lands off a window — a window covering everything would leave
     * nowhere for a swipe to mean anything, and the box on a folded phone is
     * driven by swiping.
     */
    fillsScreen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pixel = gen1PixelPx().toFloat()
    Column(
        modifier
            .then(if (opening) Modifier.gen1Opening() else Modifier)
            // Half an opened screen at most. A window that runs the width of
            // an unfolded phone stops reading as a window.
            .then(if (fillsScreen) Modifier else Modifier.gen1MaxWidth())
            .then(if (fillsScreen) Modifier else Modifier.gen1WindowBounds())
            .background(fill.copy(alpha = fill.alpha * Gen1Palette.windowOpacity))
            .drawBehind { drawGen1BorderOrTileset(ink, pixel) }
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
    opening: Boolean = false,
    content: @Composable () -> Unit,
) {
    val pixel = gen1PixelPx().toFloat()
    Box(
        modifier
            .then(if (opening) Modifier.gen1Opening() else Modifier)
            .gen1WindowBounds()
            .background(fill.copy(alpha = fill.alpha * Gen1Palette.windowOpacity))
            .drawBehind { drawGen1BorderOrTileset(ink, pixel) }
            .padding(gen1Dp(GEN1_TILE - 2))
            .padding(contentPadding),
    ) { content() }
}

/** [drawGen1Border], unless a mod has bundled a tileset to draw instead. */
private fun DrawScope.drawGen1BorderOrTileset(ink: Color, pixel: Float) {
    val tileset = Gen1Palette.borderTileset
    if (tileset != null) drawGen1BorderBitmap(tileset, pixel) else drawGen1Border(ink, pixel)
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

/**
 * The three frames a window takes to open.
 *
 * Scaled out from its own middle rather than drawn border-row by border-row:
 * at this size and this speed the two are indistinguishable, and one of them
 * does not need the window measured twice.
 */
@Composable
private fun Modifier.gen1Opening(): Modifier {
    val moves = Gen1Motion.moves(Motion.WINDOWS)
    val grown = remember(moves) { Animatable(if (moves) 0f else 1f) }
    LaunchedEffect(moves) {
        if (moves) grown.animateTo(1f, tween(OPEN_MILLIS, easing = LinearEasing))
    }
    return graphicsLayer {
        // Wider before it is tall, which is the order the games draw it in.
        scaleX = (grown.value * 2f).coerceAtMost(1f)
        scaleY = grown.value
        alpha = if (grown.value > 0f) 1f else 0f
    }
}

/** Three frames at sixty, near enough. */
private const val OPEN_MILLIS = 50
