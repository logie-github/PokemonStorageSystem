package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * SAVE FILES: every copy this app kept, and everything it has handed out.
 *
 * Two lists that answer the two questions a player has when something has gone
 * wrong. *What did this app do to my cartridge* — every write is preceded by a
 * copy of the bytes it replaced, ten deep per save, and this is where those
 * copies are listed. And *why won't it let me take this out* — the records of
 * what the app believes it has already written into which cartridge, each
 * droppable by a player who knows better.
 *
 * The copies are read here and nowhere else. This app cannot put one back:
 * a save belongs to the game, and an app that can overwrite one wholesale is
 * an app that can undo an evening of playing because a transfer looked
 * wrong. They are kept as evidence of what was replaced, and they are on the
 * device for anyone who wants them.
 *
 * Deliberately plain and deliberately behind OPTIONS. Nothing here is part of
 * moving a Pokémon; it is the machine's own paperwork, and it should be found
 * when it is looked for rather than met on the way to something else.
 */
@Composable
fun RestoreScreen(state: UiState, model: StorageViewModel) {
    // Read once per visit rather than per frame: each copy is opened and
    // classified to say what is in it, which is not work to repeat on every
    // recomposition. The transfer count moves whenever a write has happened,
    // which is the only thing that adds one.
    val copies = remember(state.transfers, state.saves.size, state.cartRevision) {
        model.backupRows()
    }
    val placements = remember(state.transfers) { model.placements() }

    fun drop(placement: com.logie.gen1storage.transfer.Placement) {
        model.prompt(
            Prompt.Confirm(
                lines = listOf(
                    "THE PC PUT ${placement.monName.uppercase()}",
                    "IN ${placement.savePath.uppercase()}.",
                    "FORGET THAT?",
                ),
                confirmLabel = "YES",
                cancelLabel = "NO",
                onConfirm = { model.forgetPlacement(placement.fingerprint) },
            )
        )
    }

    // The cursor covers what can be done, which is dropping a record and
    // leaving. The copies are a list to read.
    val count = placements.size + 1
    val cursor = rememberCursorLayer(count) { index ->
        if (index < placements.size) drop(placements[index]) else model.back()
    }

    ScreenColumn {
        item { Gen1Frame(Modifier.wrapContentWidth()) { GbText("SAVE FILES") } }

        item {
            Gen1Frame {
                Gen1Field("KEPT COPIES", "${copies.size}")
                Gen1Field("HANDED OUT", "${placements.size}")
            }
        }

        if (copies.isNotEmpty()) {
            item {
                Gen1Frame(Modifier.wrapContentWidth()) {
                    GbText("BEFORE EACH WRITE", style = Gen1TextSmall)
                }
            }
        }
        items(copies) { row ->
            Gen1Frame {
                Column {
                    Gen1Field(row.cart, if (row.readable) "" else "BAD")
                    GbText(row.takenAt, style = Gen1TextSmall, maxLines = 1)
                    GbText(row.summary, style = Gen1TextSmall, maxLines = 1)
                }
            }
        }

        if (placements.isNotEmpty()) {
            item {
                Gen1Frame(Modifier.wrapContentWidth()) {
                    GbText("WHAT THE PC HANDED OUT", style = Gen1TextSmall)
                }
            }
        }
        items(placements) { placement ->
            val index = placements.indexOf(placement)
            Gen1Frame {
                Column {
                    Gen1MenuRow(
                        placement.monName.uppercase(),
                        selected = cursor == index,
                        onSelect = {},
                        onConfirm = { drop(placement) },
                        trailing = "DROP",
                    )
                    GbText(placement.savePath.uppercase(), style = Gen1TextSmall, maxLines = 1)
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
