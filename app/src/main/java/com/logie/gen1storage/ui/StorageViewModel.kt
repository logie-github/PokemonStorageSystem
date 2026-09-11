package com.logie.gen1storage.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.compose.ui.graphics.toArgb
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
import com.logie.gen1storage.sprites.SpriteDownloader
import com.logie.gen1storage.sprites.SpriteProgress
import com.logie.gen1storage.sprites.SpriteSet
import com.logie.gen1storage.sprites.SpriteStore
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** Where the player currently is. The back stack is a plain list of these. */
sealed interface Screen {
    /** The PC's own storage menu. There is nothing above it. */
    data object Home : Screen
    data object Link : Screen
    /**
     * Choosing a cartridge. A null [game] shows only the three games; once one
     * is picked they stay on screen and its saves are listed underneath.
     */
    data class ChooseCart(val game: String?) : Screen
    /**
     * The status screen. A null [key] means the Pokémon is in this app's PC and
     * [area] is its box; otherwise [area] 0 is the save's party and 1..12 its
     * boxes.
     */
    data class Status(val key: String?, val area: Int, val slot: Int) : Screen
    data object Sprites : Screen
    data object SaveFiles : Screen
    data object Options : Screen
    data object Credits : Screen
}

/** A modal the Generation I menus would draw as a window over everything. */
sealed interface Prompt {
    data class Message(val lines: List<String>) : Prompt
    data class Confirm(val lines: List<String>, val confirmLabel: String, val onConfirm: () -> Unit) : Prompt
    data class ChooseBox(val title: String, val onChoose: (Int) -> Unit) : Prompt
    /** Which save to put a withdrawn Pokémon into, before asking where in it. */
    data class ChooseWithdrawSave(val uid: String) : Prompt
    data class ChooseWithdrawTarget(val uid: String, val key: String) : Prompt
    /** Which save to take a deposit from, when the lists are not showing all. */
    data class ChooseDepositSave(val title: String) : Prompt
    /** The long-press sprite picker for one species. */
    data class ChooseSpriteSet(val speciesId: String) : Prompt
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
    val currentStorageBox: Int = 1,
    val showAllSaves: Boolean = false,
    /** The [GbPalette] id everything is drawn through. */
    val paletteId: String = GbPalette.ORIGINAL.id,
    val windowsFollowPalette: Boolean = false,
    val windowsOnRight: Boolean = true,
    /**
     * The save the top-level PC menu deposits from and withdraws to. Set by
     * opening one, so the menu is never asking which save it means.
     */
    val activeSaveKey: String? = null,
    val loadingAll: Boolean = false,
    val spriteProgress: SpriteProgress? = null,
    val spritesInstalled: Int = 0,
    /** Bumped whenever sprites change, so drawn sprites re-read the store. */
    val spriteRevision: Int = 0,
) {
    val screen: Screen get() = stack.last()
    val palette: GbPalette get() = GbPalette.fromId(paletteId)
    val saves: List<RemoteSave> get() = account?.saves.orEmpty()
    fun remote(key: String?): RemoteSave? = saves.firstOrNull { it.key == key }
    fun save(key: String?): LoadedSave? = loaded[key]
}

class StorageViewModel(application: Application) : AndroidViewModel(application) {

    private val storageDir = File(application.filesDir, "pc")
    private val storage = StorageRepository(storageDir)
    private val journal = TransferJournal(storageDir)
    private val backups = SaveBackups(File(storageDir, "backups"))

