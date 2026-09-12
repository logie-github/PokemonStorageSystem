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
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1TradeEvolution
import com.logie.gen1storage.storage.StoredPokemon
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.logie.gen1storage.share.PokemonCardImage
import com.logie.gen1storage.storage.StorageLayout
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
import com.logie.gen1storage.sprites.TrainerStore
import com.logie.gen1storage.sync.CommitOutcome
import com.logie.gen1storage.sync.SyncResult
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.transfer.RecoveryReport
import com.logie.gen1storage.update.UpdateChecker
import com.logie.gen1storage.transfer.SaveLocation
import com.logie.gen1storage.transfer.ItemTransferEngine
import com.logie.gen1storage.transfer.Placement
import com.logie.gen1storage.transfer.PlacementLedger
import com.logie.gen1storage.transfer.TransferEngine
import com.logie.gen1storage.transfer.TransferJournal
import com.logie.gen1storage.transfer.TransferResult
import com.logie.gen1storage.transfer.WithdrawTarget
import com.logie.gen1storage.download.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.SupervisorJob
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
    /** One playthrough's trainer card, read at full size. */
    data class TrainerCard(val key: String) : Screen
    data object Options : Screen
    /** Every copy the app kept of a save before it wrote over it. */
    data object Restore : Screen
    /** The four that only evolve by being traded, and the machine to do it. */
    data object Trade : Screen
    /** One Pokédex over every cartridge at once, and over the PC. */
    data object Dex : Screen
    /** One species' Pokédex page, as the cartridge prints it. */
    data class DexEntry(val speciesId: String) : Screen
    /** What each cartridge's Pokédex knows, one line each. */
    data object DexStats : Screen
    data object Credits : Screen
}

/** Which way the ball scene runs. */
enum class TransferMotion {
    OUT, IN, RELEASE;

    /**
     * What the move is called from where the player is standing. Only one
     * that leaves this PC is being sent anywhere; one arriving is being
     * brought in.
     */
    val verb: String get() = if (this == IN) "BRING" else "SEND"
    val gerund: String get() = if (this == IN) "Bringing" else "Sending"
}

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

/**
 * A trade in progress: what went in, and what is coming back out.
 *
 * The app is both ends of the cable, so there is one Pokémon rather than two —
 * it goes out one side and its evolved form comes back the other.
 */
