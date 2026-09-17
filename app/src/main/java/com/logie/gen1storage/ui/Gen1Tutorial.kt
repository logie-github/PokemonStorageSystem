package com.logie.gen1storage.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

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
 * see [TutorialScreens.kt]. Those screens are behind glass, except the box,
 * which is handed over to be poked while he is stood next to it.
 *
 * It holds for one thing and one thing only: the handful of pictures it is
 * about to put on screen, which fills a bar from nought to a hundred before
 * he says a word. Everything else the app fetches — the other two hundred and
 * fifty followers, the other fifteen hundred sprites — lands behind him and is
 * never reported until the tour is over, here or in the header. See
 * [UiState.openingProgress].
 *
 * One beat asks rather than tells: Android grants a folder to an app when
 * somebody picks it in the system's own chooser, so the permission the app
 * needs to read a player's cartridge dumps is a question Bill asks, not a
 * setting buried three drawers into OPTIONS.
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

    // The line is not up yet.
    //
    // Nothing of the tour happens until the opening fetch has filled: he says
    // nothing, the box stays empty, and a tap means nothing either. The bar is
    // the only thing on screen doing anything, which is the honest version of
    // what is happening — and the moment it lands he starts, over a window
    // that says so. Everything the app fetches after that is fetched behind
    // him, unreported, until the tour is over.
    val connecting = !state.openingProgress.finished

    fun advance() {
        if (beat + 1 >= beats.size) onFinished() else beat++
    }

    // The A button, and nothing else: no rows to run down, so the cursor's
    // only job here is to take the next beat the same way a tap does. A beat
    // that asks something puts its own rows on top of this layer, and those
    // are then what A takes.
    // Not on the beat that hands the box over: with swipe controls on, the
    // grid turns a tap into a confirm, and that confirm is meant for the
    // Pokemon under the finger rather than for the tour.
    // What a tap or an A press means here: finish the line he is typing, then
    // turn the page, and only once he has nothing left to say does it move
    // the tour on. See [Gen1Dialogue].
    val dialogue = rememberGen1Dialogue(beat, connecting)
    fun take() {
        if (connecting) return
        // Turning his page always works. Only the moving-on is withheld: a
        // beat that asks something is answered by its rows, and those are not
        // offered until he has finished asking — so a question longer than the
        // box had a page to turn that nothing on screen could turn, and the
        // tour stopped dead on it.
        //
        // The same blip the cartridge plays on every A press through text,
        // whichever this tap did: finished the line, turned the page, or
        // moved him on to the next beat.
        if (dialogue.next()) {
            audio?.play(SoundEffect.CURSOR)
            return
        }
        if (current.ask != null) return
        audio?.play(SoundEffect.CURSOR)
        advance()
    }
    rememberCursorLayer(1) {
        if (dialogue.more || (current.ask == null && !current.handsOver)) take()
    }

    // Android's own folder chooser, which is where the permission actually
    // comes from: picking a folder in it is what grants this app access to
    // it. A player who changed their mind moves the tour straight on; one who
    // picked a folder waits for the import to actually finish first — the
    // next beat is Bill reading back what turned up, and read a beat early
    // that is always "nothing", because the import has not run yet.
    // Set the moment a folder comes back and never cleared: the beat is gone
    // once the import finishes, so nothing needs to turn this off again. It
    // exists so the two rows below can be pulled the instant that happens —
    // otherwise a slow folder leaves them sitting there answerable a second
    // time, and a second "FIND THE FOLDER" mid-import is not a tap this beat
    // is built to take twice.
    var romsImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pickRomsFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            advance()
        } else {
            romsImporting = true
            scope.launch {
                model.importRomsFolder(uri).join()
                advance()
            }
        }
    }

    // Codes accepted is an answer, so the tour does not also need to be told.
    //
    // Only a device that links *while this beat is up*: one that was already
    // linked when it opened should read what he says about where the codes
    // live rather than have the beat vanish before the sentence lands.
    val linkedWhenAsked = remember(beat) { state.linked }
    LaunchedEffect(state.linked, beat) {
        if (current.asksForCodes && state.linked && !linkedWhenAsked) advance()
    }

    // Whether his line is still arriving. He moves in time with his own
    // speech and holds still once it is down, which is the whole of what
    // separates a portrait from a talking one.
    var speaking by remember(beat, connecting) { mutableStateOf(true) }

    // The picture, coming in. He opens on a dead line — transparent, flat,
    // no contrast to it at all — and it clears the moment the connection
    // actually works, which is what he is describing while it happens: "the
    // connection appears to be a success" said over the picture succeeding.
    // The two halves of that read differently on purpose. Opacity eases up
    // in one smooth pull, a signal fading in; contrast arrives in three
    // steps rather than a slide, because a picture does not gradually gain
    // contrast — it is fuzzy and then, a beat later, it very much is not.
    var billAlpha by remember { mutableFloatStateOf(BILL_STATIC_ALPHA) }
    var billContrast by remember { mutableFloatStateOf(BILL_MIN_CONTRAST) }
    LaunchedEffect(beat, connecting) {
        val still = !Gen1Motion.moves(Motion.TEXT)
        when {
            // The dead line covers the wait as well as the beat that follows
            // it: he is flickering away behind the bar for as long as it takes
            // to fill, which is what a connection not yet made looks like.
            connecting || current.billStatic -> {
                billContrast = BILL_MIN_CONTRAST
                if (still) {
                    billAlpha = BILL_STATIC_ALPHA
                    return@LaunchedEffect
                }
                // A signal that has not caught: he surfaces for a frame and
                // is gone again, at no rhythm in particular. It runs for as
                // long as the beat is up, because what he is saying over it
                // is asking whether the thing is even on.
                while (true) {
                    billAlpha = BILL_STATIC_ALPHA * (0.25f + Random.nextFloat())
                    delay(STATIC_FLICKER_MILLIS.random())
                }
            }
            !current.billTunesIn || still -> {
                billAlpha = 1f
                billContrast = 1f
            }
            else -> {
                // Caught in bursts rather than eased up. A picture locking on
                // snaps to something like itself, loses it, snaps back harder
                // — and the last drop is the shortest, so it reads as settling
                // rather than as stopping.
                BILL_TUNE_IN.forEach { frame ->
                    billAlpha = frame.alpha
                    billContrast = frame.contrast
                    delay(frame.holdMillis)
                }
                billAlpha = 1f
                billContrast = 1f
            }
        }
    }

    // Each beat announces itself with the sound the screen it is about makes
    // when it is used for real. The transfer's waits for the Pokemon to
    // actually land, because a confirmation heard before the thing it
    // confirms is worse than none.
    LaunchedEffect(beat, connecting) {
        if (connecting) return@LaunchedEffect
        val effect = current.sound ?: return@LaunchedEffect
        if (current.soundAfterMillis > 0) delay(current.soundAfterMillis)
        audio?.play(effect)
    }

    Box(Modifier.fillMaxSize().gen1Ground().padding(gen1Dp(4))) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val stage = current.stage
                when {
                    // The bar, while there is one to watch, and nothing else
                    // on screen competing with it.
                    connecting -> ConnectingWindow(state.openingProgress.percent)
                    stage != null -> stage(model, state.spriteRevision)
                    // And once it has landed, the same window saying so, for
                    // the two beats he spends talking about the connection.
                    // What the app fetches from here on is the app's own
                    // business and is never put on screen during the tour.
                    current.connected -> ConnectingWindow(null)
                }
                // A pane over the demo that takes every touch, so a tap on it
                // means what a tap anywhere else means. Lifted for the beat
                // that asks to be touched: the box is handed over to the
                // player for as long as Bill is stood next to it, and a
                // Pokemon poked there answers exactly as it would in the real
                // one. Advancing from that beat is the text box and the arrow
                // under it, which is where a Game Boy always put it.
                if (!current.handsOver && (current.ask == null || dialogue.more)) {
                    Box(Modifier.matchParentSize().tapsTo(beat to connecting) { take() })
                }
            }
            Box {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            if (Gen1Layout.windowsOnRight) Arrangement.End else Arrangement.Start,
                    ) {
                        BillPortrait(speaking, alpha = billAlpha, contrast = billContrast)
                    }
                    Spacer(Modifier.height(gen1Dp(2)))
                    Gen1Frame(Modifier.fillMaxWidth(), opening = true) {
                        // Empty until the line is up. The box is there — he is
                        // there, flickering — but there is nothing coming down
                        // it yet, and a greeting typed over a bar that has not
                        // filled is the app talking to itself.
                        val said = remember(beat, connecting) {
                            if (connecting) emptyList()
                            else current.linesFor?.invoke(model) ?: current.lines
                        }
                        Gen1TypedLines(
                            said,
                            dialogue = dialogue,
                            // The box he talks out of is one size from the
                            // first beat to the last: three lines of room and
                            // the arrow's row under them.
                            holdLines = GEN1_DIALOGUE_LINES + 1,
                            onFinished = { speaking = false },
                        )
                        // Only once he has finished saying it, the way the
                        // cartridge only offers a choice when the box has
                        // stopped printing.
                        if (!connecting && !speaking && !dialogue.more) when (current.ask) {
                            null -> Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) { Gen1BlinkingArrow() }

                            // Pulled the moment a folder comes back, rather
                            // than left up for however long that folder takes
                            // to read — see romsImporting above.
                            TutorialAsk.ROMS -> if (!romsImporting) Gen1ChoiceRows(
                                listOf(
                                    "FIND THE FOLDER" to { pickRomsFolder.launch(null) },
                                    "NOT NOW" to { advance() },
                                )
                            )
                        }
                    }
                }
                // Bill and his text box are the way on, and on the beat that
                // hands the box over they are the only way on. Lifted for a
                // beat that asks something, where the rows underneath are
                // what a tap is for.
                // Still there while he is part-way through asking: the rows
                // only arrive once the question is fully out, and until then
                // the tap is what gets the rest of it out.
                if (current.ask == null || dialogue.more) {
                    Box(Modifier.matchParentSize().tapsTo(beat to connecting) { take() })
                }
            }
        }
    }
}

