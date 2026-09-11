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
import androidx.compose.runtime.key
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
import com.logie.gen1storage.sound.LocalCryPlayer
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat
import com.logie.gen1storage.sprites.SpriteStore

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
    onSpriteLongPress: ((String) -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    var page by remember(pokemon.fingerprint) { mutableStateOf(0) }

    // One cry, when a Pokémon is opened — not on every page turn, and not
    // again when something unrelated recomposes.
    val cries = LocalCryPlayer.current
    LaunchedEffect(pokemon.fingerprint) { cries?.cry(pokemon.species?.dexNumber) }

    Row(modifier.fillMaxSize().padding(gen1Dp(4))) {
        // Unfolded, the pages keep to the left half in a window of their own
        // rather than turning that half into a white wall — the screen behind
        // stays visible around it, as it does everywhere else.
        Gen1Frame(
            Modifier
                .fillMaxWidth(if (isUnfolded()) 0.5f else 1f)
                .gen1Clickable { page = 1 - page },
        ) {
            // Drawn here rather than inside either page. Turning the page used
            // to build a new sprite whose image started empty, and the
            // bracketed placeholder flashed over it until the file loaded
            // again; from out here it is the same sprite either way.
            val sprite: @Composable () -> Unit = {
                // Keyed on the revision so a download or a set change redraws it.
                key(spriteRevision) {
                    Gen1Sprite(
                        pokemon.speciesId,
                        gameVersionId,
                        store,
                        onLongPress = onSpriteLongPress,
                        onTap = { cries?.cry(pokemon.species?.dexNumber) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(gen1Dp(56))) {
                    sprite()
                    Spacer(Modifier.height(gen1Dp(2)))
                    GbText(pokemon.species?.let { "No.%03d".format(it.dexNumber) } ?: "No.???")
                }
                Spacer(Modifier.width(gen1Dp(4)))
                Gen1CornerRule(Modifier.weight(1f)) {
                    if (page == 0) StatusHeaderOne(pokemon) else StatusHeaderTwo(pokemon)
                }
            }
            Spacer(Modifier.height(gen1Dp(4)))
            if (page == 0) StatusPageOne(pokemon) else StatusPageTwo(pokemon)
            Spacer(Modifier.height(gen1Dp(4)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                GbText(if (page == 0) "▼ MORE" else "▲ BACK")
            }
        }
        Spacer(Modifier.weight(1f))
    }

    // Opposite the menus, so it never lands under whatever they are showing.
    Box(Modifier.fillMaxSize().padding(gen1Dp(4))) {
        Box(Modifier.align(Gen1Layout.corner(top = false, menuSide = false))) { footer() }
    }
}

@Composable
private fun StatusHeaderOne(pokemon: Gen1Pokemon) {
    GbText(
        pokemon.displayName.uppercase(),
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
            listOf(Gen1Stat.ATTACK, Gen1Stat.DEFENSE, Gen1Stat.SPEED, Gen1Stat.SPECIAL)
                .forEach { stat ->
                    GbText(stat.label)
                    GbText(
                        pokemon.stats[stat]?.toString() ?: "---",
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
        for (index in 0 until 4) {
            val slot = pokemon.moves.getOrNull(index)
            GbText(slot?.displayName?.uppercase() ?: "-")
            GbText(
                if (slot == null) "--" else "PP ${slot.pp}/${slot.maxPp ?: slot.pp}",
                modifier = Modifier.fillMaxWidth(),
                style = Gen1Text.copy(textAlign = TextAlign.End),
            )
        }
    }
}
