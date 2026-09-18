package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.pokemon.TimeCapsule

/**
 * The Time Capsule: a Generation I Pokémon on its way to Generation II.
 *
 * The cartridge's own Time Capsule is a link cable between a Game Boy running
 * Red and one running Gold, and what it does to a Pokémon on the way through
 * is written down in pret/pokecrystal rather than guessed at here — see
 * [TimeCapsule], which lists every one of those changes and where it is read
 * from.
 *
 * The two things the trip spends are shown before it is taken, because they
 * are the two a player cannot get back: the catch rate, which Generation II
 * reads as a held item, and the single Special stat, which becomes two. They
 * are written down beside the Pokémon as well, so what it was is still a fact
 * about it afterwards.
 *
 * One way only. Nothing here sends a Pokémon back, and nothing moves one
 * without being asked to.
 */
@Composable
fun TimeCapsuleScreen(state: UiState, model: StorageViewModel) {
    // Everything in the PC, the ones that can go first — not only the ones
    // that can. A Pokemon held back used to be left off the screen entirely,
    // and a Pokemon that is simply not there is the one answer a player
    // cannot do anything with. See [StorageViewModel.timeCapsuleRoster].
    val roster = model.timeCapsuleRoster()
    val count = roster.size + 1

    fun carry(index: Int) {
        if (index >= roster.size) {
            model.back()
            return
        }
        val (stored, blocked) = roster[index]
        val name = stored.pokemon.displayName.uppercase()
        // Taking a row that cannot go says so out loud. The reason is on the
        // row as well; this is for the player who pressed it anyway, which is
        // what anybody does with a row that looks like every other row.
        if (blocked != null) {
            model.prompt(Prompt.Message(listOf("$name CANNOT GO ON.", blocked)))
            return
        }
        val item = TimeCapsule.heldItemFor(stored.pokemon.catchRate)
        model.prompt(
            Prompt.Confirm(
                lines = buildList {
                    add("SEND $name FORWARD?")
                    add("IT CANNOT COME BACK.")
                    if (item != null) add("IT WILL HOLD ${itemLabel(item)}.")
                },
                confirmLabel = "YES",
                cancelLabel = "NO",
                onConfirm = { model.carryForward(stored.uid) },
            )
        )
    }

    val cursor = rememberCursorLayer(count) { carry(it) }

    ScreenColumn {
        item { Gen1Frame(Modifier.wrapContentWidth()) { GbText("TIME CAPSULE") } }

        if (roster.isEmpty()) {
            item {
                Gen1Frame {
                    GbText("THERE IS NOTHING", maxLines = 1)
                    GbText("IN THE PC.", maxLines = 1)
                }
            }
        }

        itemsIndexed(roster) { index, (stored, blocked) ->
            val item = TimeCapsule.heldItemFor(stored.pokemon.catchRate)
            Gen1Frame {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Gen1Sprite(
                        speciesId = stored.pokemon.spriteSpeciesId(),
                        gameVersionId = stored.spriteGameVersionId,
                        store = model.sprites,
                        revision = state.spriteRevision,
                        sizeInPixels = ROW_SPRITE_PIXELS,
                    )
                    Column(Modifier.weight(1f)) {
                        Gen1MenuRow(
                            pokemonRowLabel(stored.pokemon),
                            selected = cursor == index,
                            onSelect = {},
                            onConfirm = { carry(index) },
                            trailing = pokemonRowLevel(stored.pokemon),
                        )
                        // What it will arrive holding, which is its catch
                        // rate read as an item. Said before the trip rather
                        // than discovered after it — or, for one that is not
                        // going anywhere, why not.
                        GbText(
                            when {
                                blocked != null -> blocked
                                item != null -> "HOLDS ${itemLabel(item)}"
                                else -> "HOLDS NOTHING"
                            },
                            style = Gen1TextSmall,
                            maxLines = 2,
                        )
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

/**
 * An item id as a player reads it.
 *
 * The decompilation's names are constants — GOLD_BERRY, TM_HEADBUTT — and the
 * underscores are the only thing between them and the words on a screen.
 */
internal fun itemLabel(id: String): String = id.replace('_', ' ')

private const val ROW_SPRITE_PIXELS = 32
