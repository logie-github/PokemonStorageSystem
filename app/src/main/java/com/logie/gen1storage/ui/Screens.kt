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

/** One party slot the deposit list can offer, and the save it belongs to. */
private data class PartyRow(
    val key: String,
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
fun HomeScreen(state: UiState, model: StorageViewModel) {
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

    val box = state.storage.boxes.getOrNull(state.currentStorageBox - 1)
    val stored = box?.contents.orEmpty()

    // ALL POKéMON puts every save in the lists at once, so there is nothing
    // to choose; otherwise the PC works with one cartridge at a time.
    val needsCart = !state.showAllSaves && state.save(state.activeSaveKey) == null

    val depositRows = buildList {
        val keys = if (state.showAllSaves) {
            state.saves.map { it.key }
        } else {
            listOfNotNull(state.activeSaveKey)
        }
        keys.forEach { key ->
            val save = state.save(key)?.save ?: return@forEach
            save.party.forEachIndexed { index, mon -> add(PartyRow(key, index, mon, save)) }
        }
    }

    /** The label a group of party rows sits under, or null once it is open. */
    fun depositHeader(index: Int): String? {
        val row = depositRows[index]
        if (index > 0 && depositRows[index - 1].key == row.key) return null
        val version = state.remote(row.key)?.version?.label ?: ""
        return "$version ${row.save.trainerName.uppercase()}'s PARTY".trim()
    }

    // The list and the window that opens on a chosen Pokémon are separate
    // layers on the screen, so they are built as separate slots here rather
    // than nested — the message window belongs between them.
    val pick: PartyRow? = chosen?.takeIf { mode == PcMode.DEPOSIT }?.let { depositRows.getOrNull(it) }
    val storedPick = chosen?.takeIf { mode == PcMode.WITHDRAW || mode == PcMode.VIEW }
        ?.let { stored.getOrNull(it) }

    StorageSystemScreen(
        boxNumber = state.currentStorageBox,
        boxName = box?.name ?: "BOX ${state.currentStorageBox}",
        // Both directions need a cartridge in the machine. If there is not one
        // yet, that is the only question worth asking, so it gets the whole
        // screen rather than a window over this one.
        onWithdraw = {
            if (needsCart) model.open(Screen.ChooseCart(null)) else mode = PcMode.WITHDRAW
        },
        onDeposit = {
            if (needsCart) model.open(Screen.ChooseCart(null)) else mode = PcMode.DEPOSIT
        },
        onView = { mode = PcMode.VIEW },
        onChangeCart = { model.open(Screen.ChooseCart(null)) },
        onChangeBox = { mode = PcMode.CHANGE_BOX },
        onOptions = onOptions,
        message = when {
            !state.linked -> "Link this device in OPTIONS."
            needsCart -> "No cart in the machine."
            else -> "What?"
        },
        overlay = when (mode) {
            PcMode.MENU -> null

            PcMode.WITHDRAW, PcMode.VIEW -> ({
                MonListOverlay(
                    entries = stored.map {
                        MonRow(pokemonRowLabel(it.pokemon), pokemonRowLevel(it.pokemon))
                    },
                    onConfirm = { chosen = it },
                    onCancel = { mode = PcMode.MENU },
                    emptyMessage = "What? There are no POKéMON here!",
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
                )
            })

            PcMode.CHANGE_BOX -> ({
                ChangeBoxOverlay(
                    boxes = state.storage.boxes.map {
                        Triple(it.index, it.name, "${it.contents.size}/${StorageLayout.BOX_CAPACITY}")
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
                        MonAction(
                            "WITHDRAW",
                            {
                                val key = state.activeSaveKey
                                if (key == null) model.prompt(Prompt.ChooseWithdrawSave(storedPick.uid))
                                else model.prompt(Prompt.ChooseWithdrawTarget(storedPick.uid, key))
                            },
                            enabled = state.saves.isNotEmpty(),
                        ),
                        MonAction("STATS", {
                            model.open(
                                Screen.Status(null, state.currentStorageBox, stored.indexOf(storedPick))
                            )
                        }),
                    ),
                    onCancel = { chosen = null },
                    note = if (state.saves.isEmpty()) "NO SAVES ON THE ACCOUNT" else null,
                )
            })

            mode == PcMode.VIEW && storedPick != null -> ({
                // Deliberately cannot transfer: this side only rearranges the
                // PC, opens a status screen, or releases — so there is no way
                // to reach a save from here by accident.
                val name = storedPick.pokemon.displayName.uppercase()
                MonActionOverlay(
                    actions = listOf(
                        MonAction("MOVE", {
                            model.prompt(
                                Prompt.ChooseBox("MOVE TO WHICH BOX?") { target ->
                                    model.moveStored(storedPick.uid, target)
                                    chosen = null
                                }
                            )
                        }),
                        MonAction("STATS", {
                            model.open(
                                Screen.Status(null, state.currentStorageBox, stored.indexOf(storedPick))
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
                                        model.releaseStored(storedPick.uid)
                                        chosen = null
                                    },
                                )
                            )
                        }),
                    ),
                    onCancel = { chosen = null },
                )
            })

            mode == PcMode.DEPOSIT && pick != null -> ({
                // The games never let the last one go, and neither does this —
                // counted per save, not per list.
                val last = pick.save.partyCount <= 1
                MonActionOverlay(
                    actions = listOf(
                        MonAction(
                            "DEPOSIT",
                            {
                                model.prompt(
                                    Prompt.Confirm(
                                        lines = listOf("DEPOSIT ${pick.mon.displayName.uppercase()}?"),
                                        confirmLabel = "DEPOSIT",
                                        onConfirm = {
                                            model.depositFromSave(
                                                pick.key,
                                                SaveLocation.Party(pick.slot + 1),
                                                state.currentStorageBox,
                                            )
                                        },
                                    )
                                )
                            },
                            enabled = !last,
                        ),
                        MonAction("STATS", { model.open(Screen.Status(pick.key, 0, pick.slot)) }),
                    ),
                    onCancel = { chosen = null },
                    note = if (last) "CAN'T DEPOSIT THE LAST ONE" else null,
                )
            })

            else -> null
        },
    )
}