/**
 * A pane that swallows taps and does one thing with them.
 *
 * Swallows rather than passes on, and that is the point of it here. With
 * SWIPE CONTROLS on, which is how the app comes, a tap on open ground is
 * already the A button, so a tour that also watched for taps of its own
 * would take two beats at once. Consuming the gesture leaves exactly one
 * thing listening to it.
 */
private fun Modifier.tapsTo(key: Any?, action: () -> Unit): Modifier =
    pointerInput(key) { detectTapGestures { action() } }

/**
 * The line coming up, and then the line being up.
 *
 * Given a percentage it is the machine dialling: the one bar the introduction
 * ever shows, and the one thing it waits for. Given null it is the answer —
 * a window saying the connection is made, which is what he then spends two
 * beats being pleased about. Nothing after that is ever reported here.
 */
@Composable
private fun ConnectingWindow(percent: Int?) {
    Gen1Frame(
        Modifier.wrapContentWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (percent == null) {
            GbText("CONNECTION STARTED.")
            return@Gen1Frame
        }
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
fun BillPortrait(
    speaking: Boolean,
    modifier: Modifier = Modifier,
    /** How much of him is showing, nought to one. See [Gen1Tutorial]'s opening beat. */
    alpha: Float = 1f,
    /**
     * How much of the picture is there, nought being a flat mid-grey square
     * and one being the picture as drawn. Applied as a contrast pull toward
     * that grey rather than as a second recolour: doing it as a colour
     * filter over the already-recoloured bitmap means it costs nothing more
     * than the one draw call, at any value, every frame.
     */
    contrast: Float = 1f,
) {
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
                        modifier = Modifier.fillMaxSize().offset(y = lift).alpha(alpha),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        colorFilter = ColorFilter.colorMatrix(contrastMatrix(contrast)),
                    )
                }
            }
        }
    }
}

