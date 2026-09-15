package com.logie.gen1storage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stats
import com.logie.gen1storage.sprites.Gen2Sprites
import com.logie.gen1storage.sprites.SpriteSet
import com.logie.gen1storage.sprites.SpriteStore

/**
 * The species id to load this Pokémon's sprite under.
 *
 * Every UNOWN is the same species, but not the same drawing: which of its
 * twenty-six letters a cartridge shows comes out of the Pokémon's own DVs
 * (see [Gen1Stats.unownLetter]), so a sprite lookup for one has to ask for a
 * letter rather than for "UNOWN" and get whichever one happened to be
 * downloaded. Anything else is shown under its own id, unchanged.
 */
fun Gen1Pokemon.spriteSpeciesId(): String? {
    val id = speciesId ?: return null
    return if (id.equals("UNOWN", ignoreCase = true)) {
        Gen2Sprites.unownFormId(Gen1Stats.unownLetter(dvs))
    } else id
}

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
    /**
     * Bumped whenever what is on disk changes — a download finishing, a
     * palette change, a set pinned to a species.
     *
     * Part of the keys rather than something callers wrap this in, because
     * forgetting to wrap it is silent: a sprite that answered "nothing here"
     * before the download went on saying it afterwards, and the art looked
     * like it had never arrived.
     */
    revision: Int = 0,
    /**
     * Width and height in Game Boy pixels; the games draw a front sprite at
     * 56. Null leaves the size to the modifier, for a sprite that is meant to
     * fill whatever it is given.
     */
    sizeInPixels: Int? = 56,
    onLongPress: ((String) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    /** Drops the sprite's white field, for a sprite that sits on a window. */
    cutout: Boolean = false,
    /**
     * Whether to draw the shiny colours, where the art has them.
     *
     * Generation II kept a second pair of colours per species and this is the
     * switch between them. Generation I had no such thing and ignores it.
     */
    shiny: Boolean = false,
) {
    var image by remember(speciesId, gameVersionId, revision, cutout, shiny) {
        mutableStateOf<ImageBitmap?>(null)
    }
    // Whether the answer is in yet. Without this the first frame after a
    // change has no image and no reason to think one is coming, so the
    // bracketed mark flashed up every time — on the cartridge screen, once
    // per save, every time the game was switched.
    var settled by remember(speciesId, gameVersionId, revision) { mutableStateOf(false) }

    LaunchedEffect(speciesId, gameVersionId, store, revision, cutout, shiny) {
        settled = false
        // Off the main thread: this decodes a PNG and then walks it twice to
        // point sample and recolour it, and on the main thread that is a
        // dropped frame every time a sprite changes — which, walking a box
        // with the cursor, is every spot.
        image = speciesId?.let { id ->
            withContext(Dispatchers.IO) { store.load(id, gameVersionId, cutout, shiny) }
        }
        settled = true
    }

    val swipes = LocalGen1Swipe.current
    val cursor = LocalGen1Cursor.current
    Box(
        modifier
            .then(if (sizeInPixels == null) Modifier else Modifier.size(gen1Dp(sizeInPixels)))
            .then(
                if (speciesId != null && (onLongPress != null || onTap != null)) {
                    // Claims the hold when it has one of its own, so the back
                    // gesture does not fire alongside the picker.
                    (if (onLongPress != null) Modifier.gen1HoldRegion() else Modifier)
                        .pointerInput(speciesId, onTap, swipes, cursor) {
                        detectTapGestures(
                            onTap = onTap?.let { tap ->
                                { _ ->
                                    // A sprite's tap speaks rather than
                                    // chooses, so it is not a choice the
                                    // cursor can be on. Under swipes a tap is
                                    // still the A button as well: it cries
                                    // and then takes whatever is selected,
                                    // instead of being the one place on the
                                    // screen where a tap does nothing.
                                    tap()
                                    if (swipes) cursor.confirm()
                                }
                            },
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
        } else if (settled) {
            // Only once nothing is genuinely coming. Until then the space is
            // simply empty, which is quieter than a mark that is about to be
            // replaced.
            SpritePlaceholderMark()
        }
    }
}

/**
 * The bracketed mark that stands in until a sprite exists.
 *
 * A "?" where there is genuinely no art for this species, and a "…" while a
 * download is still running — the two look the same on screen and mean
 * opposite things, and on a first run almost every gap is the second one.
 * See [Gen1Loading].
 */
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
        GbText(if (Gen1Loading.fetching) "…" else "?", style = Gen1TextLarge)
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

    // Every choice in the window on one cursor, CANCEL included, so a swipe
    // reaches the way out as readily as the sets. The arrow is where the
    // cursor is; which set is already pinned is said by the tick beside it,
    // because those are two different questions and one arrow cannot answer
    // both.
    val rows = buildList<Pair<String, () -> Unit>> {
        add("MATCH THE GAME" to { onChoose(null) })
        installed.forEach { set -> add(set.label to { onChoose(set) }) }
        add("CANCEL" to onCancel)
    }
    val at = rememberCursorLayer(rows.size) { index -> rows[index].second() }

    // Width only: a fixed height here collapsed the window to a rule, which is
    // all a long press used to put on screen.
    Gen1Frame(Modifier.width(260.dp)) {
        GbText("SPRITE")
        Spacer(Modifier.height(4.dp))
        if (installed.isEmpty()) {
            GbText("NO SPRITES FOR THIS ONE YET.", style = Gen1TextSmall)
            GbText("OPTIONS → DOWNLOADS.", style = Gen1TextSmall)
            Spacer(Modifier.height(4.dp))
        }
        Gen1MenuRow(
            rows[0].first,
            selected = at == 0,
            onSelect = {},
            onConfirm = rows[0].second,
            mark = current == null,
        )
        installed.forEachIndexed { index, set ->
            Gen1MenuRow(
                set.label,
                selected = at == index + 1,
                onSelect = {},
                onConfirm = { onChoose(set) },
                mark = current == set,
            )
        }
        Spacer(Modifier.height(6.dp))
        Gen1Button("CANCEL", onCancel, selected = at == rows.lastIndex)
    }
}

/**
 * The download progress readout: the percentage, then a bar that fills the
 * width inside its own frame. Shared by the sprites and the cries.
 */
@Composable
fun DownloadProgressBar(percent: Int, modifier: Modifier = Modifier) {
    Box(modifier) {
        androidx.compose.foundation.layout.Column {
            GbText("$percent%", style = Gen1TextLarge)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
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
