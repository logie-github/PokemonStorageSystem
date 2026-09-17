package com.logie.gen1storage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos

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
 * The trainer cards as a thing you hold, rather than a list you scroll.
 *
 * Three parts, top to bottom, and the whole gesture runs between them:
 *
 *  - the **terminal** at the top, a machine with a slot in it;
 *  - the **card** in the middle, the one currently out of the wallet;
 *  - the **wallet** at the foot, with the rest of the cards fanned out of it
 *    the way a hand of playing cards is held.
 *
 * A card is taken by dragging it up into the slot — it shrinks into the
 * machine as it goes, and past about halfway the slot lights and letting go
 * puts it in. Dragging sideways riffles through the hand instead, and a tap
 * on any card still fanned out of the wallet brings that one up. Nothing is
 * committed by looking: a card let go short of the slot drops back into the
 * wallet.
 *
 * The fan is read by colour before it is read by anything else, which is how
 * the cards already worked — see [Gen1TrainerCard]. So the tabs carry the
 * game's colours and no writing: the card standing up out of the wallet is
 * the one with its name on it.
 */
@Composable
fun Gen1CardWallet(
    count: Int,
    at: Int,
    onMove: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onHold: (Int) -> Unit = {},
    /** The colours each card is drawn in, for its tab in the fan. */
    tint: (Int) -> GbPalette,
    modifier: Modifier = Modifier,
    card: @Composable (index: Int, pixelRounded: Boolean, modifier: Modifier) -> Unit,
) {
    if (count <= 0) return
    val current = at.coerceIn(0, count - 1)
    val scope = rememberCoroutineScope()
    // Reset whenever the card in hand changes, so a new one always starts
    // sitting square in the middle however the last one left.
    var dragX by remember(current) { mutableStateOf(0f) }
    var dragY by remember(current) { mutableStateOf(0f) }
    // How tall the card is, which is also how far it has to travel to be
    // swallowed by the slot. Measured rather than guessed: the card sizes
    // itself to its own contents.
    var span by remember { mutableStateOf(1f) }

    val lift = (-dragY / span).coerceIn(0f, 1f)
    val armed = lift >= UPLOAD_AT

    // A split screen has about half a phone's height for all three of these
    // and the shelf above them. The card is what there is to look at, so the
    // machine and the wallet give up their room rather than squeezing it.
    val short = isShort()

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Drawn over the card rather than under it, so a card on its way up
        // disappears into the machine instead of sliding across the front of
        // it. Layout order is unchanged; only what covers what.
        Terminal(armed = armed, short = short, modifier = Modifier.zIndex(1f))

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { span = it.height.toFloat().coerceAtLeast(1f) },
            contentAlignment = Alignment.Center,
        ) {
            card(
                current,
                true,
                Modifier
                    .fillMaxWidth()
                    .gen1SwipeRegion()
                    .gen1HoldRegion()
                    .graphicsLayer {
                        translationX = dragX
                        translationY = dragY
                        // Going into the machine, not just going up: it
                        // shrinks towards the slot as it approaches it.
                        val shrink = 1f - lift * UPLOAD_SHRINK
                        scaleX = shrink
                        scaleY = shrink
                        rotationZ = (dragX / span * TILT_PER_SPAN)
                            .coerceIn(-MAX_TILT_DEGREES, MAX_TILT_DEGREES)
                    }
                    // Tap, hold and drag are read in one place rather than by
                    // two detectors racing over the same finger. A card is the
                    // rare thing that is both pressed and dragged, and picking
                    // one out of a wallet starts with a finger resting on it —
                    // which a separate long-press detector called a hold and
                    // used to answer by opening the card about to be dragged.
                    .pointerInput(current, count) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var fromX = 0f
                            var fromY = 0f
                            val opening = withTimeoutOrNull(HOLD_MILLIS) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: return@withTimeoutOrNull CardGesture.GONE
                                    if (!change.pressed) return@withTimeoutOrNull CardGesture.TAPPED
                                    val moved = change.position - down.position
                                    if (abs(moved.x) > slop || abs(moved.y) > slop) {
                                        fromX = moved.x
                                        fromY = moved.y
                                        change.consume()
                                        return@withTimeoutOrNull CardGesture.DRAGGED
                                    }
                                }
                                @Suppress("UNREACHABLE_CODE")
                                CardGesture.GONE
                            }
                            when (opening) {
                                CardGesture.TAPPED -> return@awaitEachGesture onConfirm(current)
                                CardGesture.GONE -> return@awaitEachGesture
                                // Still down and still where it started once
                                // the timer is up: held rather than thrown.
                                null -> return@awaitEachGesture onHold(current)
                                CardGesture.DRAGGED -> Unit
                            }

                            dragX = fromX
                            dragY = fromY
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val step = change.positionChange()
                                dragX += step.x
                                dragY += step.y
                                change.consume()
                            }

                            val height = size.height.toFloat().coerceAtLeast(1f)
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val intoSlot = -dragY >= height * UPLOAD_AT
                            val goNext = !intoSlot && dragX <= -width * FLING_FRACTION &&
                                current + 1 < count
                            val goPrev = !intoSlot && dragX >= width * FLING_FRACTION &&
                                current > 0
                            scope.launch {
                                if (intoSlot) {
                                    // The rest of the way into the machine,
                                    // and only then is the card taken.
                                    val fly = Animatable(dragY)
                                    fly.animateTo(
                                        -height,
                                        tween(UPLOAD_MILLIS, easing = LinearEasing),
                                    ) { dragY = value }
                                    onConfirm(current)
                                    return@launch
                                }
                                val restX = Animatable(dragX)
                                val restY = Animatable(dragY)
                                val target = when {
                                    goNext -> -width
                                    goPrev -> width
                                    else -> 0f
                                }
                                launch { restY.animateTo(0f, tween(SETTLE_MILLIS)) { dragY = value } }
                                restX.animateTo(target, tween(SETTLE_MILLIS)) { dragX = value }
                                if (goNext) onMove(current + 1) else if (goPrev) onMove(current - 1)
                            }
                        }
                    },
            )
        }

        Wallet(count = count, current = current, tint = tint, short = short, onPick = onMove)
    }
}

