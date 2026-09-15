package com.logie.gen1storage.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.R
import com.logie.gen1storage.sprites.recolourToRamp
import kotlin.math.floor
import kotlin.math.min

/**
 * The first run, hosted.
 *
 * A machine that opens on an empty box and a menu is a machine nobody has been
 * introduced to, and this app's whole conceit is that it *is* the Storage
 * System — so the person who built that system is the one who shows you round
 * it. Bill talks the way he talks in the anime: pleased with his own work,
 * slightly surprised it worked, and off again before he has finished
 * explaining.
 *
 * Every beat that names a screen shows that screen while he names it, drawn
 * against [TutorialSamples] rather than against the player's own account —
 * see [TutorialScreens.kt]. Nothing on those screens can be touched: the tour
 * is a tour.
 *
 * It plays once. [AppSettings.tutorialSeen] is written the moment the last
 * beat is taken, and REPLAY TUTORIAL in OPTIONS is the only thing that puts
 * it back.
 */
@Composable
fun Gen1Tutorial(
    state: UiState,
    model: StorageViewModel,
    onFinished: () -> Unit,
) {
    var beat by remember { mutableIntStateOf(0) }
    val beats = remember { tutorialBeats() }
    val current = beats[beat.coerceIn(beats.indices)]

    fun advance() {
        if (beat + 1 >= beats.size) onFinished() else beat++
    }

    // The A button, and nothing else: no rows to run down, so the cursor's
    // only job here is to take the next beat the same way a tap does.
    rememberCursorLayer(1) { advance() }

    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .pointerInput(beat) { detectTapGestures { advance() } }
            .padding(gen1Dp(4)),
    ) {
        Column(Modifier.fillMaxSize()) {
            // The screen being talked about, with a pane over it that takes
            // every touch: the demo is there to be looked at, and a tap on it
            // means the same thing as a tap anywhere else.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val stage = current.stage
                val progress = state.spriteProgress
                when {
                    stage != null -> stage(model, state.spriteRevision)
                    // The opening beat has no screen to show and is the one
                    // the art is still landing behind, so the empty stage is
                    // where the download reports itself. Only here: once the
                    // tour moves on, what the app is fetching in the
                    // background is the app's own business.
                    beat == 0 && progress != null && !progress.finished ->
                        ConnectingWindow(progress.percent)
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(beat) { detectTapGestures { advance() } }
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement =
                    if (Gen1Layout.windowsOnRight) Arrangement.End else Arrangement.Start,
            ) {
                BillPortrait()
            }
            Spacer(Modifier.height(gen1Dp(2)))
            Gen1TypedBox(current.lines, Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Gen1BlinkingArrow()
                }
            }
        }
    }
}

/**
 * The sprite sheets landing, while Bill talks over them.
 *
 * This is what the first run used to be *instead* of the app — a window and a
 * bar, with everything else held shut behind it. It is the same window, now
 * sat in the space the introduction is not using, and nothing waits on it.
 */
@Composable
private fun ConnectingWindow(percent: Int) {
    Gen1Frame(
        Modifier.wrapContentWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        GbText("CONNECTING TO THE")
        GbText("POKéMON STORAGE SYSTEM...")
        Spacer(Modifier.height(12.dp))
        DownloadProgressBar(percent, Modifier.width(200.dp))
    }
}

/**
 * Bill, in a square.
 *
 * The art is a 128x128 bust — head, shoulders, lab tie and all — shown whole
 * rather than cropped, drawn by Sylvie for Rangi42/polishedcrystal and used
 * here with their permission (see CREDITS). Scaled by a whole number of
 * source pixels and drawn with no smoothing, the way every other piece of art
 * in this app is: a portrait blurred by bilinear filtering would be the one
 * soft thing on a screen made of hard pixels.
 */
@Composable
fun BillPortrait(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = Gen1Palette.palette
    val image = remember(palette.id) { loadBill(context.resources, palette) }
    val side = billPortraitSide()

    Box(
        modifier.size(side),
        contentAlignment = Alignment.Center,
    ) {
        Gen1FrameBox(Modifier.fillMaxSize()) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "BILL",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            }
        }
    }
}

/**
 * How big that square is: a quarter of the screen, rounded down to a whole
 * number of source pixels so the nearest-neighbour scale never lands on a
 * fraction and shows one row of the sprite twice as thick as its neighbour.
 *
 * Measured off the screen's long side, which is the one a portrait phone has
 * to spare — a quarter of a narrow width would be a thumbnail. In practice
 * that means the art at 1:1 on a phone and doubled on anything unfolded,
 * which is the whole of the choice a whole-number scale leaves.
 */
@Composable
private fun billPortraitSide(): Dp {
    val configuration = LocalConfiguration.current
    val quarter = maxOf(configuration.screenWidthDp, configuration.screenHeightDp) / 4f
    // Never wider than half the screen, for a landscape or unfolded display
    // where the long side is not the constraint.
    val capped = min(quarter, configuration.screenWidthDp * 0.5f)
    val scale = floor(capped / BILL_PIXELS).coerceAtLeast(1f)
    return (scale * BILL_PIXELS).dp
}

/**
 * The sprite, recoloured onto the palette.
 *
 * Recoloured rather than shown as it was drawn, exactly as [Gen1Art] handles
 * the title cards: four flat greys on disk, whichever four the player has
 * chosen on screen. The field around him is left painted rather than cut out,
 * because the square is a window with a border and a window has a background.
 */
private fun loadBill(
    resources: android.content.res.Resources,
    palette: GbPalette,
): ImageBitmap? {
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val source = runCatching {
        BitmapFactory.decodeResource(resources, R.drawable.bill, options)
    }.getOrNull() ?: return null

    val ramp = palette.ramp.map { it.toArgb() }.toIntArray()
    val recoloured = runCatching { recolourToRamp(source, ramp) }.getOrNull() ?: source
    if (recoloured !== source) source.recycle()
    return recoloured.asImageBitmap()
}

/** How many source pixels the art is across, which is also how tall. */
private const val BILL_PIXELS = 128

/** One thing Bill says, and the screen he says it over. */
private data class TutorialBeat(
    val lines: List<String>,
    val stage: (@Composable (StorageViewModel, Int) -> Unit)? = null,
)

/**
 * The tour, in order.
 *
 * Four things and a goodbye. Short on purpose: nobody meets a storage system
 * wanting a lecture about one, and every screen named here is a screen the
 * player is about to be standing on anyway.
 */
private fun tutorialBeats(): List<TutorialBeat> = listOf(
    TutorialBeat(
        listOf(
            "BILL: Ahh, there we go. I was hesitant about putting the " +
                "Pokemon Storage System on a mobile device, but the " +
                "connection appears to be a success."
        ),
    ),
    TutorialBeat(
        listOf("BILL: Every game you sync turns up as a card. Load one and the PC is reading that save."),
        stage = { model, revision -> TutorialTrainerCardScreen(model, revision) },
    ),
    TutorialBeat(
        listOf("BILL: Send one across and it leaves the cartridge for good. One copy, always. I rather insisted on that."),
        stage = { model, revision -> TutorialTransferScreen(model, revision) },
    ),
    TutorialBeat(
        listOf("BILL: And these are your boxes. Six hundred spaces - a little roomier than my first attempt."),
        stage = { model, revision -> TutorialViewBoxesScreen(model, revision) },
    ),
    TutorialBeat(
        listOf("BILL: That's the lot. The system's yours now - do look after them for me!"),
    ),
)
