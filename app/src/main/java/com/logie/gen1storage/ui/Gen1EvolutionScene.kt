package com.logie.gen1storage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.logie.gen1storage.sound.LocalGen1Audio
import com.logie.gen1storage.sound.SoundEffect
import com.logie.gen1storage.sprites.SpriteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The cartridge's evolution, as far as this app can honestly draw it.
 *
 * `engine/movie/evolution.asm` is three moves and no more: the Pokémon shrinks
 * in place, the two sprites swap back and forth faster and faster while it is
 * small, and what is left grows back at full size and cries. There is no
 * background, no particles and no colour — the screen holds one sprite the
 * whole way through, and the animation is entirely in its size and which of
 * the two is being drawn.
 *
 * The swap is the part worth getting right. The cartridge alternates between
 * the two sprites on a delay that shortens each pass, so what starts as a
 * readable back-and-forth ends as a flicker and the new shape simply wins.
 * [FLASHES] and [FLASH_FIRST_MILLIS] are that ramp.
 *
 * The size steps rather than slides — five fixed sizes down and the same five
 * back up — because the cartridge had a handful of drawn frames and not a
 * smooth scale. Each step is seven Game Boy pixels, an eighth of the sprite,
 * and it is drawn nearest-neighbour so what shrinks stays hard-edged.
 *
 * What is deliberately not copied: the "CONGRATULATIONS!" box, which the
 * window after this one says in the machine's own words, and the cancel that
 * a cartridge allows with B — an evolution here is a trade that has already
 * happened, and there is nothing left to call off.
 */
@Composable
fun Gen1EvolutionScene(
    scene: EvolutionScene,
    sprites: SpriteStore,
    /** Bumped when what is on disk changes, so a swapped set is re-read. */
    revision: Int,
) {
    val audio = LocalGen1Audio.current
    // What is on screen: how big, and which of the two. One pair of numbers
    // rather than several animations, because the cartridge's own sequence is
    // a list of frames taken in order and this is that list.
    var pixels by remember(scene) { mutableIntStateOf(SPRITE_PIXELS) }
    var showingNew by remember(scene) { mutableStateOf(false) }
    var finished by remember(scene) { mutableStateOf(false) }
    // Both sprites are held here rather than drawn by [Gen1Sprite], which
    // loads what it is given and would be asked for a different species
    // twelve times in a second and a half. Loaded once, before anything
    // moves, so the swap is a swap and not a file read.
    var before by remember(scene) { mutableStateOf<ImageBitmap?>(null) }
    var after by remember(scene) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(scene, revision) {
        withContext(Dispatchers.IO) {
            before = scene.fromSpeciesId?.let { sprites.load(it, scene.gameVersionId) }
            after = scene.toSpeciesId?.let { sprites.load(it, scene.gameVersionId) }
        }
        delay(HOLD_MILLIS)
        // Down, one whole step at a time, ticking as it goes.
        for (size in SHRINK_SIZES) {
            audio?.play(SoundEffect.TRADE_BALL)
            pixels = size
            delay(STEP_MILLIS)
        }
        // The swap, faster each pass.
        var wait = FLASH_FIRST_MILLIS
        repeat(FLASHES) {
            showingNew = !showingNew
            delay(wait)
            wait = (wait * FLASH_RAMP).toLong().coerceAtLeast(FLASH_LAST_MILLIS)
        }
        showingNew = true
        // And back up as what it has become.
        for (size in SHRINK_SIZES.reversed()) {
            audio?.play(SoundEffect.TRADE_BALL)
            pixels = size
            delay(STEP_MILLIS)
        }
        pixels = SPRITE_PIXELS
        finished = true
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
                    if (finished) "${scene.name} EVOLVED!" else "EVOLVING ${scene.name}",
                    maxLines = 1,
                )
            }

            // A fixed stage, so the window above it does not move while the
            // sprite inside changes size.
            Box(
                Modifier.size(gen1Dp(SPRITE_PIXELS)),
                contentAlignment = Alignment.Center,
            ) {
                val shown = if (showingNew) after else before
                shown?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.size(gen1Dp(pixels)),
                        contentScale = ContentScale.Fit,
                        // Nearest neighbour: a Game Boy sprite at half size is
                        // a smaller sprite, not a softer one.
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }
    }
}

/** A front sprite, which the games draw at 56 Game Boy pixels square. */
private const val SPRITE_PIXELS = 56

/**
 * The way down, in Game Boy pixels — and, reversed, the way back up. An
 * eighth of [SPRITE_PIXELS] smaller each time, stopping well short of nothing
 * so there is always a shape on screen to swap.
 */
private val SHRINK_SIZES = listOf(49, 42, 35, 28, 21)

private const val HOLD_MILLIS = 500L
private const val STEP_MILLIS = 70L

/** How many times the two swap, and how the wait between them shortens. */
private const val FLASHES = 12
private const val FLASH_FIRST_MILLIS = 150L
private const val FLASH_LAST_MILLIS = 45L
private const val FLASH_RAMP = 0.84

/** What the whole thing takes, for whatever has to wait it out. */
val EVOLUTION_SCENE_MILLIS: Long = run {
    var total = HOLD_MILLIS + SHRINK_SIZES.size * STEP_MILLIS * 2
    var wait = FLASH_FIRST_MILLIS
    repeat(FLASHES) {
        total += wait
        wait = (wait * FLASH_RAMP).toLong().coerceAtLeast(FLASH_LAST_MILLIS)
    }
    // The beat at the end, where it stands there as what it has become.
    total + 1_200L
}
