package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.gen1recomp.GameVersion

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
    Gen1TrainerCard(
        remote = remote,
        save = save,
        title = "CARD 1",
        sprites = model.sprites,
        trainers = model.trainers,
        trainerSprite = null,
        spriteRevision = spriteRevision,
        modifier = Modifier.fillMaxWidth(),
        inserted = true,
        full = true,
    )
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
    Gen1TransferScene(scene, model.sprites, spriteRevision)
}

/** The box, as [Gen1BoxGrid] draws the real one. */
@Composable
fun TutorialViewBoxesScreen(model: StorageViewModel, spriteRevision: Int) {
    val box = remember { TutorialSamples.box() }
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
                cursorSlot = 1,
                onTap = {},
                onMove = { _, _ -> },
            )
        }
    }
}
