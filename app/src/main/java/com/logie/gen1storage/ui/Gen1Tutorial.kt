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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clipToBounds
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
import com.logie.gen1storage.sound.LocalGen1Audio
import com.logie.gen1storage.sound.SoundEffect
import com.logie.gen1storage.sprites.recolourToRamp
import kotlinx.coroutines.delay
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
    val audio = LocalGen1Audio.current

    fun advance() {
        if (beat + 1 >= beats.size) onFinished() else beat++
    }

    // The A button, and nothing else: no rows to run down, so the cursor's
    // only job here is to take the next beat the same way a tap does.
    rememberCursorLayer(1) { advance() }

    // Whether his line is still arriving. He moves in time with his own
    // speech and holds still once it is down, which is the whole of what
    // separates a portrait from a talking one.
    var speaking by remember(beat) { mutableStateOf(true) }

    // Each beat announces itself with the sound the screen it is about makes
    // when it is used for real. The transfer's waits for the Pokemon to
    // actually land, because a confirmation heard before the thing it
    // confirms is worse than none.
    LaunchedEffect(beat) {
        val effect = current.sound ?: return@LaunchedEffect
        if (current.soundAfterMillis > 0) delay(current.soundAfterMillis)
        audio?.play(effect)
    }

    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .pointerInput(beat) { detectTapGestures { advance() } }
            .padding(gen1Dp(4)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val stage = current.stage
                // Whichever set is landing: the first run fetches the box's
                // art and then the sprites, and the bar reports the one that
                // is actually running rather than sitting at nothing until
                // its own stage comes round.
                val progress = state.spriteProgress ?: state.followerProgress
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
                // A pane over the demo that takes every touch, so a tap on it
                // means what a tap anywhere else means. Lifted for the beat
                // that asks to be touched: the box is handed over to the
                // player for as long as Bill is stood next to it, and a
                // Pokemon poked there answers exactly as it would in the real
                // one. Advancing from that beat is the text box and the arrow
                // under it, which is where a Game Boy always put it.
                if (!current.handsOver) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(beat) { detectTapGestures { advance() } }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement =
                    if (Gen1Layout.windowsOnRight) Arrangement.End else Arrangement.Start,
            ) {
                BillPortrait(speaking)
            }
            Spacer(Modifier.height(gen1Dp(2)))
            Gen1Frame(Modifier.fillMaxWidth(), opening = true) {
                Gen1TypedLines(current.lines, onFinished = { speaking = false })
                // The arrow only once he has finished saying it, the way the
                // cartridge only offers one when the box has stopped printing.
                if (!speaking) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Gen1BlinkingArrow()
                    }
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
 * rather than cropped. Scaled by a whole number of source pixels and drawn
 * with no smoothing, the way every other piece of art in this app is: a
 * portrait blurred by bilinear filtering would be the one soft thing on a
 * screen made of hard pixels.
 */
@Composable
fun BillPortrait(speaking: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = Gen1Palette.palette
    val image = remember(palette.id) { loadBill(context.resources, palette) }
    val side = billPortraitSide()

    // A pixel up, a pixel down, for as long as he is talking.
    //
    // Two frames rather than a smooth rise and fall, because that is what the
    // cartridge had and because a portrait easing up and down reads as a
    // breathing animation instead of a mouth moving. It stops dead on the
    // last letter, so the picture says which of the two things is happening
    // without anyone having to watch the text.
    var bobbed by remember { mutableStateOf(false) }
    LaunchedEffect(speaking) {
        if (!speaking || !Gen1Motion.moves(Motion.TEXT)) {
            bobbed = false
            return@LaunchedEffect
        }
        while (true) {
            delay(BOB_MILLIS)
            bobbed = !bobbed
        }
    }
    val lift = if (bobbed) -billPixel(side) else 0.dp

    Box(
        modifier.size(side),
        contentAlignment = Alignment.Center,
    ) {
        Gen1FrameBox(Modifier.fillMaxSize()) {
            if (image != null) {
                // Clipped, so the pixel he rises by is a pixel of him going
                // behind the window's own border rather than over it.
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    Image(
                        bitmap = image,
                        contentDescription = "BILL",
                        modifier = Modifier.fillMaxSize().offset(y = lift),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }
    }
}

/** One of the sprite's own pixels, at whatever whole scale it is drawn at. */
private fun billPixel(side: Dp): Dp = side / BILL_PIXELS

/** How long each of the two bob frames is held. */
private const val BOB_MILLIS = 190L

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
    /** The app's own sound for whatever this beat is about. */
    val sound: SoundEffect? = null,
    /** How long to wait before it, for a beat whose screen takes a moment. */
    val soundAfterMillis: Long = 0L,
    /** Whether the demo on screen is the player's to touch while it is up. */
    val handsOver: Boolean = false,
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
        listOf("BILL: Every game you sync turns up as a card. Load one and you are stood in that save. Neat, isn't it?"),
        stage = { model, revision -> TutorialTrainerCardScreen(model, revision) },
        sound = SoundEffect.SAVE,
    ),
    TutorialBeat(
        listOf("BILL: Watch. It leaves the cartridge as it arrives here - one copy, never two. I was quite firm about that."),
        stage = { model, revision -> TutorialTransferScreen(model, revision) },
        sound = SoundEffect.TRANSFER,
        // Long enough for the ball to open and the Pokemon to finish growing,
        // so the sound lands on the arrival rather than ahead of it.
        soundAfterMillis = 1_400L,
    ),
    TutorialBeat(
        listOf("BILL: Six hundred spaces, all yours. Go on - poke one, they answer."),
        stage = { model, revision -> TutorialViewBoxesScreen(model, revision) },
        sound = SoundEffect.SELECT,
        handsOver = true,
    ),
    TutorialBeat(
        listOf("BILL: Right - I'm late for something. It's your PC now. Do look after them!"),
        sound = SoundEffect.LOG_OFF,
    ),
)
