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
import androidx.compose.foundation.layout.padding
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

/**
 * Every screen is one scrolling column of Generation I windows. Selection is
 * "tap to move the cursor, tap again to confirm", so a row is never actioned by
 * accident and the rhythm still matches the games.
 */

@Composable
fun HomeScreen(state: UiState, model: StorageViewModel) {
    var selected by remember { mutableStateOf(0) }
    val entries = listOf(
        Triple("ACCESS SAVE", "${state.saves.size} SAVE(S) ON THE ACCOUNT") {
            if (state.linked) model.open(Screen.SaveList) else model.open(Screen.Link)
        },
        Triple("STORAGE BOXES", "${state.storage.total}/${StorageLayout.TOTAL_CAPACITY} STORED") {
            model.open(Screen.StorageBoxes(1))
        },
        Triple("TRANSFER", "MOVE BETWEEN SAVES") { model.open(Screen.Transfer) },
        Triple("SAVE FILES", "BACKUPS AND REPAIR") { model.open(Screen.SaveFiles) },
        Triple("OPTIONS", "SYNC AND DIAGNOSTICS") { model.open(Screen.Options) },
    )

    ScreenColumn {
        item {
            Gen1Window {
                GbText("POKéMON", style = Gen1TextLarge)
                GbText("STORAGE SYSTEM", style = Gen1TextLarge)
                Spacer(Modifier.height(6.dp))
                GbText("FOR GEN1RECOMP - RED, BLUE, YELLOW", style = Gen1TextSmall)
            }
        }
        item {
            Gen1Window(contentPadding = PaddingValues(vertical = 6.dp)) {
                entries.forEachIndexed { index, (label, subtitle, action) ->
                    Gen1MenuItem(
                        label = label,
                        subtitle = subtitle,
                        selected = selected == index,
                        onSelect = { selected = index },
                        onConfirm = action,
                    )
                }
            }
        }
        if (!state.linked) {
            item {
                Gen1Window(title = "NOT LINKED YET") {
                    GbText(
                        "OPEN SAVE SYNC IN GEN1RECOMP AND ENTER ITS TWO CODES HERE. YOUR SAVES THEN ARRIVE OVER THE NETWORK - NOTHING TO INSTALL, NO FOLDERS TO PICK.",
                        style = Gen1TextSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Gen1Button("ENTER SYNC CODES", { model.open(Screen.Link) })
                }
            }
        }
        if (state.recoveryNotes.isNotEmpty()) {
            item {
                Gen1Window(title = "RECOVERY") {
                    state.recoveryNotes.forEach { GbText(it.uppercase(), style = Gen1TextSmall) }
                }
            }
        }
    }
}

/**
 * Linking. The same two codes the game shows under SAVE SYNC, sent to the same
 * endpoint the game uses, so this device joins the account exactly as another
 * copy of the game would.
 */
@Composable
fun LinkScreen(state: UiState, model: StorageViewModel) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val ready = SyncApi.normalizeCode(first) != null && SyncApi.normalizeCode(second) != null

    ScreenColumn {
        item {
            Gen1Window(title = "SAVE SYNC") {
                GbText(
                    "IN GEN1RECOMP, OPEN SAVE SYNC AND READ OFF THE TWO CODES. ENTER THEM HERE.",
                    style = Gen1TextSmall,
                )
            }
        }
        item {
            Gen1Window(title = "SYNC CODES") {
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
            Gen1Window(title = "WHAT THIS MEANS") {
                GbText(
                    "THIS DEVICE WILL APPEAR IN THE GAME'S DEVICE LIST AND CAN READ AND WRITE EVERY SAVE ON THE ACCOUNT. TREAT THE CODES LIKE A PASSWORD - ANYONE WITH BOTH CAN DO THE SAME.",
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
            focusedContainerColor = Gen1Palette.Lightest,
            unfocusedContainerColor = Gen1Palette.Lightest,
            focusedTextColor = Gen1Palette.Ink,
            unfocusedTextColor = Gen1Palette.Ink,
            focusedIndicatorColor = Gen1Palette.Ink,
            unfocusedIndicatorColor = Gen1Palette.Dark,
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
            Gen1Window(title = "ACCESS SAVE") {
                GbText("CHOOSE A PLAYTHROUGH.", style = Gen1TextSmall)
                state.lastSyncedAtMillis?.let {
                    GbText("SYNCED ${Instant.ofEpochMilli(it)}", style = Gen1TextSmall)
                }
            }
        }
        if (state.syncing) item { Gen1Window { GbText("CHECKING THE ACCOUNT...") } }
        itemsIndexed(state.saves) { index, remote ->
            SaveCard(
                remote = remote,
                loaded = state.save(remote.key),
                selected = selected == index,
                onSelect = { selected = index },
                onConfirm = { model.openSave(remote.key) },
            )
        }
        if (!state.syncing && state.saves.isEmpty()) {
            item {
                Gen1Window {
                    GbText("NO SAVES ON THE ACCOUNT.")
                    Spacer(Modifier.height(6.dp))
                    GbText(
                        "SAVE IN THE GAME, THEN TAP SYNC NOW IN ITS SAVE SYNC DIALOG. REFRESH HERE AFTERWARDS.",
                        style = Gen1TextSmall,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gen1Button("REFRESH", { model.sync() }, enabled = !state.syncing)
                Gen1Button("OPTIONS", { model.open(Screen.Options) })
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
    Gen1Window {
        Gen1MenuItem(
            label = "${remote.version.label}   ${remote.summary.trainerName ?: remote.label}",
            subtitle = remote.slot?.uppercase() ?: "REV ${remote.rev}",
            selected = selected,
            onSelect = onSelect,
            onConfirm = onConfirm,
        )
        // The listing carries only a summary; the save itself is fetched when
        // it is opened, so these are the server's figures until then.
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

@Composable
fun SaveMenuScreen(state: UiState, model: StorageViewModel, key: String) {
    val loaded = state.save(key)
    val save = loaded?.save
    if (loaded == null || save == null) {
        ScreenColumn { item { Gen1Window { GbText("OPEN THIS SAVE FROM ACCESS SAVE FIRST.") } } }
        return
    }
    var selected by remember { mutableStateOf(0) }
    ScreenColumn {
        item {
            Gen1Window(title = "${save.trainerName}'s PC") {
                Gen1Field("GAME", loaded.remote.version.label)
                Gen1Field("BADGES", save.badgeCount.toString())
                Gen1Field("PLAY TIME", save.playTimeText)
                Gen1Field("REVISION", loaded.rev.toString())
            }
        }
        item {
            Gen1Window(contentPadding = PaddingValues(vertical = 6.dp)) {
                Gen1MenuItem(
                    label = "PARTY POKéMON",
                    subtitle = "${save.partyCount}/${Gen1RecompSave.PARTY_MAX}",
                    selected = selected == 0,
                    onSelect = { selected = 0 },
                    onConfirm = { model.open(Screen.SaveParty(key)) },
                )
                save.boxes.forEachIndexed { index, box ->
                    val number = index + 1
                    Gen1MenuItem(
                        label = save.boxName(number),
                        subtitle = "${box.size}/${Gen1RecompSave.BOX_CAPACITY}" +
                            if (save.currentBox == number) "  (CURRENT)" else "",
                        selected = selected == number,
                        onSelect = { selected = number },
                        onConfirm = { model.open(Screen.SaveBox(key, number)) },
                    )
                }
            }
        }
        item {
            Gen1Window(title = "TRANSFER") {
                GbText(
                    "DEPOSIT PUTS A POKéMON INTO THIS APP. WITHDRAW TAKES ONE OUT INTO THIS SAVE. THE GAME PICKS UP EITHER ON ITS NEXT SYNC.",
                    style = Gen1TextSmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Gen1Button("STORAGE BOXES", { model.open(Screen.StorageBoxes(1)) })
                    Gen1Button("CHANGE SAVE", { model.open(Screen.SaveList) })
                }
            }
        }
        item {
            Gen1Window(title = "RELEASE INFO") {
                GbText(
                    "THIS APP NEVER RELEASES A POKéMON. A TRANSFER MOVES IT, AND A COPY ONLY EVER EXISTS IN ONE PLACE AT A TIME.",
                    style = Gen1TextSmall,
                )
            }
        }
    }
}

@Composable
fun SavePartyScreen(state: UiState, model: StorageViewModel, key: String) {
    val save = state.save(key)?.save
    if (save == null) {
        ScreenColumn { item { Gen1Window { GbText("OPEN THIS SAVE FIRST.") } } }
        return
    }
    MonListScreen(
        title = "${save.trainerName}'s PARTY",
        entries = save.party,
        emptyMessage = "THERE ARE NO POKéMON HERE.",
        actionLabel = "DEPOSIT",
        actionEnabled = { save.partyCount > 1 },
        disabledReason = "YOU CAN'T DEPOSIT THE LAST POKéMON!",
        onAction = { index, pokemon ->
            model.prompt(
                Prompt.Confirm(
                    lines = listOf("DEPOSIT ${pokemon.displayName.uppercase()} INTO THE APP'S PC?"),
                    confirmLabel = "DEPOSIT",
                    onConfirm = { model.depositFromSave(key, SaveLocation.Party(index + 1), 1) },
                )
            )
        },
    )
}

@Composable
fun SaveBoxScreen(state: UiState, model: StorageViewModel, key: String, box: Int) {
    val save = state.save(key)?.save
    if (save == null) {
        ScreenColumn { item { Gen1Window { GbText("OPEN THIS SAVE FIRST.") } } }
        return
    }
    MonListScreen(
        title = "${save.trainerName} - ${save.boxName(box)}",
        entries = save.boxes.getOrNull(box - 1).orEmpty(),
        emptyMessage = "WHAT? THERE ARE NO POKéMON HERE!",
        actionLabel = "DEPOSIT",
        actionEnabled = { true },
        disabledReason = "",
        onAction = { index, pokemon ->
            model.prompt(
                Prompt.Confirm(
                    lines = listOf("DEPOSIT ${pokemon.displayName.uppercase()} INTO THE APP'S PC?"),
                    confirmLabel = "DEPOSIT",
                    onConfirm = { model.depositFromSave(key, SaveLocation.Box(box, index + 1), 1) },
                )
            )
        },
    )
}

@Composable
private fun MonListScreen(
    title: String,
    entries: List<Gen1Pokemon>,
    emptyMessage: String,
    actionLabel: String,
    actionEnabled: () -> Boolean,
    disabledReason: String,
    onAction: (Int, Gen1Pokemon) -> Unit,
) {
    var selected by remember(title, entries.size) { mutableStateOf(-1) }
    ScreenColumn {
        item { Gen1Window(title = title) { GbText("${entries.size} POKéMON", style = Gen1TextSmall) } }
        if (entries.isEmpty()) item { Gen1Window { GbText(emptyMessage) } }
        itemsIndexed(entries) { index, pokemon ->
            Gen1Window(contentPadding = PaddingValues(vertical = 4.dp)) {
                Gen1MenuItem(
                    label = pokemonRowLabel(pokemon),
                    subtitle = pokemonRowDetail(pokemon),
                    selected = selected == index,
                    onSelect = { selected = index },
                    onConfirm = { selected = if (selected == index) -1 else index },
                )
            }
        }
        val chosen = entries.getOrNull(selected)
        if (chosen != null) {
            item {
                Gen1PokemonPanel(chosen) {
                    val enabled = actionEnabled()
                    Gen1Button(actionLabel, { onAction(selected, chosen) }, enabled = enabled)
                    if (!enabled && disabledReason.isNotEmpty()) {
                        GbText(disabledReason, style = Gen1TextSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun StorageBoxesScreen(state: UiState, model: StorageViewModel, box: Int) {
    val boxes = state.storage.boxes
    val current = boxes.getOrNull(box - 1)
    var selected by remember(box) { mutableStateOf(-1) }

    ScreenColumn {
        item {
            Gen1Window(title = "THIS APP'S PC") {
                Gen1Field("STORED", "${state.storage.total}/${StorageLayout.TOTAL_CAPACITY}")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Gen1Button("◀", {
                        model.replace(
                            Screen.StorageBoxes(((box - 2 + StorageLayout.BOX_COUNT) % StorageLayout.BOX_COUNT) + 1)
                        )
                    })
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        GbText(current?.name?.uppercase() ?: "BOX $box")
                    }
                    Gen1Button("▶", {
                        model.replace(Screen.StorageBoxes((box % StorageLayout.BOX_COUNT) + 1))
                    })
                }
                GbText("${current?.contents?.size ?: 0}/${StorageLayout.BOX_CAPACITY}", style = Gen1TextSmall)
            }
        }
        if (current == null || current.contents.isEmpty()) {
            item { Gen1Window { GbText("THIS BOX IS EMPTY.") } }
        }
        itemsIndexed(current?.contents.orEmpty()) { index, stored ->
            Gen1Window(contentPadding = PaddingValues(vertical = 4.dp)) {
                Gen1MenuItem(
                    label = pokemonRowLabel(stored.pokemon),
                    subtitle = pokemonRowDetail(stored.pokemon),
                    selected = selected == index,
                    onSelect = { selected = index },
                    onConfirm = { selected = if (selected == index) -1 else index },
                )
            }
        }
        val chosen = current?.contents?.getOrNull(selected)
        if (chosen != null) item { StoredDetail(state, model, chosen, box) }
    }
}

@Composable
private fun StoredDetail(state: UiState, model: StorageViewModel, stored: StoredPokemon, box: Int) {
    Column {
        Gen1PokemonPanel(stored.pokemon) {
            // A withdrawal needs the destination save's actual contents, so
            // only saves already fetched can receive one.
            val open = state.saves.filter { state.save(it.key)?.isUsable == true }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gen1Button(
                    "WITHDRAW",
                    {
                        model.prompt(
                            Prompt.ChooseSave("WITHDRAW INTO WHICH SAVE?") { key ->
                                model.prompt(Prompt.ChooseWithdrawTarget(stored.uid, key))
                            }
                        )
                    },
                    enabled = open.isNotEmpty(),
                )
                Gen1Button("MOVE", {
                    model.prompt(Prompt.ChooseBox("MOVE TO WHICH BOX?") { target ->
                        model.moveStored(stored.uid, target)
                    })
                })
            }
            if (open.isEmpty()) {
                GbText("OPEN A SAVE FROM ACCESS SAVE FIRST.", style = Gen1TextSmall)
            }
        }
        Spacer(Modifier.height(10.dp))
        Gen1Window(title = "CAME FROM") {
            val from = stored.provenance
            Gen1Field("GAME", from.gameVersion)
            Gen1Field("TRAINER", from.trainerName)
            Gen1Field("IDNo/", from.trainerId?.let { "%05d".format(it) } ?: "?")
            Gen1Field("FROM", if (from.sourceKind == "party") "PARTY SLOT ${from.sourceIndex}" else "A PC BOX")
            Gen1Field("IN BOX", box.toString())
        }
    }
}

@Composable
fun TransferScreen(state: UiState, model: StorageViewModel) {
    ScreenColumn {
        item {
            Gen1Window(title = "TRANSFER") {
                GbText("A TRANSFER MOVES A POKéMON. IT IS NEVER IN TWO PLACES AT ONCE.", style = Gen1TextSmall)
            }
        }
        item {
            Gen1Window(title = "DEPOSIT") {
                GbText("SAVE → THIS APP'S PC", style = Gen1TextSmall)
                Spacer(Modifier.height(6.dp))
                GbText("OPEN A SAVE, PICK A POKéMON FROM THE PARTY OR A BOX, THEN DEPOSIT.", style = Gen1TextSmall)
                Spacer(Modifier.height(8.dp))
                Gen1Button("CHOOSE A SAVE", { model.open(Screen.SaveList) }, enabled = state.linked)
            }
        }
        item {
            Gen1Window(title = "WITHDRAW") {
                GbText("THIS APP'S PC → SAVE", style = Gen1TextSmall)
                Spacer(Modifier.height(6.dp))
                GbText("OPEN THE STORAGE BOXES, PICK A POKéMON, THEN CHOOSE THE SAVE AND WHERE IT GOES.", style = Gen1TextSmall)
                Spacer(Modifier.height(8.dp))
                Gen1Button("STORAGE BOXES", { model.open(Screen.StorageBoxes(1)) }, enabled = state.storage.total > 0)
            }
        }
        item {
            Gen1Window(title = "ON THE ACCOUNT") {
                if (state.saves.isEmpty()) GbText("NO SAVES YET.", style = Gen1TextSmall)
                state.saves.forEach { remote ->
                    Gen1Field(
                        "${remote.version.label} ${remote.summary.trainerName ?: remote.label}",
                        "REV ${remote.rev}",
                    )
                }
                Spacer(Modifier.height(6.dp))
                Gen1Field("IN THIS APP", "${state.storage.total}/${StorageLayout.TOTAL_CAPACITY}")
            }
        }
    }
}

@Composable
fun SaveFilesScreen(state: UiState, model: StorageViewModel) {
    ScreenColumn {
        item {
            Gen1Window(title = "SAVE FILES") {
                GbText(
                    "EVERY WRITE UPLOADS AGAINST THE REVISION IT READ, SO THE SERVER REFUSES IT IF THE GAME SAVED FIRST. THE COPY BEING REPLACED IS KEPT ON THIS DEVICE.",
                    style = Gen1TextSmall,
                )
            }
        }
        model.pendingTransferSummary()?.let { pending ->
            item {
                Gen1Window(title = "UNFINISHED TRANSFER") {
                    GbText(pending.uppercase(), style = Gen1TextSmall)
                    Spacer(Modifier.height(8.dp))
                    GbText(
                        "BOTH COPIES WERE KEPT. CHECK THE PC AND THE SAVE, THEN CLEAR THIS RECORD.",
                        style = Gen1TextSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Gen1Button("CLEAR RECORD", model::clearPendingTransferRecord)
                }
            }
        }
        item {
            Gen1Window(title = "LOCAL BACKUPS") {
                val backups = model.localBackups()
                if (backups.isEmpty()) {
                    GbText("NONE YET. ONE IS KEPT EACH TIME A SAVE IS WRITTEN.", style = Gen1TextSmall)
                }
                backups.take(20).forEach { entry ->
                    Gen1Field(entry.key, "REV ${entry.rev}")
                }
                if (backups.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    GbText(
                        "THESE ARE PLAIN SAVE FILES IN THIS APP'S PRIVATE STORAGE. THE TEN MOST RECENT PER SAVE ARE KEPT.",
                        style = Gen1TextSmall,
                    )
                }
            }
        }
        if (state.diagnostics.isNotEmpty()) {
            item {
                Gen1Window(title = "WHAT THE ACCOUNT HOLDS") {
                    state.diagnostics.forEach { GbText(it, style = Gen1TextSmall) }
                }
            }
        }
    }
}

@Composable
fun OptionsScreen(state: UiState, model: StorageViewModel, onShareReport: () -> Unit) {
    ScreenColumn {
        item {
            Gen1Window(title = "SAVE SYNC") {
                Gen1Field("STATUS", if (state.linked) "LINKED" else "NOT LINKED")
                model.linkedDeviceLabel?.let { Gen1Field("THIS DEVICE", it) }
                state.lastSyncedAtMillis?.let {
                    GbText("LAST CHECKED ${Instant.ofEpochMilli(it)}", style = Gen1TextSmall)
                }
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
            Gen1Window(title = "DEVICES ON THE ACCOUNT") {
                val devices = state.account?.devices.orEmpty()
                if (devices.isEmpty()) GbText("NOT KNOWN YET.", style = Gen1TextSmall)
                devices.forEach { device ->
                    Gen1Field(device.label, if (device.isThisDevice) "THIS DEVICE" else "")
                }
            }
        }
        item {
            Gen1Window(title = "DIAGNOSTICS") {
                Gen1Field("SAVES", state.saves.size.toString())
                Gen1Field("STORED", state.storage.total.toString())
                Spacer(Modifier.height(8.dp))
                Gen1Button("SEND REPORT", onShareReport)
                Spacer(Modifier.height(6.dp))
                GbText(
                    "THE REPORT CONTAINS NO SYNC CODES, NO ACCOUNT DETAILS AND NO POKéMON OR SAVE CONTENTS.",
                    style = Gen1TextSmall,
                )
            }
        }
        item {
            Gen1Window(title = "SCOPE") {
                GbText(
                    "THIS APP HANDLES GEN1RECOMP RED, BLUE AND YELLOW SAVES ONLY. GENERATION II AND LATER ARE NOT SUPPORTED.",
                    style = Gen1TextSmall,
                )
            }
        }
    }
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
            is Prompt.Message -> Gen1Dialogue(prompt.lines.map { it.uppercase() }) {
                Gen1Button("OK", model::dismissPrompt)
            }

            is Prompt.Confirm -> Gen1Dialogue(prompt.lines.map { it.uppercase() }) {
                Gen1Button(prompt.confirmLabel, prompt.onConfirm)
                Gen1Button("CANCEL", model::dismissPrompt)
            }

            is Prompt.ChooseBox -> Gen1Window(title = prompt.title) {
                LazyColumn(Modifier.height(320.dp)) {
                    itemsIndexed((1..StorageLayout.BOX_COUNT).toList()) { _, index ->
                        val box = state.storage.boxes.getOrNull(index - 1)
                        Gen1MenuItem(
                            label = box?.name ?: "BOX $index",
                            subtitle = "${box?.contents?.size ?: 0}/${StorageLayout.BOX_CAPACITY}",
                            selected = false,
                            onSelect = { prompt.onChoose(index) },
                            onConfirm = { prompt.onChoose(index) },
                            enabled = (box?.freeSlots ?: 0) > 0,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Gen1Button("CANCEL", model::dismissPrompt)
            }

            is Prompt.ChooseSave -> Gen1Window(title = prompt.title) {
                val open = state.saves.filter { state.save(it.key)?.isUsable == true }
                LazyColumn(Modifier.height(320.dp)) {
                    itemsIndexed(open) { _, remote ->
                        val save = state.save(remote.key)?.save
                        Gen1MenuItem(
                            label = "${remote.version.label}  ${save?.trainerName ?: remote.label}",
                            subtitle = "PARTY ${save?.partyCount ?: 0}/${Gen1RecompSave.PARTY_MAX}",
                            selected = false,
                            onSelect = { prompt.onChoose(remote.key) },
                            onConfirm = { prompt.onChoose(remote.key) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Gen1Button("CANCEL", model::dismissPrompt)
            }

            is Prompt.ChooseWithdrawTarget -> {
                val save = state.save(prompt.key)?.save
                Gen1Window(title = "PUT IT WHERE?") {
                    if (save == null) {
                        GbText("THAT SAVE IS NOT OPEN.")
                    } else {
                        Gen1MenuItem(
                            label = "PARTY",
                            subtitle = "${save.partyCount}/${Gen1RecompSave.PARTY_MAX}",
                            selected = false,
                            onSelect = { model.withdrawToSave(prompt.uid, prompt.key, WithdrawTarget.Party) },
                            onConfirm = { model.withdrawToSave(prompt.uid, prompt.key, WithdrawTarget.Party) },
                            enabled = save.partyCount < Gen1RecompSave.PARTY_MAX,
                        )
                        LazyColumn(Modifier.height(280.dp)) {
                            itemsIndexed(save.boxes) { index, box ->
                                val number = index + 1
                                Gen1MenuItem(
                                    label = save.boxName(number),
                                    subtitle = "${box.size}/${Gen1RecompSave.BOX_CAPACITY}",
                                    selected = false,
                                    onSelect = {
                                        model.withdrawToSave(prompt.uid, prompt.key, WithdrawTarget.Box(number))
                                    },
                                    onConfirm = {
                                        model.withdrawToSave(prompt.uid, prompt.key, WithdrawTarget.Box(number))
                                    },
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
