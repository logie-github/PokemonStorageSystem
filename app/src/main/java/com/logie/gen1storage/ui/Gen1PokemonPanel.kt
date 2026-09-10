package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat

/**
 * The Generation I status screen for one Pokémon: name and level, the HP bar,
 * the four move slots, and the trainer line. Everything a player recognises is
 * on the face of it; DVs and stat experience live behind DETAILS, as the
 * original never showed them at all.
 */
@Composable
fun Gen1PokemonPanel(
    pokemon: Gen1Pokemon,
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: @Composable () -> Unit = {},
) {
    var showDetails by remember(pokemon.fingerprint) { mutableStateOf(false) }

    Gen1Window(modifier, title = header) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GbText(pokemon.displayName.uppercase(), style = Gen1TextLarge)
            GbText(":L${pokemon.level}", style = Gen1TextLarge)
        }
        val species = pokemon.species
        GbText(
            buildString {
                append(species?.let { "No.%03d ".format(it.dexNumber) } ?: "")
                append(Gen1Data.speciesName(pokemon.speciesId))
                species?.types?.takeIf { it.isNotEmpty() }?.let { append("  ${it.joinToString("/")}") }
            },
            style = Gen1TextSmall,
        )

        Spacer(Modifier.height(8.dp))
        val maxHp = pokemon.maxHp
        if (maxHp != null) {
            GbText("HP ${pokemon.currentHp}/$maxHp")
            Gen1HpBar(pokemon.currentHp, maxHp)
        } else {
            // A Pokémon imported from a real cartridge save into a box carries
            // no stat block; the game derives one when it re-enters a party.
            GbText("HP ${pokemon.currentHp}/?")
            GbText("STATS ARE DERIVED ON WITHDRAW", style = Gen1TextSmall)
        }
        pokemon.status?.let { GbText("STATUS $it") }

        Spacer(Modifier.height(8.dp))
        val moves = pokemon.moves
        if (moves.isEmpty()) GbText("-", style = Gen1TextSmall)
        moves.forEach { slot ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GbText(slot.displayName.uppercase())
                GbText("PP ${slot.pp}${slot.maxPp?.let { "/$it" } ?: ""}", style = Gen1TextSmall)
            }
        }

        Spacer(Modifier.height(8.dp))
        Gen1Field("OT/", pokemon.otName ?: "?")
        Gen1Field("IDNo/", pokemon.otId?.let { "%05d".format(it) } ?: "?")
        Gen1Field("EXP", pokemon.exp.toString())
        if (pokemon.isShiny) GbText("RARE COLOURATION", style = Gen1TextSmall)

        Spacer(Modifier.height(8.dp))
        Gen1Button(if (showDetails) "HIDE DETAILS" else "DETAILS", { showDetails = !showDetails })

        if (showDetails) {
            Spacer(Modifier.height(8.dp))
            Gen1Window(contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                GbText("STATS")
                Gen1Stat.ORDER.forEach { stat ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        GbText(stat.label, style = Gen1TextSmall)
                        GbText(
                            "${pokemon.stats[stat]?.toString() ?: "-"}" +
                                "  DV ${pokemon.dvs[stat] ?: "-"}" +
                                "  EXP ${pokemon.statExp[stat] ?: "-"}",
                            style = Gen1TextSmall,
                        )
                    }
                }
                pokemon.catchRate?.let {
                    Spacer(Modifier.height(6.dp))
                    Gen1Field("CATCH RATE", it.toString())
                }
                Gen1Field("SPECIES ID", pokemon.speciesId ?: "?")
            }
        }

        Column(Modifier.padding(top = 10.dp)) { footer() }
    }
}

/** The one-line form used inside party and box lists. */
fun pokemonRowLabel(pokemon: Gen1Pokemon): String = pokemon.displayName.uppercase()

fun pokemonRowDetail(pokemon: Gen1Pokemon): String {
    val hp = pokemon.maxHp?.let { "${pokemon.currentHp}/$it" } ?: "${pokemon.currentHp}/?"
    val status = pokemon.status?.let { " $it" } ?: ""
    return ":L${pokemon.level}  HP $hp$status"
}