/**
 * A contrast pull toward flat mid-grey, as a colour matrix.
 *
 * `output = input * contrast + 128 * (1 - contrast)`: at 1 the picture is
 * untouched, and at 0 every channel lands on 128 regardless of what it was,
 * which is a blank grey square rather than black or white — the "no signal"
 * a contrast of zero actually looks like.
 */
private fun contrastMatrix(contrast: Float): ColorMatrix {
    val scale = contrast.coerceIn(0f, 1f)
    val shift = 128f * (1f - scale)
    return ColorMatrix(
        floatArrayOf(
            scale, 0f, 0f, 0f, shift,
            0f, scale, 0f, 0f, shift,
            0f, 0f, scale, 0f, shift,
            0f, 0f, 0f, 1f, 0f,
        )
    )
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

/** The brightest the dead line ever gets him. Faint by design — but it is
 * jittered rather than held, or the window is just a grey square. */
private const val BILL_STATIC_ALPHA = 0.42f

/** How much contrast the picture has while it is dead — none. */
private const val BILL_MIN_CONTRAST = 0f

/** How long each flicker of the dead line is held. Uneven on purpose: a
 * steady blink is a cursor, and a cursor is not what this is. */
private val STATIC_FLICKER_MILLIS = listOf(70L, 110L, 60L, 180L, 90L, 130L)

/** One frame of the picture catching. */
private data class TuneInFrame(val alpha: Float, val contrast: Float, val holdMillis: Long)

/**
 * The signal locking on, frame by frame.
 *
 * Five hundred milliseconds of snapping to the picture and losing it, twice
 * over, each grab stronger and each drop shorter than the last — which is
 * what "the connection appears to be a success" is said over.
 */
private val BILL_TUNE_IN = listOf(
    TuneInFrame(0.85f, 0.55f, 70),
    TuneInFrame(0.20f, 0f, 90),
    TuneInFrame(1f, 0.80f, 60),
    TuneInFrame(0.35f, 0.25f, 70),
    TuneInFrame(1f, 1f, 90),
    TuneInFrame(0.55f, 0.70f, 45),
)

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
    /** A beat that waits on an answer rather than on a tap. */
    val ask: TutorialAsk? = null,
    /**
     * A line worked out when the beat opens rather than written in advance.
     *
     * For the one that reports what the folder held: what he says depends on
     * what turned up, and it is said in a sentence rather than left as a
     * window of lists over the top of him.
     */
    val linesFor: ((StorageViewModel) -> List<String>)? = null,
    /**
     * Whether this is the beat with SAVE SYNC live on it, which moves itself
     * along the moment a pair of codes is accepted.
     *
     * Not a [TutorialAsk]: an ask puts rows in the text box, and rows here
     * would be a cursor layer stacked on top of the screen's own, which would
     * quietly take the A button away from its LINK button.
     */
    val asksForCodes: Boolean = false,
    /**
     * The connection has not caught yet: the portrait is faint and flat, and
     * stays that way for as long as this beat is up. Only the first beat.
     */
    val billStatic: Boolean = false,
    /**
     * The beat the picture arrives on: opacity eases up, contrast snaps in
     * three, over [BILL_TUNE_IN_MILLIS]. Only the beat right after
     * [billStatic]; everywhere after that the picture has simply arrived.
     */
    val billTunesIn: Boolean = false,
    /**
     * Whether the window saying the connection is made stands behind this
     * beat. The two he spends on the connection itself; after that he is
     * talking about screens, and each of those brings its own.
     */
    val connected: Boolean = false,
)

