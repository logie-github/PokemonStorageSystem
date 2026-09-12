package com.logie.gen1storage.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.sprites.SpriteSet
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassification
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.sound.LocalGen1Audio
import com.logie.gen1storage.sound.SoundEffect
import com.logie.gen1storage.storage.StorageLayout
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.storage.StoredPokemon
import com.logie.gen1storage.sync.LoadedSave
import com.logie.gen1storage.sync.RemoteSave
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.transfer.SaveLocation
import com.logie.gen1storage.transfer.WithdrawTarget
import java.time.Instant

/** One party slot the deposit list can offer, and the save it belongs to. */
/** One place in a save a Pokémon can be taken from, and what is there. */
private data class PartyRow(
    val key: String,
    val location: SaveLocation,
    /** 0 for the party, otherwise the box number — what groups the list. */
    val area: Int,
    val slot: Int,
    val mon: Gen1Pokemon,
    val save: Gen1RecompSave,
)

/** The one-line form used in every Generation I list. */
fun pokemonRowLabel(pokemon: Gen1Pokemon): String = pokemon.displayName.uppercase()

fun pokemonRowLevel(pokemon: Gen1Pokemon): String = ":L${pokemon.level}"

/**
 * The app's top level is the PC's own storage menu. Everything this app adds
 * that the cartridge never had — access to saves, sprites, colours,
 * diagnostics — sits behind OPTIONS.
 */
@Composable
fun StorageHomeScreen(state: UiState, model: StorageViewModel) {
    StorageSystemScreen(
        state = state,
        model = model,
        onOptions = { model.open(Screen.Options) },
    )
}

