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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassification
import com.logie.gen1storage.gen1recomp.SaveOrigin
import com.logie.gen1storage.gen1recomp.SaveSource
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.saveaccess.AccessState
import com.logie.gen1storage.storage.StorageLayout
import com.logie.gen1storage.storage.StoredPokemon
import com.logie.gen1storage.transfer.SaveLocation
import com.logie.gen1storage.transfer.WithdrawTarget

/**
 * Every screen is one scrolling column of Generation I windows. Selection is
 * "tap to move the cursor, tap again to confirm", so a row is never actioned by
 * accident and the rhythm still matches the games.
 */

@Composable
fun HomeScreen(state: UiState, model: StorageViewModel) {
    var selected by remember { mutableStateOf(0) }
    val entries = listOf(
        Triple("ACCESS SAVE", "${state.sources.count { it.isUsable }} SAVE(S) FOUND") { model.open(Screen.SaveList) },
        Triple("STORAGE BOXES", "${state.storage.total}/${StorageLayout.TOTAL_CAPACITY} STORED") {
            model.open(Screen.StorageBoxes(1))
        },
        Triple("TRANSFER", "MOVE BETWEEN SAVES") { model.open(Screen.Transfer) },
        Triple("SAVE FILES", "BACKUPS AND REPAIR") { model.open(Screen.SaveFiles) },
        Triple("OPTIONS", "ACCESS AND DIAGNOSTICS") { model.open(Screen.Options) },
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
        if (state.access != AccessState.Ready && !state.folderChosen) {
            item { AccessNotice(state, model) }
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

@Composable
private fun AccessNotice(state: UiState, model: StorageViewModel) {
    Gen1Window(title = "SAVE ACCESS") {
        GbText(
            when (val access = state.access) {
                AccessState.Connecting -> "LOOKING FOR YOUR SAVES..."
                AccessState.Missing -> "SHIZUKU IS NOT INSTALLED. YOU CAN STILL CHOOSE THE SAVE FOLDER BY HAND."
                AccessState.Stopped -> "SHIZUKU IS NOT RUNNING."
                AccessState.PermissionNeeded -> "ALLOW SHIZUKU SO SAVES CAN BE FOUND AUTOMATICALLY."
                AccessState.Ready -> "READY."
                is AccessState.Failed -> access.reason.uppercase()
            },
            style = Gen1TextSmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.access == AccessState.PermissionNeeded) {
                Gen1Button("GRANT", model::requestShizukuPermission)
            } else {
                Gen1Button("RETRY", model::retryShizuku)
            }
            Gen1Button("OPTIONS", { model.open(Screen.Options) })
        }
    }
}

@Composable
fun SaveListScreen(state: UiState, model: StorageViewModel) {
    var selected by remember { mutableStateOf(-1) }
    ScreenColumn {
        item { Gen1Window(title = "ACCESS SAVE") { GbText("CHOOSE A PLAYTHROUGH.", style = Gen1TextSmall) } }
        if (state.scanning) item { Gen1Window { GbText("SCANNING...") } }
        itemsIndexed(state.sources) { index, source ->
            SaveCard(
                source = source,
                selected = selected == index,
                onSelect = { selected = index },
                onConfirm = {
                    if (source.isUsable) model.open(Screen.SaveMenu(source.id))
                    else model.prompt(Prompt.Message(listOf(source.classification.summary)))
                },
            )
        }
        if (!state.scanning && state.sources.isEmpty()) {
            item {
                Gen1Window {
                    GbText("NO SAVES FOUND.")
                    Spacer(Modifier.height(6.dp))
                    GbText("SAVE IN THE GAME FIRST, THEN REFRESH. IF THE GAME IS INSTALLED BUT NOTHING SHOWS, OPEN OPTIONS AND CHOOSE THE SAVE FOLDER.", style = Gen1TextSmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gen1Button("REFRESH", { model.scan() }, enabled = !state.scanning)
                Gen1Button("OPTIONS", { model.open(Screen.Options) })
            }
        }
    }
}

@Composable
private fun SaveCard(source: SaveSource, selected: Boolean, onSelect: () -> Unit, onConfirm: () -> Unit) {
    val save = source.save
    Gen1Window {
        Gen1MenuItem(
            label = "${source.version.label}   ${source.trainerName}",
            subtitle = source.slotDisplay + if (source.isActiveSlot) "  (ACTIVE)" else "",
            selected = selected,
            onSelect = onSelect,
            onConfirm = onConfirm,
        )
        if (save != null) {
            Gen1Field("BADGES", save.badgeCount.toString())
            Gen1Field("PLAY TIME", save.playTimeText)
            Gen1Field("POKéDEX", save.dexOwnedCount.toString())
            Gen1Field("PARTY", "${source.partyCount}/${Gen1RecompSave.PARTY_MAX}")
            Gen1Field("IN BOXES", source.storedCount.toString())
            Gen1Field("IDNo/", source.trainerId?.let { "%05d".format(it) } ?: "?")
        }
        if (source.origin != SaveOrigin.MAIN) {
            GbText("READ FROM ITS ${source.origin.name} COPY", style = Gen1TextSmall)
        }
        if (!source.writable) GbText("READ-ONLY", style = Gen1TextSmall)
        val status = source.classification
        if (status !is SaveClassification.Valid || status.warnings.isNotEmpty()) {
            GbText(status.summary.uppercase(), style = Gen1TextSmall)
        }
        GbText(source.relativePath, style = Gen1TextSmall)
    }
}

@Composable
fun SaveMenuScreen(state: UiState, model: StorageViewModel, sourceId: String) {
    val source = state.source(sourceId)
    val save = source?.save
    if (source == null || save == null) {
        ScreenColumn { item { Gen1Window { GbText("THAT SAVE IS NO LONGER AVAILABLE.") } } }
        return
    }
    var selected by remember { mutableStateOf(0) }
    ScreenColumn {
        item {
            Gen1Window(title = "${source.trainerName}'s PC") {
                Gen1Field("GAME", source.version.label)
                Gen1Field("SLOT", source.slotDisplay)
                Gen1Field("BADGES", save.badgeCount.toString())
                Gen1Field("PLAY TIME", save.playTimeText)
                if (!source.writable) GbText("READ-ONLY: TRANSFERS ARE DISABLED", style = Gen1TextSmall)
            }
        }
        item {
            Gen1Window(contentPadding = PaddingValues(vertical = 6.dp)) {
                Gen1MenuItem(
                    label = "PARTY POKéMON",
                    subtitle = "${save.partyCount}/${Gen1RecompSave.PARTY_MAX}",
                    selected = selected == 0,
                    onSelect = { selected = 0 },
                    onConfirm = { model.open(Screen.SaveParty(sourceId)) },
                )
                save.boxes.forEachIndexed { index, box ->
                    val number = index + 1
                    Gen1MenuItem(
                        label = save.boxName(number),
                        subtitle = "${box.size}/${Gen1RecompSave.BOX_CAPACITY}" +
                            if (save.currentBox == number) "  (CURRENT)" else "",
                        selected = selected == number,
                        onSelect = { selected = number },
                        onConfirm = { model.open(Screen.SaveBox(sourceId, number)) },
                    )
                }
            }
        }
        item {
            Gen1Window(title = "TRANSFER") {
                GbText("DEPOSIT PUTS A POKéMON INTO THIS APP. WITHDRAW TAKES ONE OUT OF THE APP INTO THIS SAVE.", style = Gen1TextSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Gen1Button("STORAGE BOXES", { model.open(Screen.StorageBoxes(1)) })
                    Gen1Button("CHANGE SAVE", { model.open(Screen.SaveList) })
                }
            }
        }
        item {
            Gen1Window(title = "RELEASE INFO") {
                GbText("THIS APP NEVER RELEASES A POKéMON. A TRANSFER MOVES IT, AND A COPY ONLY EVER EXISTS IN ONE PLACE AT A TIME.", style = Gen1TextSmall)
            }
        }
    }
}

@Composable
fun SavePartyScreen(state: UiState, model: StorageViewModel, sourceId: String) {
    val source = state.source(sourceId)
    val save = source?.save
    if (source == null || save == null) {
        ScreenColumn { item { Gen1Window { GbText("THAT SAVE IS NO LONGER AVAILABLE.") } } }
        return
    }
    MonListScreen(
        title = "${source.trainerName}'s PARTY",
        entries = save.party,
        emptyMessage = "THERE ARE NO POKéMON HERE.",
        actionLabel = "DEPOSIT",
        actionEnabled = { source.writable && save.partyCount > 1 },
        disabledReason = if (!source.writable) "READ-ONLY" else "YOU CAN'T DEPOSIT THE LAST POKéMON!",
        onAction = { index, pokemon ->
            model.prompt(
                Prompt.Confirm(
                    lines = listOf("DEPOSIT ${pokemon.displayName.uppercase()} INTO THE APP'S PC?"),
                    confirmLabel = "DEPOSIT",
                    onConfirm = {
                        model.depositFromSave(sourceId, SaveLocation.Party(index + 1), 1)
                    },
                )
            )
        },
    )
}

@Composable
fun SaveBoxScreen(state: UiState, model: StorageViewModel, sourceId: String, box: Int) {
    val source = state.source(sourceId)
    val save = source?.save
    if (source == null || save == null) {
        ScreenColumn { item { Gen1Window { GbText("THAT SAVE IS NO LONGER AVAILABLE.") } } }
        return
    }
    val contents = save.boxes.getOrNull(box - 1).orEmpty()
    MonListScreen(
        title = "${source.trainerName} - ${save.boxName(box)}",
        entries = contents,
        emptyMessage = "WHAT? THERE ARE NO POKéMON HERE!",
        actionLabel = "DEPOSIT",
        actionEnabled = { source.writable },
        disabledReason = "READ-ONLY",
        onAction = { index, pokemon ->
            model.prompt(
                Prompt.Confirm(
                    lines = listOf("DEPOSIT ${pokemon.displayName.uppercase()} INTO THE APP'S PC?"),
                    confirmLabel = "DEPOSIT",
                    onConfirm = {
                        model.depositFromSave(sourceId, SaveLocation.Box(box, index + 1), 1)
                    },
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
                    if (!enabled) GbText(disabledReason, style = Gen1TextSmall)
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
                    Gen1Button("◀", { model.replace(Screen.StorageBoxes(((box - 2 + StorageLayout.BOX_COUNT) % StorageLayout.BOX_COUNT) + 1)) })
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        GbText(current?.name?.uppercase() ?: "BOX $box")
                    }
                    Gen1Button("▶", { model.replace(Screen.StorageBoxes((box % StorageLayout.BOX_COUNT) + 1)) })
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
        if (chosen != null) {
            item { StoredDetail(state, model, chosen, box) }
        }
    }
}

@Composable
private fun StoredDetail(state: UiState, model: StorageViewModel, stored: StoredPokemon, box: Int) {
    Column {
        Gen1PokemonPanel(stored.pokemon) {
            val usable = state.sources.filter { it.isUsable && it.writable }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gen1Button(
                    "WITHDRAW",
                    {
                        model.prompt(
                            Prompt.ChooseSave("WITHDRAW INTO WHICH SAVE?") { sourceId ->
                                model.prompt(Prompt.ChooseWithdrawTarget(stored.uid, sourceId))
                            }
                        )
                    },
                    enabled = usable.isNotEmpty(),
                )
                Gen1Button("MOVE", {
                    model.prompt(Prompt.ChooseBox("MOVE TO WHICH BOX?") { target ->
                        model.moveStored(stored.uid, target)
                    })
                })
            }
            if (usable.isEmpty()) GbText("NO WRITABLE SAVE IS AVAILABLE.", style = Gen1TextSmall)
        }
        Spacer(Modifier.height(10.dp))
        Gen1Window(title = "CAME FROM") {
            val from = stored.provenance
            Gen1Field("GAME", from.gameVersion)
            Gen1Field("TRAINER", from.trainerName)
            Gen1Field("IDNo/", from.trainerId?.let { "%05d".format(it) } ?: "?")
            Gen1Field("SLOT", from.slotId ?: from.savePath)
            Gen1Field("FROM", if (from.sourceKind == "party") "PARTY SLOT ${from.sourceIndex}" else "A PC BOX")
            Gen1Field("IN BOX", box.toString())
        }
    }
}

/**
 * The two halves of a move, stated plainly. Nothing happens on this screen: it
 * exists so the direction of a transfer is never ambiguous at the moment a
 * player commits to one.
 */
@Composable
fun TransferScreen(state: UiState, model: StorageViewModel) {
    val writable = state.sources.filter { it.isUsable && it.writable }
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
                Gen1Button("CHOOSE A SAVE", { model.open(Screen.SaveList) }, enabled = writable.isNotEmpty())
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
            Gen1Window(title = "READY TO RECEIVE") {
                if (writable.isEmpty()) {
                    GbText("NO WRITABLE SAVE IS AVAILABLE YET.", style = Gen1TextSmall)
                }
                writable.forEach { source ->
                    Gen1Field(
                        "${source.version.label} ${source.trainerName}",
                        "PARTY ${source.partyCount}/${Gen1RecompSave.PARTY_MAX}  PC ${source.storedCount}",
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
                GbText("EVERY WRITE KEEPS THE PREVIOUS FILE AS A .BAK AND STAGES THE NEW ONE AS A .TMP, THE SAME WAY THE GAME DOES.", style = Gen1TextSmall)
            }
        }
        model.pendingTransferSummary()?.let { pending ->
            item {
                Gen1Window(title = "UNFINISHED TRANSFER") {
                    GbText(pending.uppercase(), style = Gen1TextSmall)
                    Spacer(Modifier.height(8.dp))
                    GbText("BOTH COPIES WERE KEPT. CHECK THE PC AND THE SAVE, THEN CLEAR THIS RECORD.", style = Gen1TextSmall)
                    Spacer(Modifier.height(8.dp))
                    Gen1Button("CLEAR RECORD", model::clearPendingTransferRecord)
                }
            }
        }
        items(state.sources) { source ->
            Gen1Window(title = "${source.version.label} ${source.slotDisplay}") {
                Gen1Field("FILE", source.mainName)
                Gen1Field("PATH", source.relativePath)
                Gen1Field("READ FROM", source.origin.name)
                Gen1Field("STATUS", source.classification.summary)
                Gen1Field("SIZE", "${source.sizeBytes} B")
                Gen1Field("WRITABLE", if (source.writable) "YES" else "NO")
                Spacer(Modifier.height(8.dp))
                Gen1Button(
                    "RESTORE FROM BACKUP",
                    {
                        model.prompt(
                            Prompt.Confirm(
                                lines = listOf(
                                    "REPLACE ${source.mainName.uppercase()} WITH ITS BACKUP?",
                                    "THE CURRENT FILE IS KEPT AS .PREV.",
                                ),
                                confirmLabel = "RESTORE",
                                onConfirm = { model.restoreBackup(source.id) },
                            )
                        )
                    },
                    enabled = source.writable,
                )
            }
        }
        if (state.diagnostics.isNotEmpty()) {
            item {
                Gen1Window(title = "WHERE THE APP LOOKED") {
                    state.diagnostics.forEach { GbText(it, style = Gen1TextSmall) }
                }
            }
        }
    }
}

@Composable
fun OptionsScreen(
    state: UiState,
    model: StorageViewModel,
    onChooseFolder: () -> Unit,
    onShareReport: () -> Unit,
) {
    ScreenColumn {
        item {
            Gen1Window(title = "SAVE ACCESS") {
                GbText("SHIZUKU: ${accessLabel(state.access)}", style = Gen1TextSmall)
                Spacer(Modifier.height(6.dp))
                GbText(
                    "SHIZUKU LETS THE APP FIND GEN1RECOMP SAVES ON ITS OWN. WITHOUT IT, CHOOSE THE GAME'S SAVE FOLDER ONCE AND THE APP WILL REMEMBER IT.",
                    style = Gen1TextSmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.access == AccessState.PermissionNeeded) {
                        Gen1Button("GRANT SHIZUKU", model::requestShizukuPermission)
                    } else {
                        Gen1Button("RETRY SHIZUKU", model::retryShizuku)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Gen1Button("CHOOSE FOLDER", onChooseFolder)
                    if (state.folderChosen) Gen1Button("FORGET FOLDER", model::forgetFolder)
                }
                if (state.folderChosen) {
                    Spacer(Modifier.height(6.dp))
                    GbText("A FOLDER IS REMEMBERED.", style = Gen1TextSmall)
                }
            }
        }
        item {
            Gen1Window(title = "WHERE TO LOOK") {
                GbText("ANDROID/DATA/<GAME PACKAGE>/FILES/SAVE/${com.logie.gen1storage.gen1recomp.Gen1RecompPackages.LOVE_IDENTITY.uppercase()}", style = Gen1TextSmall)
                Spacer(Modifier.height(4.dp))
                com.logie.gen1storage.gen1recomp.Gen1RecompPackages.KNOWN.forEach {
                    GbText(it, style = Gen1TextSmall)
                }
            }
        }
        item {
            Gen1Window(title = "DIAGNOSTICS") {
                Gen1Field("SAVES", state.sources.size.toString())
                Gen1Field("STORED", state.storage.total.toString())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Gen1Button("REFRESH", { model.scan() }, enabled = !state.scanning)
                    Gen1Button("SEND REPORT", onShareReport)
                }
                Spacer(Modifier.height(6.dp))
                GbText("THE REPORT CONTAINS NO POKéMON OR SAVE CONTENTS.", style = Gen1TextSmall)
            }
        }
        item {
            Gen1Window(title = "SCOPE") {
                GbText("THIS APP HANDLES GEN1RECOMP RED, BLUE AND YELLOW SAVES ONLY. GENERATION II AND LATER ARE NOT SUPPORTED.", style = Gen1TextSmall)
            }
        }
    }
}

private fun accessLabel(access: AccessState): String = when (access) {
    AccessState.Connecting -> "CONNECTING"
    AccessState.Missing -> "NOT INSTALLED"
    AccessState.Stopped -> "NOT RUNNING"
    AccessState.PermissionNeeded -> "PERMISSION NEEDED"
    AccessState.Ready -> "READY"
    is AccessState.Failed -> access.reason.uppercase()
}

@Composable
fun ScreenColumn(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Gen1Palette.Surround),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** `items` for a plain list, kept local so screens read the same way. */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.items(
    values: List<T>,
    content: @Composable (T) -> Unit,
) = itemsIndexed(values) { _, value -> content(value) }

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
                val usable = state.sources.filter { it.isUsable && it.writable }
                LazyColumn(Modifier.height(320.dp)) {
                    itemsIndexed(usable) { _, source ->
                        Gen1MenuItem(
                            label = "${source.version.label}  ${source.trainerName}",
                            subtitle = "${source.slotDisplay}  PARTY ${source.partyCount}/${Gen1RecompSave.PARTY_MAX}",
                            selected = false,
                            onSelect = { prompt.onChoose(source.id) },
                            onConfirm = { prompt.onChoose(source.id) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Gen1Button("CANCEL", model::dismissPrompt)
            }

            is Prompt.ChooseWithdrawTarget -> {
                val source = state.source(prompt.sourceId)
                val save = source?.save
                Gen1Window(title = "PUT IT WHERE?") {
                    if (save == null) {
                        GbText("THAT SAVE IS GONE.")
                    } else {
                        Gen1MenuItem(
                            label = "PARTY",
                            subtitle = "${save.partyCount}/${Gen1RecompSave.PARTY_MAX}",
                            selected = false,
                            onSelect = { model.withdrawToSave(prompt.uid, prompt.sourceId, WithdrawTarget.Party) },
                            onConfirm = { model.withdrawToSave(prompt.uid, prompt.sourceId, WithdrawTarget.Party) },
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
                                        model.withdrawToSave(prompt.uid, prompt.sourceId, WithdrawTarget.Box(number))
                                    },
                                    onConfirm = {
                                        model.withdrawToSave(prompt.uid, prompt.sourceId, WithdrawTarget.Box(number))
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
