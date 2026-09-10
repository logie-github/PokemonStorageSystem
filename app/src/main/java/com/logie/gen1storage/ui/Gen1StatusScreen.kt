package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
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
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat

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
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    var page by remember(pokemon.fingerprint) { mutableStateOf(0) }

    Column(
        modifier
            .fillMaxSize()
            .background(Gen1Palette.Panel)
            .clickable { page = 1 - page }
            .padding(14.dp),
    ) {
        if (page == 0) StatusPageOne(pokemon) else StatusPageTwo(pokemon)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            GbText(if (page == 0) "▼ MORE" else "▲ BACK", style = Gen1TextSmall)
        }
        Spacer(Modifier.height(10.dp))
        footer()
    }
}

@Composable
private fun StatusPageOne(pokemon: Gen1Pokemon) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(120.dp)) {
            SpritePlaceholder()
            Spacer(Modifier.height(6.dp))
            GbText(pokemon.species?.let { "No.%03d".format(it.dexNumber) } ?: "No.???")
        }
        Spacer(Modifier.width(10.dp))
        Gen1CornerRule(Modifier.weight(1f)) {
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
            Spacer(Modifier.height(4.dp))
            val maxHp = pokemon.maxHp
            Row(verticalAlignment = Alignment.CenterVertically) {
                GbText("HP:", style = Gen1TextSmall.copy(color = Gen1Palette.Ink))
                Spacer(Modifier.width(4.dp))
                Gen1HpBar(pokemon.currentHp, maxHp ?: 0, Modifier.weight(1f))
            }
            GbText(
                if (maxHp != null) "%d/ %d".format(pokemon.currentHp, maxHp)
                // A box Pokémon imported from a cartridge save carries no stat
                // block until it re-enters a party; the game derives it then.
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
    }

    Spacer(Modifier.height(12.dp))

    Row(Modifier.fillMaxWidth()) {
        Gen1Frame(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
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
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val types = pokemon.species?.types.orEmpty()
            GbText("TYPE1/")
            GbText(" ${types.getOrNull(0) ?: "---"}")
            if (types.size > 1) {
                GbText("TYPE2/")
                GbText(" ${types[1]}")
            }
            Spacer(Modifier.height(8.dp))
            GbText("IDNo/")
            GbText(" ${pokemon.otId?.let { "%05d".format(it) } ?: "-----"}")
            GbText("OT/")
            GbText(" ${pokemon.otName ?: "-----"}")
        }
    }
}

@Composable
private fun StatusPageTwo(pokemon: Gen1Pokemon) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(120.dp)) {
            SpritePlaceholder()
            Spacer(Modifier.height(6.dp))
            GbText(pokemon.species?.let { "No.%03d".format(it.dexNumber) } ?: "No.???")
        }
        Spacer(Modifier.width(10.dp))
        Gen1CornerRule(Modifier.weight(1f)) {
            GbText(
                pokemon.displayName.uppercase(),
                modifier = Modifier.fillMaxWidth(),
                style = Gen1Text.copy(textAlign = TextAlign.End),
            )
            Spacer(Modifier.height(6.dp))
            GbText("EXP POINTS")
            GbText(
                pokemon.exp.toString(),
                modifier = Modifier.fillMaxWidth(),
                style = Gen1Text.copy(textAlign = TextAlign.End),
            )
            GbText("LEVEL UP")
            val owed = Gen1Growth.expToNextLevel(pokemon)
            GbText(
                if (owed == null) "---"
                else "$owed to :L${pokemon.level + 1}",
                modifier = Modifier.fillMaxWidth(),
                style = Gen1Text.copy(textAlign = TextAlign.End),
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
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

/**
 * The sprite slot. A "?" stands in until real artwork is available; the app
 * ships no Pokémon graphics of its own, and the game's are built from the
 * player's ROM rather than distributed.
 */
@Composable
fun SpritePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(96.dp)
            .drawBehind {
                val t = 3.dp.toPx()
                val corner = size.width * 0.28f
                // Corner ticks only, like a framing bracket, so the space
                // reads as "a picture goes here" rather than as a window.
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
        GbText("?", style = Gen1TextLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp)))
    }
}
