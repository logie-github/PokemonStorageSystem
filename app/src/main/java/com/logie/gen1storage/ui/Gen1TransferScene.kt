package com.logie.gen1storage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.logie.gen1storage.sprites.SpriteStore
import kotlinx.coroutines.delay

/**
 * What a transfer looks like while it is happening.
 *
 * One going out shrinks into a ball and is gone in a puff — the capture
 * without the throw or the shake, because nothing is being caught here and a
 * ball rocking three times would be saying something untrue about the
 * outcome. One coming in runs the same thing backwards: the ball is what is
 * there first, it opens, and the Pokémon comes out of the puff.
 *
 * It stays up until the transfer answers. That is deliberate: a save lives on
 * a server, so the wait is real, and a ball sitting there reads as the thing
 * being sent rather than as the app having stopped.
 */
@Composable
fun Gen1TransferScene(scene: TransferScene, store: SpriteStore, revision: Int) {
    // One number for the whole thing: how much Pokémon is on screen. At zero
    // it is a ball, at one it is the Pokémon, and which way it travels is the
    // only difference between the two directions.
    val shown = remember(scene) { Animatable(if (scene.arriving) 0f else 1f) }
    var puff by remember(scene) { mutableFloatStateOf(0f) }
    // How far a released Pokémon has drifted off the top, nought to one.
    val leaving = remember(scene) { Animatable(0f) }

    LaunchedEffect(scene) {
        if (scene.arriving) {
            // The ball is there to be looked at before it opens.
            delay(BALL_HOLD_MILLIS)
            puff = 1f
            delay(PUFF_MILLIS)
            puff = 2f
            shown.animateTo(1f, tween(GROW_MILLIS.toInt(), easing = LinearEasing))
            if (scene.motion == TransferMotion.RELEASE) {
                delay(FREED_HOLD_MILLIS)
                leaving.animateTo(1f, tween(LEAVE_MILLIS, easing = LinearEasing))
            }
        } else {
            shown.animateTo(0f, tween(GROW_MILLIS.toInt(), easing = LinearEasing))
            puff = 1f
            delay(PUFF_MILLIS)
            puff = 2f
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .padding(gen1Dp(4)),
        contentAlignment = Alignment.Center,
    ) {
        val spriteSide = gen1Dp(SPRITE_PIXELS)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(spriteSide), contentAlignment = Alignment.Center) {
                if (shown.value > 0f) {
                    Box(
                        Modifier
                            .offset {
                                // Up and clear of where the sprite was, which
                                // at this size is its own height twice over.
                                IntOffset(0, -(leaving.value * spriteSide.toPx() * 2f).toInt())
                            }
                            .scale(shown.value)
                            .alpha(shown.value * (1f - leaving.value))
                    ) {
                        key(revision) {
                            Gen1Sprite(
                                speciesId = scene.speciesId,
                                gameVersionId = scene.gameVersionId,
                                store = store,
                                sizeInPixels = SPRITE_PIXELS,
                            )
                        }
                    }
                } else {
                    PokeBall(Modifier.size(gen1Dp(BALL_PIXELS)))
                }
                if (puff == 1f) Puff(Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(gen1Dp(6)))
            // A release says its piece afterwards, in the message window every
            // other outcome uses, so there is nothing to put here.
            if (scene.motion != TransferMotion.RELEASE) {
                Gen1Frame(Modifier.wrapContentWidth()) {
                    GbText("Sending ${scene.name} to")
                    GbText("${scene.destination}.")
                    // One is arriving and the other is leaving, and the same
                    // word cannot be right for both.
                    GbText(
                        if (scene.arriving) "Hello, ${scene.name}!"
                        else "Goodbye, ${scene.name}!"
                    )
                }
            }
        }
    }
}

/**
 * The Poké Ball as the games draw it in the battle HUD — the first tile of
 * `gfx/battle/balls.png`, eight pixels square, read off the decomp rather
 * than approximated.
 */
@Composable
private fun PokeBall(modifier: Modifier = Modifier) {
    val outline = Gen1Palette.Ink
    val upper = Gen1Palette.Dark
    val lower = Gen1Palette.Light
    Box(
        modifier.drawBehind {
            val pixel = size.width / BALL_TILE.first().length
            BALL_TILE.forEachIndexed { y, row ->
                row.forEachIndexed { x, shade ->
                    val colour = when (shade) {
                        '#' -> outline
                        'o' -> upper
                        '-' -> lower
                        else -> return@forEachIndexed
                    }
                    drawRect(colour, Offset(x * pixel, y * pixel), Size(pixel, pixel))
                }
            }
        }
    )
}

/** The puff the Pokémon goes out in: square motes, thrown outwards once. */
@Composable
private fun Puff(modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            val pixel = size.width / 16f
            val centre = Offset(size.width / 2, size.height / 2)
            val reach = size.width * 0.42f
            repeat(PUFF_MOTES) { index ->
                val angle = (index.toDouble() / PUFF_MOTES) * 2 * Math.PI
                val at = Offset(
                    centre.x + (Math.cos(angle) * reach).toFloat() - pixel,
                    centre.y + (Math.sin(angle) * reach).toFloat() - pixel,
                )
                drawRect(Gen1Palette.Ink, at, Size(pixel * 2, pixel * 2))
            }
        }
    )
}

/**
 * How long each direction takes to play out.
 *
 * The transfer itself is a round trip to a server and can answer sooner than
 * this, so the result waits for these rather than cutting in. It matters most
 * coming in, where the Pokémon is the last thing to appear: answering early
 * used to clear the scene before it was ever drawn.
 */
object Gen1TransferTiming {
    const val OUT_MILLIS = GROW_MILLIS + PUFF_MILLIS + HOLD_MILLIS
    const val IN_MILLIS = BALL_HOLD_MILLIS + PUFF_MILLIS + GROW_MILLIS + HOLD_MILLIS
    const val RELEASE_MILLIS = IN_MILLIS + FREED_HOLD_MILLIS + LEAVE_MILLIS

    fun forScene(motion: TransferMotion): Long = when (motion) {
        TransferMotion.OUT -> OUT_MILLIS
        TransferMotion.IN -> IN_MILLIS
        TransferMotion.RELEASE -> RELEASE_MILLIS
    }
}

/** The front sprite's size, and the ball's, in game pixels. */
private const val SPRITE_PIXELS = 56
private const val BALL_PIXELS = 16
/** How long the Pokémon takes to go into the ball, or to come out of it. */
private const val GROW_MILLIS = 560L

/** How long a ball is on screen before it opens, on the way in. */
private const val BALL_HOLD_MILLIS = 420L

/**
 * A beat on whatever the scene ends on, so it is seen rather than glimpsed.
 * Short on purpose: the motion itself is what there is to watch, and a long
 * pause after it has stopped reads as the app having hung.
 */
private const val HOLD_MILLIS = 120L

/** A released Pokémon stands there a moment before it goes. */
private const val FREED_HOLD_MILLIS = 420L
private const val LEAVE_MILLIS = 560
private const val PUFF_MILLIS = 260L
private const val PUFF_MOTES = 8

private val BALL_TILE = listOf(
    "........",
    "...###..",
    "..#ooo#.",
    ".#o-ooo#",
    ".#ooooo#",
    ".#-----#",
    "..#---#.",
    "...###..",
)
