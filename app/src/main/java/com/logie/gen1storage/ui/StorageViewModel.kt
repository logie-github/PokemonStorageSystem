package com.logie.gen1storage.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.compose.ui.graphics.toArgb
import com.logie.gen1storage.sound.CryStore
import com.logie.gen1storage.sound.SoundEffect
import androidx.lifecycle.viewModelScope
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.ItemStack
import com.logie.gen1storage.storage.ItemRepository
import com.logie.gen1storage.storage.StorageArchive
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.storage.StorageState
import com.logie.gen1storage.sync.AccountState
import com.logie.gen1storage.sync.LoadedSave
import com.logie.gen1storage.sync.RemoteSave
import com.logie.gen1storage.sync.SaveBackups
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncAccount
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sprites.FollowerStore
import com.logie.gen1storage.sprites.SpriteDownloader
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.sprites.SpriteSet
import com.logie.gen1storage.sprites.SpriteStore
import com.logie.gen1storage.sync.SyncResult
import com.logie.gen1storage.transfer.RecoveryReport
import com.logie.gen1storage.update.UpdateChecker
import com.logie.gen1storage.transfer.SaveLocation
import com.logie.gen1storage.transfer.ItemTransferEngine
import com.logie.gen1storage.transfer.TransferEngine
import com.logie.gen1storage.transfer.TransferJournal
import com.logie.gen1storage.transfer.TransferResult
import com.logie.gen1storage.transfer.WithdrawTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /** The two PCs, the way the games boot one. */
    data object Home : Screen
    /** This app's Pokémon storage — what used to be the top level. */
    data object Storage : Screen
    /** The loaded save's item PC, and this app's. */
    data object ItemPc : Screen
    data object Link : Screen
    /**
     * Choosing a cartridge. A null [game] shows only the three games; once one
     * is picked they stay on screen and its saves are listed underneath.
     */
    data class ChooseCart(
        val game: String?,
        /**
         * When this is not empty the screen is the same one under a different
         * job: it is asking where to send these Pokémon rather than which
         * cartridge to work with, and picking a save goes on to its boxes.
         */
        val sendUids: List<String> = emptyList(),
        /**
         * Set when the cartridge is being chosen on the way into the PC. The
         * choice then opens the PC rather than returning to the menu it came
         * from, so picking a cart is the first step of the trip instead of an
         * interruption partway through one.
         */
        val thenOpenStorage: Boolean = false,
    ) : Screen
    /**
     * The status screen. A null [key] means the Pokémon is in this app's PC and
     * [area] is its box; otherwise [area] 0 is the save's party and 1..12 its
     * boxes.
     */
    data class Status(
        val key: String?,
        val area: Int,
        val slot: Int,
        /**
         * The transfer this was opened from, if any. A status screen reached
         * mid-transfer carries the button for it, so looking a Pokémon over
         * before moving it does not mean walking back out to the list.
         */
        val transfer: StatusTransfer? = null,
    ) : Screen
    data object Sprites : Screen
    /** Everything that is fetched rather than shipped: the sprites and the cries. */
    data object Downloads : Screen
    data object Cries : Screen
    data object Followers : Screen
    data object Options : Screen
    data object Credits : Screen
}

/** Which way the ball scene runs. */
enum class TransferMotion { OUT, IN, RELEASE }

/** The Pokémon a transfer is moving, and where it is going. */
data class TransferScene(
    val speciesId: String?,
    val gameVersionId: String?,
    val name: String,
    val destination: String,
    /** The others going with it, up to what the scene has room to show. */
    val alsoSpeciesIds: List<String?> = emptyList(),
    val motion: TransferMotion,
    /**
     * The question being asked, while one is. The scene holds still and shows
     * the Pokémon: what is about to happen to it is the thing being agreed
     * to, so it should be looked at rather than named in a box somewhere else.
     */
    val question: String? = null,
) {
    /** Whether the Pokémon is the last thing on screen rather than the first. */
    val arriving: Boolean get() = motion != TransferMotion.OUT
}

/** The transfer a status screen was opened from, and can finish. */
enum class StatusTransfer { WITHDRAW, DEPOSIT }