data class TradeScene(
    val fromSpeciesId: String?,
    val toSpeciesId: String?,
    /** What it is called, which does not change by evolving. */
    val name: String,
    val gameVersionId: String?,
    /** The cry to play when it arrives, by Pokédex number. */
    val toDexNumber: Int?,
)

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
    /** Naming a box, from the window that shows which one is open. */
    data class RenameBox(val index: Int) : Prompt
    /** Naming a cartridge, from the cartridge itself. */
    data class RenameCart(val key: String, val fallback: String) : Prompt
    /** Nicknaming a Pokémon in this app's PC. */
    data class RenameMon(val uid: String) : Prompt
    /** A newer release exists; saying yes opens it. */
    data class Update(val version: String, val url: String) : Prompt
    /** The long-press sprite picker for one species. */
    data class ChooseSpriteSet(val speciesId: String) : Prompt
    /** Which trainer a playthrough's card wears. */
    data class ChooseTrainerSprite(val key: String) : Prompt
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
    /**
     * There is one box, so this is always it. Kept as a name rather than a
     * bare 1 so the places that mean "where things go" still say so.
     */
    val currentStorageBox: Int = StorageLayout.THE_BOX,
    val showAllSaves: Boolean = false,
    val showAllItems: Boolean = false,
    /** The [GbPalette] id everything is drawn through. */
    val paletteId: String = GbPalette.ORIGINAL.id,
    val windowsFollowPalette: Boolean = false,
    val windowsOnRight: Boolean = true,
    val classicTransferLabels: Boolean = false,
    /** Whether this app's storage is called BILL'S PC instead of LOGIE'S PC. */
    val billsPc: Boolean = false,
    /** How fast the text prints, as the games' OPTIONS screen puts it. */
    val textSpeed: TextSpeed = TextSpeed.DEFAULT,
    /** Shown while a transfer is in flight, and cleared by its result. */
    val transferScene: TransferScene? = null,
    /** Shown while a trade is running, when the player has asked to see it. */
    val tradeScene: TradeScene? = null,
    /** Whether the PC will trade with itself at all. */
    val tradeEvolution: Boolean = false,
    /** Whether a trade is drawn on its way through. */
    val tradeAnimation: Boolean = false,
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
    /** Nothing moves, and which kinds of movement are on under that. */
    val reduceMotion: Boolean = false,
    val motionsOn: Set<String> = emptySet(),
    /** The tick under the finger. */
    val haptics: Boolean = true,
    /** Whether a shared Pokémon comes out on printer paper. */
    val printerBorder: Boolean = true,
    val loadingAll: Boolean = false,
    val spriteProgress: DownloadProgress? = null,
    val spritesInstalled: Int = 0,
    val cryProgress: DownloadProgress? = null,
    val criesInstalled: Int = 0,
    val followerProgress: DownloadProgress? = null,
    val followersInstalled: Int = 0,
    val trainerProgress: DownloadProgress? = null,
    val trainersInstalled: Int = 0,
    /** This app's own item PC. */
    val items: List<ItemStack> = emptyList(),
    /** Bumped whenever sprites change, so drawn sprites re-read the store. */
    val spriteRevision: Int = 0,
) {
    val screen: Screen get() = stack.last()

    /** Whether a given kind of movement is on, as [Gen1Motion] reads it. */
    fun moves(motion: Motion): Boolean = !reduceMotion && motion.id in motionsOn

    /** What this app's own storage is called on the screen that offers it. */
    val pokemonPcLabel: String get() = if (billsPc) "BILL'S PC" else "LOGIE'S PC"

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
    val trainers = TrainerStore(application)
    private var followerJob: Job? = null
    private var trainerJob: Job? = null
    private val credentials = SyncAccount(application)
    private val api = SyncApi(credentials = credentials::credentials)
    private val saves = SaveRepository(api, backups)
    private val ledger = PlacementLedger(storageDir)
    private val engine = TransferEngine(saves, storage, journal, ledger)
    private val itemStorage = ItemRepository(storageDir)
    private val itemEngine = ItemTransferEngine(saves, itemStorage)

    /** When the ball went up, so the result can wait for it to finish. */
    private var sceneStartedAt = 0L

    /** What YES on the scene's question will do. */
    private var pendingSend: (() -> Unit)? = null

    /**
     * Where a download runs.
     *
     * Not the view model's scope: that dies with the screen, and a download
     * of several hundred files is exactly the thing a player walks away from.
     * A foreground service keeps the process alive alongside it; this keeps
     * the work from being cancelled the moment the activity goes.
     */
    private val downloads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tells the service what to say, or takes it down when nothing is left. */
    private fun showDownload(label: String, progress: DownloadProgress?) {
        val app = getApplication<Application>()
        if (progress == null || progress.finished) {
            val stillGoing = mutable.value.let {
                listOfNotNull(it.spriteProgress, it.cryProgress, it.followerProgress, it.trainerProgress)
                    .any { p -> !p.finished }
            }
            if (!stillGoing) DownloadService.hide(app)
        } else {
            DownloadService.show(app, label, progress.percent)
        }
    }

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
                showAllItems = settings.showAllItems,
                paletteId = settings.paletteId,
                windowsFollowPalette = settings.windowsFollowPalette,
                windowsOnRight = settings.windowsOnRight,
                classicTransferLabels = settings.classicTransferLabels,
                billsPc = settings.billsPc,
                tradeEvolution = settings.tradeEvolution,
                tradeAnimation = settings.tradeAnimation,
                reduceMotion = settings.reduceMotion,
                motionsOn = enabledMotions(),
                haptics = settings.haptics,
                printerBorder = settings.printerBorder,
                textSpeed = settings.textSpeed,
                soundOff = settings.soundOff,
                soundsOn = enabledSounds(),
                spritesInstalled = sprites.installedSets().sumOf { set -> sprites.countIn(set) },
                criesInstalled = cries.count(),
                followersInstalled = followers.count(),
                trainersInstalled = trainers.count(),
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
        // Leaving the storage system is what the cartridge says SEE YA! to —
        // `BillsPCMenu`'s exit, verbatim. Only on the way out of the PC
        // itself; backing out of a download page is not saying goodbye to
        // anything.
        val leavingThePc = current.screen == Screen.Storage
        mutable.update { it.copy(stack = it.stack.dropLast(1)) }
        if (leavingThePc) message("SEE YA!")
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
                        loaded = it.loaded.stillCurrentIn(account),
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

    /**
     * The cached saves the account still agrees with.
     *
     * A transfer only ever rewrites the save it touched, so throwing every
     * blob away afterwards cost a fresh download of each cartridge the next
     * time one was picked up — the whole of the wait people were sitting
     * through when switching carts. The revision is what says whether a blob
     * is still the current one, so that is what decides, and the engine
     * re-reads and fingerprints the save again before every transfer
     * regardless: a stale blob can never be the thing that gets written.
     */
    private fun Map<String, LoadedSave>.stillCurrentIn(
        account: AccountState,
    ): Map<String, LoadedSave> = filterKeys { key ->
        account.saves.firstOrNull { it.key == key }?.rev == this[key]?.rev
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
            // What the save says is running on it, and what this app made of
            // its boxes. Names only: no trainer, no contents, because this
            // list is what the debug report ships into a public issue.
            mutable.value.save(it.key)?.save?.let { save ->
                add("  boxes ${save.boxCount} x ${save.boxCapacity}")
                save.mods.forEach { mod ->
                    add("  mod ${mod.id}${mod.version?.let { v -> " $v" } ?: ""}")
                }
            }
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

    fun setReduceMotion(on: Boolean) {
        settings.reduceMotion = on
        mutable.update { it.copy(reduceMotion = on, motionsOn = enabledMotions()) }
    }

    fun setPrinterBorder(on: Boolean) {
        settings.printerBorder = on
        mutable.update { it.copy(printerBorder = on) }
    }

    /**
     * A stored Pokémon as a picture, ready to be sent somewhere.
     *
     * Built here rather than by grabbing the screen: a screenshot carries
     * whatever else was on it and whatever the phone's own bars look like,
     * and the thing worth sending is the Pokémon.
     */
    fun cardImage(uid: String): Bitmap? {
        val stored = storage.get(uid) ?: return null
        val sprite = stored.pokemon.speciesId
            ?.let { sprites.load(it, stored.provenance.gameVersion) }
            ?.asAndroidBitmap()
        return runCatching {
            PokemonCardImage.render(
                context = getApplication(),
                pokemon = stored.pokemon,
                sprite = sprite,
                provenance = stored.provenance,
                palette = GbPalette.fromId(settings.paletteId),
                printerBorder = settings.printerBorder,
            )
        }.getOrNull()
    }

    fun cardName(uid: String): String =
        storage.get(uid)?.pokemon?.displayName ?: "pokemon"

    fun setHaptics(on: Boolean) {
        settings.haptics = on
        mutable.update { it.copy(haptics = on) }
    }

    fun setMotionEnabled(motion: Motion, enabled: Boolean) {
        settings.setMotionEnabled(motion, enabled)
        mutable.update { it.copy(motionsOn = enabledMotions()) }
    }

    fun setTradeEvolution(on: Boolean) {
        settings.tradeEvolution = on
        mutable.update { it.copy(tradeEvolution = on) }
    }

    fun setTradeAnimation(on: Boolean) {
        settings.tradeAnimation = on
        mutable.update { it.copy(tradeAnimation = on) }
    }

    /** Everything in the PC that a trade would evolve. */
    fun tradeCandidates(): List<StoredPokemon> =
        mutable.value.storage.boxes.flatMap { it.contents }
            .filter { Gen1TradeEvolution.evolves(it.pokemon.speciesId) }

    /**
     * Trades a stored Pokémon with the machine, so that it evolves.
     *
     * Nothing leaves the PC and no save is written: the Pokémon is in this
     * app's own storage on both sides of the trade, which is the only reason
     * this can be offered at all. What changes is the species and the stats
     * that follow from it.
     */
    fun tradeEvolve(uid: String) = viewModelScope.launch {
        val current = mutable.value
        val stored = storage.get(uid)
        if (stored == null) {
            message("THAT POKéMON IS NOT IN THE PC.")
            return@launch
        }
        val from = stored.pokemon.speciesId
        val to = Gen1TradeEvolution.evolutionOf(from)
        if (to == null) {
            message("${stored.pokemon.displayName.uppercase()} WOULD NOT CHANGE.")
            return@launch
        }
        val name = stored.pokemon.displayName.uppercase()

        if (current.tradeAnimation && current.moves(Motion.TRADE)) {
            mutable.update {
                it.copy(
                    busy = true,
                    prompt = null,
                    tradeScene = TradeScene(
                        fromSpeciesId = from,
                        toSpeciesId = to,
                        name = name,
                        gameVersionId = stored.provenance.gameVersion,
                        toDexNumber = Gen1Data.species(to)?.dexNumber,
                    ),
                )
            }
            delay(TRADE_SCENE_MILLIS)
        } else {
            mutable.update { it.copy(busy = true, prompt = null) }
        }

        val became = storage.evolveByTrade(uid)
        mutable.update {
            it.copy(busy = false, tradeScene = null, storage = storage.state())
        }
        if (became == null) {
            message("NOTHING HAPPENED.")
        } else {
            message("$name evolved into ${Gen1Data.speciesName(became).uppercase()}!")
        }
    }

    fun setBillsPc(on: Boolean) {
        settings.billsPc = on
        mutable.update { it.copy(billsPc = on) }
    }

    /** Takes the next speed round, which is how the games' own row works. */
    fun cycleTextSpeed() {
        val next = mutable.value.textSpeed.next
        settings.textSpeed = next
        mutable.update { it.copy(textSpeed = next) }
    }

    private fun enabledSounds(): Set<String> =
        SoundEffect.entries.filter { settings.soundEnabled(it) }.map { it.id }.toSet()

    private fun enabledMotions(): Set<String> =
        Motion.entries.filter { settings.motionEnabled(it) }.map { it.id }.toSet()

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
        trainers.setTint(palette.id, ramp)
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
    fun setShowAllItems(enabled: Boolean) {
        settings.showAllItems = enabled
        mutable.update { it.copy(showAllItems = enabled) }
        if (enabled) loadAllSaves()
    }

    fun loadAllSaves() = viewModelScope.launch {
        if (mutable.value.loadingAll) return@launch
        val held = mutable.value.loaded
        // Only the ones this app is not already holding at the revision the
        // account reports. Re-downloading a cartridge that has not moved is
        // the whole of the wait, and there is nothing in it to learn.
        val remotes = mutable.value.saves.filter { held[it.key]?.rev != it.rev }
        if (remotes.isEmpty()) return@launch
        mutable.update { it.copy(loadingAll = true) }
        // Side by side rather than one after another: each is its own request
        // and they do not depend on each other, so a shelf of six carts takes
        // about as long as the slowest one instead of all six added up.
        val fetched = withContext(Dispatchers.IO) {
            remotes.map { remote ->
                async { (saves.load(remote) as? SyncResult.Ok)?.value?.takeIf { it.isUsable } }
            }.awaitAll().filterNotNull()
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
        spriteJob = downloads.launch {
            mutable.update { it.copy(prompt = null, spriteProgress = DownloadProgress(0, 1)) }
            val result = runCatching {
                spriteDownloader.download(SpriteSet.downloadable) { progress ->
                    mutable.update { it.copy(spriteProgress = progress) }
                    showDownload("SPRITES", progress)
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
        DownloadService.hide(getApplication())
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
        cryJob = downloads.launch {
            mutable.update { it.copy(prompt = null, cryProgress = DownloadProgress(0, 1)) }
            val result = runCatching {
                cries.downloadAll { progress ->
                    mutable.update { it.copy(cryProgress = progress) }
                    showDownload("CRIES", progress)
                }
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
        DownloadService.hide(getApplication())
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
        followerJob = downloads.launch {
            mutable.update { it.copy(prompt = null, followerProgress = DownloadProgress(0, 1)) }
            val result = runCatching {
                followers.downloadAll { progress ->
                    mutable.update { it.copy(followerProgress = progress) }
                    showDownload("FOLLOWERS", progress)
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
        DownloadService.hide(getApplication())
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

    // ------- trainers

    /** Downloads the trainer battle sprites a trainer card's portrait uses. */
    fun downloadTrainers() {
        if (trainerJob?.isActive == true) return
        trainerJob = downloads.launch {
            mutable.update { it.copy(prompt = null, trainerProgress = DownloadProgress(0, 1)) }
            val result = runCatching {
                trainers.downloadAll { progress ->
                    mutable.update { it.copy(trainerProgress = progress) }
                    showDownload("TRAINERS", progress)
                }
            }
            mutable.update { state ->
                state.copy(
                    trainersInstalled = trainers.count(),
                    spriteRevision = state.spriteRevision + 1,
                    trainerProgress = result.getOrNull()
                        ?: DownloadProgress(0, 0, finished = true, error = result.exceptionOrNull()?.message),
                )
            }
        }
    }

    fun cancelTrainerDownload() {
        trainerJob?.cancel()
        trainerJob = null
        DownloadService.hide(getApplication())
        mutable.update {
            it.copy(
                trainerProgress = null,
                trainersInstalled = trainers.count(),
                spriteRevision = it.spriteRevision + 1,
            )
        }
    }

    fun dismissTrainerProgress() = mutable.update { it.copy(trainerProgress = null) }

    fun deleteTrainers() {
        trainers.clear()
        mutable.update {
            it.copy(trainersInstalled = 0, spriteRevision = it.spriteRevision + 1, prompt = null)
        }
    }

    fun trainerBytesOnDisk(): Long = trainers.bytesOnDisk()

    /** Which trainer a playthrough's card wears, or null while it wears none. */
    fun trainerSprite(key: String): String? = settings.trainerSprite(key)

    /** Sets it, or clears it when given nothing. */
    fun setTrainerSprite(key: String, id: String?) {
        settings.setTrainerSprite(key, id)
        mutable.update { it.copy(cartRevision = it.cartRevision + 1, prompt = null) }
    }

    /**
     * Fetches all four sets, one after another rather than at once.
     *
     * Three downloads racing each other over one connection finish no sooner
     * and each report a percentage that stalls while the others have the
     * line. In order, each one's bar means what it says.
     */
    fun downloadEverything() {
        downloads.launch {
            downloadSprites()
            spriteJob?.join()
            downloadCries()
            cryJob?.join()
            downloadFollowers()
            followerJob?.join()
            downloadTrainers()
            trainerJob?.join()
        }
    }

    // ------- the download, as one thing
    //
    // There are four sets and no reason for a player to be asked about them
    // one at a time: the app wants all of it, a set that is missing shows as
    // a gap wherever it was needed, and choosing to have three quarters of
    // the art is not a choice worth offering. So everything below reads the
    // four as one download.

    /** Everything already fetched, as a share of everything there is. */
    fun downloadedPercent(): Int {
        val have = mutable.value.let {
            it.spritesInstalled + it.criesInstalled + it.followersInstalled + it.trainersInstalled
        }
        return if (DOWNLOAD_TOTAL <= 0) 0
        else ((have.coerceAtMost(DOWNLOAD_TOTAL) * 100) / DOWNLOAD_TOTAL)
    }

    /** How far the download in flight has got, or null when none is. */
    fun downloadProgress(): DownloadProgress? {
        val current = mutable.value
        val all = listOfNotNull(
            current.spriteProgress,
            current.cryProgress,
            current.followerProgress,
            current.trainerProgress,
        )
        if (all.isEmpty()) return null
        // One bar over the lot rather than four in a row: the sets run one
        // after another and a bar that restarts three times reads as three
        // downloads rather than as one that is three quarters done.
        val done = current.spritesInstalled + current.criesInstalled +
            current.followersInstalled + current.trainersInstalled
        val running = all.any { !it.finished }
        return DownloadProgress(
            done = done.coerceAtMost(DOWNLOAD_TOTAL),
            total = DOWNLOAD_TOTAL,
            failed = all.sumOf { it.failed },
            finished = !running,
            error = all.firstNotNullOfOrNull { it.error },
        )
    }

    fun downloadBytesOnDisk(): Long =
        sprites.bytesOnDisk() + cries.bytesOnDisk() +
            followers.bytesOnDisk() + trainers.bytesOnDisk()

    fun cancelDownload() {
        cancelSpriteDownload()
        cancelCryDownload()
        cancelFollowerDownload()
        cancelTrainerDownload()
    }

    fun dismissDownloadProgress() = mutable.update {
        it.copy(
            spriteProgress = null,
            cryProgress = null,
            followerProgress = null,
            trainerProgress = null,
        )
    }

    fun deleteDownloads() {
        deleteSprites()
        deleteCries()
        deleteFollowers()
        deleteTrainers()
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
                // The ball goes with it. Nothing is in the air any more, and
                // a scene left standing here would hold the controls shut on
                // a transfer that never started.
                mutable.update { it.copy(busy = false, transferScene = null) }
                return@launch message("THE TRANSFER COULD NOT START.", e.message.orEmpty().uppercase())
            }
            finish(result, key)
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
        // The lead one carries the scene and the rest stand beside it. This
        // used to ask for the single one and get nothing when there were
        // several, so a run of three went out with no ball at all — the
        // sprites vanished the moment the question was answered.
        val lead = uids.firstOrNull()?.let { storage.get(it) }
        val rest = uids.drop(1).mapNotNull { storage.get(it)?.pokemon?.speciesId }
        sceneStartedAt = System.currentTimeMillis()
        viewModelScope.launch {
            mutable.update {
                it.copy(
                    busy = true,
                    prompt = null,
                    transferScene = lead?.let { stored ->
                        TransferScene(
                            speciesId = stored.pokemon.speciesId,
                            gameVersionId = stored.provenance.gameVersion,
                            name = if (uids.size == 1) stored.pokemon.displayName.uppercase()
                            else "${uids.size} POKéMON",
                            destination = loaded.save?.trainerName?.uppercase() ?: "THE SAVE",
                            alsoSpeciesIds = rest,
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
            finishMany(done, stopped, mutable.value.outLabel, listOf(key))
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
        // Same scene as one on its own, with the rest standing beside it.
        // Several used to arrive with no ball at all.
        val shown = picks.mapNotNull { (key, location) ->
            mutable.value.save(key)?.save?.let { pokemonAt(it, location) }
        }
        sceneStartedAt = System.currentTimeMillis()
        viewModelScope.launch {
            mutable.update {
                it.copy(
                    busy = true,
                    prompt = null,
                    transferScene = shown.firstOrNull()?.let { lead ->
                        TransferScene(
                            speciesId = lead.speciesId,
                            gameVersionId = mutable.value.remote(picks.first().first)?.version?.id,
                            name = if (picks.size == 1) lead.displayName.uppercase()
                            else "${picks.size} POKéMON",
                            destination = boxLabel(targetBox),
                            alsoSpeciesIds = shown.drop(1).map { mon -> mon.speciesId },
                            motion = TransferMotion.IN,
                        )
                    },
                )
            }
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
            finishMany(done, stopped, mutable.value.inLabel, picks.map { it.first }.toSet())
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
        storage.state().boxes.getOrNull(index - 1)?.label ?: "THE PC"

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
    private suspend fun finishMany(
        done: Int,
        stopped: TransferResult?,
        verb: String,
        touched: Collection<String> = emptyList(),
    ) = coroutineScope {
        // Read while the ball is still up, as [finish] does.
        val reading = async(Dispatchers.IO) { saves.listSaves() }
        awaitScene()
        val refreshed = reading.await()
        if (refreshed is SyncResult.Ok) {
            mutable.update {
                it.copy(
                    account = refreshed.value,
                    loaded = it.loaded.stillCurrentIn(refreshed.value),
                )
            }
        } else {
            // Nothing can vouch for a cached blob if the account would not
            // answer, so they all go rather than be worked against.
            mutable.update { it.copy(loaded = emptyMap()) }
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
        refreshTransferSources(*touched.toTypedArray())
        Unit
    }

    /**
     * After any transfer every cached blob and revision is stale, so the whole
     * account is re-read before the result is shown. Without that the next
     * transfer would be checked against a revision the server has moved past.
     */
    private suspend fun finish(result: TransferResult, vararg touched: String) = coroutineScope {
        // The account is re-read while the ball is still in the air rather than
        // after it lands. It is a whole round trip and it does not depend on
        // the animation, so running the two side by side takes a second off
        // every transfer without changing what is checked or when.
        val reading = async(Dispatchers.IO) { saves.listSaves() }
        awaitScene()
        val refreshed = reading.await()
        if (refreshed is SyncResult.Ok) {
            mutable.update {
                it.copy(
                    account = refreshed.value,
                    loaded = it.loaded.stillCurrentIn(refreshed.value),
                )
            }
        } else {
            // Nothing can vouch for a cached blob if the account would not
            // answer, so they all go rather than be worked against.
            mutable.update { it.copy(loaded = emptyMap()) }
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
        Unit
    }

    /**
     * Re-reads whatever the transfer lists are showing.
     *
     * Every cached blob is dropped after a transfer, which would otherwise
     * leave the deposit list empty behind the result message. Deliberately does
     * not touch the prompt, so that message stays up.
     */
    /**
     * Re-reads the cartridges a transfer has just moved past.
     *
     * A write moves the save's revision, so the blob the app was holding is
     * dropped the moment the account is re-read. Whatever is dropped has to
     * come back, or the screen it was feeding goes empty and the next transfer
     * out of it is refused for a save the app is no longer holding — which a
     * player experiences as pressing the same button and being told no, over
     * and over, with nothing they can do about it.
     *
     * So the cartridges actually touched are named rather than inferred from
     * which one happens to be in the machine, and this never defers to the
     * shared "load everything" pass: that one declines to start while another
     * is already running, which is exactly the case where a dropped blob would
     * never come back.
     */
    private fun refreshTransferSources(vararg touched: String) = viewModelScope.launch {
        val current = mutable.value
        val wanted = buildSet {
            addAll(touched)
            current.activeSaveKey?.let { add(it) }
            // Every cartridge is on screen at once in this mode, so every one
            // of them has to be current.
            if (current.showAllSaves) addAll(current.saves.map { it.key })
        }.filter { key -> current.loaded[key]?.rev != current.remote(key)?.rev }
        if (wanted.isEmpty()) return@launch

        val fetched = withContext(Dispatchers.IO) {
            wanted.mapNotNull { key ->
                current.remote(key)?.let { remote ->
                    async { (saves.load(remote) as? SyncResult.Ok)?.value?.takeIf { it.isUsable } }
                }
            }.awaitAll().filterNotNull()
        }
        if (fetched.isNotEmpty()) {
            mutable.update { it.copy(loaded = it.loaded + fetched.associateBy { save -> save.key }) }
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
    /**
     * Nicknames a stored Pokémon, or clears the nickname it has.
     *
     * Only Pokémon in this app's PC: one sitting in a cartridge is the game's
     * to name, and reaching into a save to rename it would be a write with
     * nothing to gain from it.
     */
    fun renameStored(uid: String, name: String) {
        val before = storage.get(uid)?.pokemon?.displayName?.uppercase()
        if (!storage.rename(uid, name)) {
            message("THAT POKéMON IS NOT IN THE PC.")
            return
        }
        val after = storage.get(uid)?.pokemon?.displayName?.uppercase() ?: return
        mutable.update { it.copy(storage = storage.state(), prompt = null) }
        message(if (before == after) "$after KEPT ITS NAME." else "IT IS NOW CALLED $after.")
    }

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
            finish(result, key)
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

    // ------- restoring a save from the copy kept before a write

    /** One kept copy, as the restore screen reads it. */
    data class BackupRow(
        val entry: SaveBackups.Entry,
        /** The cartridge it belongs to, named the way the carts are. */
        val cart: String,
        val takenAt: String,
        /** What is in it, so a player can tell two copies apart. */
        val summary: String,
        val readable: Boolean,
    )

    /**
     * Every copy the app has kept, newest first.
     *
     * Each is read and classified so the list can say what is in it rather
     * than only when it was taken — two copies an hour apart are otherwise
     * indistinguishable, and picking the wrong one is the whole risk here.
     */
    fun backupRows(): List<BackupRow> {
        val current = mutable.value
        return backups.all().map { entry ->
            val remote = current.remote(entry.key)
            val cart = remote?.let {
                settings.cartName(it.key) ?: "${it.version.label} ${it.label}"
            } ?: entry.key
            val save = backups.read(entry)?.let { SaveClassifier.classify(it).save }
            BackupRow(
                entry = entry,
                cart = cart.uppercase(),
                takenAt = Instant.ofEpochMilli(entry.savedAtMillis).toString().take(19).replace('T', ' '),
                summary = save?.let {
                    "${it.partyCount} OUT, ${it.storedCount} STORED, ${it.badgeCount} BADGES"
                } ?: "UNREADABLE",
                readable = save != null,
            )
        }
    }

    /**
     * Puts a save back to a copy taken before one of this app's writes.
     *
     * The copy goes through the same door every other write does: it is
     * classified before it leaves the device, committed against the revision
     * the save is at right now, and the bytes it replaces are themselves kept
     * — so a restore is as undoable as the write that prompted it.
     *
     * What it cannot undo is the other half of a transfer. A Pokémon deposited
     * since the copy was taken is in the PC, and putting the save back gives
     * the cartridge its copy as well. The player is told exactly that before
     * anything happens, because it is the one way this screen can end with two
     * of something.
     */
    fun restoreBackup(entry: SaveBackups.Entry) {
        if (engine.pendingTransfer() != null) {
            message("A PREVIOUS TRANSFER IS UNRESOLVED. FINISH IT FIRST.")
            return
        }
        val remote = mutable.value.remote(entry.key)
        if (remote == null) {
            message("THAT CARTRIDGE IS NOT ON THE ACCOUNT RIGHT NOW.")
            return
        }
        val blob = backups.read(entry)
        val root = blob?.let { SaveClassifier.classify(it).save?.root }
        if (root == null) {
            message("THAT COPY CANNOT BE READ.")
            return
        }

        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val loaded = (saves.load(remote) as? SyncResult.Ok)?.value
            if (loaded == null) {
                mutable.update { it.copy(busy = false) }
                message("THE SAVE COULD NOT BE READ.")
                return@launch
            }
            val outcome = saves.commit(loaded, root)
            mutable.update { it.copy(busy = false) }
            when (outcome) {
                is CommitOutcome.Committed -> {
                    sync()
                    message("${remote.version.label.uppercase()} WAS PUT BACK.")
                }
                is CommitOutcome.Refused -> message(outcome.reason)
                is CommitOutcome.Conflict -> message(outcome.reason)
                is CommitOutcome.Unknown -> message(outcome.reason)
            }
        }
    }

    /** What the app believes it has handed to which cartridge. */
    fun placements(): List<Placement> = engine.placements()

    fun forgetPlacement(fingerprint: String) {
        engine.forgetPlacement(fingerprint)
        message("THE RECORD WAS CLEARED.")
    }

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

/**
 * Every file the download fetches, counted once.
 *
 * The sprite sets, the hundred and fifty one cries, the follower sheets, and
 * the trainers with the sheets that come down beside them.
 */
private val DOWNLOAD_TOTAL: Int =
    SpriteSet.downloadable.size * 151 + 151 + 251 +
        (TrainerStore.ALL.size + TrainerStore.EXTRA_ART.size)