/**
 * Something the introduction needs from the player rather than something it
 * is telling them.
 *
 * A beat carrying one offers rows instead of a blinking arrow, and tapping
 * the screen does not move it on: the only way past is answering.
 */
private enum class TutorialAsk {
    /**
     * Where the player keeps their cartridge dumps.
     *
     * Asked here rather than left to be found in OPTIONS because it is the
     * one thing the app cannot do for itself and the one thing that changes
     * what it can show — sprites read out of the player's own ROMs, and the
     * palettes those ROMs unlock. Android grants the folder to the app when
     * the player picks it in the system's own chooser, which is exactly the
     * permission Bill is asking for.
     */
    ROMS,
}

/**
 * The tour, in order.
 *
 * Opens on a dead line before anything else, so the first thing said is not
 * narration — it is Bill checking the connection is actually there, which it
 * is not yet. Short past that on purpose: nobody meets a storage system
 * wanting a lecture about one, and every screen named here is a screen the
 * player is about to be standing on anyway.
 */
/**
 * What he says once the folder has been looked in.
 *
 * Three answers, because three things can have happened, and none of them is
 * a list of version names in a window over his head. A missing cartridge is
 * named when it is the only one, because then the player can go and get it;
 * past that, naming five is a wall of text and "a few" is the useful fact.
 */
private fun romsFound(model: StorageViewModel): List<String> {
    val missing = model.romsStillMissing()
    return when {
        missing.isEmpty() -> listOf("BILL: Lovely. Your ROM files have all been found. That should give the system everything it needs.")
        missing.size == 1 -> listOf(
            "BILL: Good. It looks like ${missing.single()} is missing, though. Pokémon using this data may not display properly until you add it."
        )
        else -> listOf(
            "BILL: I see a few reference files are missing. Pokémon records referencing that data may not display correctly without them, so be sure to add those later."
        )
    }
}

