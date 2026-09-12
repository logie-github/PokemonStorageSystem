package com.logie.gen1storage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import com.logie.gen1storage.sound.LocalGen1Audio
import com.logie.gen1storage.sound.SoundEffect
import com.logie.gen1storage.sprites.SpriteStore
import com.logie.gen1storage.sprites.TrainerStore
import kotlinx.coroutines.delay

/**
 * The cartridge's trade, as far as this app can honestly draw it.
 *
 * `engine/movie/trade.asm` runs a sequence of small steps, and these are the
 * ones that carry the moment: the Pokémon is shown and goes into its ball, the
 * open end of the link cable is drawn and sounds SFX_HEAL_HP, the ball travels
 * along the cable ticking SFX_TINK once per step, and the Pokémon that comes
 * back out the far end is shown and cries.
 *
 * The Game Boy and the cable are pokered's own art, cut from the same pinned
 * commit as everything else and assembled by the same tilemap the cartridge
 * uses — `gfx/trade/link_cable.tilemap` is a two-tile plug and then one
 * two-tile segment repeated, which is why the cable here is drawn the same way
 * rather than stretched.
 *
 * What is deliberately not copied: the two Game Boys sliding on and off screen,
 * the screen scroll between the halves, and the text boxes naming two trainers.
 * There is one player here and one machine, so a second trainer's farewell
 * would be a line of fiction.
 */
@Composable
fun Gen1TradeScene(
    scene: TradeScene,
    sprites: SpriteStore,
    trainers: TrainerStore,
    revision: Int,
) {
    val audio = LocalGen1Audio.current
    // Which beat is on screen. One counter rather than several animations:
    // the cartridge's own sequence is a list of steps taken in order, and this
    // is that list.
    var beat by remember(scene) { mutableIntStateOf(BEAT_SHOW_OLD) }
    var travelled by remember(scene) { mutableIntStateOf(0) }

    LaunchedEffect(scene) {
        delay(SHOW_MILLIS)
        beat = BEAT_BALL
        delay(BALL_MILLIS)
        beat = BEAT_CABLE
        audio?.play(SoundEffect.TRADE_CABLE)
        delay(CABLE_MILLIS)
        beat = BEAT_TRAVEL
        repeat(TRAVEL_STEPS) {
            // One tick per step, which is what the cartridge does: SFX_TINK
            // is played inside the loop that moves the ball four pixels.
            audio?.play(SoundEffect.TRADE_BALL)
            delay(TRAVEL_STEP_MILLIS)
            travelled = it + 1
        }
        beat = BEAT_ARRIVE
        delay(ARRIVE_MILLIS)
        beat = BEAT_SHOW_NEW
        audio?.cry(scene.toDexNumber)
    }

    Box(
        Modifier.fillMaxSize().gen1Ground().padding(gen1Dp(4)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gen1Dp(4)),
        ) {
            Gen1Frame(Modifier.wrapContentWidth()) {
                GbText(
                    when (beat) {
                        BEAT_SHOW_OLD, BEAT_BALL -> "${scene.name} IS TRADED."
                        BEAT_SHOW_NEW -> "${scene.name} EVOLVED!"
                        else -> "TRADING..."
                    },
                    maxLines = 1,
                )
            }

            Box(
                Modifier.size(gen1Dp(STAGE_WIDTH), gen1Dp(STAGE_HEIGHT)),
                contentAlignment = Alignment.Center,
            ) {
                when (beat) {
                    BEAT_SHOW_OLD -> Gen1Sprite(
                        speciesId = scene.fromSpeciesId,
                        gameVersionId = scene.gameVersionId,
                        store = sprites,
                        revision = revision,
                        sizeInPixels = SPRITE_PIXELS,
                    )

                    BEAT_SHOW_NEW -> Gen1Sprite(
                        speciesId = scene.toSpeciesId,
                        gameVersionId = scene.gameVersionId,
                        store = sprites,
                        revision = revision,
                        sizeInPixels = SPRITE_PIXELS,
                    )

                    else -> Cable(
                        trainers = trainers,
                        revision = revision,
                        // Nothing on the cable until it has been drawn.
                        ball = when (beat) {
                            BEAT_BALL -> 0
                            BEAT_CABLE -> 0
                            BEAT_TRAVEL -> travelled
                            else -> TRAVEL_STEPS
                        },
                        moving = beat == BEAT_TRAVEL,
                    )
                }
            }
        }
    }
}

/**
 * Two Game Boys with the cable between them, and the ball somewhere along it.
 *
 * The cable is the tilemap's own arrangement: the plug's two columns and then
 * one segment repeated to reach the far side.
 */
