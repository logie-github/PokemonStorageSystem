package com.logie.gen1storage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logie.gen1storage.gen1recomp.GameVersion
import kotlinx.coroutines.delay

/**
 * The introduction's own copies of the screens it talks about.
 *
 * Each one is the real screen's widget — the same trainer card, the same
 * transfer scene, the same box grid — drawn against [TutorialSamples] instead
 * of against the player's account. That is deliberate: a tour that showed
 * mock-ups would drift away from the app the first time a screen changed, and
 * a tour that showed the player's own empty PC would have nothing in it on the
 * one run where it matters.
 *
 * Nothing in here can be acted on. Every callback is empty and no cursor layer
 * is claimed, so the only thing a tap does while one is on screen is move the
 * introduction along.
 */

/** The trainer card, as [TrainerCardScreen] draws it. */
@Composable
fun TutorialTrainerCardScreen(model: StorageViewModel, spriteRevision: Int) {
    val remote = remember { TutorialSamples.remote() }
    val save = remember { TutorialSamples.save() }
    // Dealt onto the table rather than simply being there: the card slides up
    // from under the foot of the stage once, which is the difference between
    // a screenshot and something happening.
    val dealt = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (!Gen1Motion.moves(Motion.WINDOWS)) return@LaunchedEffect
        dealt.snapTo(1f)
        dealt.animateTo(0f, tween(DEAL_MILLIS, easing = LinearEasing))
    }
    // The card as the shelf draws it, not the full-size one a long press
    // opens. Full size gives the badges their own line and the lead its own
    // portrait, and in a stage with a screen's worth of height to fill that
    // came out as a card two thirds of the way down the phone with a hole in
    // the middle of it. Wrapped in its own height so nothing stretches it.
    Box(Modifier.fillMaxWidth().wrapContentHeight()) {
        Gen1TrainerCard(
            remote = remote,
            save = save,
            title = "CARD 1",
            sprites = model.sprites,
            trainers = model.trainers,
            trainerSprite = null,
            spriteRevision = spriteRevision,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = dealt.value * size.height },
            inserted = true,
        )
    }
}

/** A Pokémon on its way across, as [Gen1TransferScene] draws one. */
@Composable
fun TutorialTransferScreen(model: StorageViewModel, spriteRevision: Int) {
    val scene = remember {
        val travelling = TutorialSamples.travelling().pokemon
        TransferScene(
            speciesId = travelling.speciesId,
            gameVersionId = GameVersion.RED.id,
            name = travelling.displayName,
            destination = "THE PC",
            // Arriving rather than leaving, so the beat ends on the Pokemon
            // rather than on a closed ball — and with no question attached,
            // so the scene never claims the cursor the introduction is using.
            motion = TransferMotion.IN,
        )
    }
    // Run again every few seconds for as long as the beat is up, because a
    // scene that plays once and then stands still is a scene most people
    // arrive too late to see. Keyed rather than re-remembered: the scene is a
    // data class, so a fresh copy of it is equal to the old one and the
    // animation inside would never notice. The key rebuilds the whole thing.
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (!Gen1Motion.moves(Motion.TRANSFERS)) return@LaunchedEffect
        while (true) {
            delay(REPLAY_MILLIS)
            run++
        }
    }
    key(run) { Gen1TransferScene(scene, model.sprites, spriteRevision) }
}

/** The box, as [Gen1BoxGrid] draws the real one. */
@Composable
fun TutorialViewBoxesScreen(model: StorageViewModel, spriteRevision: Int) {
    val box = remember { TutorialSamples.box() }
    // The bracket walks the box on its own, which sets whichever Pokemon it
    // is on walking on the spot — the grid animates the one under the cursor
    // and holds the rest still. Nobody has to touch anything for the box to
    // be visibly alive, and touching one still speaks: the grid plays its own
    // cry on a tap, exactly as the real box does.
    var at by remember { mutableIntStateOf(0) }
    // Whether the player has taken it over. The bracket walks by itself until
    // somebody touches the box, and then stops where they put it: a cursor
    // that carried on strolling away from the Pokemon just picked would be
    // the app disagreeing with the finger that moved it.
    var taken by remember { mutableStateOf(false) }
    val occupied = box.contents.size
    LaunchedEffect(occupied, taken) {
        if (taken || occupied == 0 || !Gen1Motion.moves(Motion.SCROLL)) return@LaunchedEffect
        while (true) {
            delay(STEP_MILLIS)
            at = (at + 1) % occupied
        }
    }
    Column(Modifier.fillMaxSize()) {
        Gen1Frame(
            Modifier.wrapContentWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        ) {
            GbText("${box.label}  ${box.contents.size}/${box.slots.size}")
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Gen1BoxGrid(
                box = box,
                followers = model.followers,
                revision = spriteRevision,
                cursorSlot = at,
                onTap = { slot ->
                    taken = true
                    if (box.slots.getOrNull(slot) != null) at = slot
                },
                onMove = { _, _ -> },
            )
        }
    }
}

/** How long the trainer card takes to slide into place. */
private const val DEAL_MILLIS = 220

/** How often the transfer runs itself again while that beat is up. */
private const val REPLAY_MILLIS = 4_200L

/** How long the bracket rests on each Pokemon as it walks the box. */
private const val STEP_MILLIS = 620L

/**
 * SAVE SYNC, live, as [LinkScreen] draws it.
 *
 * The one screen in the tour that is not a demonstration. Everything else is
 * sample data behind glass; this is the real thing with a real keyboard,
 * because the codes have to be typed somewhere and the alternative is telling
 * a player to go and find a screen they have just been shown. It keeps its
 * own cursor while it is up, which is why the beat that shows it hands the
 * stage over rather than covering it.
 */
@Composable
fun TutorialSyncScreen(model: StorageViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    // The form rather than the screen: the screen is a list that fills the
    // height it is given, and in a stage that is most of a phone the window
    // ran off the bottom without its lower edge.
    LinkForm(state, model, Modifier.fillMaxWidth())
}