    private val settings = AppSettings(application)
    val sprites = SpriteStore(application)
    private val spriteDownloader = SpriteDownloader(sprites)
    private var spriteJob: Job? = null
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
        applySpriteTint(GbPalette.fromId(settings.paletteId))
        mutable.update {
            it.copy(
                storage = storage.state(),
                linked = credentials.isLinked,
                showAllSaves = settings.showAllSaves,
                paletteId = settings.paletteId,
                windowsFollowPalette = settings.windowsFollowPalette,
                windowsOnRight = settings.windowsOnRight,
                spritesInstalled = sprites.installedSets().sumOf { set -> sprites.countIn(set) },
            )
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

    fun setStorageBox(box: Int) = mutable.update { it.copy(currentStorageBox = box, prompt = null) }

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
                activeSaveKey = null,
                stack = listOf(Screen.Home),
            )
        }
        message("THIS DEVICE IS UNLINKED.", "YOUR STORAGE BOXES ARE UNTOUCHED.")
    }

    // ------- syncing

    /**
     * Re-reads the account.
     *
     * [silent] is what the automatic sync uses: it still refreshes everything
     * and still records what went wrong in the diagnostics, but it never opens
     * a window over whatever the player is doing. A failed poll every thirty
     * seconds is not news; losing the link is, so that one still speaks up.
     */
    fun sync(silent: Boolean = false) = viewModelScope.launch {
        val current = mutable.value
        // A transfer is mid-flight, and the revisions it is working against
        // must not be pulled out from under it.
        if (current.syncing || current.busy || !credentials.isLinked) return@launch
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
                // Reloading every save on the account is a fetch per save, so
                // the automatic pass only does it when a blob is actually
                // missing — which is exactly when a revision moved.
                if (settings.showAllSaves && (!silent || missingLoadedSaves())) loadAllSaves()
            }
            SyncResult.Unauthorized -> {
                credentials.clear()
                mutable.update {
                    it.copy(syncing = false, linked = false, account = null, activeSaveKey = null)
                }
                message("THIS DEVICE IS NO LONGER LINKED.", "LINK IT AGAIN WITH FRESH CODES.")
            }
            is SyncResult.Conflict -> mutable.update { it.copy(syncing = false) }
            is SyncResult.Failed -> {
                mutable.update {
                    it.copy(syncing = false, diagnostics = listOf("Sync failed: ${result.message}"))
                }
                if (!silent) message(result.message.uppercase())
            }
        }
    }

    private fun missingLoadedSaves(): Boolean {
        val current = mutable.value
        return current.saves.any { current.loaded[it.key] == null }
    }

    /**
     * An unfinished transfer is worth interrupting for, but only once. The
     * automatic sync re-runs recovery every thirty seconds and would otherwise
     * reopen the same window over and over.
     */
    private var reportedRecovery: List<String> = emptyList()

    private fun reportRecovery(notes: RecoveryReport) {
        if (notes.unresolved.isEmpty() || notes.unresolved == reportedRecovery) return
        reportedRecovery = notes.unresolved
        mutable.update { it.copy(prompt = Prompt.Message(notes.unresolved)) }
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

    /**
     * Fetches a save's contents and makes it the one the PC is working with.
     *
     * There is no save browser any more: a save is only ever chosen because a
     * transfer needs one, so this is called from those prompts and leaves the
     * player where they already were.
     */
    fun selectSave(key: String, onReady: (String) -> Unit = {}) = viewModelScope.launch {
        val remote = mutable.value.remote(key) ?: return@launch message("THAT SAVE IS GONE.")
        if (mutable.value.loaded[key] != null) {
            mutable.update { it.copy(activeSaveKey = key, prompt = null) }
            onReady(key)
            return@launch
        }
        mutable.update { it.copy(busy = true) }
        val result = withContext(Dispatchers.IO) { saves.load(remote) }
        mutable.update { it.copy(busy = false) }
        when (result) {
            is SyncResult.Ok -> {
                if (result.value.isUsable) {
                    mutable.update {
                        it.copy(
                            loaded = it.loaded + (key to result.value),
                            activeSaveKey = key,
                            prompt = null,
                        )
                    }
                    onReady(key)
                } else {
                    message(result.value.classification.summary.uppercase())
                }
            }
            SyncResult.Unauthorized -> message("THIS DEVICE IS NO LONGER LINKED.")
            is SyncResult.Conflict -> message("THE SERVER REFUSED THE READ.")
            is SyncResult.Failed -> message(result.message.uppercase())
        }
    }

    /**
     * Puts a cartridge in the machine.
     *
     * Held in memory rather than written to disk on purpose: which save is
     * loaded is a fact about this sitting, not a preference, and a stale one
     * silently pointing at a playthrough the player has moved on from is worse
     * than asking again.
     */
    fun chooseCart(key: String) = selectSave(key) { back() }

    /** Picks the save a withdrawal lands in, then asks where inside it. */
    fun chooseWithdrawSave(uid: String, key: String) =
        selectSave(key) { ready ->
            mutable.update { it.copy(prompt = Prompt.ChooseWithdrawTarget(uid, ready)) }
        }

    // ------- settings

    /**
     * Picks the palette. The sprites are recoloured through the same ramp, so
     * one choice covers the screen and the art together.
     */
    fun setPalette(id: String) {
        val palette = GbPalette.fromId(id)
        settings.paletteId = palette.id
        applySpriteTint(palette)
        mutable.update { it.copy(paletteId = palette.id, spriteRevision = it.spriteRevision + 1) }
    }

    fun setWindowsOnRight(enabled: Boolean) {
        settings.windowsOnRight = enabled
        mutable.update { it.copy(windowsOnRight = enabled) }
    }

    fun setWindowsFollowPalette(enabled: Boolean) {
        settings.windowsFollowPalette = enabled
        mutable.update { it.copy(windowsFollowPalette = enabled) }
    }

    private fun applySpriteTint(palette: GbPalette) {
        sprites.setTint(
            palette.id,
            if (palette.tintsSprites) palette.ramp.map { it.toArgb() }.toIntArray() else null,
        )
    }

    /**
     * Shows every save's Pokémon in the transfer lists at once.
     *
     * With it on there is no save to pick before depositing — everything on the
     * account is in the one list, grouped by where it lives. Off, the PC asks
     * which save a deposit is coming from, which is one fetch instead of all
     * of them.
     */
    fun setShowAllSaves(enabled: Boolean) {
        settings.showAllSaves = enabled
        mutable.update { it.copy(showAllSaves = enabled) }
        if (enabled) loadAllSaves()
    }

    /**
     * Fetches every save on the account so they can be shown as one list. A
     * save that fails to load is skipped rather than failing the whole view;
     * the ones that did load are still worth showing.
     */
    fun loadAllSaves() = viewModelScope.launch {
        if (mutable.value.loadingAll) return@launch
        val remotes = mutable.value.saves
        if (remotes.isEmpty()) return@launch
        mutable.update { it.copy(loadingAll = true) }
        val fetched = withContext(Dispatchers.IO) {
            remotes.mapNotNull { remote ->
                (saves.load(remote) as? SyncResult.Ok)?.value?.takeIf { it.isUsable }
            }
        }
        mutable.update { state ->
            state.copy(
                loaded = state.loaded + fetched.associateBy { it.key },
                loadingAll = false,
            )
        }
    }

    // ------- sprites

    /**
     * Downloads the Generation I front sprites. Progress is reported after
     * every file so the bar moves steadily rather than in jumps.
     */
    fun downloadSprites() {
        if (spriteJob?.isActive == true) return
        spriteJob = viewModelScope.launch {
            mutable.update { it.copy(prompt = null, spriteProgress = SpriteProgress(0, 1)) }
            val result = runCatching {
                spriteDownloader.download(SpriteSet.downloadable) { progress ->
                    mutable.update { it.copy(spriteProgress = progress) }
                }
            }
            val installed = sprites.installedSets().sumOf { set -> sprites.countIn(set) }
            mutable.update { state ->
                state.copy(
                    spritesInstalled = installed,
                    spriteRevision = state.spriteRevision + 1,
                    spriteProgress = result.getOrNull()
                        ?: SpriteProgress(0, 0, finished = true, error = result.exceptionOrNull()?.message),
                )
            }
        }
    }

    fun cancelSpriteDownload() {
        spriteJob?.cancel()
        spriteJob = null
        mutable.update {
            it.copy(
                spriteProgress = null,
                spritesInstalled = sprites.installedSets().sumOf { set -> sprites.countIn(set) },
                spriteRevision = it.spriteRevision + 1,
            )
        }
    }

    fun dismissSpriteProgress() = mutable.update { it.copy(spriteProgress = null) }

    fun deleteSprites() {
        sprites.clear()
        mutable.update {
            it.copy(spritesInstalled = 0, spriteRevision = it.spriteRevision + 1, prompt = null)
        }
    }

    /** Pins a species to one game's art, or back to whichever game it came from. */
    fun chooseSpriteSet(speciesId: String, set: SpriteSet?) {
        sprites.setOverride(speciesId, set)
        mutable.update { it.copy(prompt = null, spriteRevision = it.spriteRevision + 1) }
    }

    fun spriteBytesOnDisk(): Long = sprites.bytesOnDisk()

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
        refreshTransferSources()
    }

    /**
     * Re-reads whatever the transfer lists are showing.
     *
     * Every cached blob is dropped after a transfer, which would otherwise
     * leave the deposit list empty behind the result message. Deliberately does
     * not touch the prompt, so that message stays up.
     */
    private fun refreshTransferSources() = viewModelScope.launch {
        val current = mutable.value
        if (current.showAllSaves) {
            loadAllSaves()
            return@launch
        }
        val key = current.activeSaveKey ?: return@launch
        val remote = current.remote(key) ?: return@launch
        val result = withContext(Dispatchers.IO) { saves.load(remote) }
        if (result is SyncResult.Ok && result.value.isUsable) {
            mutable.update { it.copy(loaded = it.loaded + (key to result.value)) }
        }
    }

    /**
     * Releases a stored Pokémon at the player's request. Only ever from this
     * app's own PC — nothing in a save is touched outside a transfer.
     */
    fun releaseStored(uid: String) {
        val name = storage.get(uid)?.pokemon?.displayName?.uppercase() ?: "IT"
        if (storage.release(uid) == null) {
            message("THAT POKéMON IS NO LONGER THERE.")
            return
        }
        mutable.update { it.copy(storage = storage.state(), prompt = null) }
        message("$name was released.", "BYE BYE, $name!")
    }

    fun releasedCount(): Int = storage.releasedCount()

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
        reportedRecovery = emptyList()
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