@Composable
fun LinkScreen(state: UiState, model: StorageViewModel) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val ready = SyncApi.normalizeCode(first) != null && SyncApi.normalizeCode(second) != null
    // One thing to take on this screen once both codes are in. The fields are
    // the keyboard's; the button is the cursor's, as it is everywhere else.
    val at = rememberCursorLayer(1) { if (ready && !state.linking) model.link(first, second) }

    ScreenColumn {
        item {
            Gen1Frame {
                GbText("SAVE SYNC")
            }
        }
        item {
            Gen1Frame {
                CodeField("FIRST CODE", first) { first = it }
                Spacer(Modifier.height(10.dp))
                CodeField("SECOND CODE", second) { second = it }
                Spacer(Modifier.height(12.dp))
                Gen1Button(
                    if (state.linking) "LINKING..." else "LINK THIS DEVICE",
                    { model.link(first, second) },
                    enabled = ready && !state.linking,
                    selected = at == 0,
                )
                if (!ready) {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun CodeField(label: String, value: String, onChange: (String) -> Unit) {
    GbText(label, style = Gen1TextSmall)
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == '-' }.take(9)) },
        singleLine = true,
        placeholder = { GbText("0000-0000", style = Gen1TextSmall) },
        textStyle = Gen1Text,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Gen1Palette.Panel,
            unfocusedContainerColor = Gen1Palette.Panel,
            focusedTextColor = Gen1Palette.Ink,
            unfocusedTextColor = Gen1Palette.Ink,
            focusedIndicatorColor = Gen1Palette.Ink,
            unfocusedIndicatorColor = Gen1Palette.Shadow,
            cursorColor = Gen1Palette.Ink,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The storage system itself, in the shape the games give it.
 *
 * There is no save browser in this app, so a save is only ever named because a
 * transfer needs one: DEPOSIT asks which save it is taking from, WITHDRAW asks
 * which save it is putting into. With ALL POKéMON on, every save's party is in
 * the deposit list at once and there is nothing to ask.
 */
@Composable
fun StorageSystemScreen(
    state: UiState,
    model: StorageViewModel,
    onOptions: (() -> Unit)? = null,
) {
    var mode by remember { mutableStateOf(PcMode.MENU) }
    var chosen by remember(mode) { mutableStateOf<Int?>(null) }
    // Which rows of the open list a transfer will act on. Empty is the plain
    // one-at-a-time PC: a tap opens the window on that Pokémon, and SELECT in
    // that window is what turns the list into a set.
    var marked by remember(mode) { mutableStateOf(emptySet<Int>()) }
    // VIEW is a grid, so what is picked there is a spot in the box rather
    // than a row in a list, and the two cannot share one number.
    var gridSlot by remember(mode) { mutableStateOf<Int?>(null) }
    // Picked up by MOVE and waiting for somewhere to go. A drag does the same
    // journey in one gesture and never sets this.
    var heldUid by remember(mode) { mutableStateOf<String?>(null) }
    // Cleared after a transfer, so the ticks do not outlive what they pointed
    // at — every index shifts the moment something leaves a list.
    //
    // The list itself closes here too, and only here: the count is raised in
    // the same breath as the result is said, which is after the ball has
    // finished. So the order the player sees is the animation, then what it
    // did, then the PC's own menu waiting underneath — rather than the menu
    // reappearing behind a Pokémon still on its way out.
    LaunchedEffect(state.transfers) {
        marked = emptySet()
        chosen = null
        gridSlot = null
        mode = PcMode.MENU
    }
    fun toggle(index: Int) {
        marked = if (index in marked) marked - index else marked + index
    }

    // Standing on the PC's own menu rather than in something opened from it.
    val atMenu = mode == PcMode.MENU
    val box = state.storage.boxes.firstOrNull()
    val thisBoxLabel = box?.label ?: "THE PC"
    val boxContents = box?.contents.orEmpty()
    // WITHDRAW offers everything the app holds; VIEW is about the box that is
    // open, because that is the one being rearranged.
    val stored = if (mode == PcMode.WITHDRAW) {
        state.storage.boxes.flatMap { it.contents }
    } else {
        boxContents
    }

    // ALL POKéMON puts every save in the lists at once, so there is nothing
    // to choose; otherwise the PC works with one cartridge at a time.
    val needsCart = !state.showAllSaves && state.save(state.activeSaveKey) == null
    var refusal by remember(mode) { mutableStateOf<String?>(null) }


    // A save's boxes, and only its boxes. A transfer never touches a party:
    // the party is what the player is actually carrying, the games guard it
    // with the last-Pokémon rule, and there is nothing here worth the risk of
    // reaching into it.
    val depositRows = buildList {
        val keys = if (state.showAllSaves) state.saves.map { it.key } else listOfNotNull(state.activeSaveKey)
        keys.forEach { key ->
            val save = state.save(key)?.save ?: return@forEach
            save.boxes.forEachIndexed { boxIndex, box ->
                box.forEachIndexed { index, mon ->
                    add(PartyRow(key, SaveLocation.Box(boxIndex + 1, index + 1), boxIndex + 1, index, mon, save))
                }
            }
        }
    }

    /** The label a group starts under, or null once that group is open. */
    fun depositHeader(index: Int): String? {
        val row = depositRows[index]
        val previous = depositRows.getOrNull(index - 1)
        if (previous != null && previous.key == row.key && previous.area == row.area) return null
        val where = "BOX ${row.area}"
        return if (state.showAllSaves) "${row.save.trainerName.uppercase()} $where" else where
    }

    // An empty list has nothing to draw a window around, so it speaks through
    // the message window instead — which is what the cartridge does.
    // VIEW draws the box either way, so an empty one speaks for itself: a
    // grid of empty spots is already the answer the message would give.
    val emptiness = when (mode) {
        PcMode.WITHDRAW -> "What? There are no POKéMON here!".takeIf { stored.isEmpty() }
        PcMode.DEPOSIT -> "There are no POKéMON here.".takeIf { depositRows.isEmpty() }
        else -> null
    }

    /**
     * The status screen for a stored Pokémon, found by uid.
     *
     * WITHDRAW lists every box at once, so the row's position in the list is
     * not its slot in the open box — looking one up by where it actually lives
     * is the only reading that holds for both lists.
     */
    fun statusOf(uid: String, transfer: StatusTransfer?): Screen.Status? {
        val boxIndex = state.storage.find(uid)?.first ?: return null
        val slot = state.storage.boxes.getOrNull(boxIndex - 1)
            ?.contents?.indexOfFirst { it.uid == uid }
            ?.takeIf { it >= 0 } ?: return null
        return Screen.Status(null, boxIndex, slot, transfer)
    }

    /**
     * "SEND PIKACHU TO RED?" — the one question a transfer asks.
     *
     * Nothing moves and nothing is written until it is answered, so the ball
     * going up is the player's own doing rather than the first they hear of
     * it. Where it is going was never in question; whether to send it is.
     */
    fun confirmSend(
        what: String,
        where: String,
        speciesId: String?,
        gameVersionId: String?,
        motion: TransferMotion,
        alsoSpeciesIds: List<String?> = emptyList(),
        send: () -> Unit,
    ) {
        model.askToSend(
            TransferScene(
                speciesId = speciesId,
                gameVersionId = gameVersionId,
                name = what,
                destination = where,
                alsoSpeciesIds = alsoSpeciesIds,
                motion = motion,
            ),
            "${motion.verb} $what TO $where?",
            send,
        )
    }

    /**
     * Takes them out, the way the cartridge does: no question about where.
     *
     * A withdrawal goes to the box the save has open, exactly as a deposit
     * goes to the box this PC has open. The cartridge is only asked for when
     * there is not one in the machine at all.
     */
    fun startWithdraw(uids: List<String>) {
        chosen = null
        val active = state.activeSaveKey
        val loaded = state.save(active)?.save
        if (active == null || loaded == null) {
            model.open(Screen.ChooseCart(null, uids))
            return
        }
        // The first one stands for the set when there are several; there is
        // one sprite's worth of room and it is better than none.
        val first = uids.firstOrNull()?.let { state.storage.find(it)?.second }
        val what = if (uids.size == 1) first?.pokemon?.displayName?.uppercase() ?: "IT"
        else "${uids.size} POKéMON"
        confirmSend(
            what = what,
            where = loaded.trainerName.uppercase(),
            speciesId = first?.pokemon?.speciesId,
            gameVersionId = first?.provenance?.gameVersion,
            motion = TransferMotion.OUT,
            alsoSpeciesIds = uids.drop(1).mapNotNull {
                state.storage.find(it)?.second?.pokemon?.speciesId
            },
        ) {
            model.withdrawToSave(uids, active, WithdrawTarget.Box(loaded.currentBox))
        }
    }

    // The list and the window that opens on a chosen Pokémon are separate
    // layers on the screen, so they are built as separate slots here rather
    // than nested — the message window belongs between them.
    val pick: PartyRow? = chosen?.takeIf { mode == PcMode.DEPOSIT }?.let { depositRows.getOrNull(it) }
    val storedPick = when (mode) {
        PcMode.WITHDRAW -> chosen?.let { stored.getOrNull(it) }
        // The grid's window opens on the second tap, so it follows `chosen`
        // while the cursor on the grid itself stays on `gridSlot`.
        PcMode.VIEW -> chosen?.let { box?.slots?.getOrNull(it) }
        else -> null
    }

    StorageSystemScreen(
        outLabel = state.outLabel,
        inLabel = state.inLabel,
        boxLabel = thisBoxLabel,
        // The caption and the box's name belong to the menu and go when the
        // menu does: a list open over them, a question in the middle of the
        // screen, or a message come up at the foot of it. The name goes under
        // a message rather than being drawn beneath it — half a window showing
        // past the edge of another reads as a mistake.
        notice = refusal ?: emptiness,
        showCaption = atMenu && refusal == null && emptiness == null,
        showBox = atMenu &&
            refusal == null &&
            emptiness == null &&
            state.prompt == null &&
            state.transferScene == null,
        // Both directions need a cartridge in the machine. If there is not one
        // yet, that is the only question worth asking, so it gets the whole
        // screen rather than a window over this one.
        // The cartridge checks before it opens a list, not after: a list you
        // cannot act on is worse than being told so on the menu you are
        // standing on. Both guards are `BillsPCWithdraw` and `BillsPCDeposit`
        // in pokered, in the same order.
        onWithdraw = {
            refusal = null
            val target = state.save(state.activeSaveKey)?.save
            when {
                target == null -> model.open(Screen.ChooseCart(null))
                state.storage.total == 0 -> refusal = "What? There are no POKéMON here!"
                target.boxFreeSlots(target.currentBox) <= 0 ->
                    refusal = "You can't take any more POKéMON."
                else -> mode = PcMode.WITHDRAW
            }
        },
        onDeposit = {
            refusal = null
            when {
                needsCart -> model.open(Screen.ChooseCart(null))
                (box?.freeSlots ?: 0) <= 0 -> refusal = "Oops! This Box is full of POKéMON."
                else -> mode = PcMode.DEPOSIT
            }
        },
        onView = { mode = PcMode.VIEW },
        onChangeCart = { model.open(Screen.ChooseCart(null)) },
        onRenameBox = { model.prompt(Prompt.RenameBox(StorageLayout.THE_BOX)) },
        onOptions = onOptions,
        caption = when {
            !state.linked -> "Link this device in OPTIONS."
            needsCart -> "No cart in the machine."
            else -> "What?"
        },
        overlay = if (emptiness != null) null else when (mode) {
            PcMode.MENU -> null

            PcMode.VIEW -> ({
                val open = box ?: state.storage.boxes.firstOrNull()
                if (open != null) {
                    val held = heldUid?.let { uid ->
                        state.storage.find(uid)?.second?.pokemon?.displayName?.uppercase()
                    }
                    BoxGridOverlay(
                        box = open,
                        followers = model.followers,
                        sprites = model.sprites,
                        revision = state.spriteRevision,
                        heldName = held,
                        startSlot = gridSlot,
                        // Tapping a Pokémon opens its stats, because that
                        // is what tapping one is asking for nine times in ten.
                        // Tapping the same one again — after coming back from
                        // those stats — is the second question, and opens what
                        // can be done with it.
                        onTap = { slot ->
                            val carrying = heldUid
                            val here = open.slots.getOrNull(slot)
                            when {
                                carrying != null -> {
                                    model.moveStoredToSlot(carrying, open.index, slot)
                                    heldUid = null
                                    gridSlot = null
                                }
                                here == null -> gridSlot = null
                                gridSlot == slot -> chosen = slot
                                else -> {
                                    gridSlot = slot
                                    statusOf(here.uid, null)?.let(model::open)
                                }
                            }
                        },
                        onMove = { from, to ->
                            open.slots.getOrNull(from)?.let {
                                model.moveStoredToSlot(it.uid, open.index, to)
                            }
                            gridSlot = null
                        },
                        onCancel = {
                            if (heldUid != null) heldUid = null else mode = PcMode.MENU
                        },
                    )
                }
            })

            PcMode.WITHDRAW -> ({
                MonListOverlay(
                    entries = stored.map {
                        MonRow(pokemonRowLabel(it.pokemon), pokemonRowLevel(it.pokemon))
                    },
                    onConfirm = { chosen = it },
                    onCancel = { mode = PcMode.MENU },
                    emptyMessage = "What? There are no POKéMON here!",
                    marked = marked,
                    onToggle = ::toggle,
                    actionLabel = TRANSFER_LABEL.takeIf {
                        mode == PcMode.WITHDRAW && marked.isNotEmpty() && state.saves.isNotEmpty()
                    },
                    onAction = { startWithdraw(marked.mapNotNull { stored.getOrNull(it)?.uid }) },
                )
            })

            PcMode.DEPOSIT -> ({
                MonListOverlay(
                    entries = depositRows.mapIndexed { index, row ->
                        MonRow(
                            pokemonRowLabel(row.mon),
                            pokemonRowLevel(row.mon),
                            header = depositHeader(index),
                        )
                    },
                    onConfirm = { chosen = it },
                    onCancel = { mode = PcMode.MENU },
                    emptyMessage = "There are no POKéMON here.",
                    marked = marked,
                    onToggle = ::toggle,
                    actionLabel = TRANSFER_LABEL.takeIf { marked.isNotEmpty() },
                    onAction = {
                        val picks = marked.mapNotNull { index ->
                            depositRows.getOrNull(index)?.let { it.key to it.location }
                        }
                        chosen = null
                        val lead = marked.minOrNull()?.let { depositRows.getOrNull(it) }
                        val rest = marked.sorted().drop(1)
                            .mapNotNull { depositRows.getOrNull(it)?.mon?.speciesId }
                        confirmSend(
                            what = "${picks.size} POKéMON",
                            where = thisBoxLabel,
                            speciesId = lead?.mon?.speciesId,
                            gameVersionId = state.remote(lead?.key)?.version?.id,
                            motion = TransferMotion.IN,
                            alsoSpeciesIds = rest,
                        ) {
                            model.depositFromSave(picks, StorageLayout.THE_BOX)
                        }
                    },
                )
            })
        },
        action = when {
            mode == PcMode.WITHDRAW && storedPick != null -> ({
                MonActionOverlay(
                    actions = listOf(
                        // Always asks which save it is going to, and then
                        // where inside it — a withdrawal leaves the app, and
                        // that is not something to infer from what happens to
                        // be loaded.
                        MonAction(
                            state.outLabel,
                            { startWithdraw(listOf(storedPick.uid)) },
                            enabled = state.saves.isNotEmpty(),
                        ),
                        MonAction("STATS", {
                            statusOf(storedPick.uid, StatusTransfer.WITHDRAW)?.let(model::open)
                        }),
                        // Ticks this one and hands the list back. From here a
                        // tap on any row ticks it too, so several go in one
                        // trip without a mode to switch into first.
                        MonAction("SELECT", {
                            chosen?.let { toggle(it) }
                            chosen = null
                        }),
                    ),
                    onCancel = { chosen = null },
                    note = if (state.saves.isEmpty()) "NO SAVES ON THE ACCOUNT" else null,
                )
            })

            mode == PcMode.VIEW && storedPick != null -> ({
                // Deliberately cannot transfer: this side only rearranges the
                // PC or releases — so there is no way to reach a save from
                // here by accident. Stats are the first tap on the grid.
                val name = storedPick.pokemon.displayName.uppercase()
                MonActionOverlay(
                    actions = listOf(
                        // Picks it up. From here a tap on any spot puts it
                        // down, and the box arrows still work, so moving one
                        // to another box is the same gesture as moving it two
                        // spots left.
                        MonAction("MOVE", {
                            heldUid = storedPick.uid
                            chosen = null
                            gridSlot = null
                        }),
                        // Only when there is something to take. Generation I
                        // Pokémon hold nothing, so this row simply never
                        // appears for one out of Red, Blue or Yellow.
                        *storedPick.pokemon.heldItem?.let { item ->
                            arrayOf(
                                MonAction("TAKE ${item.replace('_', ' ')}", {
                                    model.takeHeldItem(storedPick.uid)
                                    chosen = null
                                    gridSlot = null
                                })
                            )
                        }.orEmpty(),
                        MonAction("RELEASE", {
                            model.prompt(
                                Prompt.Confirm(
                                    lines = listOf(
                                        "Once released, $name is",
                                        "gone forever. Ok?",
                                    ),
                                    confirmLabel = "YES",
                                    cancelLabel = "NO",
                                    onConfirm = {
                                        model.releaseStored(storedPick.uid)
                                        chosen = null
                                        gridSlot = null
                                    },
                                )
                            )
                        }),
                    ),
                    // Both cleared, so the next tap on that Pokémon is the
                    // first tap again and opens its stats.
                    onCancel = { chosen = null; gridSlot = null },
                )
            })

            mode == PcMode.DEPOSIT && pick != null -> ({
                // The games never let the last one go, and neither does this —
                // counted per save, not per list.
                MonActionOverlay(
                    actions = listOf(
                        MonAction(
                            state.inLabel,
                            {
                                chosen = null
                                confirmSend(
                                    what = pick.mon.displayName.uppercase(),
                                    where = thisBoxLabel,
                                    speciesId = pick.mon.speciesId,
                                    gameVersionId = state.remote(pick.key)?.version?.id,
                                    motion = TransferMotion.IN,
                                ) {
                                    model.depositFromSave(
                                        pick.key,
                                        pick.location,
                                        StorageLayout.THE_BOX,
                                    )
                                }
                            },
                        ),
                        MonAction("STATS", {
                            model.open(
                                Screen.Status(pick.key, pick.area, pick.slot, StatusTransfer.DEPOSIT)
                            )
                        }),
                        MonAction("SELECT", {
                            chosen?.let { toggle(it) }
                            chosen = null
                        }),
                    ),
                    onCancel = { chosen = null },
                )
            })

            else -> null
        },
    )
}

/**
 * The button under a list that acts on everything ticked in it.
 *
 * One word, and the same word both ways: which direction it is going is
 * already settled by the row that opened the list, and the ticks say how many.
 */
private const val TRANSFER_LABEL = "TRANSFER"

private enum class PcMode { MENU, WITHDRAW, DEPOSIT, VIEW }

/** The full status screen for one Pokémon, wherever it lives. */
@Composable
fun StatusScreen(
    state: UiState,
    model: StorageViewModel,
    key: String?,
    area: Int,
    slot: Int,
    transfer: StatusTransfer? = null,
) {
    val pokemon = if (key == null) {
        state.storage.boxes.getOrNull(area - 1)?.contents?.getOrNull(slot)?.pokemon
    } else {
        val save = state.save(key)?.save
        if (area == 0) save?.party?.getOrNull(slot)
        else save?.boxes?.getOrNull(area - 1)?.getOrNull(slot)
    }
    if (pokemon == null) {
        ScreenColumn { item { Gen1Frame { GbText("THAT POKéMON IS NO LONGER THERE.") } } }
        return
    }
    // A stored Pokémon is shown in the art of the game it was deposited from.
    val gameVersionId = if (key == null) {
        state.storage.boxes.getOrNull(area - 1)?.contents?.getOrNull(slot)?.provenance?.gameVersion
    } else {
        state.remote(key)?.version?.id
    }
    // How many there are to walk through where this one lives, so the ends of
    // the box are ends rather than somewhere that looks the same but does
    // nothing.
    val siblings = if (key == null) {
        state.storage.boxes.getOrNull(area - 1)?.contents?.size ?: 0
    } else {
        val save = state.save(key)?.save
        if (area == 0) save?.party?.size ?: 0
        else save?.boxes?.getOrNull(area - 1)?.size ?: 0
    }

    // Every way off this screen, in the order a swipe walks them, and only the
    // ones that are actually there: at the first of a box there is no PREV, and
    // the cursor should not have to step over a word that does nothing. The
    // buttons then look their position up rather than counting for themselves,
    // so the arrow is always on the one a tap would take.
    val actions = buildList<Pair<String, () -> Unit>> {
        if (slot > 0) {
            add(PREV_LABEL to { model.replace(Screen.Status(key, area, slot - 1, transfer)) })
        }
        transferAction(state, model, key, area, slot, transfer, pokemon)?.let(::add)
        if (slot < siblings - 1) {
            add(NEXT_LABEL to { model.replace(Screen.Status(key, area, slot + 1, transfer)) })
        }
        add(BACK_LABEL to { model.back() })
    }
    val at = rememberCursorLayer(actions.size) { index ->
        actions.getOrNull(index)?.second?.invoke()
    }
    fun isOn(label: String) = actions.getOrNull(at)?.first == label

    Gen1StatusScreen(
        pokemon = pokemon,
        gameVersionId = gameVersionId,
        store = model.sprites,
        spriteRevision = state.spriteRevision,
        onSpriteLongPress = { species -> model.prompt(Prompt.ChooseSpriteSet(species)) },
        footer = {
            Gen1BoxButton(BACK_LABEL, { model.back() }, selected = isOn(BACK_LABEL))
        },
        underBox = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Gen1BoxButton(
                    PREV_LABEL,
                    { model.replace(Screen.Status(key, area, slot - 1, transfer)) },
                    enabled = slot > 0,
                    selected = isOn(PREV_LABEL),
                )
                // Only the transfer this screen was opened from. Looking a
                // Pokémon over is most of why a transfer stalls here, so the
                // way on is under it rather than back through the list.
                actions.firstOrNull { it.first !in WALKING_LABELS }?.let { (label, act) ->
                    Gen1Button(
                        label,
                        act,
                        Modifier.wrapContentWidth(),
                        selected = isOn(label),
                    )
                }
                Gen1BoxButton(
                    NEXT_LABEL,
                    { model.replace(Screen.Status(key, area, slot + 1, transfer)) },
                    enabled = slot < siblings - 1,
                    selected = isOn(NEXT_LABEL),
                )
            }
        },
    )
}

