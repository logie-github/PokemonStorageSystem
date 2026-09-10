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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassification
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.storage.StorageLayout
import com.logie.gen1storage.storage.StoredPokemon
import com.logie.gen1storage.sync.LoadedSave
import com.logie.gen1storage.sync.RemoteSave
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.transfer.SaveLocation
import com.logie.gen1storage.transfer.WithdrawTarget
import java.time.Instant

/** The one-line form used in every Generation I list. */
fun pokemonRowLabel(pokemon: Gen1Pokemon): String = pokemon.displayName.uppercase()

fun pokemonRowLevel(pokemon: Gen1Pokemon): String = ":L${pokemon.level}"

/**
 * The app's top level is the PC's own storage menu. Everything this app adds
 * that the cartridge never had — access to saves, sprites, colours,
 * diagnostics — sits behind OPTIONS.
 */
@Composable
fun HomeScreen(state: UiState, model: StorageViewModel) {
    StorageSystemScreen(
        state = state,
        model = model,
        key = state.activeSaveKey,
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
                Spacer(Modifier.height(6.dp))
                GbText(
                    "IN GEN1RECOMP, OPEN SAVE SYNC AND READ OFF THE TWO CODES.",
                    style = Gen1TextSmall,
                )
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
                    GbText("EACH CODE IS EIGHT DIGITS.", style = Gen1TextSmall)
                }
            }
        }
        item {
            Gen1Frame {
                GbText(
                    "THIS DEVICE WILL APPEAR IN THE GAME'S DEVICE LIST AND CAN READ AND WRITE EVERY SAVE ON THE ACCOUNT. TREAT THE CODES LIKE A PASSWORD.",
                    style = Gen1TextSmall,
                )
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

@Composable
fun SaveListScreen(state: UiState, model: StorageViewModel) {
    var selected by remember { mutableStateOf(-1) }
    ScreenColumn {
        item {
            Gen1Frame {
                GbText("ACCESS SAVE")
                GbText("CHOOSE A PLAYTHROUGH.", style = Gen1TextSmall)
                state.lastSyncedAtMillis?.let {
                    GbText("SYNCED ${Instant.ofEpochMilli(it)}", style = Gen1TextSmall)
                }
            }
        }
        if (state.syncing) item { Gen1Frame { GbText("CHECKING THE ACCOUNT...") } }
        itemsIndexed(state.saves) { index, remote ->
            SaveCard(remote, state.save(remote.key), selected == index, { selected = index }) {
                model.openSave(remote.key)
            }
        }
        if (!state.syncing && state.saves.isEmpty()) {
            item {
                Gen1Frame {
                    GbText("NO SAVES ON THE ACCOUNT.")
                    Spacer(Modifier.height(6.dp))
                    GbText(
                        "SAVE IN THE GAME, THEN TAP SYNC NOW IN ITS SAVE SYNC DIALOG.",
                        style = Gen1TextSmall,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gen1Button("REFRESH", { model.sync() }, enabled = !state.syncing)
                if (state.showAllSaves) {
                    Gen1Button("ALL POKéMON", { model.open(Screen.AllPokemon) })
                }
            }
        }
    }
}

@Composable
private fun SaveCard(
    remote: RemoteSave,
    loaded: LoadedSave?,
    selected: Boolean,
    onSelect: () -> Unit,
    onConfirm: () -> Unit,
) {
    Gen1Frame {
        Gen1MenuRow(
            "${remote.version.label}   ${remote.summary.trainerName ?: remote.label}",
            selected,
            onSelect,
            onConfirm,
        )
        remote.summary.badges?.let { Gen1Field("BADGES", it.toString()) }
        remote.summary.timeText?.let { Gen1Field("PLAY TIME", it) }
        remote.summary.dexCount?.let { Gen1Field("POKéDEX", it.toString()) }
        val save = loaded?.save
        if (save != null) {
            Gen1Field("PARTY", "${save.partyCount}/${Gen1RecompSave.PARTY_MAX}")
            Gen1Field("IN BOXES", save.storedCount.toString())
            Gen1Field("IDNo/", save.trainerId?.let { "%05d".format(it) } ?: "?")
        }
        val status = loaded?.classification
        if (status != null && (status !is SaveClassification.Valid || status.warnings.isNotEmpty())) {
            GbText(status.summary.uppercase(), style = Gen1TextSmall)
        }
    }
}

/** "<TRAINER> turned on the PC." then the PC's own menu. */
@Composable
fun PcScreen(state: UiState, model: StorageViewModel, key: String) {
    val loaded = state.save(key)
    val save = loaded?.save
    if (save == null) {
        ScreenColumn { item { Gen1Frame { GbText("OPEN THIS SAVE FROM ACCESS SAVE FIRST.") } } }
        return
    }
    var selected by remember(key) { mutableStateOf(0) }
    PcMainScreen(
        trainerName = save.trainerName.uppercase(),
        selected = selected,
        onSelect = { selected = it },
        onStorageSystem = { model.open(Screen.StorageSystem(key)) },
        onTrainerPc = { model.open(Screen.SaveMenu(key)) },
        onLogOff = { model.back() },
        message = listOf("${save.trainerName.uppercase()} turned on", "the PC."),
    )
}

/** The trainer's own PC: their party and their in-game boxes, to look through. */
@Composable
fun SaveMenuScreen(state: UiState, model: StorageViewModel, key: String) {
    val save = state.save(key)?.save
    if (save == null) {
        ScreenColumn { item { Gen1Frame { GbText("OPEN THIS SAVE FIRST.") } } }
        return
    }
    var selected by remember(key) { mutableStateOf(0) }
    ScreenColumn {
        item {
            Gen1Frame {
                GbText("${save.trainerName.uppercase()}'s PC")
                Gen1Field("BADGES", save.badgeCount.toString())
                Gen1Field("PLAY TIME", save.playTimeText)
            }
        }
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Gen1MenuRow(
                    "PARTY POKéMON",
                    selected == 0,
                    { selected = 0 },
                    { model.open(Screen.SaveParty(key)) },
                    trailing = "${save.partyCount}/${Gen1RecompSave.PARTY_MAX}",
                )
                save.boxes.forEachIndexed { index, box ->
                    val number = index + 1
                    Gen1MenuRow(
                        save.boxName(number),
                        selected == number,
                        { selected = number },
                        { model.open(Screen.SaveBox(key, number)) },
                        trailing = "${box.size}/${Gen1RecompSave.BOX_CAPACITY}",
                    )
                }
            }
        }
    }
}

/**
 * The storage system itself.
 *
 * TRANSFER lists both sides at once — the current box, then the open save's
 * party — so the Pokémon is picked before the direction, and the direction is
 * then whichever way it has to go. VIEW POKéMON lists only what this app holds,
 * where a Pokémon can be moved between boxes, looked at, or released.
 */
@Composable
fun StorageSystemScreen(
    state: UiState,
    model: StorageViewModel,
    key: String?,
    onOptions: (() -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(PcMode.MENU) }
    var listCursor by remember(mode) { mutableStateOf(0) }
    var actionCursor by remember(mode) { mutableStateOf(0) }
    var chosen by remember(mode) { mutableStateOf<Int?>(null) }

    val box = state.storage.boxes.getOrNull(state.currentStorageBox - 1)
    val stored = box?.contents.orEmpty()
    val save = key?.let { state.save(it)?.save }
    val boxLabel = box?.name?.uppercase() ?: "BOX ${state.currentStorageBox}"

    // One list, both sides. The index a row sits at is what the cursor and the
    // action window work from, so the two halves are built together.
    val partyOffset = stored.size
    val rows = buildList {
        stored.forEachIndexed { index, entry ->
            add(
                MonRow(
                    pokemonRowLabel(entry.pokemon),
                    pokemonRowLevel(entry.pokemon),
                    header = if (index == 0) boxLabel else null,
                )
            )
        }
        val party = save?.party.orEmpty()
        party.forEachIndexed { index, mon ->
            add(
                MonRow(
                    pokemonRowLabel(mon),
                    pokemonRowLevel(mon),
                    header = if (index == 0) "${save?.trainerName?.uppercase()}'s PARTY" else null,
                )
            )
        }
    }

    StorageSystemScreen(
        boxNumber = state.currentStorageBox,
        boxName = box?.name ?: "BOX ${state.currentStorageBox}",
        selected = selected,
        onSelect = { selected = it },
        onTransfer = { mode = PcMode.TRANSFER },
        onView = { mode = PcMode.VIEW },
        onChangeBox = { mode = PcMode.CHANGE_BOX },
        onOptions = onOptions,
        message = when {
            !state.linked -> "Link this device in OPTIONS."
            save == null -> "Open a save in OPTIONS."
            else -> "What?"
        },
        overlay = when (mode) {
            PcMode.MENU -> null

            PcMode.TRANSFER -> ({
                MonListOverlay(
                    entries = rows,
                    selected = listCursor,
                    onSelect = { listCursor = it; chosen = it.takeIf { i -> i < rows.size } },
                    onConfirm = { chosen = it },
                    onCancel = { mode = PcMode.MENU },
                    emptyMessage = "What? There are no POKéMON here!",
                    action = {
                        val index = chosen
                        when {
                            index == null || index >= rows.size -> Unit

                            // In the box already: the only way it can go is out.
                            index < partyOffset -> {
                                val pick = stored[index]
                                MonActionOverlay(
                                    actions = listOf(
                                        MonAction(
                                            "WITHDRAW",
                                            {
                                                if (key == null) {
                                                    model.prompt(
                                                        Prompt.Message(
                                                            listOf("OPEN A SAVE FIRST.", "OPTIONS → ACCESS SAVE.")
                                                        )
                                                    )
                                                } else {
                                                    model.prompt(Prompt.ChooseWithdrawTarget(pick.uid, key))
                                                }
                                            },
                                            enabled = key != null,
                                        ),
                                        MonAction("STATS", {
                                            model.open(
                                                Screen.Status(null, state.currentStorageBox, index)
                                            )
                                        }),
                                    ),
                                    selected = actionCursor,
                                    onSelect = { actionCursor = it },
                                    onCancel = { chosen = null },
                                    note = if (key == null) "NO SAVE IS OPEN" else null,
                                )
                            }

                            // Still in the save: the only way it can go is in.
                            else -> {
                                val slot = index - partyOffset
                                val pick = save?.party?.getOrNull(slot)
                                if (pick != null && key != null) {
                                    val last = (save.partyCount) <= 1
                                    MonActionOverlay(
                                        actions = listOf(
                                            MonAction(
                                                "DEPOSIT",
                                                {
                                                    model.prompt(
                                                        Prompt.Confirm(
                                                            lines = listOf("DEPOSIT ${pick.displayName.uppercase()}?"),
                                                            confirmLabel = "DEPOSIT",
                                                            onConfirm = {
                                                                model.depositFromSave(
                                                                    key,
                                                                    SaveLocation.Party(slot + 1),
                                                                    state.currentStorageBox,
                                                                )
                                                            },
                                                        )
                                                    )
                                                },
                                                enabled = !last,
                                            ),
                                            MonAction("STATS", { model.open(Screen.Status(key, 0, slot)) }),
                                        ),
                                        selected = actionCursor,
                                        onSelect = { actionCursor = it },
                                        onCancel = { chosen = null },
                                        note = if (last) "CAN'T DEPOSIT THE LAST ONE" else null,
                                    )
                                }
                            }
                        }
                    },
                )
            })

            PcMode.VIEW -> ({
                // Deliberately cannot transfer: this side only rearranges the
                // PC, opens a status screen, or releases — so there is no way
                // to reach a save from here by accident.
                MonListOverlay(
                    entries = stored.map {
                        MonRow(pokemonRowLabel(it.pokemon), pokemonRowLevel(it.pokemon))
                    },
                    selected = listCursor,
                    onSelect = { listCursor = it; chosen = it.takeIf { i -> i < stored.size } },
                    onConfirm = { chosen = it },
                    onCancel = { mode = PcMode.MENU },
                    emptyMessage = "What? There are no POKéMON here!",
                    action = {
                        val pick = chosen?.let { stored.getOrNull(it) }
                        if (pick != null) {
                            val name = pick.pokemon.displayName.uppercase()
                            MonActionOverlay(
                                actions = listOf(
                                    MonAction("MOVE", {
                                        model.prompt(
                                            Prompt.ChooseBox("MOVE TO WHICH BOX?") { target ->
                                                model.moveStored(pick.uid, target)
                                                chosen = null
                                            }
                                        )
                                    }),
                                    MonAction("STATS", {
                                        model.open(
                                            Screen.Status(null, state.currentStorageBox, stored.indexOf(pick))
                                        )
                                    }),
                                    MonAction("RELEASE", {
                                        model.prompt(
                                            Prompt.Confirm(
                                                lines = listOf(
                                                    "RELEASE $name?",
                                                    "IT LEAVES THE PC AND DOES NOT COME BACK.",
                                                ),
                                                confirmLabel = "RELEASE",
                                                onConfirm = {
                                                    model.releaseStored(pick.uid)
                                                    chosen = null
                                                },
                                            )
                                        )
                                    }),
                                ),
                                selected = actionCursor,
                                onSelect = { actionCursor = it },
                                onCancel = { chosen = null },
                            )
                        }
                    },
                )
            })

            PcMode.CHANGE_BOX -> ({
                ChangeBoxOverlay(
                    boxes = state.storage.boxes.map {
                        Triple(it.index, it.name, "${it.contents.size}/${StorageLayout.BOX_CAPACITY}")
                    },
                    selected = listCursor,
                    onSelect = { listCursor = it },
                    onConfirm = { model.setStorageBox(it); mode = PcMode.MENU },
                    onCancel = { mode = PcMode.MENU },
                )
            })
        },
    )
}

private enum class PcMode { MENU, TRANSFER, VIEW, CHANGE_BOX }

@Composable
fun SavePartyScreen(state: UiState, model: StorageViewModel, key: String) {
    val save = state.save(key)?.save ?: return
    MonBrowseScreen(
        title = "${save.trainerName.uppercase()}'s PARTY",
        entries = save.party,
        emptyMessage = "There are no POKéMON here.",
        onStats = { index -> model.open(Screen.Status(key, 0, index)) },
    )
}

@Composable
fun SaveBoxScreen(state: UiState, model: StorageViewModel, key: String, box: Int) {
    val save = state.save(key)?.save ?: return
    MonBrowseScreen(
        title = save.boxName(box),
        entries = save.boxes.getOrNull(box - 1).orEmpty(),
        emptyMessage = "What? There are no POKéMON here!",
        onStats = { index -> model.open(Screen.Status(key, box, index)) },
    )
}

@Composable
private fun MonBrowseScreen(
    title: String,
    entries: List<Gen1Pokemon>,
    emptyMessage: String,
    onStats: (Int) -> Unit,
) {
    var cursor by remember(title, entries.size) { mutableStateOf(-1) }
    ScreenColumn {
        item { Gen1Frame { GbText(title); GbText("${entries.size} POKéMON", style = Gen1TextSmall) } }
        if (entries.isEmpty()) item { Gen1Frame { GbText(emptyMessage) } }
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                entries.forEachIndexed { index, pokemon ->
                    Gen1MenuRow(
                        pokemonRowLabel(pokemon),
                        cursor == index,
                        { cursor = index },
                        { onStats(index) },
                        trailing = pokemonRowLevel(pokemon),
                    )
                }
            }
        }
    }
}

