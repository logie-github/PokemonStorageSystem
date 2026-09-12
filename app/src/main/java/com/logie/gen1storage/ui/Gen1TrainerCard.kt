package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
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
            if (read) {
                Gen1Sprite(
                    speciesId = lead?.speciesId,
                    gameVersionId = remote.version.id,
                    store = sprites,
                    revision = spriteRevision,
                    sizeInPixels = if (full) 56 else 32,
                )
            } else {
                Spacer(Modifier.size(gen1Dp(if (full) 56 else 32)))
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