private const val PREV_LABEL = "PREV"
private const val NEXT_LABEL = "NEXT"
private const val BACK_LABEL = "BACK"

/** The three that are always the same, so the transfer is whatever is left. */
private val WALKING_LABELS = setOf(PREV_LABEL, NEXT_LABEL, BACK_LABEL)

/**
 * The transfer this status screen was opened from, if it was opened from one,
 * as a label and the thing pressing it does.
 *
 * Not a composable: the same action has to be both a button and a row on the
 * cursor, and handing back what it does lets the one description serve both.
 */
private fun transferAction(
    state: UiState,
    model: StorageViewModel,
    key: String?,
    area: Int,
    slot: Int,
    transfer: StatusTransfer?,
    pokemon: Gen1Pokemon,
): Pair<String, () -> Unit>? = when (transfer) {
    StatusTransfer.WITHDRAW -> {
        val uid = if (key == null) {
            state.storage.boxes.getOrNull(area - 1)?.contents?.getOrNull(slot)?.uid
        } else null
        if (uid == null) null else state.outLabel to {
            model.back()
            val active = state.activeSaveKey
            val loaded = state.save(active)?.save
            if (active != null && loaded != null) {
                model.askToSend(
                    TransferScene(
                        speciesId = pokemon.speciesId,
                        gameVersionId = null,
                        name = pokemon.displayName.uppercase(),
                        destination = loaded.trainerName.uppercase(),
                        motion = TransferMotion.OUT,
                    ),
                    "${TransferMotion.OUT.verb} " +
                        "${pokemon.displayName.uppercase()} TO " +
                        "${loaded.trainerName.uppercase()}?",
                ) {
                    model.withdrawToSave(
                        listOf(uid),
                        active,
                        WithdrawTarget.Box(loaded.currentBox),
                    )
                }
            } else {
                model.open(Screen.ChooseCart(null, listOf(uid)))
            }
        }
    }

    StatusTransfer.DEPOSIT -> {
        if (key == null || area <= 0) null else state.inLabel to {
            model.back()
            val where = state.storage.boxes.firstOrNull()?.label ?: "THE PC"
            model.askToSend(
                TransferScene(
                    speciesId = pokemon.speciesId,
                    gameVersionId = state.remote(key)?.version?.id,
                    name = pokemon.displayName.uppercase(),
                    destination = where,
                    motion = TransferMotion.IN,
                ),
                "${TransferMotion.IN.verb} " +
                    "${pokemon.displayName.uppercase()} TO $where?",
            ) {
                model.depositFromSave(
                    key,
                    SaveLocation.Box(area, slot + 1),
                    StorageLayout.THE_BOX,
                )
            }
        }
    }

    null -> null
}

