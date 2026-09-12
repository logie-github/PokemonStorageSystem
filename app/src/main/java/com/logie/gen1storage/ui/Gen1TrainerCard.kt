package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.logie.gen1storage.sprites.TrainerStore
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.sprites.SpriteStore
import com.logie.gen1storage.sync.RemoteSave

/**
 * A playthrough as its trainer card.
 *
 * The cartridge's own TRAINER INFO screen, which is what Generation I has
 * instead of the later games' trainer card: NAME, MONEY, TIME, and the eight
 * badges in gym order. The name it is known by here — a cartridge can be given
 * one — leads, and the game it is from colours the whole card, so a shelf of
 * them is read by colour before it is read by name.
 *
 * This is what a save *is*, from the player's side. The app used to show a save
 * as a row of facts about a file; a trainer card is the same facts as the thing
 * the player actually remembers having.
 */
@Composable
fun Gen1TrainerCard(
    remote: RemoteSave,
    save: Gen1RecompSave?,
    /** What the player has called this one, if they have. */
    title: String,
    sprites: SpriteStore,
    /** The trainer art, for the portrait the player has chosen. */
    trainers: TrainerStore,
    /** Which trainer this card wears, or null while it wears none. */
    trainerSprite: String?,
    spriteRevision: Int,
    modifier: Modifier = Modifier,
    /** Where the cursor is, drawn as the arrow every other row uses. */
    cursor: Boolean = false,
    /** Whether this is the card in the machine. */
    inserted: Boolean = false,
    /** The long form: the badges get their own line and the lead is bigger. */
    full: Boolean = false,
) {
    val palette = paletteFor(remote.version.id)
    val trainer = (save?.trainerName ?: remote.summary.trainerName ?: "?").uppercase()
    val lead = save?.party?.firstOrNull()
    val read = save != null

    Gen1Frame(
        modifier,
        fill = palette.lightest,
        ink = palette.darkest,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GbText(
                        if (cursor) "▶$title" else title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (inserted) GbText("IN USE", style = Gen1TextSmall, maxLines = 1)
                }
                Field("NAME", trainer)
                Field("MONEY", save?.money?.let { "¥$it" } ?: " ")
                Field("TIME", save?.playTimeText ?: " ")
            }
            // The card's own corner, as the games put the player there.
            //
            // The trainer the player picked, and until they pick one the
            // party's lead — which is what this corner held before there were
            // trainers to choose from, so a card is never empty for the sake
            // of a choice nobody has made yet.
            val side = if (full) 56 else 32
            when {
                trainerSprite != null -> Gen1TrainerSprite(
                    id = trainerSprite,
                    store = trainers,
                    revision = spriteRevision,
                    sizeInPixels = side,
                )
                read -> Gen1Sprite(
                    speciesId = lead?.speciesId,
                    gameVersionId = remote.version.id,
                    store = sprites,
                    revision = spriteRevision,
                    sizeInPixels = side,
                )
                else -> Spacer(Modifier.size(gen1Dp(side)))
            }
        }

        Spacer(Modifier.height(gen1Dp(2)))
        GbText("BADGES", style = Gen1TextSmall, maxLines = 1)
        Badges(save?.badges ?: List(Gen1RecompSave.BADGE_IDS.size) { false })

        if (full) {
            Spacer(Modifier.height(gen1Dp(3)))
            Field("GAME", GameVersion.fromId(remote.version.id)?.label ?: "?")
            Field(
                "POKéMON",
                save?.let { "${it.partyCount} OUT, ${it.storedCount} STORED" } ?: " ",
            )
            Field("SEEN", save?.let { "${it.dexOwnedCount}" } ?: " ")
            Field("BOXES", save?.let { "${it.boxCount}" } ?: " ")
        }
    }
}

/**
 * One trainer's battle sprite, off the device.
 *
 * Blank where that one has not been downloaded: the bracketed mark a Pokémon
 * gets means "there is no art for this species", and a trainer the player
 * deliberately chose is better shown as a gap than as a shrug.
 */
