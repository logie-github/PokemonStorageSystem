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
    LaunchedEffect(state.transfers) { marked = emptySet(); chosen = null; gridSlot = null }
    fun toggle(index: Int) {
        marked = if (index in marked) marked - index else marked + index
    }

    val box = state.storage.boxes.getOrNull(state.currentStorageBox - 1)
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
        if (active != null && loaded != null) {
            model.withdrawToSave(uids, active, WithdrawTarget.Box(loaded.currentBox))
        } else {
            model.open(Screen.ChooseCart(null, uids))
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
        boxLabel = box?.label ?: "BOX ${state.currentStorageBox}",
        // Hidden rather than drawn under a message that would cover it.
        showBox = refusal == null && emptiness == null,
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
        onChangeBox = { mode = PcMode.CHANGE_BOX },
        onRenameBox = { model.prompt(Prompt.RenameBox(state.currentStorageBox)) },
        onOptions = onOptions,
        message = refusal
            ?: emptiness
            ?: when {
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
                        revision = state.spriteRevision,
                        heldName = held,
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
                        onPreviousBox = {
                            gridSlot = null
                            model.setStorageBox(
                                if (open.index <= 1) StorageLayout.BOX_COUNT else open.index - 1
                            )
                        },
                        onNextBox = {
                            gridSlot = null
                            model.setStorageBox(
                                if (open.index >= StorageLayout.BOX_COUNT) 1 else open.index + 1
                            )
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
                    actionLabel = "${state.outLabel} ${marked.size}".takeIf {
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
                    actionLabel = "${state.inLabel} ${marked.size}".takeIf { marked.isNotEmpty() },
                    onAction = {
                        val picks = marked.mapNotNull { index ->
                            depositRows.getOrNull(index)?.let { it.key to it.location }
                        }
                        chosen = null
                        model.depositFromSave(picks, state.currentStorageBox)
                    },
                )
            })

            PcMode.CHANGE_BOX -> ({
                ChangeBoxOverlay(
                    boxes = state.storage.boxes.map {
                        Triple(it.index, it.label, "${it.contents.size}/${StorageLayout.BOX_CAPACITY}")
                    },
                    onConfirm = { model.setStorageBox(it); mode = PcMode.MENU },
                    onCancel = { mode = PcMode.MENU },
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
                                model.depositFromSave(
                                    pick.key,
                                    pick.location,
                                    state.currentStorageBox,
                                )
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

private enum class PcMode { MENU, WITHDRAW, DEPOSIT, VIEW, CHANGE_BOX }

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
    Gen1StatusScreen(
        pokemon = pokemon,
        gameVersionId = gameVersionId,
        store = model.sprites,
        spriteRevision = state.spriteRevision,
        onSpriteLongPress = { species -> model.prompt(Prompt.ChooseSpriteSet(species)) },
        footer = { Gen1BoxButton("BACK", { model.back() }) },
        underBox = {
            // Only the transfer this screen was opened from. Looking a
            // Pokémon over is most of why a transfer stalls here, so the way
            // on is under it rather than back through the list.
            when (transfer) {
                StatusTransfer.WITHDRAW -> if (key == null) {
                    val uid = state.storage.boxes.getOrNull(area - 1)
                        ?.contents?.getOrNull(slot)?.uid
                    if (uid != null) {
                        Gen1Button(state.outLabel, {
                            model.back()
                            val active = state.activeSaveKey
                            val loaded = state.save(active)?.save
                            if (active != null && loaded != null) {
                                model.withdrawToSave(
                                    listOf(uid),
                                    active,
                                    WithdrawTarget.Box(loaded.currentBox),
                                )
                            } else {
                                model.open(Screen.ChooseCart(null, listOf(uid)))
                            }
                        }, Modifier.wrapContentWidth())
                    }
                }

                StatusTransfer.DEPOSIT -> if (key != null && area > 0) {
                    Gen1Button(state.inLabel, {
                        model.back()
                        model.depositFromSave(
                            key,
                            SaveLocation.Box(area, slot + 1),
                            state.currentStorageBox,
                        )
                    }, Modifier.wrapContentWidth())
                }

                null -> Unit
            }
        },
    )
}

/**
 * Everything that is fetched rather than shipped.
 *
 * The front sprites, the cries and the follower sheets are all the games' own
 * material or the community's, so none of them are in the APK and all three
 * are the player's to download. One menu for the set keeps OPTIONS from
 * growing a row per thing that can be fetched.
 */
@Composable
fun DownloadsScreen(state: UiState, model: StorageViewModel) {
    val entries = listOf<Pair<String, () -> Unit>>(
        "DOWNLOAD SPRITES" to { model.open(Screen.Sprites) },
        "DOWNLOAD CRIES" to { model.open(Screen.Cries) },
        "DOWNLOAD FOLLOWERS" to { model.open(Screen.Followers) },
    )
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
        item {
            Gen1Frame(Modifier.wrapContentWidth()) {
                Gen1Field("SPRITES", "${state.spritesInstalled}")
                Gen1Field("CRIES", "${state.criesInstalled} OF 151")
                Gen1Field("FOLLOWERS", "${state.followersInstalled} OF 251")
            }
        }
    }
}

/**
 * One downloadable set's screen: a question, then a percentage and a bar.
 *
 * Shared by all three because they differ only in what they are counting.
 * The player never has to think about where any of it comes from or which
 * file is which.
 */
@Composable
private fun DownloadPage(
    heading: String,
    progress: DownloadProgress?,
    installed: String,
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

    ScreenColumn {
        item {
            Gen1Frame {
                GbText(heading)
                Spacer(Modifier.height(6.dp))
            }
        }

        if (progress != null) {
            item {
                Gen1Frame(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
                    DownloadProgressBar(progress.percent, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    if (progress.finished) {
                        GbText(
                            if (progress.error != null) "STOPPED: ${progress.error.uppercase()}"
                            else if (progress.failed > 0) "DONE. ${progress.failed} COULD NOT BE FETCHED."
                            else "DONE.",
                            style = Gen1TextSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Gen1Button("OK", onDismiss)
                    } else {
                        GbText("${progress.done} OF ${progress.total}", style = Gen1TextSmall)
                        Spacer(Modifier.height(8.dp))
                        Gen1Button("STOP", onStop)
                    }
                }
            }
        }

        if (!running) {
            item {
                Gen1Frame(Modifier.wrapContentWidth()) {
                    GbText("ON THIS DEVICE")
                    GbText(installed, style = Gen1TextSmall)
                    Spacer(Modifier.height(gen1Dp(2)))
                    GbText("SPACE USED")
                    GbText(spaceUsed, style = Gen1TextSmall)
                }
            }
            // Their own buttons rather than a row crammed inside the window,
            // which is what was breaking DELETE across two lines.
            item {
                Gen1Button(
                    if (hasSome) "DOWNLOAD MISSING" else "DOWNLOAD",
                    {
                        model.prompt(
                            Prompt.Confirm(
                                lines = listOf(confirmLine),
                                confirmLabel = "YES",
                                cancelLabel = "NO",
                                onConfirm = onDownload,
                            )
                        )
                    },
                    Modifier.wrapContentWidth(),
                )
            }
            if (hasSome) {
                item {
                    Gen1Button(
                        "DELETE",
                        {
                            model.prompt(
                                Prompt.Confirm(
                                    lines = listOf(deleteLine),
                                    confirmLabel = "YES",
                                    cancelLabel = "NO",
                                    onConfirm = onDelete,
                                )
                            )
                        },
                        Modifier.wrapContentWidth(),
                    )
                }
            }
        }
    }
}

@Composable
fun CriesScreen(state: UiState, model: StorageViewModel) = DownloadPage(
    heading = "POKéMON CRIES",
    progress = state.cryProgress,
    installed = "${state.criesInstalled} OF 151 CRIES",
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
    installed = "${state.followersInstalled} OF 251 SHEETS",
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
    installed = "${state.spritesInstalled} SPRITES",
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
 * Everything the cartridge's PC menu does not have.
 *
 * The main menu is the PC's own, so this screen carries the rest: the saves on
 * the account, transfers, the sprite download, the save files, and the app's
 * own settings.
 */
@Composable
fun OptionsScreen(state: UiState, model: StorageViewModel, onShareReport: () -> Unit) {
    // The system's own pickers. Nothing is read or written outside the one
    // file the player points at, and the app asks for no storage permission.
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let(model::exportTo) }
    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(model::importFrom) }
    val entries = listOf<Pair<String, () -> Unit>>(
        "DOWNLOADS" to { model.open(Screen.Downloads) },
        "SOUND FX" to { model.open(Screen.SoundEffects) },
    )
    val cursor = rememberCursorLayer(entries.size) { entries[it].second() }

    ScreenColumn {
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                entries.forEachIndexed { index, (label, action) ->
                    Gen1MenuRow(label, cursor == index, {}, action)
                }
            }
        }
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                GbText("COLOR")
                GbText("THE SCREEN AND THE SPRITES.", style = Gen1TextSmall)
                Spacer(Modifier.height(4.dp))
                GbPalette.ALL.forEach { palette ->
                    // The swatch is part of the control, not a picture beside
                    // it: tapping the colours is the obvious way to pick them.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .gen1Clickable { model.setPalette(palette.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Gen1MenuRow(
                            palette.label,
                            selected = state.paletteId == palette.id,
                            onSelect = { model.setPalette(palette.id) },
                            onConfirm = { model.setPalette(palette.id) },
                            modifier = Modifier.weight(1f),
                        )
                        PaletteSwatch(palette)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Gen1Toggle(
                    label = "BLACK ON WHITE BOXES",
                    on = !state.windowsFollowPalette,
                    onToggle = { model.setWindowsFollowPalette(!state.windowsFollowPalette) },
                )

            }
        }
        item {
            Gen1Frame {
                GbText("SAVE SYNC")
                Gen1Field("STATUS", if (state.linked) "LINKED" else "NOT LINKED")
                state.lastSyncedAtMillis?.let {
                    Gen1Field("LAST SYNC", java.time.Instant.ofEpochMilli(it).toString().take(19))
                }
                model.linkedDeviceLabel?.let { Gen1Field("THIS DEVICE", it) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.linked) {
                        val audio = LocalGen1Audio.current
                        Gen1Button(
                            "SYNC NOW",
                            { audio?.play(SoundEffect.SAVE); model.sync() },
                            enabled = !state.syncing,
                        )
                        Gen1Button("UNLINK", {
                            model.prompt(
                                Prompt.Confirm(
                                    lines = listOf(
                                        "UNLINK THIS DEVICE?",
                                        "YOUR STORAGE BOXES STAY ON THIS DEVICE.",
                                    ),
                                    confirmLabel = "UNLINK",
                                    onConfirm = { model.unlink() },
                                )
                            )
                        })
                    } else {
                        Gen1Button("ENTER SYNC CODES", { model.open(Screen.Link) })
                    }
                }
            }
        }
        item {
            Gen1Frame {
                GbText("THE PC")
                Gen1Field("STORED", "${state.storage.total} POKéMON")
                GbText("EXPORTS AS A LUA FILE.", style = Gen1TextSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Gen1Button(
                        "EXPORT",
                        { exportFile.launch(model.exportFileName()) },
                        enabled = state.storage.total > 0,
                    )
                    // Anything, not text/plain: a .lua a file manager has
                    // never seen is handed over with no type at all, and a
                    // filtered picker would simply grey it out.
                    Gen1Button("IMPORT", { importFile.launch(arrayOf("*/*")) })
                }
            }
        }
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                GbText("CONTROLS")
                Spacer(Modifier.height(gen1Dp(2)))
                Gen1Toggle(
                    label = "ALIGNMENT",
                    on = state.windowsOnRight,
                    onToggle = { model.setWindowsOnRight(!state.windowsOnRight) },
                    onLabel = "RIGHT",
                    offLabel = "LEFT",
                )
                Gen1Toggle(
                    label = "ALL POKéMON",
                    on = state.showAllSaves,
                    onToggle = { model.setShowAllSaves(!state.showAllSaves) },
                )
                Gen1Toggle(
                    label = "WITHDRAW/DEPOSIT",
                    on = state.classicTransferLabels,
                    onToggle = { model.setClassicTransferLabels(!state.classicTransferLabels) },
                )
            }
        }
        item {
            Gen1Frame(Modifier.wrapContentWidth()) {
                var cursor by remember { mutableStateOf(false) }
                Gen1MenuRow("SEND REPORT", cursor, { cursor = true }, onShareReport)
            }
        }
        item {
            val context = LocalContext.current
            Gen1Frame(Modifier.wrapContentWidth()) {
                GbText("LOGIE 2026")
                GbText("VISIT MY RECOMP MOD REPO:", style = Gen1TextSmall)
                // The whole line is the link. A URL at this size is a hard
                // thing to hit, and the row around it is not doing anything
                // else.
                Gen1MenuRow(
                    "github.com/logie-github/TM-Case",
                    selected = false,
                    onSelect = {},
                    onConfirm = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(TM_CASE_REPO))
                            )
                        }
                    },
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Gen1Button("CREDITS", { model.open(Screen.Credits) })
            }
        }
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
            Gen1Button("OK", { onDone(name) })
            Gen1Button("CLEAR", { onDone("") })
            Gen1Button("CANCEL", onCancel)
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
private fun SavePicker(
    title: String,
    state: UiState,
    onChoose: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Gen1Frame {
        GbText(title)
        if (state.saves.isEmpty()) {
            GbText("NO SAVES FOUND.", style = Gen1TextSmall)
        }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            itemsIndexed(state.saves) { _, remote ->
                val save = state.save(remote.key)?.save
                val trainer = save?.trainerName ?: remote.summary.trainerName ?: remote.label
                Gen1MenuRow(
                    "${remote.version.label}  ${trainer.uppercase()}",
                    selected = state.activeSaveKey == remote.key,
                    onSelect = { onChoose(remote.key) },
                    onConfirm = { onChoose(remote.key) },
                    trailing = save?.let { "${it.partyCount}/${Gen1RecompSave.PARTY_MAX}" } ?: "",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Gen1Button("CANCEL", onCancel)
    }
}

/** The four shades of a palette, drawn as the ramp itself so it can be judged. */
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
) {
    Gen1MenuRow(
        label,
        selected = on,
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
                Spacer(Modifier.height(10.dp))
                Gen1Button("OK", model::dismissPrompt)
            }

            is Prompt.Confirm -> Gen1DialogueBox(prompt.lines.map { it.uppercase() }) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Gen1Button(prompt.confirmLabel, prompt.onConfirm)
                    Gen1Button(prompt.cancelLabel, model::dismissPrompt)
                }
            }

            is Prompt.ChooseBox -> Gen1Frame {
                GbText(prompt.title)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed((1..StorageLayout.BOX_COUNT).toList()) { _, index ->
                        val box = state.storage.boxes.getOrNull(index - 1)
                        Gen1MenuRow(
                            box?.label ?: "BOX $index",
                            selected = false,
                            onSelect = { prompt.onChoose(index) },
                            onConfirm = { prompt.onChoose(index) },
                            trailing = "${box?.contents?.size ?: 0}/${StorageLayout.BOX_CAPACITY}",
                            enabled = (box?.freeSlots ?: 0) > 0,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Gen1Button("CANCEL", model::dismissPrompt)
            }

            is Prompt.RenameBox -> NamePrompt(
                heading = "BOX ${prompt.index}",
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
                Gen1Frame(Modifier.wrapContentWidth()) {
                    GbText("VERSION ${prompt.version} IS OUT.")
                    GbText("UPDATE?", style = Gen1TextSmall)
                    Spacer(Modifier.height(gen1Dp(3)))
                    Row(horizontalArrangement = Arrangement.spacedBy(gen1Dp(3))) {
                        Gen1Button("YES", {
                            model.dismissPrompt()
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(prompt.url))
                                )
                            }
                        })
                        Gen1Button("NO", model::dismissPrompt)
                    }
                }
            }

            is Prompt.ChooseDepositSave -> SavePicker(
                title = prompt.title,
                state = state,
                onChoose = { key -> model.selectSave(key) },
                onCancel = model::dismissPrompt,
            )

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

/**
 * Which of the interface's sounds may be heard.
 *
 * One switch over the lot, and under it one per sound. A recording that is not
 * on the device is marked so, because a switch that is on and silent is worse
 * than one that says why.
 */
@Composable
fun SoundEffectsScreen(state: UiState, model: StorageViewModel) {
    ScreenColumn {
        item {
            Gen1Frame {
                GbText("SOUND FX", style = Gen1TextLarge)
            }
        }
        item {
            Gen1Frame {
                Gen1Toggle(
                    label = "DISABLE ALL",
                    on = state.soundOff,
                    onToggle = { model.setSoundOff(!state.soundOff) },
                )
            }
        }
        if (!state.soundOff) {
            item {
                Gen1Frame {
                    SoundEffect.entries.forEach { effect ->
                        Gen1Toggle(
                            label = effect.label,
                            on = effect.id in state.soundsOn,
                            onToggle = {
                                model.setSoundEnabled(effect, effect.id !in state.soundsOn)
                            },
                        )
                    }
                }
            }
        }
    }
}