/** Every sprite the normal download fetches: each set, every species. */
private val SPRITE_TOTAL = SpriteSet.downloadable.size * 151

private fun percentOf(have: Int, whole: Int): Int =
    if (whole <= 0) 0 else ((have.coerceAtMost(whole) * 100) / whole)

/**
 * One downloadable set's screen: a question, then a share and a bar.
 *
 * Shared by all three because they differ only in what they are counting.
 * Every choice on it is on the one cursor, so a swipe reaches the buttons
 * here exactly as it reaches a menu row anywhere else.
 */
@Composable
private fun DownloadPage(
    heading: String,
    progress: DownloadProgress?,
    /** How much of the set is here, as a share of the whole. */
    installedPercent: Int,
    spaceUsed: String,
    hasSome: Boolean,
    confirmLine: String,
    deleteLine: String,
    model: StorageViewModel,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = progress != null && !progress.finished

    val choices = buildList<Pair<String, () -> Unit>> {
        if (running) {
            add("STOP" to onStop)
        } else {
            if (progress != null) add("OK" to onDismiss)
            add((if (hasSome) "DOWNLOAD MISSING" else "DOWNLOAD") to {
                model.prompt(
                    Prompt.Confirm(
                        lines = listOf(confirmLine),
                        confirmLabel = "YES",
                        cancelLabel = "NO",
                        onConfirm = onDownload,
                    )
                )
            })
            if (hasSome) add("DELETE" to {
                model.prompt(
                    Prompt.Confirm(
                        lines = listOf(deleteLine),
                        confirmLabel = "YES",
                        cancelLabel = "NO",
                        onConfirm = onDelete,
                    )
                )
            })
        }
        add("BACK" to { model.back() })
    }
    val cursor = rememberCursorLayer(choices.size) { choices[it].second() }

    ScreenColumn {
        item { Gen1Frame(Modifier.wrapContentWidth()) { GbText(heading) } }

        if (progress != null) {
            item {
                Gen1Frame(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
                    // The share, and nothing else. A running count of files is
                    // the machine's business, not the player's.
                    DownloadProgressBar(progress.percent, Modifier.fillMaxWidth())
                    if (progress.finished) {
                        Spacer(Modifier.height(10.dp))
                        GbText(
                            if (progress.error != null) "STOPPED: ${progress.error.uppercase()}"
                            else "DONE.",
                            style = Gen1TextSmall,
                        )
                    }
                }
            }
        }

        if (!running) {
            item {
                Gen1Frame(Modifier.wrapContentWidth()) {
                    Gen1Field("ON THIS DEVICE", "$installedPercent%")
                    Gen1Field("SPACE USED", spaceUsed)
                }
            }
        }

        itemsIndexed(choices) { index, (label, action) ->
            Gen1BoxButton(label, action, selected = cursor == index)
        }
    }
}