@Composable
fun Gen1TrainerSprite(
    id: String,
    store: TrainerStore,
    revision: Int,
    modifier: Modifier = Modifier,
    sizeInPixels: Int = TrainerStore.SIZE,
) {
    var image by remember(id, revision) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id, revision, store) {
        image = withContext(Dispatchers.IO) { store.load(id) }
    }
    Box(modifier.size(gen1Dp(sizeInPixels)), contentAlignment = Alignment.Center) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = id,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                // Pixel art at a whole multiple; smoothing would undo it.
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/** "NAME/ RED" — the games' own label-then-value, on one line. */
@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        GbText("$label/", style = Gen1TextSmall, maxLines = 1)
        Spacer(Modifier.width(gen1Dp(2)))
        GbText(
            value,
            modifier = Modifier.weight(1f),
            style = Gen1Text.copy(textAlign = TextAlign.End),
            maxLines = 1,
        )
    }
}

/**
 * The eight gyms, in order, filled where the badge has been won.
 *
 * Drawn rather than lettered: the cartridge shows eight badge pictures, and
 * eight squares in a row read the same way at a glance — how far along this
 * playthrough is, without anybody having to count words.
 */
@Composable
private fun Badges(won: List<Boolean>) {
    val pixel = gen1PixelPx().toFloat()
    Row(horizontalArrangement = Arrangement.spacedBy(gen1Dp(2))) {
        won.forEach { earned ->
            Box(
                Modifier.size(gen1Dp(BADGE_PIXELS)).drawBehind {
                    val edge = pixel
                    if (earned) {
                        drawRect(Gen1Palette.Ink, Offset.Zero, size)
                        return@drawBehind
                    }
                    // An outline for one not yet won: the slot is still there,
                    // which is the point of drawing eight of them.
                    drawRect(Gen1Palette.Ink, Offset.Zero, Size(size.width, edge))
                    drawRect(Gen1Palette.Ink, Offset(0f, size.height - edge), Size(size.width, edge))
                    drawRect(Gen1Palette.Ink, Offset.Zero, Size(edge, size.height))
                    drawRect(Gen1Palette.Ink, Offset(size.width - edge, 0f), Size(edge, size.height))
                }
            )
        }
    }
}

/** One badge slot's side, in game pixels. */
private const val BADGE_PIXELS = 7

/**
 * Which trainer a card wears.
 *
 * Every trainer the game has, in the game's own order, each shown as itself
 * rather than named in a list: a row of words would be asking the player to
 * remember what a CHANNELER looks like. NONE is first and puts the card back
 * to showing the party's lead.
 *
 * A trainer whose art is not on the device is still offered and still picked;
 * it simply shows as a gap until DOWNLOADS has fetched it. Hiding them would
 * mean the list changing shape depending on what had arrived.
 */
@Composable
fun TrainerSpritePicker(
    chosen: String?,
    store: TrainerStore,
    revision: Int,
    onChoose: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    val rows = TrainerStore.ALL
    // NONE, then every trainer, then the way out.
    val count = rows.size + 2
    val at = rememberCursorLayer(count) { index ->
        when (index) {
            0 -> onChoose(null)
            count - 1 -> onCancel()
            else -> onChoose(rows[index - 1].id)
        }
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(at) { scroll.animateScrollToItem(at) }

    Gen1Frame(Modifier.gen1MaxWidth().wrapContentWidth(), opening = true) {
        GbText("TRAINER")
        LazyColumn(Modifier.heightIn(max = 360.dp), state = scroll) {
            item {
                Gen1MenuRow(
                    "NONE",
                    selected = at == 0,
                    onSelect = {},
                    onConfirm = { onChoose(null) },
                    mark = chosen == null,
                )
            }
            itemsIndexed(rows) { index, trainer ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Gen1TrainerSprite(
                        id = trainer.id,
                        store = store,
                        revision = revision,
                        sizeInPixels = PICKER_SPRITE_PIXELS,
                    )
                    Gen1MenuRow(
                        trainer.label,
                        selected = at == index + 1,
                        onSelect = {},
                        onConfirm = { onChoose(trainer.id) },
                        mark = chosen == trainer.id,
                    )
                }
            }
            item {
                Gen1MenuRow(
                    "CANCEL",
                    selected = at == count - 1,
                    onSelect = {},
                    onConfirm = onCancel,
                )
            }
        }
    }
}

/** Small enough that a row is still a row, big enough to recognise. */
private const val PICKER_SPRITE_PIXELS = 24
