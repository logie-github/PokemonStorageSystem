package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.gen1recomp.ItemStack

/**
 * The two PCs, the way the games boot one.
 *
 * Generation I opens a PC on a list of them rather than on any one machine's
 * contents: Bill's holds Pokémon, the player's holds items. This app is
 * Bill's, and the cartridge in the machine brings its own.
 */
@Composable
fun MainMenuScreen(state: UiState, model: StorageViewModel) {
    val save = state.save(state.activeSaveKey)?.save
    val player = save?.trainerName?.uppercase()
    val entries = listOf<Pair<String, () -> Unit>>(
        "LOGIE'S PC" to { model.open(Screen.Storage) },
        "${player ?: "PLAYER"}'S PC" to {
            // No cartridge in the machine means there is no item PC to open,
            // so the only useful thing to ask for is the cartridge.
            if (save == null) model.open(Screen.ChooseCart(null)) else model.open(Screen.ItemPc)
        },
        "OPTIONS" to { model.open(Screen.Options) },
    )
    // The shared cursor rather than a count of its own, so the arrow is on a
    // row from the moment the menu opens and a swipe moves it.
    val cursor = rememberCursorLayer(entries.size) { entries[it].second() }

    ScreenColumn {
        item {
            Gen1Frame(
                Modifier.wrapContentWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                entries.forEachIndexed { index, (label, action) ->
                    Gen1MenuRow(label, cursor == index, {}, action)
                }
            }
        }
    }
}

/**
 * The player's own PC: items, and the way between it and this app's.
 *
 * Laid out like the Pokémon side, because it is the same trip — a list on one
 * side, a list on the other, and one question about how many.
 */
@Composable
fun ItemPcScreen(state: UiState, model: StorageViewModel) {
    val key = state.activeSaveKey
    val save = state.save(key)?.save
    var mode by remember { mutableStateOf(ItemMode.MENU) }

    if (save == null || key == null) {
        ScreenColumn {
            item { Gen1Frame(Modifier.wrapContentWidth()) { GbText("NO CART IN THE MACHINE.") } }
            item { Gen1BoxButton("CHANGE CART", { model.open(Screen.ChooseCart(null)) }) }
        }
        return
    }

    val here = save.pcItems
    val stored = state.items
    val rows = listOf<Pair<String, () -> Unit>>(
        "WITHDRAW" to { mode = ItemMode.WITHDRAW },
        "DEPOSIT" to { mode = ItemMode.DEPOSIT },
    )
    val cursor = rememberCursorLayer(rows.size) { rows[it].second() }

    Box(Modifier.fillMaxSize()) {
        ScreenColumn {
            item {
                Gen1Frame(
                    Modifier.wrapContentWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    GbText("${save.trainerName.uppercase()}'S PC")
                    Spacer(Modifier.height(gen1Dp(2)))
                    rows.forEachIndexed { index, (label, take) ->
                        Gen1MenuRow(
                            label,
                            selected = cursor == index,
                            onSelect = {},
                            onConfirm = take,
                            enabled = if (index == 0) stored.isNotEmpty() else here.isNotEmpty(),
                        )
                    }
                }
            }
            item { Gen1BoxButton("BACK", { model.back() }) }
        }

        if (mode != ItemMode.MENU) {
            val intoApp = mode == ItemMode.DEPOSIT
            val list = if (intoApp) here else stored
            ItemListOverlay(
                title = if (intoApp) "TAKE WHAT?" else "PUT BACK WHAT?",
                items = list,
                onChoose = { stack ->
                    mode = ItemMode.MENU
                    model.prompt(
                        Prompt.ChooseQuantity(
                            title = stack.label,
                            max = stack.count,
                        ) { count -> model.transferItem(key, stack.id, count, intoApp) }
                    )
                },
                onCancel = { mode = ItemMode.MENU },
            )
        }
    }
}

private enum class ItemMode { MENU, WITHDRAW, DEPOSIT }

/** A list of items, drawn where the Pokémon lists are drawn. */
@Composable
private fun ItemListOverlay(
    title: String,
    items: List<ItemStack>,
    onChoose: (ItemStack) -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = true, menuSide = true),
    ) {
        Gen1Frame(Modifier.gen1MaxWidth().wrapContentWidth().padding(top = gen1Dp(5))) {
            GbText(title)
            Spacer(Modifier.height(gen1Dp(2)))
            if (items.isEmpty()) GbText("NOTHING HERE.", style = Gen1TextSmall)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                itemsIndexed(items) { _, stack ->
                    Gen1MenuRow(
                        stack.label,
                        selected = false,
                        onSelect = {},
                        onConfirm = { onChoose(stack) },
                        trailing = "x${stack.count}",
                    )
                }
                item { Gen1MenuRow("CANCEL", selected = false, onSelect = {}, onConfirm = onCancel) }
            }
        }
    }
}

/**
 * How many of a stack to move.
 *
 * The games ask this, and they are right to: an item is a count, so moving
 * one is a different act from moving all twenty, and there is no way to guess
 * which was meant.
 */
@Composable
fun QuantityPrompt(title: String, max: Int, onChoose: (Int) -> Unit, onCancel: () -> Unit) {
    var count by remember(title, max) { mutableIntStateOf(1) }
    Gen1Frame(Modifier.wrapContentWidth()) {
        GbText(title)
        Spacer(Modifier.height(gen1Dp(2)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.gen1Clickable { count = if (count <= 1) max else count - 1 }) {
                GbText("◀")
            }
            Spacer(Modifier.width(gen1Dp(4)))
            GbText("x$count", style = Gen1TextLarge)
            Spacer(Modifier.width(gen1Dp(4)))
            Box(Modifier.gen1Clickable { count = if (count >= max) 1 else count + 1 }) {
                GbText("▶")
            }
        }
        Spacer(Modifier.height(gen1Dp(3)))
        Row(horizontalArrangement = Arrangement.spacedBy(gen1Dp(3))) {
            Gen1Button("OK", { onChoose(count) })
            Gen1Button("ALL", { onChoose(max) })
            Gen1Button("CANCEL", onCancel)
        }
    }
}