@Composable
fun CriesScreen(state: UiState, model: StorageViewModel) = DownloadPage(
    heading = "POKéMON CRIES",
    progress = state.cryProgress,
    installedPercent = percentOf(state.criesInstalled, 151),
    spaceUsed = if (state.criesInstalled > 0) "${model.cryBytesOnDisk() / 1024} KB"
    else "ABOUT 2 MB TO DOWNLOAD",
    hasSome = state.criesInstalled > 0,
    confirmLine = "Download Pokémon cries from repo?",
    deleteLine = "DELETE EVERY DOWNLOADED CRY?",
    model = model,
    onDownload = model::downloadCries,
    onStop = model::cancelCryDownload,
    onDismiss = model::dismissCryProgress,
    onDelete = model::deleteCries,
)

@Composable
fun FollowersScreen(state: UiState, model: StorageViewModel) = DownloadPage(
    heading = "OVERWORLD FOLLOWERS",
    progress = state.followerProgress,
    installedPercent = percentOf(state.followersInstalled, 251),
    spaceUsed = if (state.followersInstalled > 0) "${model.followerBytesOnDisk() / 1024} KB"
    else "ABOUT 1 MB TO DOWNLOAD",
    hasSome = state.followersInstalled > 0,
    confirmLine = "Download follower sprites from repo?",
    deleteLine = "DELETE EVERY FOLLOWER SHEET?",
    model = model,
    onDownload = model::downloadFollowers,
    onStop = model::cancelFollowerDownload,
    onDismiss = model::dismissFollowerProgress,
    onDelete = model::deleteFollowers,
)

@Composable
fun SpritesScreen(state: UiState, model: StorageViewModel) = DownloadPage(
    heading = "POKéMON SPRITES",
    progress = state.spriteProgress,
    installedPercent = percentOf(state.spritesInstalled, SPRITE_TOTAL),
    spaceUsed = if (state.spritesInstalled > 0) "${model.spriteBytesOnDisk() / (1024 * 1024)} MB"
    else "ABOUT 25 MB TO DOWNLOAD",
    hasSome = state.spritesInstalled > 0,
    confirmLine = "Download Pokémon sprites from repo?",
    deleteLine = "DELETE EVERY DOWNLOADED SPRITE?",
    model = model,
    onDownload = model::downloadSprites,
    onStop = model::cancelSpriteDownload,
    onDismiss = model::dismissSpriteProgress,
    onDelete = model::deleteSprites,
)

/**
 * Everything the cartridge's PC menu does not have, sorted into its own
 * drawers.
 *
 * It had grown into one column of seven windows, each with its own buttons
 * and its own explaining, which is a lot of screen for a handful of switches.
 * The switches have not changed; only where they live has. Folded, a drawer
 * replaces this list rather than opening over it, so there is only ever one
 * menu on screen; opened up, the list keeps its half and the drawer takes
 * the other.
 */
