package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.pokemon.Gen1Growth
import com.logie.gen1storage.sound.LocalGen1Audio
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat
import com.logie.gen1storage.pokemon.tradeEvolves
import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.sprites.SpriteStore
import com.logie.gen1storage.storage.Provenance

/**
 * The Generation I status screen, both pages.
 *
 * Deliberately limited to what the cartridge itself shows. DVs and stat
 * experience are read from the save and carried through every transfer, but
 * the games never put them on screen and neither does this — the storage
 * system is meant to feel like the PC, not like a save editor.
 */
@Composable
fun Gen1StatusScreen(
    pokemon: Gen1Pokemon,
    gameVersionId: String?,
    store: SpriteStore,
    spriteRevision: Int,
    modifier: Modifier = Modifier,
    /**
     * Whether this is the screen or a pane inside one.
     *
     * The screen keeps to half the width when there is a lot of it, so what
     * it was opened from stays readable beside it. A pane has already been
     * given its half and fills it.
     */
    inPane: Boolean = false,
    /**
     * Whether opening this Pokémon is worth hearing.
     *
     * A pane that follows the cursor would cry on every step of it, which is
     * thirty cries crossing a box.
     */
    speaks: Boolean = true,
    /**
     * Where this one came from, for a Pokémon held in this app's PC.
     *
     * The cartridge never had to say: everything in its PC came from itself.
     * This machine holds Pokémon from several playthroughs at once, and which
     * cartridge and which trainer a Pokémon arrived from is the one thing it
     * knows that no cartridge could. Null for a Pokémon still in a save,
     * where the question does not arise.
     */
    provenance: Provenance? = null,
    onSpriteLongPress: ((String) -> Unit)? = null,
    footer: @Composable () -> Unit = {},
    /**
     * Drawn directly under the box, for the transfer this screen was opened
     * from. Under the box rather than in the corner with BACK: it acts on the
     * Pokémon above it, so it belongs to it.
     */
    underBox: @Composable () -> Unit = {},
    /**
     * Which page is showing, where the screen around this one is driving it.
     *
     * The pages are a sideways thing — the cartridge turns them with left and
     * right — and the cursor on this screen belongs to the actions beside it,
     * so the screen that owns that cursor is the one that can give left and
     * right to the pages. Left null the pages turn themselves, which is what
     * a pane and a tap do.
     */
    page: Int? = null,
    onPage: ((Int) -> Unit)? = null,
) {
    var ownPage by remember(pokemon.fingerprint) { mutableStateOf(0) }
    val shownPage = page ?: ownPage
    val turnPage: (Int) -> Unit = onPage ?: { ownPage = it }

    // One cry, when a Pokémon is opened — not on every page turn, and not
    // again when something unrelated recomposes.
    val cries = LocalGen1Audio.current
    LaunchedEffect(pokemon.fingerprint, speaks) {
        if (speaks) cries?.cry(pokemon.species?.dexNumber)
    }

    Row(modifier.fillMaxSize().padding(gen1Dp(4))) {
        // Unfolded, the pages keep to the left half in a window of their own
        // rather than turning that half into a white wall — the screen behind
        // stays visible around it, as it does everywhere else.
        Column(Modifier.fillMaxWidth(if (isUnfolded() && !inPane) 0.5f else 1f)) {
        Gen1Frame(
            Modifier
                .fillMaxWidth()
                .gen1Clickable { turnPage((shownPage + 1) % PAGES) },
        ) {
            // Drawn here rather than inside either page. Turning the page used
            // to build a new sprite whose image started empty, and the
            // bracketed placeholder flashed over it until the file loaded
            // again; from out here it is the same sprite either way.
            val sprite: @Composable () -> Unit = {
                Gen1Sprite(
                    pokemon.spriteSpeciesId(),
                    gameVersionId,
                    store,
                    revision = spriteRevision,
                    onLongPress = onSpriteLongPress,
                    onTap = { cries?.cry(pokemon.species?.dexNumber) },
                    shiny = pokemon.isShiny,
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(gen1Dp(56))) {
                    sprite()
                    Spacer(Modifier.height(gen1Dp(2)))
                    GbText(pokemon.species?.let { "No.%03d".format(it.dexNumber) } ?: "No.???")
                }
                Spacer(Modifier.width(gen1Dp(4)))
                Gen1CornerRule(Modifier.weight(1f)) {
                    when (shownPage) {
                        0 -> StatusHeaderOne(pokemon)
                        1 -> StatusHeaderTwo(pokemon)
                        else -> StatusHeaderThree(pokemon, gameVersionId)
                    }
                }
            }
            Spacer(Modifier.height(gen1Dp(4)))
            // No prompt to turn the page. The whole window takes a tap and
            // there are only three: a player finds that in one tap and never
            // needs telling again.
            when (shownPage) {
                0 -> StatusPageOne(pokemon)
                1 -> StatusPageTwo(pokemon)
                else -> StatusPageThree(pokemon, gameVersionId)
            }
            provenance?.let {
                Spacer(Modifier.height(gen1Dp(3)))
                CameFrom(it)
            }
        }
            Spacer(Modifier.height(gen1Dp(3)))
            underBox()
        }
        Spacer(Modifier.weight(1f))
    }

    // Opposite the menus, so it never lands under whatever they are showing.
    if (!inPane) {
        Box(Modifier.fillMaxSize().padding(gen1Dp(4))) {
            Box(Modifier.align(Gen1Layout.corner(top = false, menuSide = false))) { footer() }
        }
    }
}

