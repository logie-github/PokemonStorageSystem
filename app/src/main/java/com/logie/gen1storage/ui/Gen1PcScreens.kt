package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
    val tick = rememberConfirmTick()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            // One tap takes the row. The two-tap cursor-then-A rhythm belongs
            // to the swipe controls, where there is a cursor to move first.
            .gen1Clickable(enabled) {
                sound?.let { audio?.play(it) }
                tick()
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
        // The arrow goes in the room the box already holds for it. On a row of
        // its own under the text it grew the window by a line the moment the
        // last letter landed.
        Gen1TypedLines(lines, arrowWhenDone = more, onFinished = { finished = true })
        if (finished) actions()
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
    onDeposit: () -> Unit,
    /** Whether a trainer card is in the machine, which is what a transfer needs. */
    hasCard: Boolean,
    onView: () -> Unit,
    onChangeCart: () -> Unit,
    /**
     * The trade machine, when the player has switched it on. Null keeps the
     * row off the menu entirely rather than greying it out: a row nobody can
     * take is a question the menu does not need to raise.
     */
    onTrade: (() -> Unit)? = null,
    /** Sending a Generation I Pokémon on to Generation II. */
    onTimeCapsule: (() -> Unit)? = null,
    /** One Pokédex over every cartridge, which no cartridge could offer. */
    onDex: (() -> Unit)? = null,
    onOptions: (() -> Unit)? = null,
    /** What the two directions are called, which is the player's to choose. */
    outLabel: String,
    inLabel: String,
    /** Whether the machine's own caption is on screen. */
    showCaption: Boolean = true,
    /**
     * Whether the menu itself is on screen.
     *
     * Off whenever anything has been opened from it. The cartridge stacks its
     * windows and lets the one underneath show around the edges, which is how
     * a Game Boy showed you where you were; on a phone it reads as two menus
     * fighting over the same corner rather than as depth. One thing at a time
     * is what a modern screen expects, and B brings the last one back.
     */
    showMenu: Boolean = true,
    /** What the machine says while it is waiting: "What?", and its like. */
    caption: String = "What?",
    /**
     * An answer to something just done — a list that would not open, a
     * transfer refused. It is an event rather than a state, so it comes up at
     * the foot of the screen where messages come up, not hung off the menu.
     */
    notice: String? = null,
    overlay: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    /**
     * What the cursor is on, drawn in the half of the screen the menus do not
     * use — only where there is a half to spare.
     *
     * A folded phone has one column and this never appears on one. Opened up
     * there is a whole side sitting empty beside every list, and the thing
     * worth putting in it is whatever the cursor is pointing at, so that
     * walking a list reads the Pokémon as it goes rather than after it stops.
     */
    preview: @Composable (() -> Unit)? = null,
) {
    val rows: List<Triple<String, () -> Unit, SoundEffect>> = buildList {
        // The card first. It is the first thing that happens — the machine
        // asks for a card before it will do anything else — and it is what
        // the row under it depends on, so it leads the menu the way inserting
        // one leads everything else.
        add(Triple("TRAINER CARD", onChangeCart, SoundEffect.CURSOR))
        // Then the box, which is the row that always works and the one a
        // player wants most of the time. Taking a Pokémon out starts there
        // too: picking one in the box is how you say which one, and a
        // separate list saying the same thing was the same trip twice.
        add(Triple("VIEW PC BOX", onView, SoundEffect.SELECT))
        // Bringing one in needs somewhere to bring it from, so it is not
        // offered until there is a card in the machine. A row that always
        // answers "put a card in first" is a row that never did anything.
        if (hasCard) add(Triple("$inLabel PKMN", onDeposit, SoundEffect.SELECT))
        if (onTrade != null) add(Triple("TRADE", onTrade, SoundEffect.SELECT))
        // Beside the trade machine, because it is the same idea one game
        // later: this app is both ends of the cable.
        if (onTimeCapsule != null) {
            add(Triple("TIME CAPSULE", onTimeCapsule, SoundEffect.SELECT))
        }
        if (onDex != null) add(Triple("POKéDEX", onDex, SoundEffect.SELECT))
        // Everything the cartridge's PC does not have lives behind this row.
        if (onOptions != null) add(Triple("OPTIONS", onOptions, SoundEffect.OPTIONS))
    }

    // The menu and the machine's caption are one block in the top corner, in
    // that order: the caption is the menu idling, so it hangs off the bottom of
    // the menu rather than sitting at the foot of the screen with the whole
    // screen between it and the thing it is about. Then whatever list is open
    // over them, then an answer to something just done at the foot of the
    // screen, then the small window that opens on a chosen Pokémon above
    // everything.
    //
    // The box used to name itself in the far bottom corner. There is one box
    // and the player is standing in front of it, so the window was telling
    // them where they already were.
    //
    // Which edge they sit against is a setting. Everything mirrors together, so
    // the menu and the list keep overlapping the same way round either way.
    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .padding(gen1Dp(2)),
    ) {
        // Under everything: the windows keep their own edge and the pane fills
        // what is left, so neither has to know about the other.
        if (preview != null && isUnfolded()) {
            Row(Modifier.fillMaxSize()) {
                if (Gen1Layout.windowsOnRight) {
                    Box(Modifier.weight(1f)) { preview() }
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.weight(1f)) { preview() }
                }
            }
        }

        if (showMenu) {
            val selected = rememberCursorLayer(rows.size) { rows[it].second() }
            Column(
                Modifier.align(Gen1Layout.corner(top = true, menuSide = true)),
                horizontalAlignment = Gen1Layout.menuSide,
            ) {
                Gen1Frame(Modifier.wrapContentWidth()) {
                    rows.forEachIndexed { index, (label, action, sound) ->
                        Gen1MenuRow(label, selected == index, {}, action, sound = sound)
                    }
                }
                if (showCaption) {
                    Spacer(Modifier.height(gen1Dp(3)))
                    Gen1Frame(Modifier.gen1MaxWidth().wrapContentWidth()) {
                        Gen1TypedLines(listOf(caption))
                    }
                }
            }
        }

        // The list stays while a window on one of its Pokémon is open. It
        // used to be replaced by it, and choosing one in the box then took the
        // box off the screen and left a five-row menu in the corner with
        // nothing to say what it was about. The cartridge does the same thing
        // this does now: the submenu opens under the list it was opened from.
        overlay?.invoke()

        if (notice != null) {
            Box(
                Modifier.fillMaxSize(),
                // Where messages come up, against the same edge as the menu
                // and the windows that open under it.
                contentAlignment = Gen1Layout.corner(top = false, menuSide = true),
            ) {
                Gen1Frame(Modifier.gen1MaxWidth().wrapContentWidth(), opening = true) {
                    Gen1TypedLines(listOf(notice))
                }
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
    /**
     * Which row the cursor is on, as it moves — for the pane beside the list
     * on a screen wide enough to have one. Null while it is on CANCEL or the
     * button, where there is no Pokémon to be looking at.
     */
    onHighlight: (Int?) -> Unit = {},
) {
    // Once anything is ticked the list is choosing a set, so a row toggles
    // rather than opening the window on one Pokémon. That window is where a
    // set is started from, so there is no mode to find and none to leave.
    val choosingMany = marked.isNotEmpty()
    val take: (Int) -> Unit = { index -> if (choosingMany) onToggle(index) else onConfirm(index) }
    // Rows, then CANCEL at the foot of the list, then the button below the
    // window. In that order because that is the order they are drawn in: the
    // cursor walks to the bottom of the box and then out of it.
    val cancelAt = entries.size
    val actionAt = if (actionLabel != null) entries.size + 1 else -1
    val selected = rememberCursorLayer(entries.size + if (actionLabel != null) 2 else 1) { index ->
        when (index) {
            actionAt -> onAction()
            cancelAt -> onCancel()
            else -> take(index)
        }
    }
    // The cursor can walk past the bottom of what is drawn, so the list has to
    // follow it. Without this a swipe moves a cursor nobody can see.
    val scroll = rememberLazyListState()
    // Only for what is actually in the list. The button below it is not, and
    // asking the list to scroll to a row it does not have just pins it to the
    // bottom.
    LaunchedEffect(selected) {
        if (selected <= cancelAt) scroll.scrollToRow(selected)
        onHighlight(selected.takeIf { it < entries.size })
    }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = true, menuSide = true),
    ) {
        Column(
            Modifier.padding(top = gen1Dp(5)),
            horizontalAlignment = Gen1Layout.menuSide,
        ) {
            Gen1Frame(Modifier.gen1MaxWidth().wrapContentWidth(), opening = true) {
                if (entries.isEmpty()) {
                    GbText(emptyMessage)
                }
                LazyColumn(
                    Modifier.heightIn(max = 320.dp),
                    state = scroll,
                    userScrollEnabled = !LocalGen1Swipe.current,
                ) {
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
                    item {
                        Gen1MenuRow("CANCEL", selected == cancelAt, {}, onCancel)
                    }
                }
            }
            // Under the box rather than as a row inside it. A row in the list
            // reads as another Pokémon to pick; the thing that acts on
            // everything ticked is not one of them, so it is its own window
            // sitting below the one it acts on.
            if (actionLabel != null) {
                Spacer(Modifier.height(gen1Dp(3)))
                Gen1BoxButton(actionLabel, onAction, selected = selected == actionAt)
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
    /** What the way out is called, where the menu is a step rather than a question. */
    cancelLabel: String = "CANCEL",
) {
    val selected = rememberCursorLayer(actions.size + 1) { index ->
        val action = actions.getOrNull(index)
        if (action == null) onCancel() else if (action.enabled) action.onAction()
    }
    // Under the list it was opened from, against the same edge: the list is
    // still there — it is what this window is about — and the far corner of
    // that edge is where a window that answers something belongs. In the top
    // corner it simply covered the list it came from.
    Box(
        Modifier.fillMaxSize().padding(gen1Dp(2)),
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
            Gen1MenuRow(cancelLabel, selected == actions.size, {}, onCancel)
            if (note != null) GbText(note, style = Gen1TextSmall)
        }
    }
}