@Composable
private fun Cable(trainers: TrainerStore, revision: Int, ball: Int, moving: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GameBoy(trainers, revision)
        Box(contentAlignment = Alignment.CenterStart) {
            Row {
                repeat(CABLE_SEGMENTS) {
                    Column {
                        CableTile(trainers, revision, TILE_CABLE_TOP)
                        CableTile(trainers, revision, TILE_CABLE_BOTTOM)
                    }
                }
            }
            if (ball > 0) {
                val step = gen1Dp(TrainerStore.TILE)
                Box(
                    Modifier.offset {
                        IntOffset(
                            ((ball - 1).coerceAtMost(CABLE_SEGMENTS - 1) * step.toPx()).toInt(),
                            0,
                        )
                    }
                ) {
                    // The cartridge alternates the ball's tile every step,
                    // which is the bulge the cable makes as it passes.
                    TradeBall(trainers, revision, bulging = moving && ball % 2 == 1)
                }
            }
        }
        GameBoy(trainers, revision)
    }
}

@Composable
private fun GameBoy(trainers: TrainerStore, revision: Int) {
    val image = remember(revision) { trainers.art(TrainerStore.TRADE_GAME_BOY) }
    Box(Modifier.size(gen1Dp(GAME_BOY_WIDTH), gen1Dp(GAME_BOY_HEIGHT))) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
    }
}

@Composable
private fun CableTile(trainers: TrainerStore, revision: Int, index: Int) {
    val image = remember(revision, index) { trainers.tile(TrainerStore.TRADE_CABLE, index) }
    Box(Modifier.size(gen1Dp(TrainerStore.TILE))) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/**
 * The ball, as the cartridge assembles it: one tile drawn four ways.
 *
 * `Trade_BallInsideLinkCableOAMBlock` is the same tile id four times over with
 * the X and Y flip bits set differently, so the whole ball is one eighth of a
 * tile's worth of art mirrored into a circle.
 */
@Composable
private fun TradeBall(trainers: TrainerStore, revision: Int, bulging: Boolean) {
    val index = if (bulging) TILE_BALL_BULGE else TILE_BALL
    val image = remember(revision, index) { trainers.tile(TrainerStore.TRADE_BALL, index) }
    if (image == null) {
        Spacer(Modifier.size(gen1Dp(TrainerStore.TILE * 2)))
        return
    }
    Column {
        Row {
            Quadrant(image, flipX = false, flipY = false)
            Quadrant(image, flipX = true, flipY = false)
        }
        Row {
            Quadrant(image, flipX = false, flipY = true)
            Quadrant(image, flipX = true, flipY = true)
        }
    }
}

@Composable
private fun Quadrant(
    image: androidx.compose.ui.graphics.ImageBitmap,
    flipX: Boolean,
    flipY: Boolean,
) {
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier
            .size(gen1Dp(TrainerStore.TILE))
            .graphicsLayer(
                scaleX = if (flipX) -1f else 1f,
                scaleY = if (flipY) -1f else 1f,
            ),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

// The tilemap's own indices into gfx/trade/link_cable.png, which is three
// tiles across: the repeated segment is its first two.
private const val TILE_CABLE_TOP = 0
private const val TILE_CABLE_BOTTOM = 1

// gfx/trade/cable_ball.png is two tiles across; the ball is the lower pair.
private const val TILE_BALL = 2
private const val TILE_BALL_BULGE = 3

private const val BEAT_SHOW_OLD = 0
private const val BEAT_BALL = 1
private const val BEAT_CABLE = 2
private const val BEAT_TRAVEL = 3
private const val BEAT_ARRIVE = 4
private const val BEAT_SHOW_NEW = 5

private const val SPRITE_PIXELS = 56
private const val GAME_BOY_WIDTH = 48
private const val GAME_BOY_HEIGHT = 64
private const val CABLE_SEGMENTS = 9
private const val STAGE_WIDTH = GAME_BOY_WIDTH * 2 + CABLE_SEGMENTS * TrainerStore.TILE
private const val STAGE_HEIGHT = GAME_BOY_HEIGHT

private const val SHOW_MILLIS = 1_200L
private const val BALL_MILLIS = 500L
private const val CABLE_MILLIS = 500L
private const val TRAVEL_STEPS = 9
private const val TRAVEL_STEP_MILLIS = 160L
private const val ARRIVE_MILLIS = 500L

/** What the whole thing takes, for whatever has to wait it out. */
const val TRADE_SCENE_MILLIS =
    SHOW_MILLIS + BALL_MILLIS + CABLE_MILLIS +
        TRAVEL_STEPS * TRAVEL_STEP_MILLIS + ARRIVE_MILLIS + 1_400L