/** Every save's Pokémon in one list, when the option is on. */
@Composable
fun AllPokemonScreen(state: UiState, model: StorageViewModel) {
    var cursor by remember { mutableStateOf(-1) }
    data class Row(val key: String, val area: Int, val slot: Int, val where: String, val mon: Gen1Pokemon)

    val rows = buildList {
        state.saves.forEach { remote ->
            val save = state.save(remote.key)?.save ?: return@forEach
            val trainer = save.trainerName.uppercase()
            save.party.forEachIndexed { index, mon ->
                add(Row(remote.key, 0, index, "${remote.version.label} $trainer PARTY", mon))
            }
            save.boxes.forEachIndexed { boxIndex, box ->
                box.forEachIndexed { index, mon ->
                    add(Row(remote.key, boxIndex + 1, index, "${remote.version.label} $trainer BOX ${boxIndex + 1}", mon))
                }
            }
        }
    }

    ScreenColumn {
        item {
            Gen1Frame {
                GbText("ALL POKéMON")
                GbText(
                    "${rows.size} ACROSS ${state.saves.count { state.save(it.key)?.isUsable == true }} SAVE(S)",
                    style = Gen1TextSmall,
                )
                if (state.loadingAll) GbText("LOADING SAVES...", style = Gen1TextSmall)
                Spacer(Modifier.height(8.dp))
                Gen1Button("RELOAD", { model.loadAllSaves() }, enabled = !state.loadingAll)
            }
        }
        if (state.storage.total > 0) {
            item {
                Gen1Frame {
                    GbText("IN THIS APP'S PC")
                    GbText("${state.storage.total} STORED", style = Gen1TextSmall)
                }
            }
        }
        itemsIndexed(rows) { index, row ->
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Gen1MenuRow(
                    pokemonRowLabel(row.mon),
                    cursor == index,
                    { cursor = index },
                    { model.open(Screen.Status(row.key, row.area, row.slot)) },
                    trailing = pokemonRowLevel(row.mon),
                )
                GbText(row.where, style = Gen1TextSmall)
            }
        }
        if (rows.isEmpty() && !state.loadingAll) {
            item { Gen1Frame { GbText("NOTHING LOADED YET. TAP RELOAD.") } }
        }
    }
}