/**
 * The machine the card goes into.
 *
 * A lip with a slot cut across it, which is the whole of what a card needs to
 * be pushed into something. It lights up once the card is far enough in that
 * letting go would post it, so the moment the gesture becomes a decision is
 * visible before it is taken rather than after.
 */
@Composable
private fun Terminal(armed: Boolean, short: Boolean, modifier: Modifier = Modifier) {
    val pixel = gen1PixelPx().toFloat()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth(TERMINAL_WIDTH)
                .height(gen1Dp(if (short) TERMINAL_PIXELS_SHORT else TERMINAL_PIXELS))
                .drawBehind {
                    val outline = steppedCardOutline(size, pixel, 2)
                    drawPath(outline, if (armed) Gen1Palette.Muted else Gen1Palette.Panel)
                    drawPath(outline, Gen1Palette.Ink, style = Stroke(pixel))
                    // The slot: a mouth across the middle, wide enough to read
                    // as something a card goes into rather than a rule.
                    val inset = pixel * 4
                    val mouth = pixel * 3
                    drawRect(
                        Gen1Palette.Ink,
                        Offset(inset, (size.height - mouth) / 2f),
                        Size(size.width - inset * 2, mouth),
                    )
                },
        )
        // The line under it is the first thing to go where there is no room:
        // the slot lighting up says the same thing without it.
        if (!short) {
            GbText(
                if (armed) "LET GO TO INSERT" else "DRAG A CARD UP HERE",
                style = Gen1TextSmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * The wallet itself, with everything still in it fanned out of the top.
 *
 * The tabs are rotated about a point well below the wallet, which is what
 * makes a row of them splay like a hand held in one hand rather than lean
 * like a row of books. The one currently out stands clear of the others, and
 * the wallet's own front is drawn last so the cards come out of it rather
 * than sit on it.
 */
@Composable
private fun Wallet(
    count: Int,
    current: Int,
    tint: (Int) -> GbPalette,
    short: Boolean,
    onPick: (Int) -> Unit,
) {
    val pixel = gen1PixelPx().toFloat()
    val spread = (FAN_DEGREES_EACH * (count - 1)).coerceAtMost(FAN_DEGREES_MOST)
    val tabHeight = if (short) TAB_HEIGHT_PIXELS_SHORT else TAB_HEIGHT_PIXELS
    // Turning about a point below the wallet swings the outer cards of the
    // fan downward as well as outward — the far edge of a hand sits lower
    // than the middle of it. Left alone they hang out of the bottom of the
    // wallet, so the whole fan is lifted by as much as the outermost card
    // drops. R(1 - cos t), with the pivot's distance for R.
    val sag = FAN_PIVOT * tabHeight *
        (1f - cos(Math.toRadians((spread / 2f).toDouble())).toFloat())
    Box(
        Modifier
            .fillMaxWidth()
            .height(gen1Dp(if (short) WALLET_PIXELS_SHORT else WALLET_PIXELS))
            // Nothing of the fan outside the wallet: a rotated card is not
            // clipped by its own layer, and the shelf above is not somewhere
            // for a corner of one to appear.
            .clipToBounds(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The one in hand last, so it is drawn over the rest of the fan.
        (0 until count).sortedBy { it == current }.forEach { index ->
            val angle = if (count <= 1) 0f else {
                -spread / 2f + spread * index / (count - 1).toFloat()
            }
            val colours = tint(index)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        // A pivot far below the tab turns a rotation into a
                        // place on an arc, so no card has to be positioned.
                        transformOrigin = TransformOrigin(0.5f, FAN_PIVOT)
                        rotationZ = angle
                        translationY = -sag * pixel -
                            if (index == current) pixel * TAB_RAISE_PIXELS else 0f
                    }
                    .size(gen1Dp(TAB_WIDTH_PIXELS), gen1Dp(tabHeight))
                    // Deliberately not gen1Clickable, which hands a tap to the
                    // cursor's confirm: pointing at a card in the fan is not
                    // taking it. This only moves the cursor, which is what
                    // every other list's onSelect does with a finger.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(index) }
                    .drawBehind {
                        val outline = steppedCardOutline(size, pixel, 2)
                        drawPath(outline, colours.lightest)
                        // A band of the game's own darkest along the top, so a
                        // tab reads as the head of a card rather than a chip.
                        clipPath(outline) {
                            drawRect(colours.dark, Offset.Zero, Size(size.width, pixel * 3))
                        }
                        drawPath(
                            outline,
                            if (index == current) Gen1Palette.Ink else colours.darkest,
                            style = Stroke(pixel),
                        )
                    },
            )
        }

        // The wallet's front, over the foot of the fan.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(WALLET_WIDTH)
                .height(gen1Dp(if (short) WALLET_FRONT_PIXELS_SHORT else WALLET_FRONT_PIXELS))
                .drawBehind { drawWalletFront(pixel) },
        )
    }
}

