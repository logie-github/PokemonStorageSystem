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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.logie.gen1storage.sprites.SpriteStore
import kotlinx.coroutines.delay

/**
 * What a transfer looks like while it is happening.
 *
 * The Pokémon shrinks into a ball and is gone in a puff — the capture without
 * the throw or the shake, because nothing is being caught here and a ball
 * rocking three times would be saying something untrue about the outcome.
 *
 * It stays up until the transfer answers. That is deliberate: a save lives on
 * a server, so the wait is real, and a ball sitting there reads as the thing
 * being sent rather than as the app having stopped.
 */
@Composable
fun Gen1TransferScene(scene: TransferScene, store: SpriteStore, revision: Int) {
    // One clock for the whole thing. The sprite shrinks, the puff opens out of
    // where it was, and the ball is what is left.
    val shrink = remember(scene) { Animatable(1f) }
    var puff by remember(scene) { mutableFloatStateOf(0f) }

    LaunchedEffect(scene) {
        shrink.animateTo(0f, tween(SHRINK_MILLIS, easing = LinearEasing))
        puff = 1f
        delay(PUFF_MILLIS.toLong())
        puff = 2f
    }

    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .padding(gen1Dp(4)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(gen1Dp(SPRITE_PIXELS)), contentAlignment = Alignment.Center) {
                if (shrink.value > 0f) {
                    Box(
                        Modifier
                            .scale(shrink.value)
                            .alpha(shrink.value)
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
            Gen1Frame(Modifier.wrapContentWidth()) {
                GbText("Sending ${scene.name} to")
                GbText("${scene.destination}.")
                Spacer(Modifier.height(gen1Dp(2)))
                // One is arriving and the other is leaving, and the same
                // word cannot be right for both.
                GbText(if (scene.arriving) "Hello, ${scene.name}!" else "Goodbye, ${scene.name}!")
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

/** The front sprite's size, and the ball's, in game pixels. */
private const val SPRITE_PIXELS = 56
private const val BALL_PIXELS = 16
private const val SHRINK_MILLIS = 320
private const val PUFF_MILLIS = 180
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
