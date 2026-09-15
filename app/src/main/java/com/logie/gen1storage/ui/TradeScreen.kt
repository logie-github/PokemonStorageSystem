package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.pokemon.tradeEvolutionName
import com.logie.gen1storage.pokemon.tradeEvolutionOf

/**
 * The trade machine: every Pokémon a cartridge alone can never evolve.
 *
 * Generation I gates Alakazam, Machamp, Golem and Gengar behind a trade, and
 * Generation II keeps those four and adds six more behind a trade **and** a
 * held item — Politoed, Slowking, Kingdra, Steelix, Scizor, Porygon2 — which
 * means behind a second cartridge, a second Game Boy and a link cable either
 * way. This app is already both ends of a cable — a Pokémon deposited here
 * and withdrawn again has been traded in every sense the cartridge would
 * recognise — so the machine can simply do it, for whichever ten a Pokémon's
 * own generation offers.
 *
 * Nothing leaves the PC and no save is written. The Pokémon that goes in is the
 * Pokémon that comes out, one species further along, with its nickname, its
 * original trainer, its experience and its stat experience exactly as they were.
 */
@Composable
fun TradeScreen(state: UiState, model: StorageViewModel) {
    val candidates = model.tradeCandidates()
    val count = candidates.size + 1

    fun trade(index: Int) {
        if (index >= candidates.size) {
            model.back()
            return
        }
        val stored = candidates[index]
        val name = stored.pokemon.displayName.uppercase()
        val becomes = tradeEvolutionOf(stored.pokemon)
            ?.let { tradeEvolutionName(it, stored.pokemon.generation).uppercase() }
            ?: return
        model.prompt(
            Prompt.Confirm(
                lines = listOf("TRADE $name?", "IT WILL BECOME $becomes."),
                confirmLabel = "YES",
                cancelLabel = "NO",
                onConfirm = { model.tradeEvolve(stored.uid) },
            )
        )
    }

    val cursor = rememberCursorLayer(count) { trade(it) }

    ScreenColumn {
        item { Gen1Frame(Modifier.wrapContentWidth()) { GbText("TRADE") } }

        if (candidates.isEmpty()) {
            item {
                Gen1Frame {
                    GbText("NOTHING IN THE PC", maxLines = 1)
                    GbText("EVOLVES BY TRADING.", maxLines = 1)
                }
            }
        }

        items(candidates) { stored ->
            val index = candidates.indexOf(stored)
            val becomes = tradeEvolutionOf(stored.pokemon)
                ?.let { tradeEvolutionName(it, stored.pokemon.generation).uppercase() }
                .orEmpty()
            Gen1Frame {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Gen1Sprite(
                        speciesId = stored.pokemon.spriteSpeciesId(),
                        gameVersionId = stored.provenance.gameVersion,
                        store = model.sprites,
                        revision = state.spriteRevision,
                        sizeInPixels = ROW_SPRITE_PIXELS,
                    )
                    Column(Modifier.weight(1f)) {
                        Gen1MenuRow(
                            pokemonRowLabel(stored.pokemon),
                            selected = cursor == index,
                            onSelect = {},
                            onConfirm = { trade(index) },
                            trailing = pokemonRowLevel(stored.pokemon),
                        )
                        GbText("BECOMES $becomes", style = Gen1TextSmall, maxLines = 1)
                    }
                }
            }
        }

        item {
            Gen1Frame(
                Modifier.wrapContentWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Gen1MenuRow(
                    "BACK",
                    selected = cursor == count - 1,
                    onSelect = {},
                    onConfirm = { model.back() },
                )
            }
        }
    }
}

private const val ROW_SPRITE_PIXELS = 32