/**
 * "CAME FROM / RED · ASH · 12 MAR 2026" — the line the cartridge never needed.
 *
 * Small, and at the foot of the window rather than among the stats, because
 * it is a fact about the Pokémon's history rather than about the Pokémon. The
 * trainer named here is whoever deposited it, which is not always the one on
 * the OT line: a traded Pokémon carries its original trainer for ever and
 * still arrived here from somebody else's cartridge.
 */
@Composable
private fun CameFrom(provenance: Provenance) {
    val game = GameVersion.fromId(provenance.gameVersion)?.label ?: "?"
    val day = remember(provenance.depositedAtEpochMillis) {
        java.time.Instant.ofEpochMilli(provenance.depositedAtEpochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d yyyy"))
            .uppercase()
    }
    // One sentence rather than a heading with two lines under it. Where it
    // came from and when it arrived are one fact about this Pokémon, and
    // three stacked lines made it look like three.
    Gen1CornerRule(Modifier.fillMaxWidth()) {
        GbText(
            "TRANSFERRED FROM $game VERSION - $day",
            style = Gen1TextSmall.copy(color = Gen1Palette.Ink),
        )
    }
}

@Composable
private fun StatusHeaderOne(pokemon: Gen1Pokemon) {
    GbText(
        pokemon.displayName.uppercase() + (pokemon.gender?.symbol?.let { " $it" } ?: ""),
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
    )
    GbText(
        ":L${pokemon.level}",
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
    )
    Spacer(Modifier.height(gen1Dp(2)))
    val maxHp = pokemon.maxHp
    Row(verticalAlignment = Alignment.CenterVertically) {
        GbText("HP:", style = Gen1TextSmall.copy(color = Gen1Palette.Ink))
        Spacer(Modifier.width(gen1Dp(2)))
        Gen1HpBar(pokemon.currentHp, maxHp ?: 0, Modifier.weight(1f))
    }
    GbText(
        if (maxHp != null) "%d/ %d".format(pokemon.currentHp, maxHp)
        // A box Pokémon imported from a cartridge save carries no stat block
        // until it re-enters a party; the game derives it then.
        else "%d/ ???".format(pokemon.currentHp),
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
    )
    GbText(
        "STATUS/${pokemon.status ?: "OK"}",
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
    )
}

@Composable
private fun StatusPageOne(pokemon: Gen1Pokemon) {
    Row(Modifier.fillMaxWidth()) {
        Gen1Frame(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = gen1Dp(2), vertical = gen1Dp(2)),
        ) {
            // Four rows out of a Generation I save and five out of a
            // Generation II one, where Special is two stats.
            pokemon.battleStats.forEach { stat ->
                GbText(stat.label)
                GbText(
                    stat.value?.toString() ?: "---",
                    modifier = Modifier.fillMaxWidth(),
                    style = Gen1Text.copy(textAlign = TextAlign.End),
                )
            }
        }
        Spacer(Modifier.width(gen1Dp(4)))
        Column(Modifier.weight(1f)) {
            val types = pokemon.species?.types.orEmpty()
            GbText("TYPE1/")
            GbText(" ${types.getOrNull(0) ?: "---"}")
            if (types.size > 1) {
                GbText("TYPE2/")
                GbText(" ${types[1]}")
            }
            Spacer(Modifier.height(gen1Dp(3)))
            GbText("IDNo/")
            GbText(" ${pokemon.otId?.let { "%05d".format(it) } ?: "-----"}")
            GbText("OT/")
            GbText(" ${pokemon.otName ?: "-----"}")
            // The one thing a cartridge cannot do on its own, said where a
            // player is already looking at what this Pokémon is.
            if (tradeEvolves(pokemon)) {
                Spacer(Modifier.height(gen1Dp(3)))
                GbText("EVOLVES BY", style = Gen1TextSmall, maxLines = 1)
                GbText("TRADE", style = Gen1TextSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatusHeaderTwo(pokemon: Gen1Pokemon) {
    GbText(
        pokemon.displayName.uppercase(),
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
    )
    Spacer(Modifier.height(gen1Dp(3)))
    GbText("EXP POINTS")
    GbText(
        pokemon.exp.toString(),
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
    )
    GbText("LEVEL UP")
    val owed = Gen1Growth.expToNextLevel(pokemon)
    GbText(
        if (owed == null) "---" else "$owed to :L${pokemon.level + 1}",
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
    )
}

@Composable
private fun StatusPageTwo(pokemon: Gen1Pokemon) {
    Gen1Frame(contentPadding = PaddingValues(horizontal = gen1Dp(2), vertical = gen1Dp(2))) {
        // Always four slots: the games draw an empty move slot as "-".
        //
        // The move and its PP on one line rather than stacked. The cartridge
        // has twenty columns and has to put the PP underneath; there is room
        // here, and a name with its own count under it read as eight rows
        // rather than four moves.
        for (index in 0 until 4) {
            val slot = pokemon.moves.getOrNull(index)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                GbText(
                    slot?.displayName?.uppercase() ?: "-",
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                Spacer(Modifier.width(gen1Dp(4)))
                GbText(if (slot == null) "--" else "PP ${slot.pp}/${slot.maxPp ?: slot.pp}")
            }
        }
    }
}

/**
 * The Pokédex header: what the cartridge calls this kind of Pokémon.
 *
 * "SEED POKéMON", "MOUSE POKéMON" — the classification is printed as its own
 * line above the entry in every game in the series, and it is the one part of
 * a dex page that is not a sentence.
 */
@Composable
private fun StatusHeaderThree(pokemon: Gen1Pokemon, gameVersionId: String?) {
    val entry = pokemon.dexPage(gameVersionId)
    GbText(
        pokemon.displayName.uppercase(),
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
    )
    Spacer(Modifier.height(gen1Dp(3)))
    GbText(
        entry?.let { "${it.category} POKéMON" } ?: "POKéMON",
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
        maxLines = 1,
    )
    GbText(
        entry?.let { "HT ${it.heightText}" } ?: "HT ---",
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
        maxLines = 1,
    )
    GbText(
        entry?.let { "WT ${it.weightText}" } ?: "WT ---",
        modifier = Modifier.fillMaxWidth(),
        style = Gen1Text.copy(textAlign = TextAlign.End),
        maxLines = 1,
    )
}

/**
 * The Pokédex entry itself, in the words of the game it came from.
 *
 * Red and Blue share a set of entries and Yellow rewrote nearly all of them,
 * and Gold, Silver and Crystal each wrote their own again, so the text follows
 * the save rather than the species: a Pikachu out of a
 * Yellow cartridge reads differently from one out of Red, and it should. Which
 * game it is goes unsaid — the card already says where the Pokémon came from,
 * and a line of credit under every entry is the app talking about itself.
 *
 * Set as the cartridge sets it — the Pokédex's own two pages of three short
 * lines, kept as written rather than reflowed, because the line breaks are
 * part of how the entry reads. No window of its own: the page it is on is
 * already a window, and a box inside a box is one rule too many.
 */
@Composable
private fun StatusPageThree(pokemon: Gen1Pokemon, gameVersionId: String?) {
    val entry = pokemon.dexPage(gameVersionId)
    // The entry is the body text of this page, so it is drawn in the same
    // ink and at the same size as everything else on it. The cartridge's own
    // breaks are dropped and the words wrap to this window instead: see
    // [Gen1DexEntry.flowing].
    val style = Gen1Text
    Column {
        if (entry == null) {
            // A modded species, or one the tables never had. Said plainly
            // rather than left blank, which would read as a bug.
            GbText("NO DATA ON THIS POKéMON.", style = style, maxLines = 1)
            return@Column
        }
        GbText(entry.flowing, modifier = Modifier.fillMaxWidth(), style = style)
    }
}

/** Stats, moves, and the Pokédex entry. */
const val PAGES = 3
