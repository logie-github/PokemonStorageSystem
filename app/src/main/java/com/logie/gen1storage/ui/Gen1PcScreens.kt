package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .gen1Clickable(enabled) { if (selected) onConfirm() else onSelect() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp)) {
            if (selected && enabled) GbText("▶", style = Gen1Text)
        }
        GbText(
            label.uppercase(),
            modifier = Modifier.weight(1f),
            style = Gen1Text.copy(color = if (enabled) Gen1Palette.Ink else Gen1Palette.Shadow),
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
 * The storage system's menu.
 *
 * WITHDRAW and DEPOSIT are the cartridge's own two rows. VIEW POKéMON is the
 * browse-and-rearrange side, and RELEASE lives in the window that opens on a
 * chosen Pokémon rather than on the menu, where it was one tap from
 * everything else.
 *
 * The box is changed by tapping the BOX No. window itself, which is why there
 * is no row for it.
 */
@Composable
fun StorageSystemScreen(
    boxNumber: Int,
    boxName: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    onWithdraw: () -> Unit,
    onDeposit: () -> Unit,
    onView: () -> Unit,
    onChangeBox: () -> Unit,
    onOptions: (() -> Unit)? = null,
    message: String = "What?",
    overlay: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val rows: List<Pair<String, () -> Unit>> = buildList {
        add("WITHDRAW PKMN" to onWithdraw)
        add("DEPOSIT PKMN" to onDeposit)
        add("VIEW POKéMON" to onView)
        // Everything the cartridge's PC does not have lives behind this row.
        if (onOptions != null) add("OPTIONS" to onOptions)
    }

    // Four layers, bottom to top: the menu and the box window, then whatever
    // list is open over the bottom right, then the message window over its
    // bottom left, and finally the small action window over all of it. The
    // order is the whole point — the message has to stay readable behind a
    // list, and the action window has to sit above both.
    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.End) {
            // The window sits against the right edge, as the cartridge puts it;
            // the rows inside it stay left-aligned so the cursor column lines up.
            Gen1Frame(Modifier.fillMaxWidth(0.72f)) {
                rows.forEachIndexed { index, (label, action) ->
                    Gen1MenuRow(label, selected == index, { onSelect(index) }, action)
                }
            }
            Spacer(Modifier.weight(1f))
            Gen1Frame(Modifier.gen1Clickable(onClick = onChangeBox)) {
                GbText("BOX No. $boxNumber")
                GbText(boxName.uppercase(), style = Gen1TextSmall)
                GbText("TAP TO CHANGE", style = Gen1TextSmall)
            }
        }

        overlay?.invoke()

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            // Sized to its text rather than to the row: a one-word message in a
            // window most of the screen wide reads as a bug.
            Gen1Frame(Modifier.wrapContentWidth()) { GbText(message, style = Gen1Text) }
        }

        action?.invoke()
    }
}

/**
 * One row of a Pokémon list. [header] labels the group this row starts, which
 * is how the transfer list keeps a save's party and the PC box apart while
 * still being one list with one cursor.
 */
data class MonRow(val name: String, val trailing: String, val header: String? = null)

/**
 * The list a WITHDRAW, DEPOSIT or VIEW opens, drawn over the menu it came from.
 *
 * Pinned to the bottom right, which is where the cartridge puts it. The window
 * that opens on a chosen Pokémon is a separate layer above this one, so that
 * it is not clipped by the list's own bounds.
 */
@Composable
fun MonListOverlay(
    entries: List<MonRow>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
    emptyMessage: String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Gen1Frame(Modifier.wrapContentWidth()) {
            if (entries.isEmpty()) {
                GbText(emptyMessage)
            }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                itemsIndexed(entries) { index, row ->
                    if (row.header != null) {
                        if (index > 0) Spacer(Modifier.height(6.dp))
                        GbText(row.header, style = Gen1TextSmall)
                    }
                    Gen1MenuRow(
                        row.name,
                        selected == index,
                        { onSelect(index) },
                        { onConfirm(index) },
                        trailing = row.trailing,
                    )
                }
                item {
                    Gen1MenuRow("CANCEL", selected == entries.size, { onSelect(entries.size) }, onCancel)
                }
            }
        }
    }
}

/** One entry in the window that opens on a chosen Pokémon. */
data class MonAction(
    val label: String,
    val onAction: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * The small window that opens on a chosen Pokémon.
 *
 * Row-driven rather than fixed, because what a Pokémon can have done to it
 * depends on where it is: one in the PC can be moved and released, one still
 * in a save can only be deposited or looked at.
 */
@Composable
fun MonActionOverlay(
    actions: List<MonAction>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onCancel: () -> Unit,
    note: String? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Gen1Frame(Modifier.wrapContentWidth()) {
            actions.forEachIndexed { index, entry ->
                Gen1MenuRow(
                    entry.label,
                    selected == index,
                    { onSelect(index) },
                    entry.onAction,
                    enabled = entry.enabled,
                )
            }
            Gen1MenuRow("CANCEL", selected == actions.size, { onSelect(actions.size) }, onCancel)
            if (note != null) GbText(note, style = Gen1TextSmall)
        }
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Gen1Frame(Modifier.wrapContentWidth()) {
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
    }
}
