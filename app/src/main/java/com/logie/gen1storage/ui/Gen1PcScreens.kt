package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
            // One tap takes the row. The two-tap cursor-then-A rhythm belongs
            // to the swipe controls, where there is a cursor to move first.
            .gen1Clickable(enabled) { onSelect(); onConfirm() },
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
 * WITHDRAW and DEPOSIT are the cartridge's own two rows. VIEW BOXES is the
 * browse-and-rearrange side, and RELEASE lives in the window that opens on a
 * chosen Pokémon rather than on the menu, where it was one tap from
 * everything else.
 *
 * The box is changed by tapping the BOX No. window itself, which is why there
 * is no row for it.
 */
@Composable
fun StorageSystemScreen(
    boxLabel: String,
    onWithdraw: () -> Unit,
    onDeposit: () -> Unit,
    onView: () -> Unit,
    onChangeCart: () -> Unit,
    onChangeBox: () -> Unit,
    onRenameBox: () -> Unit,
    onOptions: (() -> Unit)? = null,
    message: String = "What?",
    overlay: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val rows: List<Pair<String, () -> Unit>> = buildList {
        add("WITHDRAW PKMN" to onWithdraw)
        add("DEPOSIT PKMN" to onDeposit)
        add("VIEW BOXES" to onView)
        add("CHANGE CART" to onChangeCart)
        // Everything the cartridge's PC does not have lives behind this row.
        if (onOptions != null) add("OPTIONS" to onOptions)
    }

    // Four layers, and the order is the whole point: the menu, then whatever
    // list is open over it, then the message window, then the small window that
    // opens on a chosen Pokémon over all of it. The message has to stay
    // readable behind a list, and the action window has to sit above both.
    //
    // Which edge they sit against is a setting. Everything mirrors together, so
    // the menu and the list keep overlapping the same way round either way.
    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .padding(gen1Dp(2)),
    ) {
        val selected = rememberCursorLayer(rows.size) { rows[it].second() }
        Gen1Frame(
            Modifier
                .align(Gen1Layout.corner(top = true, menuSide = true))
                .wrapContentWidth()
        ) {
            rows.forEachIndexed { index, (label, action) ->
                Gen1MenuRow(label, selected == index, {}, action)
            }
        }

        overlay?.invoke()

        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Gen1Layout.corner(top = false, menuSide = false),
        ) {
            Gen1Frame(Modifier.wrapContentWidth()) { GbText(message, style = Gen1Text) }
        }

        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Gen1Layout.corner(top = false, menuSide = true),
        ) {
            // Tap to change box, hold to rename it — the same window, because
            // it is the same subject, and there is nowhere better to put a
            // rename than on the thing being renamed.
            Gen1Frame(
                Modifier
                    .wrapContentWidth()
                    .pointerInput(onChangeBox, onRenameBox) {
                        detectTapGestures(
                            onTap = { onChangeBox() },
                            onLongPress = { onRenameBox() },
                        )
                    }
            ) {
                GbText(boxLabel.uppercase())
            }
        }

        action?.invoke()
    }
}

/** How much of the screen a list may cover before it has to scroll sideways. */
private const val LIST_MAX_WIDTH = 0.78f

/**
 * One row of a Pokémon list. [header] labels the group this row starts, which
 * is how the transfer list keeps a save's party and the PC box apart while
 * still being one list with one cursor.
 */
data class MonRow(val name: String, val trailing: String, val header: String? = null)

/**
 * The list a WITHDRAW, DEPOSIT or VIEW opens, drawn over the menu it came from.
 *
 * It opens from the top right and covers most of the menu without hiding it —
 * the first letters of each menu row stay visible down the left, which is what
 * the cartridge does and what makes the two read as one screen rather than as
 * two. The window that opens on a chosen Pokémon is a separate layer above
 * this one, so it is not clipped by the list's own bounds.
 */
@Composable
fun MonListOverlay(
    entries: List<MonRow>,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
    emptyMessage: String,
) {
    // One past the end is CANCEL, so the cursor can reach it like any row.
    val selected = rememberCursorLayer(entries.size + 1) { index ->
        if (index < entries.size) onConfirm(index) else onCancel()
    }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = true, menuSide = true),
    ) {
        Gen1Frame(
            Modifier
                .widthIn(max = (LocalConfiguration.current.screenWidthDp * LIST_MAX_WIDTH).dp)
                .wrapContentWidth()
                .padding(top = gen1Dp(5))
        ) {
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
                        {},
                        { onConfirm(index) },
                        trailing = row.trailing,
                    )
                }
                item {
                    Gen1MenuRow("CANCEL", selected == entries.size, {}, onCancel)
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
    onCancel: () -> Unit,
    note: String? = null,
) {
    val selected = rememberCursorLayer(actions.size + 1) { index ->
        val action = actions.getOrNull(index)
        if (action == null) onCancel() else if (action.enabled) action.onAction()
    }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = false, menuSide = true),
    ) {
        Gen1Frame(Modifier.wrapContentWidth()) {
            actions.forEachIndexed { index, entry ->
                Gen1MenuRow(
                    entry.label,
                    selected == index,
                    {},
                    entry.onAction,
                    enabled = entry.enabled,
                )
            }
            Gen1MenuRow("CANCEL", selected == actions.size, {}, onCancel)
            if (note != null) GbText(note, style = Gen1TextSmall)
        }
    }
}

/** The CHANGE BOX list. */
@Composable
fun ChangeBoxOverlay(
    boxes: List<Triple<Int, String, String>>,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val selected = rememberCursorLayer(boxes.size + 1) { index ->
        val box = boxes.getOrNull(index)
        if (box == null) onCancel() else onConfirm(box.first)
    }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = true, menuSide = true),
    ) {
        Gen1Frame(Modifier.wrapContentWidth().padding(top = gen1Dp(5))) {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                itemsIndexed(boxes) { index, (number, label, count) ->
                    Gen1MenuRow(
                        label,
                        selected == index,
                        {},
                        { onConfirm(number) },
                        trailing = count,
                    )
                }
                item {
                    Gen1MenuRow("CANCEL", selected == boxes.size, {}, onCancel)
                }
            }
        }
    }
}
