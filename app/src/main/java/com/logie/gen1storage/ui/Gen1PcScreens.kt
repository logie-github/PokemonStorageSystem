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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.logie.gen1storage.sound.LocalGen1Audio
import com.logie.gen1storage.sound.SoundEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
    /** Ticked, for a row that is one of several the next action will act on. */
    mark: Boolean = false,
    /** What taking this row sounds like. Null for rows that are not a choice. */
    sound: SoundEffect? = SoundEffect.CURSOR,
) {
    val audio = LocalGen1Audio.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            // One tap takes the row. The two-tap cursor-then-A rhythm belongs
            // to the swipe controls, where there is a cursor to move first.
            .gen1Clickable(enabled) {
                sound?.let { audio?.play(it) }
                onSelect()
                onConfirm()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp)) {
            if (selected && enabled) GbText("▶", style = Gen1Text)
        }
        // Its own column rather than part of the label, so the names still
        // line up whether or not anything in the list is ticked.
        if (mark) Box(Modifier.width(gen1Dp(6))) { GbText("*", style = Gen1Text) }
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
    var finished by remember(lines) { mutableStateOf(false) }
    Gen1Frame(modifier.fillMaxWidth(), opening = true) {
        Gen1TypedLines(lines, onFinished = { finished = true })
        if (finished) {
            if (more) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Gen1BlinkingArrow()
                }
            }
            actions()
        }
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
    /** What the two directions are called, which is the player's to choose. */
    outLabel: String,
    inLabel: String,
    /** False while a message is showing that would be drawn over it anyway. */
    showBox: Boolean = true,
    message: String = "What?",
    overlay: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val rows: List<Triple<String, () -> Unit, SoundEffect>> = buildList {
        // Named for which way the Pokémon is going, not for which side of
        // the machine is doing it: OUT leaves this PC for the cartridge, IN
        // comes the other way.
        add(Triple("$outLabel PKMN", onWithdraw, SoundEffect.SELECT))
        add(Triple("$inLabel PKMN", onDeposit, SoundEffect.SELECT))
        add(Triple("VIEW BOXES", onView, SoundEffect.SELECT))
        add(Triple("CHANGE CART", onChangeCart, SoundEffect.CURSOR))
        // Everything the cartridge's PC does not have lives behind this row.
        if (onOptions != null) add(Triple("OPTIONS", onOptions, SoundEffect.OPTIONS))
    }

    // Four layers, and the order is the whole point: the menu, then whatever
    // list is open over it, then the box window, then the message over that,
    // then the small window that opens on a chosen Pokémon above everything.
    // The message sits over the box window because when a box is empty the
    // message is the thing that matters.
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
            rows.forEachIndexed { index, (label, action, sound) ->
                Gen1MenuRow(label, selected == index, {}, action, sound = sound)
            }
        }

        overlay?.invoke()

        if (showBox) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Gen1Layout.corner(top = false, menuSide = true),
            ) {
                // Tap to change box, hold to rename it — the same window,
                // because it is the same subject, and there is nowhere better
                // to put a rename than on the thing being renamed.
                Gen1Frame(
                    Modifier
                        .wrapContentWidth()
                        .gen1HoldRegion()
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
        }

        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Gen1Layout.corner(top = false, menuSide = false),
        ) {
            // Sized to its text, and drawn over the box window rather than
            // beside it: when a box is empty the message is what matters,
            // and that is where the cartridge puts it.
            Gen1Frame(Modifier.gen1MaxWidth().wrapContentWidth()) {
                Gen1TypedLines(listOf(message))
            }
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
    /** The rows ticked for a transfer of several at once. */
    marked: Set<Int> = emptySet(),
    onToggle: (Int) -> Unit = {},
    /** The row that acts on everything ticked. Absent while nothing is. */
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    // Once anything is ticked the list is choosing a set, so a row toggles
    // rather than opening the window on one Pokémon. That window is where a
    // set is started from, so there is no mode to find and none to leave.
    val choosingMany = marked.isNotEmpty()
    val take: (Int) -> Unit = { index -> if (choosingMany) onToggle(index) else onConfirm(index) }
    val extras = if (actionLabel != null) 2 else 1
    // One past the end is CANCEL, so the cursor can reach it like any row.
    val selected = rememberCursorLayer(entries.size + extras) { index ->
        when {
            index < entries.size -> take(index)
            actionLabel != null && index == entries.size -> onAction()
            else -> onCancel()
        }
    }
    // The cursor can walk past the bottom of what is drawn, so the list has to
    // follow it. Without this a swipe moves a cursor nobody can see.
    val scroll = rememberLazyListState()
    LaunchedEffect(selected) { scroll.animateScrollToItem(selected) }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = true, menuSide = true),
    ) {
        Gen1Frame(
            Modifier
                .gen1MaxWidth()
                .wrapContentWidth()
                .padding(top = gen1Dp(5)),
            opening = true,
        ) {
            if (entries.isEmpty()) {
                GbText(emptyMessage)
            }
            LazyColumn(Modifier.heightIn(max = 320.dp), state = scroll) {
                itemsIndexed(entries) { index, row ->
                    if (row.header != null) {
                        if (index > 0) Spacer(Modifier.height(gen1Dp(3)))
                        GbText(row.header)
                    }
                    Gen1MenuRow(
                        row.name,
                        selected == index,
                        {},
                        { take(index) },
                        trailing = row.trailing,
                        mark = index in marked,
                    )
                }
                if (actionLabel != null) {
                    item {
                        Spacer(Modifier.height(gen1Dp(3)))
                        Gen1MenuRow(actionLabel, selected == entries.size, {}, onAction)
                    }
                }
                item {
                    Gen1MenuRow("CANCEL", selected == entries.size + extras - 1, {}, onCancel)
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
        Gen1Frame(Modifier.wrapContentWidth(), opening = true) {
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
    val scroll = rememberLazyListState()
    LaunchedEffect(selected) { scroll.animateScrollToItem(selected) }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = true, menuSide = true),
    ) {
        Gen1Frame(Modifier.wrapContentWidth().padding(top = gen1Dp(5)), opening = true) {
            LazyColumn(Modifier.heightIn(max = 380.dp), state = scroll) {
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
