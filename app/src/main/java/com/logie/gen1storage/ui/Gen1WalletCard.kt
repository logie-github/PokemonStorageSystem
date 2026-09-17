package com.logie.gen1storage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A card's own corner, cut in a staircase rather than a curve.
 *
 * The games have nothing rounder than a diagonal line of pixels to draw a
 * curve with, so "rounded" here is the same trick every sprite on the
 * cartridge uses for one: a step in from each straight edge, one Game Boy
 * pixel at a time, [corner] steps deep. It reads as a corner being taken off
 * without ever pretending to be a circle.
 */
private fun steppedCardOutline(size: Size, pixel: Float, corner: Int): Path {
    val w = size.width
    val h = size.height
    val r = corner.coerceAtMost((minOf(w, h) / (2 * pixel)).toInt()).coerceAtLeast(0)
    return Path().apply {
        if (r == 0) {
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
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

/** How many Game Boy pixels each corner steps in — chunky enough to read as cut. */
private const val WALLET_CORNER_STEPS = 3

/**
 * The wallet's own frame: the same flat panel [Gen1Frame] draws, in the same
 * two colours, but with its corners stepped off instead of squared. This is
 * the one place in the app a card is drawn as something other than the
 * cartridge's own TRAINER INFO window — everywhere else a trainer card still
 * opens in the ordinary frame.
 */
@Composable
fun Gen1WalletCardFrame(
    modifier: Modifier = Modifier,
    fill: Color = Gen1Palette.Panel,
    ink: Color = Gen1Palette.Ink,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val pixel = gen1PixelPx().toFloat()
    Column(
        modifier
            .gen1MaxWidth()
            .gen1WindowBounds()
            .drawWithContent {
                val outline = steppedCardOutline(size, pixel, WALLET_CORNER_STEPS)
                clipPath(outline) {
                    drawRect(fill)
                    this@drawWithContent.drawContent()
                }
                drawPath(outline, ink, style = Stroke(pixel))
            }
            .padding(gen1Dp(WALLET_CORNER_STEPS + 1))
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A hand of cards, browsed by dragging the front one aside rather than by
 * scrolling past it.
 *
 * One card is shown at a readable size at a time, with a sliver of the next
 * two peeking out behind it to say the hand goes on. Dragging the front card
 * far enough — or letting go with the drag still going — sends it off and
 * puts the next one in its place; short of that it springs back, because a
 * look at the next card is not the same as picking it.
 *
 * The front card is also the cursor's: SWIPE CONTROLS' LEFT and RIGHT walk
 * the same hand, and a tap takes it exactly as a tap on the old list did.
 * [at] is the cursor's own position so the two stay in lock step whichever
 * drove the change.
 */
@Composable
fun Gen1CardWallet(
    count: Int,
    at: Int,
    onMove: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onHold: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    card: @Composable (index: Int, pixelRounded: Boolean, modifier: Modifier) -> Unit,
) {
    if (count <= 0) return
    val current = at.coerceIn(0, count - 1)
    val density = LocalDensity.current
    val tiltRange = with(density) { TILT_SPAN_DP.dp.toPx() }
    val scope = rememberCoroutineScope()
    var dragPx by remember(current) { mutableStateOf(0f) }

    Box(modifier, contentAlignment = Alignment.Center) {
        for (behind in PEEK_CARDS downTo 1) {
            val peek = current + behind
            if (peek >= count) continue
            card(
                peek,
                true,
                Modifier
                    .fillMaxWidth(1f - behind * PEEK_NARROWING)
                    .offset(y = gen1Dp(behind * PEEK_STEP_PIXELS))
                    .alpha(1f - behind * PEEK_FADE),
            )
        }

        card(
            current,
            true,
            Modifier
                .fillMaxWidth()
                .gen1SwipeRegion()
                .gen1HoldRegion()
                .graphicsLayer {
                    translationX = dragPx
                    rotationZ = (dragPx / tiltRange * MAX_TILT_DEGREES).coerceIn(-MAX_TILT_DEGREES, MAX_TILT_DEGREES)
                }
                .pointerInput(current) {
                    detectTapGestures(
                        onTap = { onConfirm(current) },
                        onLongPress = { onHold(current) },
                    )
                }
                .pointerInput(current, count) {
                    detectDragGestures(
                        onDragEnd = {
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val threshold = width * FLING_FRACTION
                            val goNext = dragPx <= -threshold && current + 1 < count
                            val goPrev = dragPx >= threshold && current > 0
                            val target = when {
                                goNext -> -width
                                goPrev -> width
                                else -> 0f
                            }
                            scope.launch {
                                val settle = Animatable(dragPx)
                                settle.animateTo(target, tween(SETTLE_MILLIS)) { dragPx = value }
                                if (goNext) onMove(current + 1) else if (goPrev) onMove(current - 1)
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                val settle = Animatable(dragPx)
                                settle.animateTo(0f, tween(SETTLE_MILLIS)) { dragPx = value }
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        dragPx += amount.x
                    }
                },
        )
    }
}

/** How many cards peek out behind the front one. */
private const val PEEK_CARDS = 2

/** How much narrower each card behind the front is, as a fraction of width. */
private const val PEEK_NARROWING = 0.04f

/** How far down each card behind the front sits, in game pixels. */
private const val PEEK_STEP_PIXELS = 5

/** How much dimmer each card behind the front is. */
private const val PEEK_FADE = 0.22f

/** How far the front card has to travel before it counts as thrown, not looked at. */
private const val FLING_FRACTION = 0.35f

/** How far a full-width drag tilts the card, in degrees. */
private const val MAX_TILT_DEGREES = 10f

/** The drag distance, in dp, that reaches the full tilt. */
private const val TILT_SPAN_DP = 260

/** How long the spring-back or throw-off settle takes. */
private const val SETTLE_MILLIS = 150