private enum class PcMode { MENU, WITHDRAW, DEPOSIT, VIEW, CHANGE_BOX }

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
                // The key is a game and a playthrough id; only the game half
                // means anything to a player, and the id overran the row.
                backups.take(20).forEach {
                    Gen1Field(it.key.substringBefore('/').uppercase(), "REV ${it.rev}")
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
    val entries = listOf<Pair<String, () -> Unit>>(
        "DOWNLOAD SPRITES" to { model.open(Screen.Sprites) },
        "SAVE FILES" to { model.open(Screen.SaveFiles) },
    )

    ScreenColumn {
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                entries.forEachIndexed { index, (label, action) ->
                    Gen1MenuRow(label, cursor == index, { cursor = index }, action)
                }
            }
        }
        item {
            Gen1Frame(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                GbText("COLOUR")
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
                GbText(
                    "SWIPE TO MOVE THE CURSOR, TAP TO TAKE WHAT IT IS ON, DOUBLE TAP FOR OPTIONS, TAP AND HOLD TO GO BACK. TAPPING A MENU ROW TAKES IT DIRECTLY.",
                    style = Gen1TextSmall,
                )
                Spacer(Modifier.height(10.dp))
                Gen1Toggle(
                    label = "ALL POKéMON",
                    on = state.showAllSaves,
                    onToggle = { model.setShowAllSaves(!state.showAllSaves) },
                )
                GbText(
                    "PUTS EVERY SAVE'S POKéMON IN THE TRANSFER LISTS AT ONCE, SO THERE IS NO SAVE TO PICK FIRST.",
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
                Spacer(Modifier.height(8.dp))
                Gen1Button("SEND REPORT", onShareReport)
                Spacer(Modifier.height(6.dp))
                GbText(
                    "NO SYNC CODES, ACCOUNT DETAILS, POKéMON OR SAVE CONTENTS ARE INCLUDED.",
                    style = Gen1TextSmall,
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
 * Who made what this app is built out of.
 *
 * None of it is this project's work, and two of the four ask to be credited in
 * writing, so the app says so where a player can actually read it rather than
 * only in a README they will never open.
 */
@Composable
fun CreditsScreen() {
    ScreenColumn {
        item {
            Gen1Frame {
                GbText("CREDITS", style = Gen1TextLarge)
            }
        }
        item {
            Gen1Frame {
                GbText("SPRITES")
                GbText("THE RBY SPRITES PROJECT", style = Gen1TextSmall)
                GbText("BY SHIRATHEMOGUL", style = Gen1TextSmall)
                Spacer(Modifier.height(4.dp))
                GbText("github.com/ShiraTheMogul/", style = Gen1TextSmall)
                GbText("rby-sprites-project", style = Gen1TextSmall)
            }
        }
        item {
            Gen1Frame {
                GbText("FONT")
                GbText("POKEMON-FONT BY SUPERPENCIL", style = Gen1TextSmall)
                GbText("SIL OPEN FONT LICENSE 1.1", style = Gen1TextSmall)
                Spacer(Modifier.height(4.dp))
                GbText("github.com/cooljeanius/", style = Gen1TextSmall)
                GbText("pokemon-font", style = Gen1TextSmall)
            }
        }
        item {
            Gen1Frame {
                GbText("GAME DATA")
                GbText("SPECIES, MOVES AND THE STAT", style = Gen1TextSmall)
                GbText("FORMULAS COME FROM PRET/POKERED,", style = Gen1TextSmall)
                GbText("THE DISASSEMBLY OF THE ORIGINAL", style = Gen1TextSmall)
                GbText("GAMES.", style = Gen1TextSmall)
            }
        }
        item {
            Gen1Frame {
                GbText("SAVES")
                GbText("THE SAVE FORMAT AND THE SYNC", style = Gen1TextSmall)
                GbText("SERVICE ARE GEN1RECOMP'S. THIS", style = Gen1TextSmall)
                GbText("APP READS AND WRITES THEM THE", style = Gen1TextSmall)
                GbText("WAY THE GAME ITSELF DOES.", style = Gen1TextSmall)
            }
        }
        item {
            Gen1Frame {
                GbText("POKéMON IS NINTENDO, CREATURES", style = Gen1TextSmall)
                GbText("AND GAME FREAK'S. THIS APP IS", style = Gen1TextSmall)
                GbText("NOT AFFILIATED WITH THEM AND", style = Gen1TextSmall)
                GbText("SHIPS NONE OF THEIR CODE OR ART.", style = Gen1TextSmall)
            }
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
            GbText("NO SAVES ON THE ACCOUNT.", style = Gen1TextSmall)
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

/** An on/off row drawn as a menu entry with its state in the right column. */
@Composable
private fun Gen1Toggle(label: String, on: Boolean, onToggle: () -> Unit) {
    Gen1MenuRow(label, selected = on, onSelect = onToggle, onConfirm = onToggle, trailing = if (on) "ON" else "OFF")
}

/**
 * A column of windows over the screen.
 *
 * The right inset is deliberately larger than the left: nothing in this
 * interface should reach both edges of the screen at once, and leaving a strip
 * of the dithered ground showing down one side is what keeps a list of windows
 * looking like windows.
 */
@Composable
fun ScreenColumn(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .gen1Ground(),
        contentPadding = PaddingValues(
            start = gen1Dp(2),
            end = gen1Dp(10),
            top = gen1Dp(2),
            bottom = gen1Dp(6),
        ),
        verticalArrangement = Arrangement.spacedBy(gen1Dp(4)),
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
            .padding(gen1Dp(4)),
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

            is Prompt.ChooseWithdrawSave -> SavePicker(
                title = "PUT IT IN WHICH SAVE?",
                state = state,
                onChoose = { key -> model.chooseWithdrawSave(prompt.uid, key) },
                onCancel = model::dismissPrompt,
            )

            is Prompt.ChooseDepositSave -> SavePicker(
                title = prompt.title,
                state = state,
                onChoose = { key -> model.selectSave(key) },
                onCancel = model::dismissPrompt,
            )

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
