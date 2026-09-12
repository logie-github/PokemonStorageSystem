package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Species

/**
 * One Pokédex over every cartridge at once.
 *
 * A cartridge's Pokédex is a fact about that playthrough: Red's says what Red
 * has caught and knows nothing of Blue. This machine holds several playthroughs
 * and can move a Pokémon between them, which makes a different question worth
 * asking — *is this species anywhere at all, and if so where* — and that is the
 * question no cartridge can answer.
 *
 * Three states per species, in order of how much they are worth knowing:
 * a Pokémon of that species is in this PC right now, so it can be sent to any
 * cartridge that wants it; some cartridge has caught one; some cartridge has at
 * least seen one. The first is the useful one, and it is why this screen exists
 * rather than being a pretty total.
 *
 * Only cartridges whose save has actually been read can say anything, so the
 * screen says how many it is speaking for and offers to read the rest.
 */
@Composable
fun DexScreen(state: UiState, model: StorageViewModel) {
    var filter by remember { mutableStateOf(DexFilter.ALL) }

    val loaded = state.saves.mapNotNull { state.save(it.key) }
    val owned = remember(loaded) { loaded.flatMapTo(HashSet()) { it.save?.dexOwned.orEmpty() } }
    val seen = remember(loaded) { loaded.flatMapTo(HashSet()) { it.save?.dexSeen.orEmpty() } }
    val inThePc = remember(state.storage.revision) {
        state.storage.boxes.flatMap { it.contents }
            .mapNotNullTo(HashSet()) { it.pokemon.speciesId }
    }

    fun mark(species: Gen1Species): String = when {
        species.id in inThePc -> "PC"
        species.id in owned -> "OWN"
        species.id in seen -> "SEEN"
        else -> "---"
    }

    val rows = remember(filter, owned, seen, inThePc) {
        Gen1Data.species
            .sortedBy { it.dexNumber }
            .filter { species ->
                when (filter) {
                    DexFilter.ALL -> true
                    DexFilter.HERE -> species.id in inThePc
                    DexFilter.MISSING -> species.id !in owned && species.id !in inThePc
                }
            }
    }

    val unread = state.saves.count { state.save(it.key) == null }
    // The filter, the rows, whatever reading the rest is offered as, and out.
    val extras = if (unread > 0) 2 else 1
    val count = rows.size + 1 + extras

    fun take(index: Int) {
        when {
            index == 0 -> filter = filter.next
            index <= rows.size -> Unit
            index == rows.size + 1 && unread > 0 -> model.loadAllSaves()
            else -> model.back()
        }
    }

    val cursor = rememberCursorLayer(count) { take(it) }

    ScreenColumn {
        item { Gen1Frame(Modifier.wrapContentWidth()) { GbText("POKéDEX") } }

        item {
            Gen1Frame {
                Gen1Field("IN THE PC", "${inThePc.size}/${Gen1Data.species.size}")
                Gen1Field("CAUGHT ANYWHERE", "${owned.size}/${Gen1Data.species.size}")
                Gen1Field("SEEN ANYWHERE", "${seen.size}/${Gen1Data.species.size}")
                Gen1Field(
                    "READING",
                    "${loaded.size} OF ${state.saves.size} CARDS",
                )
            }
        }

        item {
            Gen1Frame(
                Modifier.wrapContentWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Gen1MenuRow(
                    "SHOWING",
                    selected = cursor == 0,
                    onSelect = {},
                    onConfirm = { filter = filter.next },
                    trailing = filter.label,
                )
            }
        }

        items(rows) { species ->
            val index = rows.indexOf(species) + 1
            Gen1Frame {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Gen1MenuRow(
                            "No.%03d %s".format(species.dexNumber, species.displayName),
                            selected = cursor == index,
                            onSelect = {},
                            onConfirm = {},
                            trailing = mark(species),
                        )
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            item { Gen1Frame { GbText("NOTHING TO SHOW.", maxLines = 1) } }
        }

        if (unread > 0) {
            item {
                Gen1Frame(
                    Modifier.wrapContentWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Gen1MenuRow(
                        if (state.loadingAll) "READING..." else "READ $unread MORE",
                        selected = cursor == rows.size + 1,
                        onSelect = {},
                        onConfirm = { model.loadAllSaves() },
                        enabled = !state.loadingAll,
                    )
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

/** What the list is showing, taken round by pressing the row. */
enum class DexFilter(val label: String) {
    ALL("ALL"),
    HERE("IN THE PC"),
    MISSING("NOT CAUGHT");

    val next: DexFilter get() = entries[(ordinal + 1) % entries.size]
}
