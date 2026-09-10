package com.logie.gen1storage.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.storage.StorageState
import com.logie.gen1storage.sync.AccountState
import com.logie.gen1storage.sync.LoadedSave
import com.logie.gen1storage.sync.RemoteSave
import com.logie.gen1storage.sync.SaveBackups
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncAccount
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sync.SyncResult
import com.logie.gen1storage.transfer.RecoveryReport
import com.logie.gen1storage.transfer.SaveLocation
import com.logie.gen1storage.transfer.TransferEngine
import com.logie.gen1storage.transfer.TransferJournal
import com.logie.gen1storage.transfer.TransferResult
import com.logie.gen1storage.transfer.WithdrawTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** Where the player currently is. The back stack is a plain list of these. */
sealed interface Screen {
    data object Home : Screen
    data object Link : Screen
    data object SaveList : Screen
    data class SaveMenu(val key: String) : Screen
    data class SaveParty(val key: String) : Screen
    data class SaveBox(val key: String, val box: Int) : Screen
    data class StorageBoxes(val box: Int) : Screen
    data object Transfer : Screen
    data object SaveFiles : Screen
    data object Options : Screen
}

/** A modal the Generation I menus would draw as a window over everything. */
sealed interface Prompt {
    data class Message(val lines: List<String>) : Prompt
    data class Confirm(val lines: List<String>, val confirmLabel: String, val onConfirm: () -> Unit) : Prompt
    data class ChooseBox(val title: String, val onChoose: (Int) -> Unit) : Prompt
    data class ChooseWithdrawTarget(val uid: String, val key: String) : Prompt
    data class ChooseSave(val title: String, val onChoose: (String) -> Unit) : Prompt
}

data class UiState(
    val stack: List<Screen> = listOf(Screen.Home),
    val linked: Boolean = false,
    val linking: Boolean = false,
    val syncing: Boolean = false,
    val busy: Boolean = false,
    val account: AccountState? = null,
    /** Saves whose blob has been fetched, keyed by `<version>/<playthroughId>`. */
    val loaded: Map<String, LoadedSave> = emptyMap(),
    val storage: StorageState = StorageState(emptyList(), 0),
    val diagnostics: List<String> = emptyList(),
    val recoveryNotes: List<String> = emptyList(),
    val prompt: Prompt? = null,
    val lastSyncedAtMillis: Long? = null,
) {
    val screen: Screen get() = stack.last()
    val saves: List<RemoteSave> get() = account?.saves.orEmpty()
    fun remote(key: String?): RemoteSave? = saves.firstOrNull { it.key == key }
    fun save(key: String?): LoadedSave? = loaded[key]
}

class StorageViewModel(application: Application) : AndroidViewModel(application) {

    private val storageDir = File(application.filesDir, "pc")
    private val storage = StorageRepository(storageDir)
    private val journal = TransferJournal(storageDir)
    private val backups = SaveBackups(File(storageDir, "backups"))

    private val credentials = SyncAccount(application)
    private val api = SyncApi(credentials = credentials::credentials)
    private val saves = SaveRepository(api, backups)
    private val engine = TransferEngine(saves, storage, journal)

    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()

