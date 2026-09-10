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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The PC as the games lay it out: a menu window pinned to one corner, a
 * dialogue box along the bottom, and submenus that open over the top of what
 * they belong to rather than replacing it.
 */

/** One row of a Generation I menu: cursor, label, and an optional right column. */
@Composable
fun Gen1MenuRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onConfirm: () -> Unit,
    trailing: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled) { if (selected) onConfirm() else onSelect() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp)) {
            if (selected && enabled) GbText("▶", style = Gen1Text)
        }
        GbText(
            label.uppercase(),
            modifier = Modifier.weight(1f),
            style = Gen1Text.copy(color = if (enabled) Gen1Palette.Ink else Gen1Palette.Dark),
            maxLines = 1,
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            GbText(trailing, style = Gen1Text)
        }
    }
}

/** The dialogue box the games keep along the bottom of the screen. */
@Composable
fun Gen1DialogueBox(
    lines: List<String>,
    modifier: Modifier = Modifier,
    more: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    Gen1Frame(modifier.fillMaxWidth()) {
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GbText(line, modifier = Modifier.weight(1f))
                if (more && line == lines.last()) GbText("▼", style = Gen1Text)
            }
        }
        actions()
    }
}

/**
 * The screen a save's PC opens on. Structure follows the cartridge:
 * SOMEONE'S PC is the storage system, the trainer's own PC is their party and
 * boxes, and LOG OFF backs out.
 */
@Composable
fun PcMainScreen(
    trainerName: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    onStorageSystem: () -> Unit,
    onTrainerPc: () -> Unit,
    onLogOff: () -> Unit,
    message: List<String>,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Gen1Palette.Surround)
            .padding(12.dp),
    ) {
        Gen1Frame(Modifier.fillMaxWidth(0.86f)) {
            Gen1MenuRow("SOMEONE'S PC", selected == 0, { onSelect(0) }, onStorageSystem)
            Gen1MenuRow("$trainerName's PC", selected == 1, { onSelect(1) }, onTrainerPc)
            Gen1MenuRow("LOG OFF", selected == 2, { onSelect(2) }, onLogOff)
        }
        Spacer(Modifier.weight(1f))
        Gen1DialogueBox(message, more = true)
    }
}

/**
 * The storage system's own menu, with the box number in its small window at
 * the bottom right and "What?" in the dialogue box beside it.
 */
@Composable
fun StorageSystemScreen(
    boxNumber: Int,
    boxName: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    onWithdraw: () -> Unit,
    onDeposit: () -> Unit,
    onMove: () -> Unit,
    onRelease: () -> Unit,
    onChangeBox: () -> Unit,
    onExit: () -> Unit,
    overlay: @Composable (() -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Gen1Palette.Surround)
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Gen1Frame(Modifier.fillMaxWidth(0.72f)) {
                Gen1MenuRow("WITHDRAW PKMN", selected == 0, { onSelect(0) }, onWithdraw)
                Gen1MenuRow("DEPOSIT PKMN", selected == 1, { onSelect(1) }, onDeposit)
                Gen1MenuRow("MOVE PKMN", selected == 2, { onSelect(2) }, onMove)
                Gen1MenuRow("RELEASE PKMN", selected == 3, { onSelect(3) }, onRelease)
                Gen1MenuRow("CHANGE BOX", selected == 4, { onSelect(4) }, onChangeBox)
                Gen1MenuRow("SEE YA!", selected == 5, { onSelect(5) }, onExit)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Gen1Frame(Modifier.weight(1f)) { GbText("What?", style = Gen1Text) }
                Gen1Frame(Modifier.width(160.dp)) {
                    GbText("BOX No. $boxNumber")
                    GbText(boxName.uppercase(), style = Gen1TextSmall)
                }
            }
        }
        overlay?.invoke()
    }
}

/** The list a WITHDRAW or DEPOSIT opens, drawn over the menu it came from. */
@Composable
fun MonListOverlay(
    entries: List<Pair<String, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
    emptyMessage: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(56.dp))
        Gen1Frame(Modifier.fillMaxWidth().padding(start = 40.dp)) {
            if (entries.isEmpty()) {
                GbText(emptyMessage)
            }
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                itemsIndexed(entries) { index, (name, level) ->
                    Gen1MenuRow(name, selected == index, { onSelect(index) }, { onConfirm(index) }, trailing = level)
                }
                item {
                    Gen1MenuRow("CANCEL", selected == entries.size, { onSelect(entries.size) }, onCancel)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
        }
    }
}

/** The small WITHDRAW / STATS / CANCEL window that opens on a chosen Pokémon. */
@Composable
fun MonActionOverlay(
    actionLabel: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAction: () -> Unit,
    onStats: () -> Unit,
    onCancel: () -> Unit,
    actionEnabled: Boolean = true,
    note: String? = null,
) {
    Gen1Frame(Modifier.width(230.dp)) {
        Gen1MenuRow(actionLabel, selected == 0, { onSelect(0) }, onAction, enabled = actionEnabled)
        Gen1MenuRow("STATS", selected == 1, { onSelect(1) }, onStats)
        Gen1MenuRow("CANCEL", selected == 2, { onSelect(2) }, onCancel)
        if (note != null) GbText(note, style = Gen1TextSmall)
    }
}

/** The CHANGE BOX list. */
@Composable
fun ChangeBoxOverlay(
    boxes: List<Triple<Int, String, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(40.dp))
        Gen1Frame(Modifier.fillMaxWidth().padding(start = 24.dp)) {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                itemsIndexed(boxes) { index, (number, name, count) ->
                    Gen1MenuRow(
                        "BOX $number $name".trim(),
                        selected == index,
                        { onSelect(index) },
                        { onConfirm(number) },
                        trailing = count,
                    )
                }
                item {
                    Gen1MenuRow("CANCEL", selected == boxes.size, { onSelect(boxes.size) }, onCancel)
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