@Composable
fun OptionsScreen(state: UiState, model: StorageViewModel, onShareReport: () -> Unit) {
    var drawer by remember { mutableStateOf<OptionsDrawer?>(null) }
    // Back closes the drawer before it leaves OPTIONS. Without this the
    // drawer is a screen the back stack has never heard of, and one press
    // walked straight out of the settings the player was in the middle of.
    androidx.activity.compose.BackHandler(enabled = drawer != null) { drawer = null }

    if (drawer != null && !isUnfolded()) {
        OptionsDrawerContent(drawer!!, state, model, onShareReport) { drawer = null }
        return
    }

    if (isUnfolded()) {
        Row(Modifier.fillMaxSize()) {
            // The list keeps the edge the menus use; the drawer opens into
            // the space beside it rather than over the top of it.
            if (!Gen1Layout.windowsOnRight) {
                Box(Modifier.weight(1f)) { OptionsList(state, model) { drawer = it } }
                Box(Modifier.weight(1f)) {
                    drawer?.let {
                        OptionsDrawerContent(it, state, model, onShareReport) { drawer = null }
                    }
                }
            } else {
                Box(Modifier.weight(1f)) {
                    drawer?.let {
                        OptionsDrawerContent(it, state, model, onShareReport) { drawer = null }
                    }
                }
                Box(Modifier.weight(1f)) { OptionsList(state, model) { drawer = it } }
            }
        }
        return
    }

    OptionsList(state, model) { drawer = it }
}

/** The drawers, in the order they are offered. */
enum class OptionsDrawer(val label: String) {
    VISUAL("VISUAL"),
    AUDIO("AUDIO"),
    LAYOUT("LAYOUT"),
    DOWNLOADS("DOWNLOADS"),
    SAVES("SAVES"),
    ABOUT("ABOUT"),
}

@Composable
private fun OptionsList(
    state: UiState,
    model: StorageViewModel,
    onOpen: (OptionsDrawer) -> Unit,
) {
    val drawers = OptionsDrawer.entries
    val cursor = rememberCursorLayer(drawers.size) { onOpen(drawers[it]) }
    ScreenColumn {
        item {
            Gen1Frame(
                Modifier.wrapContentWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                drawers.forEachIndexed { index, entry ->
                    Gen1MenuRow(
                        entry.label,
                        selected = cursor == index,
                        onSelect = {},
                        onConfirm = { onOpen(entry) },
                    )
                }
            }
        }
    }
}

/** One line in a drawer: a word, what it currently says, and what taking it does. */
private data class OptionRow(
    val label: String,
    val trailing: String? = null,
    val enabled: Boolean = true,
    val swatch: GbPalette? = null,
    val action: () -> Unit,
)

/**
 * A drawer's contents, all on one cursor.
 *
 * Every choice a drawer offers goes in one list and one cursor layer runs
 * down it, so a swipe reaches a switch here exactly as it reaches a menu row
 * anywhere else. Two layers on one screen would mean only the top one moved,
 * which is how the settings ended up half-reachable.
 */
@Composable
private fun OptionsDrawerContent(
    drawer: OptionsDrawer,
    state: UiState,
    model: StorageViewModel,
    onShareReport: () -> Unit,
    onBack: () -> Unit,
) {
    val audio = LocalGen1Audio.current
    val rows = buildList {
        when (drawer) {
            OptionsDrawer.VISUAL -> {
                GbPalette.ALL.forEach { palette ->
                    add(
                        OptionRow(
                            palette.label,
                            trailing = if (state.paletteId == palette.id) "ON" else null,
                            swatch = palette,
                        ) { model.setPalette(palette.id) }
                    )
                }
                add(
                    OptionRow(
                        "BLACK ON WHITE BOXES",
                        trailing = if (!state.windowsFollowPalette) "ON" else "OFF",
                    ) { model.setWindowsFollowPalette(!state.windowsFollowPalette) }
                )
            }

            OptionsDrawer.AUDIO -> {
                add(
                    OptionRow("DISABLE ALL", trailing = if (state.soundOff) "ON" else "OFF") {
                        model.setSoundOff(!state.soundOff)
                    }
                )
                if (!state.soundOff) {
                    SoundEffect.entries.forEach { effect ->
                        val on = effect.id in state.soundsOn
                        add(OptionRow(effect.label, trailing = if (on) "ON" else "OFF") {
                            model.setSoundEnabled(effect, !on)
                        })
                    }
                }
            }

            OptionsDrawer.LAYOUT -> {
                add(
                    OptionRow("ALIGNMENT", if (state.windowsOnRight) "RIGHT" else "LEFT") {
                        model.setWindowsOnRight(!state.windowsOnRight)
                    }
                )
                add(OptionRow("ALL POKéMON", if (state.showAllSaves) "ON" else "OFF") {
                    model.setShowAllSaves(!state.showAllSaves)
                })
                add(OptionRow("ALL ITEMS", if (state.showAllItems) "ON" else "OFF") {
                    model.setShowAllItems(!state.showAllItems)
                })
                add(
                    OptionRow(
                        "WITHDRAW/DEPOSIT",
                        if (state.classicTransferLabels) "ON" else "OFF",
                    ) { model.setClassicTransferLabels(!state.classicTransferLabels) }
                )
            }

            OptionsDrawer.DOWNLOADS -> {
                add(OptionRow("SPRITES", "${percentOf(state.spritesInstalled, SPRITE_TOTAL)}%") {
                    model.open(Screen.Sprites)
                })
                add(OptionRow("CRIES", "${percentOf(state.criesInstalled, 151)}%") {
                    model.open(Screen.Cries)
                })
                add(OptionRow("FOLLOWERS", "${percentOf(state.followersInstalled, 251)}%") {
                    model.open(Screen.Followers)
                })
                add(OptionRow("DOWNLOAD ALL") { model.downloadEverything() })
            }

            OptionsDrawer.SAVES -> {
                if (state.linked) {
                    add(OptionRow("SYNC NOW", enabled = !state.syncing) {
                        audio?.play(SoundEffect.SAVE)
                        model.sync()
                    })
                    add(OptionRow("UNLINK") {
                        model.prompt(
                            Prompt.Confirm(
                                lines = listOf("UNLINK THIS DEVICE?"),
                                confirmLabel = "YES",
                                cancelLabel = "NO",
                                onConfirm = { model.unlink() },
                            )
                        )
                    })
                } else {
                    add(OptionRow("ENTER SYNC CODES") { model.open(Screen.Link) })
                }
            }

            OptionsDrawer.ABOUT -> {
                add(OptionRow("CREDITS") { model.open(Screen.Credits) })
                add(OptionRow("SEND REPORT", action = onShareReport))
            }
        }
        add(OptionRow("BACK", action = onBack))
    }

    val cursor = rememberCursorLayer(rows.size) { rows[it].action() }

    ScreenColumn {
        item { Gen1Frame(Modifier.wrapContentWidth()) { GbText(drawer.label) } }

        if (drawer == OptionsDrawer.SAVES) {
            item {
                Gen1Frame {
                    Gen1Field("STATUS", if (state.linked) "LINKED" else "NOT LINKED")
                    state.lastSyncedAtMillis?.let {
                        Gen1Field(
                            "LAST SYNC",
                            java.time.Instant.ofEpochMilli(it).toString().take(19),
                        )
                    }
                    model.linkedDeviceLabel?.let { Gen1Field("THIS DEVICE", it) }
                }
            }
        }

        // Whatever is being fetched, said here as well as on its own screen —
        // DOWNLOAD ALL starts three of them and never left this drawer.
        if (drawer == OptionsDrawer.DOWNLOADS) {
            val busy = listOfNotNull(
                state.spriteProgress?.let { "SPRITES" to it },
                state.cryProgress?.let { "CRIES" to it },
                state.followerProgress?.let { "FOLLOWERS" to it },
            ).firstOrNull { !it.second.finished }
            if (busy != null) {
                item {
                    Gen1Frame(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
                        GbText(busy.first, style = Gen1TextSmall)
                        Spacer(Modifier.height(gen1Dp(2)))
                        DownloadProgressBar(busy.second.percent, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                rows.forEachIndexed { index, row ->
                    if (row.swatch != null) {
                        Row(
                            Modifier.fillMaxWidth().gen1Clickable { row.action() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Gen1MenuRow(
                                row.label,
                                selected = cursor == index,
                                onSelect = {},
                                onConfirm = row.action,
                                trailing = row.trailing,
                                modifier = Modifier.weight(1f),
                            )
                            PaletteSwatch(row.swatch)
                        }
                    } else {
                        Gen1MenuRow(
                            row.label,
                            selected = cursor == index,
                            onSelect = {},
                            onConfirm = row.action,
                            trailing = row.trailing,
                            enabled = row.enabled,
                        )
                    }
                }
            }
        }

        if (drawer == OptionsDrawer.ABOUT) item { OptionsLinkRow() }
    }
}


@Composable
private fun OptionsLinkRow() {
    val context = LocalContext.current
    Gen1Frame(Modifier.wrapContentWidth()) {
        GbText("LOGIE 2026")
        // The whole line is the link. A URL at this size is a hard thing to
        // hit, and the row around it is not doing anything else.
        Gen1MenuRow(
            "github.com/logie-github/TM-Case",
            selected = false,
            onSelect = {},
            onConfirm = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TM_CASE_REPO)))
                }
            },
        )
    }
}