/** The full status screen for one Pokémon, wherever it lives. */
@Composable
fun StatusScreen(state: UiState, model: StorageViewModel, key: String?, area: Int, slot: Int) {
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
    ) {
        Gen1Button("BACK", { model.back() })
    }
}

/**
 * The sprite download. One question, then a percentage and a bar; the player
 * never has to think about where the art comes from or which file is which.
 */
@Composable
fun SpritesScreen(state: UiState, model: StorageViewModel) {
    val progress = state.spriteProgress
    val running = progress != null && !progress.finished

    ScreenColumn {
        item {
            Gen1Frame {
                GbText("POKéMON SPRITES")
                Spacer(Modifier.height(6.dp))
                GbText(
                    "SPRITES ARE SHOWN IN THE ART OF THE GAME A POKéMON CAME FROM. TAP AND HOLD ANY SPRITE TO PICK A DIFFERENT GAME.",
                    style = Gen1TextSmall,
                )
            }
        }

        if (progress != null) {
            item {
                Gen1Frame(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
                    SpriteProgressBar(progress.percent, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    if (progress.finished) {
                        GbText(
                            if (progress.error != null) "STOPPED: ${progress.error.uppercase()}"
                            else if (progress.failed > 0) "DONE. ${progress.failed} COULD NOT BE FETCHED."
                            else "DONE.",
                            style = Gen1TextSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Gen1Button("OK", model::dismissSpriteProgress)
                    } else {
                        GbText("${progress.done} OF ${progress.total}", style = Gen1TextSmall)
                        Spacer(Modifier.height(8.dp))
                        Gen1Button("STOP", model::cancelSpriteDownload)
                    }
                }
            }
        }

        if (!running) {
            item {
                Gen1Frame {
                    Gen1Field("ON THIS DEVICE", "${state.spritesInstalled} SPRITES")
                    if (state.spritesInstalled > 0) {
                        Gen1Field("SPACE USED", "${model.spriteBytesOnDisk() / (1024 * 1024)} MB")
                    } else {
                        // Full-size art, so the download is worth naming up front.
                        GbText("ABOUT 25 MB FOR BOTH GAMES.", style = Gen1TextSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Gen1Button(
                            if (state.spritesInstalled > 0) "DOWNLOAD MISSING" else "DOWNLOAD",
                            {
                                model.prompt(
                                    Prompt.Confirm(
                                        lines = listOf("Download Pokémon sprites from repo?"),
                                        confirmLabel = "YES",
                                        onConfirm = { model.downloadSprites() },
                                    )
                                )
                            },
                        )
                        if (state.spritesInstalled > 0) {
                            Gen1Button("DELETE", {
                                model.prompt(
                                    Prompt.Confirm(
                                        lines = listOf("DELETE EVERY DOWNLOADED SPRITE?"),
                                        confirmLabel = "DELETE",
                                        onConfirm = { model.deleteSprites() },
                                    )
                                )
                            })
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun SaveFilesScreen(state: UiState, model: StorageViewModel) {
    ScreenColumn {
        item {
            Gen1Frame {
                GbText("SAVE FILES")
                GbText(
                    "EVERY WRITE UPLOADS AGAINST THE REVISION IT READ, SO THE SERVER REFUSES IT IF THE GAME SAVED FIRST.",
                    style = Gen1TextSmall,
                )
            }
        }
        if (state.recoveryNotes.isNotEmpty()) {
            item {
                Gen1Frame {
                    GbText("RECOVERY")
                    state.recoveryNotes.forEach { GbText(it.uppercase(), style = Gen1TextSmall) }
                }
            }
        }
        model.pendingTransferSummary()?.let { pending ->
            item {
                Gen1Frame {
                    GbText("UNFINISHED TRANSFER")
                    GbText(pending.uppercase(), style = Gen1TextSmall)
                    Spacer(Modifier.height(8.dp))
                    Gen1Button("CLEAR RECORD", model::clearPendingTransferRecord)
                }
            }
        }
        model.releasedCount().takeIf { it > 0 }?.let { released ->
            item {
                Gen1Frame {
                    GbText("RELEASED")
                    Gen1Field("RECORDED", released.toString())
                    GbText(
                        "A RELEASE LEAVES THE PC, BUT THE ENTRY IS KEPT IN A LOG BESIDE THE STORAGE FILE SO IT IS NOT GONE.",
                        style = Gen1TextSmall,
                    )
                }
            }
        }
        item {
            Gen1Frame {
                GbText("LOCAL BACKUPS")
                val backups = model.localBackups()
                if (backups.isEmpty()) {
                    GbText("NONE YET. ONE IS KEPT EACH TIME A SAVE IS WRITTEN.", style = Gen1TextSmall)
                }
                backups.take(20).forEach { Gen1Field(it.key, "REV ${it.rev}") }
            }
        }
        if (state.diagnostics.isNotEmpty()) {
            item {
                Gen1Frame {
                    GbText("WHAT THE ACCOUNT HOLDS")
                    state.diagnostics.forEach { GbText(it, style = Gen1TextSmall) }
                }
            }
        }
    }
}

/**
 * Everything the cartridge's PC menu does not have.
 *
 * The main menu is the PC's own, so this screen carries the rest: the saves on
 * the account, transfers, the sprite download, the save files, and the app's
 * own settings.
 */
@Composable
fun OptionsScreen(state: UiState, model: StorageViewModel, onShareReport: () -> Unit) {
    var cursor by remember { mutableStateOf(-1) }
    val entries = buildList<Triple<String, String, () -> Unit>> {
        add(
            Triple("ACCESS SAVE", "${state.saves.size} SAVE(S) ON THE ACCOUNT") {
                if (state.linked) model.open(Screen.SaveList) else model.open(Screen.Link)
            }
        )
        if (state.showAllSaves) {
            add(Triple("ALL POKéMON", "EVERY SAVE IN ONE LIST") { model.open(Screen.AllPokemon) })
        }
        add(
            Triple(
                "DOWNLOAD SPRITES",
                if (state.spritesInstalled > 0) "${state.spritesInstalled} SPRITES READY" else "NOT DOWNLOADED",
            ) { model.open(Screen.Sprites) }
        )
        add(Triple("SAVE FILES", "BACKUPS AND REPAIR") { model.open(Screen.SaveFiles) })
    }

    ScreenColumn {
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                entries.forEachIndexed { index, (label, subtitle, action) ->
                    Gen1MenuRow(label, cursor == index, { cursor = index }, action)
                    GbText(subtitle, style = Gen1TextSmall)
                }
            }
        }
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                GbText("COLOUR")
                GbText("THE SCREEN AND THE SPRITES.", style = Gen1TextSmall)
                Spacer(Modifier.height(4.dp))
                GbPalette.ALL.forEach { palette ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                GbText(
                    "ON KEEPS EVERY WINDOW BLACK ON WHITE, THE WAY THE GAMES DRAW THEM. OFF LETS THEM TAKE THE PALETTE TOO.",
                    style = Gen1TextSmall,
                )
            }
        }
        item {
            Gen1Frame {
                GbText("SAVE SYNC")
                Gen1Field("STATUS", if (state.linked) "LINKED" else "NOT LINKED")
                model.linkedDeviceLabel?.let { Gen1Field("THIS DEVICE", it) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.linked) {
                        Gen1Button("SYNC NOW", { model.sync() }, enabled = !state.syncing)
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
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                GbText("CONTROLS")
                Spacer(Modifier.height(4.dp))
                Gen1Toggle(
                    label = "SWIPE CONTROLS",
                    on = state.swipeControls,
                    onToggle = { model.setSwipeControls(!state.swipeControls) },
                )
                GbText(
                    "SWIPE FOR THE D-PAD, TAP FOR A, TAP AND HOLD FOR B, DOUBLE TAP FOR START, TAP THEN HOLD FOR SELECT.",
                    style = Gen1TextSmall,
                )
                Spacer(Modifier.height(10.dp))
                Gen1Toggle(
                    label = "SHOW ALL SAVES AT ONCE",
                    on = state.showAllSaves,
                    onToggle = { model.setShowAllSaves(!state.showAllSaves) },
                )
                GbText(
                    "LOADS EVERY SAVE ON THE ACCOUNT AND LISTS THEIR POKéMON TOGETHER.",
                    style = Gen1TextSmall,
                )
            }
        }
        item {
            Gen1Frame {
                GbText("DEVICES ON THE ACCOUNT")
                val devices = state.account?.devices.orEmpty()
                if (devices.isEmpty()) GbText("NOT KNOWN YET.", style = Gen1TextSmall)
                devices.forEach { Gen1Field(it.label, if (it.isThisDevice) "THIS DEVICE" else "") }
            }
        }
        item {
            Gen1Frame {
                GbText("DIAGNOSTICS")
                Gen1Field("SAVES", state.saves.size.toString())
                Gen1Field("STORED", state.storage.total.toString())
                Spacer(Modifier.height(8.dp))
                Gen1Button("SEND REPORT", onShareReport)
                Spacer(Modifier.height(6.dp))
                GbText(
                    "NO SYNC CODES, ACCOUNT DETAILS, POKéMON OR SAVE CONTENTS ARE INCLUDED.",
                    style = Gen1TextSmall,
                )
            }
        }
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

/** An on/off row drawn as a menu entry with its state in the right column. */
@Composable
private fun Gen1Toggle(label: String, on: Boolean, onToggle: () -> Unit) {
    Gen1MenuRow(label, selected = on, onSelect = onToggle, onConfirm = onToggle, trailing = if (on) "ON" else "OFF")
}

@Composable
fun ScreenColumn(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Gen1Palette.Surround),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun PromptWindow(state: UiState, model: StorageViewModel) {
    val prompt = state.prompt ?: return
    Box(
        Modifier
            .fillMaxSize()
            .background(Gen1Palette.Surround.copy(alpha = 0.85f))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
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
                    Gen1Button("CANCEL", model::dismissPrompt)
                }
            }

            is Prompt.ChooseBox -> Gen1Frame {
                GbText(prompt.title)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed((1..StorageLayout.BOX_COUNT).toList()) { _, index ->
                        val box = state.storage.boxes.getOrNull(index - 1)
                        Gen1MenuRow(
                            box?.name ?: "BOX $index",
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

            is Prompt.ChooseSpriteSet -> SpriteSetPicker(
                speciesId = prompt.speciesId,
                store = model.sprites,
                onChoose = { model.chooseSpriteSet(prompt.speciesId, it) },
                onCancel = model::dismissPrompt,
            )

            is Prompt.ChooseWithdrawTarget -> {
                val save = state.save(prompt.key)?.save
                Gen1Frame {
                    GbText("PUT IT WHERE?")
                    if (save == null) {
                        GbText("THAT SAVE IS NOT OPEN.")
                    } else {
                        Gen1MenuRow(
                            "PARTY",
                            selected = false,
                            onSelect = { model.withdrawToSave(prompt.uid, prompt.key, WithdrawTarget.Party) },
                            onConfirm = { model.withdrawToSave(prompt.uid, prompt.key, WithdrawTarget.Party) },
                            trailing = "${save.partyCount}/${Gen1RecompSave.PARTY_MAX}",
                            enabled = save.partyCount < Gen1RecompSave.PARTY_MAX,
                        )
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            itemsIndexed(save.boxes) { index, box ->
                                val number = index + 1
                                Gen1MenuRow(
                                    save.boxName(number),
                                    selected = false,
                                    onSelect = {
                                        model.withdrawToSave(prompt.uid, prompt.key, WithdrawTarget.Box(number))
                                    },
                                    onConfirm = {
                                        model.withdrawToSave(prompt.uid, prompt.key, WithdrawTarget.Box(number))
                                    },
                                    trailing = "${box.size}/${Gen1RecompSave.BOX_CAPACITY}",
                                    enabled = box.size < Gen1RecompSave.BOX_CAPACITY,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Gen1Button("CANCEL", model::dismissPrompt)
                }
            }
        }
    }
}