/** The billfold: a stepped panel with a seam across it, and nothing else. */
private fun DrawScope.drawWalletFront(pixel: Float) {
    val outline = steppedCardOutline(size, pixel, 3)
    drawPath(outline, Gen1Palette.Shadow)
    drawPath(outline, Gen1Palette.Ink, style = Stroke(pixel))
    // The fold, a little above the middle, which is what says billfold.
    drawRect(
        Gen1Palette.Ink,
        Offset(pixel * 2, size.height * 0.42f),
        Size(size.width - pixel * 4, pixel),
    )
}

/** What a finger on the card in hand turned out to be doing. */
private enum class CardGesture { TAPPED, DRAGGED, GONE }

/** How long a finger has to rest before it is holding rather than dragging. */
private const val HOLD_MILLIS = 500L

/** How much of the card's own height counts as far enough into the slot. */
private const val UPLOAD_AT = 0.45f

/** How far the card shrinks on its way into the machine. */
private const val UPLOAD_SHRINK = 0.35f

/** How long the last of the trip into the slot takes. */
private const val UPLOAD_MILLIS = 160

/** How wide the machine is across the screen. */
private const val TERMINAL_WIDTH = 0.72f

/** The machine's lip, in game pixels, and what it shrinks to on a short screen. */
private const val TERMINAL_PIXELS = 14
private const val TERMINAL_PIXELS_SHORT = 10

/** How tall the wallet and its fan stand together, in game pixels. */
private const val WALLET_PIXELS = 46
private const val WALLET_PIXELS_SHORT = 32

/** The billfold's own front, in game pixels. */
private const val WALLET_FRONT_PIXELS = 18
private const val WALLET_FRONT_PIXELS_SHORT = 13

/** How wide the billfold is across the screen. */
private const val WALLET_WIDTH = 0.62f

/** One card's tab in the fan, in game pixels. */
private const val TAB_WIDTH_PIXELS = 22
private const val TAB_HEIGHT_PIXELS = 30
private const val TAB_HEIGHT_PIXELS_SHORT = 22

/** How far the card in hand stands out of the fan, in game pixels. */
private const val TAB_RAISE_PIXELS = 5

/** How far apart two cards in the fan sit, and how far the whole fan opens. */
private const val FAN_DEGREES_EACH = 9f
private const val FAN_DEGREES_MOST = 54f

/** How far below a tab the fan turns about, in tab heights. */
private const val FAN_PIVOT = 3.2f

/** How far the card has to travel sideways to be riffled past. */
private const val FLING_FRACTION = 0.35f

/** How far a card tilts as it is dragged across its own width. */
private const val MAX_TILT_DEGREES = 10f
private const val TILT_PER_SPAN = 14f

/** How long the spring-back or riffle settle takes. */
private const val SETTLE_MILLIS = 150