/** A modal the Generation I menus would draw as a window over everything. */
sealed interface Prompt {
    data class Message(val lines: List<String>) : Prompt
    data class Confirm(
        val lines: List<String>,
        val confirmLabel: String,
        val onConfirm: () -> Unit,
        val cancelLabel: String = "CANCEL",
    ) : Prompt
    data class ChooseBox(val title: String, val onChoose: (Int) -> Unit) : Prompt
    /** Naming a box, from the window that shows which one is open. */
    data class RenameBox(val index: Int) : Prompt
    /** Naming a cartridge, from the cartridge itself. */
    data class RenameCart(val key: String, val fallback: String) : Prompt
    /** A newer release exists; saying yes opens it. */
    data class Update(val version: String, val url: String) : Prompt
    /** The long-press sprite picker for one species. */
    data class ChooseSpriteSet(val speciesId: String) : Prompt
    /** How many of a stack to move. */
    data class ChooseQuantity(
        val title: String,
        val max: Int,
        val onChoose: (Int) -> Unit,
    ) : Prompt
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
    val classicTransferLabels: Boolean = false,
    /** Shown while a transfer is in flight, and cleared by its result. */
    val transferScene: TransferScene? = null,
    /**
     * The save the top-level PC menu deposits from and withdraws to. Set by
     * opening one, so the menu is never asking which save it means.
     */
    val activeSaveKey: String? = null,
    /** Bumped when a cartridge is renamed, so the carts redraw. */
    val cartRevision: Int = 0,
    /** Bumped by each transfer that went through, so one can be heard. */
    val transfers: Int = 0,
    /** All sound off, and which individual effects are on under that. */
    val soundOff: Boolean = false,
    val soundsOn: Set<String> = emptySet(),
    val loadingAll: Boolean = false,
    val spriteProgress: DownloadProgress? = null,
    val spritesInstalled: Int = 0,
    val cryProgress: DownloadProgress? = null,
    val criesInstalled: Int = 0,
    val followerProgress: DownloadProgress? = null,
    val followersInstalled: Int = 0,
    /** This app's own item PC. */
    val items: List<ItemStack> = emptyList(),
    /** Bumped whenever sprites change, so drawn sprites re-read the store. */
    val spriteRevision: Int = 0,
) {
    val screen: Screen get() = stack.last()

    /** Out of this PC, and into it, as the player has asked them to be named. */
    val outLabel: String get() = if (classicTransferLabels) "WITHDRAW" else "TRANSFER OUT"
    val inLabel: String get() = if (classicTransferLabels) "DEPOSIT" else "TRANSFER IN"
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
    private val cries = CryStore(application)
    private var cryJob: Job? = null
    val followers = FollowerStore(application)
    private var followerJob: Job? = null
    private val credentials = SyncAccount(application)
    private val api = SyncApi(credentials = credentials::credentials)
    private val saves = SaveRepository(api, backups)
    private val engine = TransferEngine(saves, storage, journal)
    private val itemStorage = ItemRepository(storageDir)
    private val itemEngine = ItemTransferEngine(saves, itemStorage)

    /** When the ball went up, so the result can wait for it to finish. */
    private var sceneStartedAt = 0L

    /** What YES on the scene's question will do. */
    private var pendingSend: (() -> Unit)? = null

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
                items = itemStorage.state(),
                linked = credentials.isLinked,
                showAllSaves = settings.showAllSaves,
                paletteId = settings.paletteId,
                windowsFollowPalette = settings.windowsFollowPalette,
                windowsOnRight = settings.windowsOnRight,
                classicTransferLabels = settings.classicTransferLabels,
                soundOff = settings.soundOff,
                soundsOn = enabledSounds(),
                spritesInstalled = sprites.installedSets().sumOf { set -> sprites.countIn(set) },
                criesInstalled = cries.count(),
                followersInstalled = followers.count(),
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
        // In a finally, because the flag is what stops a second sync starting
        // on top of the first — if an unexpected throw ever left it set, the
        // automatic sync would go quiet for the rest of the session and look
        // exactly like a feature that was never wired up.
        try {
            runSync(silent)
        } finally {
            mutable.update { it.copy(syncing = false) }
        }
    }

    private suspend fun runSync(silent: Boolean) {
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
                    it.copy(linked = false, account = null, activeSaveKey = null)
                }
                message("THIS DEVICE IS NO LONGER LINKED.", "LINK IT AGAIN WITH FRESH CODES.")
            }
            is SyncResult.Conflict -> Unit
            is SyncResult.Failed -> {
                mutable.update {
                    it.copy(diagnostics = listOf("Sync failed: ${result.message}"))
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
    fun chooseCart(key: String, thenOpenStorage: Boolean = false) = selectSave(key) {
        mutable.update { state ->
            val stack = state.stack.dropLastWhile { it is Screen.ChooseCart }
            val next = if (thenOpenStorage) stack + Screen.Storage else stack
            state.copy(stack = next.ifEmpty { listOf(Screen.Home) })
        }
    }

    /**
     * Picks the save a withdrawal lands in, and does it.
     *
     * No question about where inside: it goes to the box that save has open,
     * exactly as a deposit goes to the box this PC has open. The cartridge
     * screen is left behind first so the result is read against the PC.
     */
    fun chooseWithdrawSave(uids: List<String>, key: String) =
        selectSave(key) { ready ->
            mutable.update { state ->
                val stack = state.stack.dropLastWhile { it is Screen.ChooseCart }
                state.copy(stack = stack.ifEmpty { listOf(Screen.Home) }, prompt = null)
            }
            val box = mutable.value.save(ready)?.save?.currentBox ?: 1
            withdrawToSave(uids, ready, WithdrawTarget.Box(box))
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

    fun setClassicTransferLabels(on: Boolean) {
        settings.classicTransferLabels = on
        mutable.update { it.copy(classicTransferLabels = on) }
    }

    private fun enabledSounds(): Set<String> =
        SoundEffect.entries.filter { settings.soundEnabled(it) }.map { it.id }.toSet()

    fun setSoundOff(off: Boolean) {
        settings.soundOff = off
        mutable.update { it.copy(soundOff = off, soundsOn = enabledSounds()) }
    }

    fun setSoundEnabled(effect: SoundEffect, enabled: Boolean) {
        settings.setSoundEnabled(effect, enabled)
        mutable.update { it.copy(soundsOn = enabledSounds()) }
    }

    /** Whether an effect may be heard, for the player to consult. */
    fun soundEnabled(effect: SoundEffect): Boolean = settings.soundEnabled(effect)

    fun setWindowsOnRight(enabled: Boolean) {
        settings.windowsOnRight = enabled
        mutable.update { it.copy(windowsOnRight = enabled) }
    }

    fun setWindowsFollowPalette(enabled: Boolean) {
        settings.windowsFollowPalette = enabled
        mutable.update { it.copy(windowsFollowPalette = enabled) }
    }

    private fun applySpriteTint(palette: GbPalette) {
        val ramp =
            if (palette.tintsSprites) palette.ramp.map { it.toArgb() }.toIntArray() else null
        sprites.setTint(palette.id, ramp)
        followers.setTint(palette.id, ramp)
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
            mutable.update { it.copy(prompt = null, spriteProgress = DownloadProgress(0, 1)) }
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
                        ?: DownloadProgress(0, 0, finished = true, error = result.exceptionOrNull()?.message),
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

    // ------- cries

    /**
     * Downloads every Generation I cry that is not already on the device.
     *
     * Same shape as the sprites: the cries are the games' own recordings, so
     * none of them ship here and the player fetches the set once rather than
     * waiting on one at a time as each Pokémon is opened.
     */
    fun downloadCries() {
        if (cryJob?.isActive == true) return
        cryJob = viewModelScope.launch {
            mutable.update { it.copy(prompt = null, cryProgress = DownloadProgress(0, 1)) }
            val result = runCatching {
                cries.downloadAll { progress -> mutable.update { it.copy(cryProgress = progress) } }
            }
            mutable.update { state ->
                state.copy(
                    criesInstalled = cries.count(),
                    cryProgress = result.getOrNull()
                        ?: DownloadProgress(0, 0, finished = true, error = result.exceptionOrNull()?.message),
                )
            }
        }
    }

    fun cancelCryDownload() {
        cryJob?.cancel()
        cryJob = null
        mutable.update { it.copy(cryProgress = null, criesInstalled = cries.count()) }
    }

    fun dismissCryProgress() = mutable.update { it.copy(cryProgress = null) }

    fun deleteCries() {
        cries.clear()
        mutable.update { it.copy(criesInstalled = 0, prompt = null) }
    }

    fun cryBytesOnDisk(): Long = cries.bytesOnDisk()

    // ------- followers

    /** Downloads the overworld follower sheets the box grid draws. */
    fun downloadFollowers() {
        if (followerJob?.isActive == true) return
        followerJob = viewModelScope.launch {
            mutable.update { it.copy(prompt = null, followerProgress = DownloadProgress(0, 1)) }
            val result = runCatching {
                followers.downloadAll { progress ->
                    mutable.update { it.copy(followerProgress = progress) }
                }
            }
            mutable.update { state ->
                state.copy(
                    followersInstalled = followers.count(),
                    spriteRevision = state.spriteRevision + 1,
                    followerProgress = result.getOrNull()
                        ?: DownloadProgress(0, 0, finished = true, error = result.exceptionOrNull()?.message),
                )
            }
        }
    }

    fun cancelFollowerDownload() {
        followerJob?.cancel()
        followerJob = null
        mutable.update {
            it.copy(
                followerProgress = null,
                followersInstalled = followers.count(),
                spriteRevision = it.spriteRevision + 1,
            )
        }
    }

    fun dismissFollowerProgress() = mutable.update { it.copy(followerProgress = null) }

    fun deleteFollowers() {
        followers.clear()
        mutable.update {
            it.copy(followersInstalled = 0, spriteRevision = it.spriteRevision + 1, prompt = null)
        }
    }

    fun followerBytesOnDisk(): Long = followers.bytesOnDisk()

    /**
     * Fetches all three sets, one after another rather than at once.
     *
     * Three downloads racing each other over one connection finish no sooner
     * and each report a percentage that stalls while the others have the
     * line. In order, each one's bar means what it says.
     */
    fun downloadEverything() {
        viewModelScope.launch {
            downloadSprites()
            spriteJob?.join()
            downloadCries()
            cryJob?.join()
            downloadFollowers()
            followerJob?.join()
        }
    }

    // ------- export and import

    /**
     * Writes the whole PC to a Lua file the player chooses.
     *
     * Every Pokémon goes across as it is held, uid and all, so the file is a
     * restoration rather than a description — and it is the same Lua the saves
     * and the storage file are written in, readable by this app's own parser
     * and by anyone who opens it in a text editor.
     */
    fun exportTo(uri: Uri) = viewModelScope.launch {
        val state = storage.state()
        if (state.total == 0) return@launch message("THERE IS NOTHING IN THE PC.")
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val bytes = StorageArchive.encode(state, System.currentTimeMillis())
                val resolver = getApplication<Application>().contentResolver
                // Truncated first: writing over a longer file otherwise leaves
                // the tail of the old one behind, which would not parse.
                resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("THE FILE COULD NOT BE OPENED")
                bytes.size
            }
        }
        result.fold(
            onSuccess = { size ->
                message("EXPORTED ${state.total} POKéMON.", "${size / 1024} KB WRITTEN.")
            },
            onFailure = { message("THE EXPORT FAILED.", it.message.orEmpty().uppercase()) },
        )
    }

    /**
     * Reads an exported file back into the PC.
     *
     * A uid already held is the same Pokémon and is skipped, so importing a
     * file twice cannot duplicate anything. What was added, what was already
     * here and what would not fit are all reported: an import that quietly
     * dropped something would be the one failure this app cannot have.
     */
    fun importFrom(uri: Uri) = viewModelScope.launch {
        val archive = runCatching {
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("THE FILE COULD NOT BE OPENED")
                StorageArchive.decode(bytes)
            }
        }.getOrElse { Result.failure(it) }

        val decoded = archive.getOrElse {
            return@launch message("THE IMPORT FAILED.", it.message.orEmpty().uppercase())
        }
        val report = withContext(Dispatchers.IO) { storage.importArchive(decoded) }
        mutable.update { it.copy(storage = storage.state()) }
        message(
            *buildList {
                add("IMPORTED ${report.added} POKéMON.")
                if (report.skipped > 0) add("${report.skipped} WERE ALREADY HERE.")
                if (report.unplaced > 0) add("${report.unplaced} DID NOT FIT; THE PC IS FULL.")
                if (report.unreadable > 0) add("${report.unreadable} COULD NOT BE READ.")
            }.toTypedArray()
        )
    }

    /** What an export would be called, so the picker opens with a name in it. */
    fun exportFileName(): String =
        "gen1storage-" + java.time.LocalDate.now().toString() + ".lua"

    // ------- transfers

    fun depositFromSave(key: String, location: SaveLocation, targetBox: Int) {
        val loaded = mutable.value.save(key) ?: return message("OPEN THE SAVE FIRST.")
        val moving = loaded.save?.let { pokemonAt(it, location) }
        sceneStartedAt = System.currentTimeMillis()
        viewModelScope.launch {
            mutable.update {
                it.copy(
                    busy = true,
                    prompt = null,
                    transferScene = moving?.let { mon ->
                        TransferScene(
                            speciesId = mon.speciesId,
                            gameVersionId = loaded.remote.version.id,
                            name = mon.displayName.uppercase(),
                            destination = boxLabel(targetBox),
                            motion = TransferMotion.IN,
                        )
                    },
                )
            }
            val result = try {
                withContext(Dispatchers.IO) { engine.deposit(loaded, location, targetBox) }
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false) }
                return@launch message("THE TRANSFER COULD NOT START.", e.message.orEmpty().uppercase())
            }
            finish(result)
        }
    }

    fun withdrawToSave(uid: String, key: String, target: WithdrawTarget) =
        withdrawToSave(listOf(uid), key, target)

    /**
     * Withdraws one Pokémon or several into the same save.
     *
     * Each one is its own transfer, journalled and committed on its own, so a
     * refusal partway leaves everything before it already in the save and
     * everything after it still in the PC — never a half-written one. The save
     * is re-read between steps because each commit moves its revision, and the
     * engine refuses a transfer aimed at a revision that has moved.
     */
    fun withdrawToSave(uids: List<String>, key: String, target: WithdrawTarget) {
        if (uids.isEmpty()) return
        val loaded = mutable.value.save(key) ?: return message("OPEN THE SAVE FIRST.")
        val moving = uids.singleOrNull()?.let { storage.get(it) }
        sceneStartedAt = System.currentTimeMillis()
        viewModelScope.launch {
            mutable.update {
                it.copy(
                    busy = true,
                    prompt = null,
                    transferScene = moving?.let { stored ->
                        TransferScene(
                            speciesId = stored.pokemon.speciesId,
                            gameVersionId = stored.provenance.gameVersion,
                            name = stored.pokemon.displayName.uppercase(),
                            destination = loaded.save?.trainerName?.uppercase() ?: "THE SAVE",
                            motion = TransferMotion.OUT,
                        )
                    },
                )
            }
            var done = 0
            var stopped: TransferResult? = null
            for (uid in uids) {
                val loaded = freshSave(key)
                if (loaded == null) {
                    stopped = TransferResult.Refused("THE SAVE COULD NOT BE READ")
                    break
                }
                val result = runTransfer { engine.withdraw(loaded, uid, target) }
                if (result is TransferResult.Success) done++ else { stopped = result; break }
            }
            finishMany(done, stopped, mutable.value.outLabel)
        }
    }

    /**
     * Deposits one Pokémon or several out of the same save.
     *
     * Worked from the bottom of the save up: taking a Pokémon out closes the
     * gap behind it, so a slot below the one being taken would shift while the
     * list still pointed at where it used to be. Going downwards means every
     * slot not yet reached is untouched by the ones already done.
     */
    fun depositFromSave(picks: List<Pair<String, SaveLocation>>, targetBox: Int) {
        if (picks.isEmpty()) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val ordered = picks.sortedWith(
                compareBy<Pair<String, SaveLocation>> { it.first }
                    .thenByDescending { (it.second as? SaveLocation.Box)?.box ?: 0 }
                    .thenByDescending { slotOf(it.second) }
            )
            var done = 0
            var stopped: TransferResult? = null
            for ((key, location) in ordered) {
                val loaded = freshSave(key)
                if (loaded == null) {
                    stopped = TransferResult.Refused("THE SAVE COULD NOT BE READ")
                    break
                }
                val result = runTransfer { engine.deposit(loaded, location, targetBox) }
                if (result is TransferResult.Success) done++ else { stopped = result; break }
            }
            finishMany(done, stopped, mutable.value.inLabel)
        }
    }

    /**
     * Lets the ball finish before the result lands on top of it.
     *
     * A save answers when it answers, and often sooner than the animation
     * runs. Coming in, the Pokémon is the last thing drawn, so an early
     * answer used to clear the scene before it was ever on screen.
     */
    private suspend fun awaitScene() {
        val scene = mutable.value.transferScene ?: return
        val left = Gen1TransferTiming.forScene(scene.motion) -
            (System.currentTimeMillis() - sceneStartedAt)
        if (left > 0) delay(left)
    }

    private fun boxLabel(index: Int): String =
        storage.state().boxes.getOrNull(index - 1)?.label ?: "BOX $index"

    /** The Pokémon a save location points at, for naming it before it moves. */
    private fun pokemonAt(save: Gen1RecompSave, location: SaveLocation) = when (location) {
        is SaveLocation.Party -> save.party.getOrNull(location.slot - 1)
        is SaveLocation.Box -> save.boxes.getOrNull(location.box - 1)?.getOrNull(location.slot - 1)
    }

    private fun slotOf(location: SaveLocation): Int = when (location) {
        is SaveLocation.Party -> location.slot
        is SaveLocation.Box -> location.slot
    }

    private suspend fun runTransfer(block: suspend () -> TransferResult): TransferResult =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            TransferResult.Refused(
                e.message.orEmpty().uppercase().ifBlank { "THE TRANSFER COULD NOT START" }
            )
        }

    /** Re-reads a save from the account, so a transfer is aimed at its current revision. */
    private suspend fun freshSave(key: String): LoadedSave? {
        val remote = mutable.value.remote(key) ?: return null
        val result = withContext(Dispatchers.IO) { saves.load(remote) }
        return (result as? SyncResult.Ok)?.value?.takeIf { it.isUsable }
    }

    /**
     * Closes a run of transfers: what went through, then what stopped it.
     *
     * Both are said, and in that order, because a run that moved four and was
     * refused on the fifth has genuinely moved four — a bare refusal would
     * read as though nothing had happened.
     */
    private suspend fun finishMany(done: Int, stopped: TransferResult?, verb: String) {
        awaitScene()
        mutable.update { it.copy(loaded = emptyMap()) }
        val refreshed = withContext(Dispatchers.IO) { saves.listSaves() }
        if (refreshed is SyncResult.Ok) {
            mutable.update { it.copy(account = refreshed.value) }
        }
        mutable.update { it.copy(storage = storage.state(), busy = false, transferScene = null) }
        if (done > 0) mutable.update { it.copy(transfers = it.transfers + done) }

        val lines = buildList {
            if (done > 0) add("$verb $done POKéMON.")
            when (stopped) {
                null, is TransferResult.Success -> Unit
                is TransferResult.Refused -> add(stopped.reason)
                is TransferResult.NeedsRecovery -> {
                    add(stopped.reason)
                    add("NOTHING WAS LOST. OPEN SAVE FILES TO FINISH IT.")
                }
            }
        }
        if (lines.isNotEmpty()) message(*lines.toTypedArray())
        refreshTransferSources()
    }

    /**
     * After any transfer every cached blob and revision is stale, so the whole
     * account is re-read before the result is shown. Without that the next
     * transfer would be checked against a revision the server has moved past.
     */
    private suspend fun finish(result: TransferResult) {
        awaitScene()
        mutable.update { it.copy(loaded = emptyMap()) }
        val refreshed = withContext(Dispatchers.IO) { saves.listSaves() }
        if (refreshed is SyncResult.Ok) {
            mutable.update { it.copy(account = refreshed.value) }
        }
        mutable.update { it.copy(storage = storage.state(), busy = false, transferScene = null) }
        when (result) {
            is TransferResult.Success -> {
                mutable.update { it.copy(transfers = it.transfers + 1) }
                message(result.message)
            }
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
        val stored = storage.get(uid)
        val name = stored?.pokemon?.displayName?.uppercase() ?: "IT"
        if (storage.release(uid) == null) {
            message("THAT POKéMON IS NO LONGER THERE.")
            return
        }
        sceneStartedAt = System.currentTimeMillis()
        mutable.update {
            it.copy(
                storage = storage.state(),
                prompt = null,
                transferScene = stored?.let { gone ->
                    TransferScene(
                        speciesId = gone.pokemon.speciesId,
                        gameVersionId = gone.provenance.gameVersion,
                        name = name,
                        destination = "",
                        motion = TransferMotion.RELEASE,
                    )
                },
            )
        }
        // The ball opens, it goes, and only then is it said out loud.
        viewModelScope.launch {
            awaitScene()
            mutable.update { it.copy(transferScene = null) }
            message("$name was released.", "BYE BYE, $name!")
        }
    }

    fun releasedCount(): Int = storage.releasedCount()

    /**
     * Looks for a newer release, once, when the app opens.
     *
     * Silent about everything except finding one: a failed check is not
     * something to interrupt anyone with.
     */
    fun checkForUpdate(currentVersion: String) = viewModelScope.launch {
        val update = withContext(Dispatchers.IO) { UpdateChecker.check(currentVersion) } ?: return@launch
        // Never over something the player is already reading.
        if (mutable.value.prompt != null) return@launch
        mutable.update { it.copy(prompt = Prompt.Update(update.version, update.url)) }
    }

    fun cartName(key: String): String? = settings.cartName(key)

    fun renameCart(key: String, name: String) {
        settings.setCartName(key, name)
        mutable.update { it.copy(prompt = null, cartRevision = it.cartRevision + 1) }
    }

    /** Names a box, or clears the name again when given nothing. */
    fun renameBox(index: Int, name: String) {
        storage.renameBox(index, name)
        mutable.update { it.copy(storage = storage.state(), prompt = null) }
    }

    /**
     * Puts a stored Pokémon on a named spot, which is what a drag across the
     * grid or a MOVE then a tap comes to. A spot that is taken swaps, so a
     * full box can still be rearranged.
     */
    fun moveStoredToSlot(uid: String, targetBox: Int, targetSlot: Int) {
        if (!storage.moveToSlot(uid, targetBox, targetSlot)) {
            message("THAT POKéMON COULD NOT BE MOVED.")
            return
        }
        mutable.update { it.copy(storage = storage.state(), prompt = null) }
    }

    // ------- asking first

    /**
     * Puts the Pokémon on screen and asks about it.
     *
     * Nothing is read or written until the answer comes back: this only holds
     * the question and the thing that will act on it.
     */
    fun askToSend(scene: TransferScene, question: String, send: () -> Unit) {
        pendingSend = send
        mutable.update { it.copy(prompt = null, transferScene = scene.copy(question = question)) }
    }

    /** YES. The transfer sets its own scene going, so the question simply goes. */
    fun confirmSend() {
        val send = pendingSend ?: return
        pendingSend = null
        send()
    }

    fun cancelSend() {
        pendingSend = null
        mutable.update { it.copy(transferScene = null) }
    }

    // ------- items

    /**
     * Moves an item between the save's PC and this app's.
     *
     * Both directions go through the same confirmation as a Pokémon transfer
     * and re-read the account afterwards, because an item transfer writes the
     * save and so moves its revision exactly as a deposit does.
     */
    fun transferItem(key: String, id: String, count: Int, intoApp: Boolean) {
        if (mutable.value.save(key) == null) return message("OPEN THE SAVE FIRST.")
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val loaded = freshSave(key)
            if (loaded == null) {
                mutable.update { it.copy(busy = false) }
                return@launch message("THE SAVE COULD NOT BE READ.")
            }
            val result = runTransfer {
                if (intoApp) itemEngine.deposit(loaded, id, count)
                else itemEngine.withdraw(loaded, id, count)
            }
            mutable.update { it.copy(items = itemStorage.state()) }
            finish(result)
        }
    }

    /**
     * Takes what a stored Pokémon is carrying and puts it in this app's PC.
     *
     * Nothing leaves the device, so there is no save to commit and nothing to
     * roll back: the item is added before the Pokémon is written back without
     * it, which is the same ordering the save transfers use.
     */
    fun takeHeldItem(uid: String) {
        val stored = storage.get(uid)
        val item = stored?.pokemon?.heldItem
        if (item == null) {
            message("IT IS NOT HOLDING ANYTHING.")
            return
        }
        if (itemStorage.add(item, 1) <= 0) {
            message("THERE IS NO ROOM FOR IT.")
            return
        }
        if (storage.takeHeldItem(uid) == null) {
            itemStorage.remove(item, 1)
            message("IT IS NOT HOLDING ANYTHING.")
            return
        }
        mutable.update {
            it.copy(storage = storage.state(), items = itemStorage.state(), prompt = null)
        }
        message("TOOK THE ${item.replace('_', ' ')}.")
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