/**
 * Who made what this app is built out of, and whose the rest of it is.
 *
 * Given the whole screen rather than a column of windows: it is the one page
 * here that is meant to be read rather than used, and breaking it into boxes
 * would make it harder to.
 */
@Composable
fun CreditsScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .padding(gen1Dp(4)),
    ) {
        Gen1Frame(Modifier.fillMaxSize()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(gen1Dp(4))) {
                item { GbText("CREDITS", style = Gen1TextLarge) }
                item {
                    Credit(
                        "SPRITES",
                        "THE RBY SPRITES PROJECT",
                        "BY SHIRATHEMOGUL",
                        "github.com/ShiraTheMogul/",
                        "rby-sprites-project",
                    )
                }
                item {
                    Credit(
                        "CRIES",
                        "POKEAPI/CRIES",
                        "github.com/PokeAPI/cries",
                    )
                }
                item {
                    Credit(
                        "FOLLOWERS",
                        "POKEPCFOLLOWERS",
                        "FORK BY BURGERSLAYER7",
                        "github.com/burgerslayer7/",
                        "PokePCFollowers",
                    )
                }
                item {
                    Credit(
                        "FOLLOWERS, ORIGINAL",
                        "POKEPCFOLLOWERS",
                        "BY GAMECORNER-033",
                        "github.com/gamecorner-033/",
                        "PokePCFollowers",
                    )
                }
                item {
                    Credit(
                        "FOLLOWER ART",
                        "SHOCKSLAYER AND THE",
                        "FOLLOWERS EX / POKEPC LINEAGE",
                    )
                }
                item {
                    Credit(
                        "FONT",
                        "POKEMON-FONT BY SUPERPENCIL",
                        "SIL OPEN FONT LICENSE 1.1",
                        "github.com/cooljeanius/",
                        "pokemon-font",
                    )
                }
                item {
                    Credit(
                        "GAME DATA",
                        "PRET/POKERED",
                        "github.com/pret/pokered",
                    )
                }
                item {
                    Credit(
                        "SAVES",
                        "GEN1RECOMP",
                        "github.com/bryanthaboi/",
                        "gen1recomp",
                    )
                }
                item { Spacer(Modifier.height(gen1Dp(4))) }
                item {
                    Column {
                        GbText("POKEMON")
                        Spacer(Modifier.height(gen1Dp(2)))
                        LEGAL.forEach { paragraph ->
                            GbText(paragraph, style = Gen1TextSmall)
                            Spacer(Modifier.height(gen1Dp(3)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Credit(heading: String, vararg lines: String) {
    Column {
        GbText(heading)
        lines.forEach { GbText(it, style = Gen1TextSmall) }
    }
}

/**
 * Kept as its own text rather than folded into the layout, so it is obvious
 * what is a statement and what is furniture.
 */
private val LEGAL = listOf(
    "© THE POKéMON COMPANY, NINTENDO, AND GAME FREAK.",
    "POKéMON AND ALL RELATED NAMES, CHARACTERS, TRADEMARKS, AND INTELLECTUAL PROPERTY ARE THE PROPERTY OF THEIR RESPECTIVE OWNERS.",
    "THIS PROJECT IS NOT AFFILIATED WITH, ENDORSED BY, SPONSORED BY, OR OTHERWISE ASSOCIATED WITH THE POKéMON COMPANY, NINTENDO, OR GAME FREAK. THIS PROJECT GRATEFULLY ACKNOWLEDGES THEIR IDEAS, CREATIVE WORK, AND CONTRIBUTIONS TO THE POKéMON FRANCHISE.",
    "NO COPYRIGHT INFRINGEMENT IS INTENDED.",
    "NO RIPPED GAME ASSETS ARE DISTRIBUTED IN THIS APK.",
)

/**
 * Naming something — a box, a cartridge — in a window sized to the name.
 *
 * Ten characters, which is what the games allow and what the label on a
 * cartridge has room for. CLEAR puts it back to whatever it was called before
 * anyone renamed it.
 */
@Composable
private fun NamePrompt(
    heading: String,
    initial: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(heading) { mutableStateOf(initial) }
    // The field itself belongs to the keyboard; the three choices under it are
    // ordinary rows on the cursor, so the window can be finished without one.
    val at = rememberCursorLayer(3, columns = 3) { index ->
        when (index) {
            0 -> onDone(name)
            1 -> onDone("")
            else -> onCancel()
        }
    }
    Gen1Frame(Modifier.wrapContentWidth()) {
        GbText(heading)
        Spacer(Modifier.height(gen1Dp(2)))
        OutlinedTextField(
            value = name,
            onValueChange = { text ->
                name = text.filter { it != '\n' }.take(StorageRepository.MAX_BOX_NAME)
            },
            singleLine = true,
            textStyle = Gen1Text,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Gen1Palette.Panel,
                unfocusedContainerColor = Gen1Palette.Panel,
                focusedTextColor = Gen1Palette.Ink,
                unfocusedTextColor = Gen1Palette.Ink,
                focusedIndicatorColor = Gen1Palette.Ink,
                unfocusedIndicatorColor = Gen1Palette.Shadow,
                cursorColor = Gen1Palette.Ink,
            ),
            modifier = Modifier.width(gen1Dp(90)),
        )
        Spacer(Modifier.height(gen1Dp(3)))
        Row(horizontalArrangement = Arrangement.spacedBy(gen1Dp(3))) {
            Gen1Button("OK", { onDone(name) }, selected = at == 0)
            Gen1Button("CLEAR", { onDone("") }, selected = at == 1)
            Gen1Button("CANCEL", onCancel, selected = at == 2)
        }
    }
}

/**
 * The only place a save is named.
 *
 * A save is shown by its game and trainer — the playthrough id it is really
 * keyed by is an implementation detail and never appears. If the blob has been
 * fetched the party count comes with it; otherwise the row still works and the
 * count fills in once it loads.
 */
@Composable
private fun PaletteSwatch(palette: GbPalette) {
    Row(
        Modifier
            .background(Gen1Palette.Ink)
            .padding(2.dp)
    ) {
        // Lightest first, so it reads left to right the way the ramp is written.
        palette.ramp.reversed().forEach { shade ->
            Box(Modifier.size(width = 14.dp, height = 20.dp).background(shade))
        }
    }
}

private const val TM_CASE_REPO = "https://github.com/logie-github/TM-Case"

/** An on/off row drawn as a menu entry with its state in the right column. */
@Composable
private fun Gen1Toggle(
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    onLabel: String = "ON",
    offLabel: String = "OFF",
    /** Whether the cursor is on this row. The arrow means nothing else. */
    selected: Boolean = false,
) {
    // A switch says what it is through its trailing word, and only that. It
    // used to light the cursor arrow as well, which left rows in OPTIONS
    // wearing arrows that had nothing to do with where the cursor was.
    Gen1MenuRow(
        label,
        selected = selected,
        onSelect = onToggle,
        onConfirm = onToggle,
        trailing = if (on) onLabel else offLabel,
    )
}

/**
 * A column of windows over the screen.
 *
 * Each window is sized to what is in it and pinned to the same edge the PC's
 * menus use, so a strip of the dithered ground always shows down the other
 * side. Nothing here reaches both edges at once.
 */
@Composable
fun ScreenColumn(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .gen1Ground(),
        contentPadding = PaddingValues(
            start = gen1Dp(2),
            end = gen1Dp(2),
            top = gen1Dp(2),
            bottom = gen1Dp(6),
        ),
        verticalArrangement = Arrangement.spacedBy(gen1Dp(4)),
        // The same edge the menus use, so a screen of windows and the PC read
        // as the same machine rather than as two.
        horizontalAlignment = Gen1Layout.menuSide,
        content = content,
    )
}

@Composable
fun PromptWindow(state: UiState, model: StorageViewModel) {
    val prompt = state.prompt ?: return
    // No scrim and no centring: a prompt is a window like every other
    // window here, so it opens in the same corner the action windows do and
    // leaves what it came from readable behind it.
    Box(
        Modifier.fillMaxSize().padding(gen1Dp(4)),
        contentAlignment = Gen1Layout.corner(top = false, menuSide = true),
    ) {
        when (prompt) {
            is Prompt.Message -> Gen1DialogueBox(prompt.lines.map { it.uppercase() }) {
                Spacer(Modifier.height(gen1Dp(2)))
                Gen1ChoiceRows(listOf("OK" to model::dismissPrompt))
            }

            is Prompt.Confirm -> Gen1DialogueBox(prompt.lines.map { it.uppercase() }) {
                Spacer(Modifier.height(gen1Dp(2)))
                Gen1ChoiceRows(
                    listOf(
                        prompt.confirmLabel to prompt.onConfirm,
                        prompt.cancelLabel to model::dismissPrompt,
                    )
                )
            }

            is Prompt.RenameBox -> NamePrompt(
                heading = state.storage.boxes.firstOrNull()?.label ?: "THE PC",
                initial = state.storage.boxes.getOrNull(prompt.index - 1)?.name.orEmpty(),
                onDone = { model.renameBox(prompt.index, it) },
                onCancel = model::dismissPrompt,
            )

            is Prompt.RenameCart -> NamePrompt(
                heading = prompt.fallback,
                initial = model.cartName(prompt.key).orEmpty(),
                onDone = { model.renameCart(prompt.key, it) },
                onCancel = model::dismissPrompt,
            )

            is Prompt.Update -> {
                val context = LocalContext.current
                Gen1Frame(Modifier.wrapContentWidth(), opening = true) {
                    GbText("VERSION ${prompt.version} IS OUT.")
                    GbText("UPDATE?", style = Gen1TextSmall)
                    Spacer(Modifier.height(gen1Dp(3)))
                    val update = {
                        model.dismissPrompt()
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(prompt.url))
                            )
                        }
                        Unit
                    }
                    val at = rememberCursorLayer(2) { if (it == 0) update() else model.dismissPrompt() }
                    Row(horizontalArrangement = Arrangement.spacedBy(gen1Dp(3))) {
                        Gen1Button("YES", update, selected = at == 0)
                        Gen1Button("NO", model::dismissPrompt, selected = at == 1)
                    }
                }
            }

            is Prompt.ChooseQuantity -> QuantityPrompt(
                title = prompt.title,
                max = prompt.max,
                onChoose = { count -> model.dismissPrompt(); prompt.onChoose(count) },
                onCancel = model::dismissPrompt,
            )

            is Prompt.ChooseSpriteSet -> SpriteSetPicker(
                speciesId = prompt.speciesId,
                store = model.sprites,
                onChoose = { model.chooseSpriteSet(prompt.speciesId, it) },
                onCancel = model::dismissPrompt,
            )
        }
    }
}