    /** What this device calls itself in the game's device list. */
    private val deviceLabel: String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "Android" }
            .take(40)

    init {
        mutable.update {
            it.copy(storage = storage.state(), linked = credentials.isLinked)
        }
        if (credentials.isLinked) sync()
    }

    // ------- navigation

    fun open(screen: Screen) = mutable.update { it.copy(stack = it.stack + screen) }

    fun replace(screen: Screen) =
        mutable.update { it.copy(stack = it.stack.dropLast(1) + screen) }

    fun back(): Boolean {
        val current = mutable.value
        if (current.prompt != null) {
            mutable.update { it.copy(prompt = null) }
            return true
        }
        if (current.stack.size <= 1) return false
        mutable.update { it.copy(stack = it.stack.dropLast(1)) }
        return true
    }

    fun home() = mutable.update { it.copy(stack = listOf(Screen.Home), prompt = null) }

    fun dismissPrompt() = mutable.update { it.copy(prompt = null) }

    fun prompt(prompt: Prompt) = mutable.update { it.copy(prompt = prompt) }

    private fun message(vararg lines: String) =
        mutable.update { it.copy(prompt = Prompt.Message(lines.toList()), busy = false) }

    // ------- linking

    val isLinked: Boolean get() = credentials.isLinked
    val linkedDeviceLabel: String? get() = credentials.deviceLabel

    /**
     * Links this device with the two codes from the game's SAVE SYNC dialog.
     * Same call the game itself makes (`POST /sync/link`, no auth), so linking
     * here is exactly linking another copy of the game.
     */
    fun link(code1: String, code2: String) = viewModelScope.launch {
        if (mutable.value.linking) return@launch
        mutable.update { it.copy(linking = true) }
        val result = withContext(Dispatchers.IO) { api.link(code1, code2, deviceLabel) }
        mutable.update { it.copy(linking = false) }
        when (result) {
            is SyncResult.Ok -> {
                credentials.save(result.value, deviceLabel)
                mutable.update { it.copy(linked = true, stack = listOf(Screen.Home)) }
                sync()
                message("THIS DEVICE IS LINKED.")
            }
            SyncResult.Unauthorized -> message("THOSE CODES WERE NOT ACCEPTED.", "CHECK THEM AND TRY AGAIN.")
            is SyncResult.Conflict -> message("THOSE CODES WERE NOT ACCEPTED.")
            is SyncResult.Failed -> message(result.message.uppercase())
        }
    }

    fun unlink() = viewModelScope.launch {
        mutable.update { it.copy(busy = true, prompt = null) }
        withContext(Dispatchers.IO) { api.unlink(credentials.deviceId) }
        credentials.clear()
        mutable.update {
            it.copy(
                busy = false,
                linked = false,
                account = null,
                loaded = emptyMap(),
                stack = listOf(Screen.Home),
            )
        }
        message("THIS DEVICE IS UNLINKED.", "YOUR STORAGE BOXES ARE UNTOUCHED.")
    }

    // ------- syncing

    fun sync() = viewModelScope.launch {
        if (mutable.value.syncing || !credentials.isLinked) return@launch
        mutable.update { it.copy(syncing = true) }
        when (val result = withContext(Dispatchers.IO) { saves.listSaves() }) {
            is SyncResult.Ok -> {
                val account = result.value
                val notes = withContext(Dispatchers.IO) { engine.recover(account.saves) }
                mutable.update {
                    it.copy(
                        account = account,
                        // Every cached blob is stale the moment the account
                        // moves; drop any whose revision changed.
                        loaded = it.loaded.filterKeys { key ->
                            account.saves.firstOrNull { row -> row.key == key }
                                ?.rev == it.loaded[key]?.rev
                        },
                        storage = storage.state(),
                        syncing = false,
                        lastSyncedAtMillis = System.currentTimeMillis(),
                        diagnostics = diagnosticsFor(account) + storage.loadNotes,
                        recoveryNotes = notes.resolved + notes.unresolved,
                    )
                }
                reportRecovery(notes)
            }
            SyncResult.Unauthorized -> {
                credentials.clear()
                mutable.update { it.copy(syncing = false, linked = false, account = null) }
                message("THIS DEVICE IS NO LONGER LINKED.", "LINK IT AGAIN WITH FRESH CODES.")
            }
            is SyncResult.Conflict -> mutable.update { it.copy(syncing = false) }
            is SyncResult.Failed -> {
                mutable.update {
                    it.copy(syncing = false, diagnostics = listOf("Sync failed: ${result.message}"))
                }
                message(result.message.uppercase())
            }
        }
    }

    private fun reportRecovery(notes: RecoveryReport) {
        if (notes.unresolved.isNotEmpty()) {
            mutable.update { it.copy(prompt = Prompt.Message(notes.unresolved)) }
        }
    }

    /**
     * Deliberately free of trainer names, playthrough ids and save contents:
     * this list is what the debug report ships, and that report goes into a
     * public issue.
     */
    private fun diagnosticsFor(account: AccountState): List<String> = buildList {
        add("Saves on the account: ${account.saves.size}")
        account.saves.forEach {
            add("- ${it.version.id} rev ${it.rev}, badges ${it.summary.badges ?: "?"}, readable summary=${it.summary.trainerName != null}")
        }
        account.unsupported.forEach { add("- a non-Generation-I save on the account was ignored") }
        add("Devices linked: ${account.devices.size}")
    }

    /** Fetches a save's actual contents; the listing alone carries only a summary. */
    fun openSave(key: String) = viewModelScope.launch {
        val remote = mutable.value.remote(key) ?: return@launch message("THAT SAVE IS GONE.")
        if (mutable.value.loaded[key] != null) {
            open(Screen.SaveMenu(key))
            return@launch
        }
        mutable.update { it.copy(busy = true) }
        val result = withContext(Dispatchers.IO) { saves.load(remote) }
        mutable.update { it.copy(busy = false) }
        when (result) {
            is SyncResult.Ok -> {
                mutable.update { it.copy(loaded = it.loaded + (key to result.value)) }
                if (result.value.isUsable) open(Screen.SaveMenu(key))
                else message(result.value.classification.summary.uppercase())
            }
            SyncResult.Unauthorized -> message("THIS DEVICE IS NO LONGER LINKED.")
            is SyncResult.Conflict -> message("THE SERVER REFUSED THE READ.")
            is SyncResult.Failed -> message(result.message.uppercase())
        }
    }

    // ------- transfers

    fun depositFromSave(key: String, location: SaveLocation, targetBox: Int) {
        val loaded = mutable.value.save(key) ?: return message("OPEN THE SAVE FIRST.")
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val result = withContext(Dispatchers.IO) { engine.deposit(loaded, location, targetBox) }
            finish(result)
        }
    }

    fun withdrawToSave(uid: String, key: String, target: WithdrawTarget) {
        val loaded = mutable.value.save(key) ?: return message("OPEN THE SAVE FIRST.")
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val result = withContext(Dispatchers.IO) { engine.withdraw(loaded, uid, target) }
            finish(result)
        }
    }

    /**
     * After any transfer every cached blob and revision is stale, so the whole
     * account is re-read before the result is shown. Without that the next
     * transfer would be checked against a revision the server has moved past.
     */
    private suspend fun finish(result: TransferResult) {
        mutable.update { it.copy(loaded = emptyMap()) }
        val refreshed = withContext(Dispatchers.IO) { saves.listSaves() }
        if (refreshed is SyncResult.Ok) {
            mutable.update { it.copy(account = refreshed.value) }
        }
        mutable.update { it.copy(storage = storage.state(), busy = false) }
        when (result) {
            is TransferResult.Success -> message(
                result.message,
                "THE GAME WILL PICK THIS UP ON ITS NEXT SYNC.",
            )
            is TransferResult.Refused -> message(result.reason)
            is TransferResult.NeedsRecovery -> message(
                result.reason,
                "NOTHING WAS LOST. OPEN SAVE FILES TO FINISH IT.",
            )
        }
    }

    fun moveStored(uid: String, targetBox: Int) {
        if (!storage.moveTo(uid, targetBox)) {
            message("BOX $targetBox IS FULL.")
            return
        }
        mutable.update { it.copy(storage = storage.state(), prompt = null) }
    }

    // ------- maintenance

    fun clearPendingTransferRecord() {
        engine.dismissUnresolved()
        mutable.update { it.copy(recoveryNotes = emptyList(), prompt = null) }
    }

    fun pendingTransferSummary(): String? {
        val entry = engine.pendingTransfer() ?: return null
        return "${entry.kind.name} of ${entry.savePath} at rev ${entry.baseRev}, " +
            "started ${Instant.ofEpochMilli(entry.startedAtEpochMillis)}"
    }

    fun localBackups(): List<SaveBackups.Entry> = backups.all()

    fun debugReport(): String {
        val current = mutable.value
        val app = getApplication<Application>()
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return buildString {
            appendLine("## Gen1 Storage debug report")
            appendLine("Generated: ${Instant.now()}")
            appendLine("App version: $version")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Linked: ${current.linked}")
            appendLine("Saves on the account: ${current.saves.size}")
            appendLine("Stored Pokemon: ${current.storage.total}")
            appendLine("Local backups: ${localBackups().size}")
            appendLine("Pending transfer: ${pendingTransferSummary() ?: "none"}")
            appendLine()
            appendLine("### Account")
            current.diagnostics.forEach { appendLine("- $it") }
            if (current.recoveryNotes.isNotEmpty()) {
                appendLine()
                appendLine("### Recovery")
                current.recoveryNotes.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("No sync codes, account id, device token, Pokemon data,")
            appendLine("trainer names or save contents are included.")
        }.take(12000)
    }
}
