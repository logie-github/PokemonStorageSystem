package com.logie.gen1storage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.sprites.SpriteSet
import com.logie.gen1storage.sprites.SpriteStore

/**
 * A Pokémon's sprite, in the art of the game it came from.
 *
 * Falls back to a bracketed "?" when nothing has been downloaded for that
 * species, so the layout is identical either way. A long press opens the set
 * picker, which is how a player pins a species to a particular game's art.
 */
@Composable
fun Gen1Sprite(
    speciesId: String?,
    gameVersionId: String?,
    store: SpriteStore,
    modifier: Modifier = Modifier,
    /** Width and height in Game Boy pixels; the games draw a front sprite at 56. */
    sizeInPixels: Int = 56,
    onLongPress: ((String) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
) {
    var image by remember(speciesId, gameVersionId) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(speciesId, gameVersionId, store) {
        image = speciesId?.let { store.load(it, gameVersionId) }
    }

    Box(
        modifier
            .size(gen1Dp(sizeInPixels))
            .then(
                if (speciesId != null && (onLongPress != null || onTap != null)) {
                    // Claims the hold when it has one of its own, so the back
                    // gesture does not fire alongside the picker.
                    (if (onLongPress != null) Modifier.gen1HoldRegion() else Modifier)
                        .pointerInput(speciesId, onTap) {
                        detectTapGestures(
                            onTap = onTap?.let { tap -> { _ -> tap() } },
                            onLongPress = onLongPress?.let { press -> { _ -> press(speciesId) } },
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = speciesId,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                // Nearest neighbour: these are pixel sprites, and smoothing
                // them would undo the whole point of the presentation.
                filterQuality = FilterQuality.None,
            )
        } else {
            SpritePlaceholderMark()
        }
    }
}

/** The bracketed "?" that stands in until a sprite exists. */
@Composable
fun SpritePlaceholderMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val t = 3.dp.toPx()
                val corner = size.width * 0.28f
                listOf(
                    Offset(0f, 0f) to Size(corner, t),
                    Offset(0f, 0f) to Size(t, corner),
                    Offset(size.width - corner, 0f) to Size(corner, t),
                    Offset(size.width - t, 0f) to Size(t, corner),
                    Offset(0f, size.height - t) to Size(corner, t),
                    Offset(0f, size.height - corner) to Size(t, corner),
                    Offset(size.width - corner, size.height - t) to Size(corner, t),
                    Offset(size.width - t, size.height - corner) to Size(t, corner),
                ).forEach { (offset, boxSize) -> drawRect(Gen1Palette.Ink, offset, boxSize) }
            },
        contentAlignment = Alignment.Center,
    ) {
        GbText("?", style = Gen1TextLarge)
    }
}

/**
 * The set picker a long press opens: a small Generation I window listing the
 * games whose art is actually on the device, plus the option to go back to
 * whichever game the Pokémon came from.
 */
@Composable
fun SpriteSetPicker(
    speciesId: String,
    store: SpriteStore,
    onChoose: (SpriteSet?) -> Unit,
    onCancel: () -> Unit,
) {
    val installed = store.installedSets().filter { store.has(it, speciesId) }
    val current = store.overrideFor(speciesId)

    // Width only: a fixed height here collapsed the window to a rule, which is
    // all a long press used to put on screen.
    Gen1Frame(Modifier.width(260.dp)) {
        GbText("SPRITE")
        Spacer(Modifier.height(4.dp))
        if (installed.isEmpty()) {
            GbText("NO SPRITES FOR THIS ONE YET.", style = Gen1TextSmall)
            GbText("OPTIONS → DOWNLOAD SPRITES.", style = Gen1TextSmall)
            Spacer(Modifier.height(4.dp))
        }
        Gen1MenuRow(
            "MATCH THE GAME",
            selected = current == null,
            onSelect = { onChoose(null) },
            onConfirm = { onChoose(null) },
        )
        installed.forEach { set ->
            Gen1MenuRow(
                set.label,
                selected = current == set,
                onSelect = { onChoose(set) },
                onConfirm = { onChoose(set) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Gen1Button("CANCEL", onCancel)
    }
}

/**
 * The download progress readout: the percentage, then a bar that fills the
 * width inside its own frame.
 */
@Composable
fun SpriteProgressBar(percent: Int, modifier: Modifier = Modifier) {
    Box(modifier) {
        androidx.compose.foundation.layout.Column {
            GbText("$percent%", style = Gen1TextLarge)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxSize()
                    .height(28.dp)
                    .drawBehind {
                        val border = 3.dp.toPx()
                        val inset = border * 2
                        // Outer rule.
                        drawRect(Gen1Palette.Ink, Offset.Zero, Size(size.width, border))
                        drawRect(Gen1Palette.Ink, Offset(0f, size.height - border), Size(size.width, border))
                        drawRect(Gen1Palette.Ink, Offset.Zero, Size(border, size.height))
                        drawRect(Gen1Palette.Ink, Offset(size.width - border, 0f), Size(border, size.height))
                        // The fill, padded inside the rule.
                        val available = size.width - inset * 2
                        val filled = available * (percent.coerceIn(0, 100) / 100f)
                        if (filled > 0f) {
                            drawRect(
                                Gen1Palette.Ink,
                                Offset(inset, inset),
                                Size(filled, size.height - inset * 2),
                            )
                        }
                    }
            )
        }
    }
}