private fun tutorialBeats(): List<TutorialBeat> = listOf(
    TutorialBeat(
        listOf("BILL: Hello? Can you hear me? Is this thing turned on?"),
        billStatic = true,
        connected = true,
    ),
    TutorialBeat(
        listOf(
            "BILL: Ahh, there we go. The connection to the Pokémon " +
                "Storage System appears to be a success!"
        ),
        billTunesIn = true,
        connected = true,
    ),
    TutorialBeat(
        listOf(
            "BILL: This is the Pokemon Storage System. It works on the same " +
                "network as the PCs inside POKéMON CENTERs all over the Kanto " +
                "region. Normally, your Pokémon are sent back to the lab or " +
                "research facility your Pokedex is registered with. This app " +
                "lets you reach that system and store your pokemon on your " +
                "phone, instead."
        ),
    ),
    TutorialBeat(
        listOf(
            "BILL: This app is an upgrade of the software used by the Pokemon " +
                "Centers, so you will be able to do more useful things."
        ),
    ),
    TutorialBeat(
        listOf(
            "BILL: There are six hundred storage spaces, so your Pokémon should " +
                "have plenty of room to stretch their legs."
        ),
    ),
    TutorialBeat(
        listOf(
            "BILL: First, in order to sync your app with the storage system " +
                "you'll need to register your Trainer Card. Enter the sync code " +
                "found on your PC. Save Sync will connect your record and you'll " +
                "be able to store your pokemon!",
            "BILL: Think of it like registering another destination on the " +
                "Pokémon transfer network. Once that's done, the system will " +
                "know where your Pokémon should be sent."
        ),
        stage = { model, _ -> TutorialSyncScreen(model) },
        handsOver = true,
        asksForCodes = true,
    ),
    TutorialBeat(
        listOf(
            "BILL: There is one more thing. The PC needs the data from your " +
                "reference files so it knows how Pokémon from each region should " +
                "look. Could you show me where you've kept your ROM files?"
        ),
        ask = TutorialAsk.ROMS,
    ),
    TutorialBeat(
        emptyList(),
        linesFor = ::romsFound,
    ),
    TutorialBeat(
        listOf(
            "BILL: This is a Trainer Card. Insert yours into the app and the " +
                "Storage System will connect to your records.",
            "BILL: You'll be able to see the Pokémon from all across your " +
                "journey, any items they brought with them items, and any other " +
                "relevant data."
        ),
        stage = { model, revision -> TutorialTrainerCardScreen(model, revision) },
        sound = SoundEffect.SAVE,
    ),
    TutorialBeat(
        listOf(
            "BILL: To store a Pokémon in the app, deposit a pokemon into your " +
                "PC and save your progress. The next time that Trainer Card " +
                "syncs, the save sync will move the Pokémon out of its current " +
                "box and into the Storage System."
        ),
        stage = { model, revision -> TutorialTransferScreen(model, revision) },
        // No beat-level sound here: the scene itself replays every few
        // seconds for as long as this beat is up, and it plays its own
        // arrival chime on each loop — see [TutorialTransferScreen].
    ),
    TutorialBeat(
        listOf(
            "BILL: Normally, pokemon center boxes are limited to a small number " +
                "of pokemon due to hardware constraints. Pokémon are divided " +
                "between several boxes at their registered storage location. " +
                "Here, all six hundred spaces are kept together.",
            "BILL: I believe the pokemon prefer the extra company."
        ),
        stage = { model, revision -> TutorialViewBoxesScreen(model, revision) },
        sound = SoundEffect.SELECT,
        handsOver = true,
    ),
    TutorialBeat(
        listOf(
            "BILL: From the app, you can send a Pokémon back to a PC at a " +
                "pokemon center.",
            "BILL: You can also check its stats, see which moves it knows, and " +
                "make sure you're sending the right Pokémon before you transfer " +
                "it back out."
        ),
        stage = { model, revision -> TutorialViewBoxesScreen(model, revision) },
        handsOver = true,
    ),
    TutorialBeat(
        listOf("BILL: That's everything! Thank you for testing the mobile Pokemon Storage System."),
        sound = SoundEffect.LOG_OFF,
    ),
)
