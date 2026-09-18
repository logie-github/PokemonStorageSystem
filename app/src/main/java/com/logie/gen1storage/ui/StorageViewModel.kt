package com.logie.gen1storage.ui

import android.app.Application
import android.app.backup.BackupManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.logie.gen1storage.sound.CryStore
import com.logie.gen1storage.sound.SoundEffect
import androidx.lifecycle.viewModelScope
import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.ItemStack
import com.logie.gen1storage.rom.RomStore
import com.logie.gen1storage.rom.RomVersion
import com.logie.gen1storage.storage.ItemRepository
import com.logie.gen1storage.storage.StorageArchive
import com.logie.gen1storage.pokemon.Gen2Data
import com.logie.gen1storage.pokemon.Gen2Mail
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.TimeCapsule
import com.logie.gen1storage.pokemon.tradeEvolutionName
import com.logie.gen1storage.pokemon.tradeEvolutionOf
import com.logie.gen1storage.pokemon.tradeEvolves
import com.logie.gen1storage.backup.BackupExport
import com.logie.gen1storage.backup.DriveBackup
import com.logie.gen1storage.backup.StorageBackupAgent
import com.logie.gen1storage.storage.Hop
import com.logie.gen1storage.storage.Lineage
import com.logie.gen1storage.storage.LineageBook
import com.logie.gen1storage.storage.StoredPokemon
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.logie.gen1storage.share.PokemonCardImage
import com.logie.gen1storage.share.PrintBorder
import com.logie.gen1storage.share.PrintKind
import com.logie.gen1storage.share.ShareCard
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
import com.logie.gen1storage.transfer.MailEngine
import com.logie.gen1storage.transfer.Placement
import com.logie.gen1storage.transfer.PlacementLedger
import com.logie.gen1storage.transfer.TransferEngine
import com.logie.gen1storage.transfer.TransferJournal
import com.logie.gen1storage.transfer.TransferResult
import com.logie.gen1storage.transfer.WithdrawTarget
import com.logie.gen1storage.download.DownloadService
import kotlinx.coroutines.CoroutineExceptionHandler
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
    /** The loaded save's PC MAILBOX, Generation II only. */
    data object Mailbox : Screen
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
        /**
         * Which face of the PC the choice opens on.
         *
         * DEPOSIT asks for a card when there is none in the machine, and used
         * to forget that it had: picking one put the card in and dropped the
         * player back at the PC's own menu, so the thing they pressed DEPOSIT
         * to do had to be started over. Worse where the card they picked had
         * nothing boxed and two others did — DEPOSIT sent them straight back
         * to the shelf, and the trip went round in a circle.
         */
        val then: PcMode = PcMode.MENU,
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
    /**
     * The printer, with a stored Pokémon in it.
     *
     * Its own screen rather than a prompt because choosing what to print is
     * looking at the prints, and a window over the status screen would leave
     * no room to look at anything.
     */
    data class Printer(val uid: String) : Screen
    data object Options : Screen
    /** The four that only evolve by being traded, and the machine to do it. */
    data object Trade : Screen
    data object TimeCapsule : Screen
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
data class EvolutionScene(
    val fromSpeciesId: String?,
    val toSpeciesId: String?,
    /** What it is called, which does not change by evolving. */
    val name: String,
    val gameVersionId: String?,
    /** The cry to play when it arrives, by Pokédex number. */
    val toDexNumber: Int?,
    /**
     * The art the "after" sprite is drawn from, once it is not [gameVersionId]
     * any more. Null for a trade evolution, where the before and after are two
     * different Pokémon out of the one cartridge. Set for a Time Capsule
     * crossing, where they are the one Pokémon out of two different games'
     * drawings of it — Generation I's on the way in, Generation II's on the
     * way out.
     */
    val toGameVersionId: String? = null,
    /** What the window says while the trade is running. */
    val captionWhile: String = "EVOLVING $name",
    /** What the window says once it has landed. */
    val captionAfter: String = "$name EVOLVED!",
)

/** The transfer a status screen was opened from, and can finish. */
enum class StatusTransfer { WITHDRAW, DEPOSIT }

/** A modal the Generation I menus would draw as a window over everything. */
sealed interface Prompt {
    /**
     * Something said and then dismissed.
     *
     * [openGame] is set where what was said is a transfer that landed: the
     * cartridge has the Pokémon on the server, and the save on the phone
     * running the game catches up the next time the game syncs, which it does
     * on the way into a save. So the window offers to start it, and the row
     * appears only if the game is installed.
     */
    data class Message(val lines: List<String>, val openGame: Cartridge? = null) : Prompt

    /**
     * A save to open the game at: which game, and which of that game's saves.
     *
     * The slot is the point of it. An account can hold several playthroughs
     * of one version, and opening the game at the wrong one would sync the
     * right save and then show the player a different game.
     *
     * A null slot still opens the game, at whichever save it had last. That
     * is worth doing on its own: the sync the game runs on the way in covers
     * every save on the account, so the Pokémon lands in the right file
     * whatever the player is looking at. The slot only decides where they
     * arrive.
     */
    data class Cartridge(
        /** The save on the account, which is what a remembered slot is kept against. */
        val key: String,
        val versionId: String,
        val slot: String?,
        /** What every device on the account calls this save. */
        val playthroughId: String?,
        val label: String,
        val trainerName: String?,
    )
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

    // ------- mail

    /** A letter, read in full — from a party Pokémon or the MAILBOX alike. */
    data class ReadMail(val letter: Gen2Mail.Letter) : Prompt

    /**
     * The MAIL row on a live party Pokémon's status screen: READ or SEND TO
     * PC, `MonMailAction`'s own two live verbs. TAKE — losing the message
     * for the stationery — is not offered; see [MailEngine]'s own note on
     * why.
     */
    data class MailAction(val key: String, val slot: Int) : Prompt

    /** One letter in the MAILBOX: READ or ATTACH. `MailboxMenu`'s own two. */
    data class MailboxAction(val key: String, val index: Int) : Prompt

    /** ATTACH MAIL's party list: which Pokémon takes this letter. */
    data class AttachMail(val key: String, val mailboxIndex: Int) : Prompt
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
    val gameSelection: Boolean = true,
    /** The [GbPalette] id everything is drawn through. */
    val paletteId: String = GbPalette.ORIGINAL.id,
    val windowsFollowPalette: Boolean = false,
    val windowsOnRight: Boolean = true,
    /** Whether this app's storage is called BILL'S PC instead of LOGIE'S PC. */
    val billsPc: Boolean = true,
    /**
     * Whether the introduction has been sat through. False is what a fresh
     * install looks like, and it is the only thing that plays it.
     */
    val tutorialSeen: Boolean = true,
    /**
     * Whether this install came back from a backup and has not been asked
     * about it yet. While it stands the app shows that one question and
     * nothing else — not even the introduction. See [AppSettings.restoreOffer].
     */
    val restoreOffer: Boolean = false,
    /** How fast the text prints, as the games' OPTIONS screen puts it. */
    val textSpeed: TextSpeed = TextSpeed.DEFAULT,
    /** Shown while a transfer is in flight, and cleared by its result. */
    val transferScene: TransferScene? = null,
    /** Shown while a trade is running, when the player has asked to see it. */
    val evolutionScene: EvolutionScene? = null,
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
    /** Whether Android may copy this app into the player's Google account. */
    val cloudBackup: Boolean = false,
    /** What the system has actually done about backing this app up. */
    val backupNote: String = "OFF",
    /** Whether Generation II art follows the palette instead of its own colours. */
    val gbcFollowsPalette: Boolean = false,
    /** Swipes drive the cursor, and lists do not scroll under a finger. */
    val swipeControls: Boolean = true,
    val motionsOn: Set<String> = emptySet(),
    /** The tick under the finger. */
    val haptics: Boolean = true,
    /** Whether a shared Pokémon comes out on printer paper. */
    val printBorder: PrintBorder = PrintBorder.PAPER,
    val loadingAll: Boolean = false,
    val spriteProgress: DownloadProgress? = null,
    val spritesInstalled: Int = 0,
    val followerProgress: DownloadProgress? = null,
    val followersInstalled: Int = 0,
    val trainerProgress: DownloadProgress? = null,
    val trainersInstalled: Int = 0,
    /**
     * The opening fetch: the handful of pictures the introduction itself puts
     * on screen, which is the only art anybody is waiting on.
     *
     * The introduction holds behind this and nothing else. Everything after it
     * — two hundred and fifty followers, fifteen hundred sprites — lands while
     * Bill talks over the top of it and is never reported on screen until the
     * tour is done, because a bar creeping along under a conversation is the
     * app telling a new player to watch the loading rather than the thing it
     * has gone to the trouble of showing them.
     *
     * Finished from the start on any run that already has the art, so a replay
     * of the introduction opens straight onto him.
     */
    val openingProgress: DownloadProgress = DownloadProgress(0, 0, finished = true),
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
    val outLabel: String get() = "TRANSFER OUT"
    val inLabel: String get() = "TRANSFER IN"
    val palette: GbPalette get() = GbPalette.fromId(paletteId)

    /**
     * The art download that is actually running, or null when none is.
     *
     * Whichever stage is in flight rather than a figure across all four sets:
     * a first run fetches two of them, so a combined percentage would stop at
     * half and look stuck. The header shows this, and every screen that draws
     * a gap where a sprite should be reads [fetchingArt] to say "waiting"
     * instead of "missing".
     */
    val artProgress: DownloadProgress?
        get() = listOfNotNull(followerProgress, spriteProgress, trainerProgress)
            .firstOrNull { !it.finished }

    val fetchingArt: Boolean get() = artProgress != null
    val saves: List<RemoteSave> get() = account?.saves.orEmpty()
    fun remote(key: String?): RemoteSave? = saves.firstOrNull { it.key == key }
    fun save(key: String?): LoadedSave? = loaded[key]
}

class StorageViewModel(application: Application) : AndroidViewModel(application) {

    private val storageDir = File(application.filesDir, "pc")

    /**
     * Tells the platform this app has something new worth backing up.
     *
     * Android's backup runs on its own schedule — overnight, charging, on an
     * unmetered network — but an app only joins the queue for that pass by
     * saying its data has changed. This used to be said once, when the switch
     * was turned on, so the first backup went and everything deposited after
     * it waited on the system happening to come round again.
     *
     * Debounced, because a bulk import persists the boxes once per Pokémon
     * and the platform does not need telling six hundred times.
     */
    private var backupSignal: Job? = null

    /** Said once per run, so a bulk import does not ask twenty-five times over. */
    private var warnedBackupSize = false

    private fun backupWanted() {
        if (backupSignal?.isActive == true) return
        backupSignal = viewModelScope.launch {
            delay(BACKUP_SIGNAL_MILLIS)
            runCatching { BackupManager(getApplication()).dataChanged() }
            checkBackupSize()
        }
    }

    /**
     * A rough tally of what would actually go up: everything under the
     * app's own files directory that `backup_rules.xml` does not exclude,
     * plus every SharedPreferences file, which is where the settings and the
     * sync credentials live. Rough because a live directory can change under
     * the walk; close enough to warn by, which is all this is for.
     */
    private fun backupSizeBytes(): Long {
        val app = getApplication<Application>()
        val filesDir = app.filesDir
        val excludedDirs = listOf("roms", "sprites", "cries", "followers", "trainers", "pc/backups")
            .map { File(filesDir, it) }
        val excludedFiles = setOf("install.marker", "backup.marker", "restore.marker", "last-crash.txt")
        val filesTotal = runCatching {
            filesDir.walkTopDown()
                .filter { it.isFile }
                .filterNot { f -> f.name in excludedFiles || excludedDirs.any { f.startsWith(it) } }
                .sumOf { it.length() }
        }.getOrDefault(0L)
        val prefsDir = File(app.dataDir, "shared_prefs")
        val prefsTotal = if (prefsDir.isDirectory) {
            runCatching { prefsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                .getOrDefault(0L)
        } else 0L
        return filesTotal + prefsTotal
    }

    /**
     * Warns once a session when the backup is close enough to the
     * platform's twenty-five megabyte allowance to matter.
     *
     * The platform does not say when an app goes over — it just drops the
     * whole backup, silently — so the only way anybody finds out is a report
     * that turns out to be from before it started happening. Twenty
     * megabytes is early enough to still be a warning rather than a
     * post-mortem.
     */
    private fun checkBackupSize() {
        if (warnedBackupSize || !settings.cloudBackup) return
        val bytes = runCatching { backupSizeBytes() }.getOrDefault(0L)
        if (bytes < BACKUP_WARN_BYTES) return
        warnedBackupSize = true
        val mb = bytes / (1024 * 1024)
        message(
            "YOUR BACKUP IS GETTING BIG:",
            "ABOUT ${mb}MB OF A 25MB LIMIT.",
            "OVER THAT, GOOGLE DROPS THE",
            "WHOLE BACKUP, SILENTLY.",
        )
    }

    private val storage = StorageRepository(storageDir, ::backupWanted)
    private val journal = TransferJournal(storageDir)
    private val backups = SaveBackups(File(storageDir, "backups"))

    private val settings = AppSettings(application)
    val sprites = SpriteStore(application)
    private val spriteDownloader = SpriteDownloader(sprites)
    private var spriteJob: Job? = null
    private val cries = CryStore(application)
    val followers = FollowerStore(application)
    val trainers = TrainerStore(application)
    private var followerJob: Job? = null
    private var trainerJob: Job? = null
    private val credentials = SyncAccount(application)
    private val api = SyncApi(credentials = credentials::credentials)
    private val saves = SaveRepository(api, backups) { key, slot ->
        settings.setGameSlotId(key, slot)
    }
    private val ledger = PlacementLedger(storageDir)
    /** Where every Pokémon the app has held has been. See [LineageBook]. */
    private val lineages = LineageBook(storageDir)
    private val engine = TransferEngine(saves, storage, journal, ledger, lineages)
    private val itemStorage = ItemRepository(storageDir)
    private val itemEngine = ItemTransferEngine(saves, itemStorage)
    private val mailEngine = MailEngine(saves)
    val roms = RomStore(File(application.filesDir, "roms")).also { sprites.romStore = it }

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
    /**
     * Where every download runs, and the net under all of them.
     *
     * A scope without a handler sends anything a `launch` throws to the
     * process's uncaught handler, which is the app closing. Each set of art
     * added here brought a few more lines that could throw outside the
     * `runCatching` around the fetching itself — a count off the disk, a
     * notification, a state update — and every one of them was a new way for
     * DOWNLOAD ALL to take the app down. This is the backstop: nothing that
     * happens under a download ends the process, whatever is added next.
     * [downloadStage] is the near net, this is the far one.
     */
    private val downloads = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, error -> noteDownloadFailure(null, error) }
    )

    /**
     * Runs one stage of the download with nothing able to escape it.
     *
     * Everything the stage does goes inside, the bookkeeping afterwards
     * included, because a count taken off the disk and a state update are as
     * able to throw as a socket is and used not to be covered. A stage that
     * fails says so on the screen it belongs to and in the report; it never
     * closes the app and never stops the stages after it.
     */
    private fun downloadStage(label: String, body: suspend () -> Unit): Job =
        downloads.launch {
            runCatching { body() }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                noteDownloadFailure(label, error)
            }
        }

    /**
     * What went wrong, where a player can hand it over.
     *
     * Onto whichever bar is in flight, so the screen says STOPPED rather than
     * sitting at some percentage forever, and into a file the report under
     * ABOUT carries — the stack of something that did not crash the app is
     * otherwise lost entirely.
     */
    private fun noteDownloadFailure(label: String?, error: Throwable) {
        val said = "${label ?: "DOWNLOAD"}: ${error.javaClass.simpleName}" +
            (error.message?.let { ": $it" } ?: "")
        runCatching {
            val file = java.io.File(getApplication<Application>().filesDir, DOWNLOAD_FAILURE_FILE)
            val trace = java.io.StringWriter()
                .also { error.printStackTrace(java.io.PrintWriter(it)) }
                .toString().take(2000)
            file.writeText("${java.time.Instant.now()}\n$said\n$trace")
        }
        mutable.update { state ->
            fun stop(progress: DownloadProgress?) =
                progress?.takeIf { !it.finished }
                    ?.copy(finished = true, error = said) ?: progress
            state.copy(
                spriteProgress = stop(state.spriteProgress),
                followerProgress = stop(state.followerProgress),
                trainerProgress = stop(state.trainerProgress),
            )
        }
    }

    /** The last figure the notification was told, so it is not told it again. */
    private var shownPercent = -1

    /** Tells the service what to say, or takes it down when nothing is left. */
    /** Set while DOWNLOAD ALL is running all four stages as one download. */
    @Volatile
    private var holdingDownloadService = false

    private fun showDownload(label: String, progress: DownloadProgress?) {
        val app = getApplication<Application>()
        // Three hundred files report three hundred times and the bar has a
        // hundred positions. Rewriting the notification for every file is work
        // nobody can see.
        if (progress != null && !progress.finished && progress.percent == shownPercent) return
        shownPercent = progress?.percent?.takeIf { !progress.finished } ?: -1
        if (progress == null || progress.finished) {
            val stillGoing = mutable.value.let {
                listOfNotNull(it.spriteProgress, it.followerProgress, it.trainerProgress)
                    .any { p -> !p.finished }
            }
            // DOWNLOAD ALL holds it up across all four stages. Between one
            // stage finishing and the next reporting there is a moment when
            // nothing is running, and taking the service down in it meant the
            // next stage had to start it again — from a process that by then
            // is very often not the app on screen, which is a start the system
            // refuses and a clock it can crash the app over. Four stages, four
            // of those. One download, one service.
            if (!stillGoing && !holdingDownloadService) DownloadService.hide(app)
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
        noticeRestore()
        sprites.gbcFollowsPalette = settings.gbcFollowsPalette
        // The trainers were left out of this, so GBC SPRITES / PALETTE
        // recoloured the Pokemon and the Generation II trainer art went on
        // wearing its own greens and browns beside them.
        trainers.gbcFollowsPalette = settings.gbcFollowsPalette
        // What GBC SPRITES / ORIGINAL draws through: each game's own colours,
        // the same ones its title card and its trainer card are drawn in. Set
        // once, because the games' palettes do not change — only which of the
        // two sets of colours is asked for does.
        val own = gameOwnRamps()
        sprites.gameRamps = own
        trainers.gameRamps = own
        applySpriteTint(GbPalette.fromId(settings.paletteId))
        // Settled here rather than when the download starts, because the
        // introduction is drawn before that: a first frame that found nothing
        // waiting would let Bill open his mouth and then be overtaken by the
        // bar a moment later.
        val installedSprites = sprites.installedSets().sumOf { set -> sprites.countIn(set) }
        val installedFollowers = followers.count()
        val bareInstall = installedSprites == 0 && installedFollowers == 0
        mutable.update {
            it.copy(
                openingProgress =
                    if (bareInstall) DownloadProgress(0, 1) else DownloadProgress(0, 0, finished = true),
                storage = storage.state(),
                items = itemStorage.state(),
                linked = credentials.isLinked,
                showAllSaves = settings.showAllSaves,
                showAllItems = settings.showAllItems,
                gameSelection = settings.gameSelection,
                paletteId = settings.paletteId,
                windowsFollowPalette = settings.windowsFollowPalette,
                windowsOnRight = settings.windowsOnRight,
                billsPc = settings.billsPc,
                tutorialSeen = settings.tutorialSeen,
                restoreOffer = settings.restoreOffer,
                tradeEvolution = settings.tradeEvolution,
                tradeAnimation = settings.tradeAnimation,
                reduceMotion = settings.reduceMotion,
                cloudBackup = settings.cloudBackup,
                backupNote = backupNote(),
                gbcFollowsPalette = settings.gbcFollowsPalette,
                swipeControls = settings.swipeControls,
                motionsOn = enabledMotions(),
                haptics = settings.haptics,
                printBorder = settings.printBorder,
                textSpeed = settings.textSpeed,
                soundOff = settings.soundOff,
                soundsOn = enabledSounds(),
                spritesInstalled = sprites.installedSets().sumOf { set -> sprites.countIn(set) },
                followersInstalled = followers.count(),
                trainersInstalled = trainers.count(),
            )
        }
        if (credentials.isLinked) sync()
    }

    // ------- navigation

    /**
     * Where the PC screen was standing: its menu, a list, or the box.
     *
     * Held here rather than inside the screen because that screen is built
     * twice. Opening a Pokémon's stats on a wide display draws the screen it
     * was opened from beside them, and that is a second, fresh copy of the PC
     * — which came up on its own menu, so pressing STATS in the box appeared
     * to throw the box away and go back to the front of the machine.
     */
    var pcMode by mutableStateOf(PcMode.MENU)

    fun open(screen: Screen) {
        // Entering the PC from somewhere else starts at its menu; opening
        // something over it — stats, a cartridge list — leaves it where it is.
        if (screen == Screen.Storage) pcMode = PcMode.MENU
        mutable.update { it.copy(stack = it.stack + screen) }
    }

    fun replace(screen: Screen) =
        mutable.update { it.copy(stack = it.stack.dropLast(1) + screen) }

    fun back(): Boolean {
        val current = mutable.value
        if (current.prompt != null) {
            mutable.update { it.copy(prompt = null) }
            return true
        }
        // A list open over the PC is a window on it, and B closes the window
        // it is standing in before it walks the stack — the same order the
        // prompt above takes. Without this, backing out of DEPOSIT, WITHDRAW
        // or the box left the machine altogether and said SEE YA! on the way,
        // and a list that came up empty had no CANCEL row to close instead:
        // "There are no POKéMON here." could only be answered by leaving.
        if (current.screen == Screen.Storage && pcMode != PcMode.MENU) {
            pcMode = PcMode.MENU
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


    fun home() {
        wantsEvolving = emptyList()
        afterEvolutions = null
        mutable.update { it.copy(stack = listOf(Screen.Home), prompt = null) }
    }

    /**
     * Answers whatever is on screen, then asks the next thing waiting.
     *
     * There is one prompt slot and a transfer can leave questions behind it:
     * four Pokémon brought in, two of which a trade would change. Each is
     * asked as the one before it is answered, so they arrive in the order
     * they came in rather than all at once or not at all.
     */
    fun dismissPrompt() {
        mutable.update { it.copy(prompt = null) }
        askNextEvolution()
    }

    fun prompt(prompt: Prompt) = mutable.update { it.copy(prompt = prompt) }

    private fun message(vararg lines: String) =
        mutable.update { it.copy(prompt = Prompt.Message(lines.toList()), busy = false) }

    /**
     * What a landed transfer says, with the offer to go and see it.
     *
     * The save is only changed on the server until the game next syncs, and
     * the game syncs on the way into a save, so the shortest path from here
     * to a Pokémon actually being in the cartridge is starting the game.
     */
    private fun transferMessage(lines: List<String>, touched: Array<out String>) {
        val state = mutable.value
        // The save that was actually written, not just its version: that is
        // the one the player is about to want to be looking at.
        // The card in the machine, and only failing that the save that was
        // written. A player who has Gino's card inserted means Gino when they
        // say open the game, whichever cartridge the Pokémon was sent to —
        // and the sync on the way in covers every save on the account, so the
        // one that was written is up to date either way.
        val written = state.remote(state.activeSaveKey)
            ?: touched.firstNotNullOfOrNull { key -> state.remote(key) }
        val cartridge = when {
            written != null -> Prompt.Cartridge(
                key = written.key,
                versionId = written.version.id,
                // What the server lists, and failing that what the player has
                // told this card it is. Neither is guesswork: the first is
                // the id the game uploaded, the second is a position counted
                // off the game's own save screen.
                slot = openAt(written.key),
                playthroughId = written.playthroughId,
                label = written.version.label,
                trainerName = written.summary.trainerName?.uppercase(),
            )
            else -> touched.firstNotNullOfOrNull { key ->
                GameVersion.fromId(key.substringBefore('/'))
            }?.let {
                Prompt.Cartridge("", it.id, null, null, it.label, null)
            }
        }
        val said = Prompt.Message(lines, openGame = cartridge)
        // Anything that wants to evolve is asked about first. A transfer's own
        // result carries the way into the game, and that is the last thing to
        // offer — putting it first sends the player off before the machine has
        // finished with what they just brought in.
        if (wantsEvolving.isNotEmpty() && state.tradeEvolution) {
            afterEvolutions = said
            mutable.update { it.copy(busy = false) }
            askNextEvolution()
        } else {
            mutable.update { it.copy(prompt = said, busy = false) }
        }
    }

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
            // A sync can change what the account link looks like — codes
            // cleared on an Unauthorized, a save's revision moving on —
            // without ever touching the boxes, and none of that is a change
            // `persist()` sees. So it says so itself, same as a deposit does.
            backupWanted()
        } finally {
            mutable.update { it.copy(syncing = false) }
        }
    }

    private suspend fun runSync(silent: Boolean) {
        val before = mutable.value
        when (val result = withContext(Dispatchers.IO) { saves.listSaves() }) {
            is SyncResult.Ok -> {
                val account = result.value
                val notes = withContext(Dispatchers.IO) { engine.recover(account.saves) }
                // Which cartridges the game has written since the last look.
                // Only meaningful against an account this app has already
                // seen: on the first sync of a session everything is new.
                val moved = if (before.account == null) emptyList() else {
                    account.saves.filter { row ->
                        before.remote(row.key)?.rev?.let { it != row.rev } == true
                    }.map { it.key }
                }
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
                // A cartridge the game has just saved is read again straight
                // away, so what is on screen is what is in the save rather
                // than an empty list waiting for somebody to open something.
                // Only the ones this app was already holding, or the one in
                // the machine: a revision moving on a cartridge nobody is
                // looking at is not a reason to fetch it.
                val worthReading = moved.filter {
                    it in before.loaded || it == before.activeSaveKey
                }
                if (worthReading.isNotEmpty()) refreshTransferSources(*worthReading.toTypedArray())
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
        account.unsupported.forEach { add("- a save from a game this app does not know was ignored") }
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
        // Cached is only good while it is at the revision the account
        // reports. Any cache hit used to short-circuit this outright, so a
        // cartridge read once early on — before anything was deposited into
        // its in-game PC box, say — stayed that stale copy for the rest of
        // the session: reselecting it never looked at the account again, so
        // DEPOSIT went on reading boxes as they were at the first look,
        // however long ago that was and however much has changed on the
        // cartridge since. [loadAllSaves] already gets this right; this is
        // the same check.
        if (mutable.value.loaded[key]?.rev == remote.rev) {
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
    fun chooseCart(
        key: String,
        thenOpenStorage: Boolean = false,
        then: PcMode = PcMode.MENU,
    ) = selectSave(key) {
        // Arriving at the PC with a card just inserted starts at its menu, the
        // same as walking up to it does — unless the card was asked for by
        // something that was already underway, which opens on that instead.
        // See [pcMode] and [Screen.ChooseCart.then].
        if (thenOpenStorage) pcMode = then
        mutable.update { state ->
            val stack = state.stack.dropLastWhile { it is Screen.ChooseCart }
            // The PC is where the card was asked for as often as not, and
            // pushing a second copy of it over the first left the way out
            // going nowhere: BACK said SEE YA! and landed on the PC again,
            // so leaving took two goes and a goodbye that was not meant.
            val next = when {
                !thenOpenStorage -> stack
                stack.lastOrNull() == Screen.Storage -> stack
                else -> stack + Screen.Storage
            }
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
        // The row this came from is already greyed out and unreachable by a
        // cursor without the ROM its palette belongs to; this is the same
        // rule enforced again in case anything else ever calls this.
        val requiredRom = palette.requiredRom
        if (requiredRom != null && !roms.has(requiredRom)) return
        settings.paletteId = palette.id
        applySpriteTint(palette)
        mutable.update { it.copy(paletteId = palette.id, spriteRevision = it.spriteRevision + 1) }
    }

    fun setSwipeControls(on: Boolean) {
        settings.swipeControls = on
        mutable.update { it.copy(swipeControls = on) }
    }

    fun setReduceMotion(on: Boolean) {
        settings.reduceMotion = on
        mutable.update { it.copy(reduceMotion = on, motionsOn = enabledMotions()) }
    }

    /**
     * Whether every sprite is drawn through the chosen palette, or each in
     * the colours of the game it came from.
     *
     * The sprite cache is keyed on it, so the change is on screen at once
     * rather than at the next thing that happens to reload a sprite.
     */
    fun setGbcFollowsPalette(on: Boolean) {
        settings.gbcFollowsPalette = on
        sprites.gbcFollowsPalette = on
        trainers.gbcFollowsPalette = on
        mutable.update {
            it.copy(gbcFollowsPalette = on, spriteRevision = it.spriteRevision + 1)
        }
    }

    /** What a print comes out with around it. See [AppSettings.printBorder]. */
    fun setPrintBorder(border: PrintBorder) {
        settings.printBorder = border
        mutable.update { it.copy(printBorder = border) }
    }

    /**
     * A stored Pokémon as a picture, ready to be sent somewhere.
     *
     * Built here rather than by grabbing the screen: a screenshot carries
     * whatever else was on it and whatever the phone's own bars look like,
     * and the thing worth sending is the Pokémon.
     */
    fun cardImage(
        uid: String,
        kind: PrintKind = PrintKind.DEX,
        border: PrintBorder = settings.printBorder,
    ): Bitmap? {
        val stored = storage.get(uid) ?: return null
        val sprite = stored.pokemon.speciesId
            ?.let { sprites.load(it, stored.spriteGameVersionId, cutout = false) }
            ?.asAndroidBitmap()
        return runCatching {
            PokemonCardImage.render(
                context = getApplication(),
                pokemon = stored.pokemon,
                sprite = sprite,
                provenance = stored.provenance,
                palette = GbPalette.fromId(settings.paletteId),
                kind = kind,
                border = border,
                dexTile = { trainers.dexTile(it) },
            )
        }.getOrNull()
    }

    fun cardName(uid: String): String =
        storage.get(uid)?.pokemon?.displayName ?: "pokemon"

    /**
     * Draws a stored Pokémon's card and hands it to whatever the phone shares
     * with.
     *
     * Off the main thread: a card is a bitmap the size of a Game Boy screen
     * six times over, drawn a rectangle at a time, and that is long enough to
     * show on a scrolling list. The chooser is opened from the application
     * context with NEW_TASK, so this does not need the activity.
     */
    fun shareCard(
        uid: String,
        kind: PrintKind = PrintKind.DEX,
        border: PrintBorder = settings.printBorder,
    ) = viewModelScope.launch {
        val name = cardName(uid)
        val card = withContext(Dispatchers.Default) { cardImage(uid, kind, border) }
        val sent = card != null &&
            ShareCard.share(getApplication(), card, name)
        if (!sent) message("THAT CARD COULD NOT BE SENT.")
    }

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
    /**
     * The Generation I Pokémon in this PC that could go forward.
     *
     * Everything the app is holding that has not made the trip and that
     * Generation II has a row for. One mid-transfer is left out: a Pokémon
     * promised to a cartridge is not one to change underneath it.
     */
    fun timeCapsuleCandidates(): List<StoredPokemon> =
        timeCapsuleRoster().mapNotNull { (stored, blocked) -> stored.takeIf { blocked == null } }

    /**
     * Why one cannot go through, or null when it can.
     *
     * Four separate reasons, kept apart rather than rolled into one test,
     * because a Pokemon that is simply not on the screen is a bug report with
     * nothing in it. Three of them are the app's own bookkeeping and one —
     * the third — is a guess read off the Pokemon's own fields, which is the
     * one that can be wrong about a Pokemon nobody has touched.
     */
    fun timeCapsuleBlock(stored: StoredPokemon): String? = when {
        // Nothing here settles a transfer note any more, so a mark left by an
        // older build is a mark that never clears. Named rather than obeyed
        // in silence.
        stored.inFlight -> "IT IS WAITING ON A TRANSFER."
        stored.generation != 1 -> "IT IS ALREADY IN GENERATION II."
        TimeCapsule.hasCrossed(stored.data) -> "ITS OWN FIELDS ARE GENERATION II'S."
        stored.pokemon.speciesId?.let { Gen2Data.species(Gen2Data.idOf(it)) } == null ->
            "GENERATION II HAS NO ENTRY FOR IT."
        else -> null
    }

    /**
     * Everything in the PC, with the reason each one cannot go on beside it,
     * the ones that can first.
     *
     * The screen used to be handed the ones that could go and nothing else,
     * so a Pokemon held back by any of the four tests above was not refused —
     * it was absent, which is the one answer a player cannot act on. Every
     * one of them is listed now and says for itself why it is staying.
     */
    fun timeCapsuleRoster(): List<Pair<StoredPokemon, String?>> =
        mutable.value.storage.boxes.flatMap { it.contents }
            .map { it to timeCapsuleBlock(it) }
            .sortedBy { (_, blocked) -> blocked != null }

    /**
     * Sends one forward, with what it was written down beside it.
     *
     * Every change the conversion makes is pret/pokecrystal's — see
     * [TimeCapsule]. Nothing leaves the PC and no save is written: this app
     * is holding the Pokémon on both sides of the cable, which is the only
     * reason it can be offered at all.
     */
    fun carryForward(uid: String) = viewModelScope.launch {
        val current = mutable.value
        val stored = storage.get(uid)
        if (stored == null) {
            message("THAT POKéMON IS NOT IN THE PC.")
            return@launch
        }
        val name = stored.pokemon.displayName.uppercase()
        // Whichever Generation II card is in the machine is the cartridge the
        // player is playing, so it is the one this Pokemon is bound for and
        // the one whose art it comes back wearing. Gold only when there is no
        // Generation II card in at all, which is the old behaviour for
        // everybody who never inserts one.
        val into = current.save(current.activeSaveKey)?.save
            ?.takeIf { it.isGen2 }
            ?.let { current.remote(current.activeSaveKey)?.version?.id }
            ?: GameVersion.GOLD.id
        val intoLabel = GameVersion.fromId(into)?.label ?: GameVersion.GOLD.label

        // The cartridges never gave the Time Capsule an animation of its own —
        // crossing over was the ordinary link-trade sequence, the same cable
        // and the same ball any other trade used, just with a cartridge on
        // each end that spoke a different generation. This app is both ends of
        // that cable already (see [tradeEvolve]), so the same scene draws it:
        // Generation I's picture of the Pokémon going out, Generation II's
        // coming back — nothing evolved, only the art it left in changed.
        if (current.tradeAnimation && current.moves(Motion.TRADE)) {
            val fromSpecies = stored.pokemon.speciesId
            val toSpecies = fromSpecies?.let { Gen2Data.idOf(it) }
            mutable.update {
                it.copy(
                    busy = true,
                    prompt = null,
                    evolutionScene = EvolutionScene(
                        fromSpeciesId = fromSpecies,
                        toSpeciesId = toSpecies,
                        name = name,
                        gameVersionId = stored.spriteGameVersionId,
                        toGameVersionId = into,
                        toDexNumber = Gen2Data.species(toSpecies)?.dexNumber,
                        captionWhile = "$name IS GOING",
                        captionAfter = "$name CAME THROUGH!",
                    ),
                )
            }
            delay(EVOLUTION_SCENE_MILLIS)
        } else {
            mutable.update { it.copy(busy = true, prompt = null) }
        }

        val record = storage.carryForward(uid, gameVersion = into)
        mutable.update { it.copy(busy = false, evolutionScene = null, storage = storage.state()) }
        if (record == null) {
            message("$name COULD NOT GO ON.")
            return@launch
        }
        val lines = buildList {
            add("$name came through to $intoLabel.")
            record.heldItem?.let { add("It is holding ${itemLabel(it)}.") }
        }
        mutable.update { it.copy(prompt = Prompt.Message(lines)) }
    }

    fun tradeCandidates(): List<StoredPokemon> =
        mutable.value.storage.boxes.flatMap { it.contents }
            .filter { tradeEvolves(it.pokemon) }

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
        val generation = stored.pokemon.generation
        val to = tradeEvolutionOf(stored.pokemon)
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
                    evolutionScene = EvolutionScene(
                        fromSpeciesId = from,
                        toSpeciesId = to,
                        name = name,
                        gameVersionId = stored.spriteGameVersionId,
                        toDexNumber = if (generation >= 2) Gen2Data.species(to)?.dexNumber
                        else Gen1Data.species(to)?.dexNumber,
                    ),
                )
            }
            delay(EVOLUTION_SCENE_MILLIS)
        } else {
            mutable.update { it.copy(busy = true, prompt = null) }
        }

        val became = storage.evolveByTrade(uid)
        mutable.update {
            it.copy(busy = false, evolutionScene = null, storage = storage.state())
        }
        if (became == null) {
            message("NOTHING HAPPENED.")
        } else {
            message("$name evolved into ${tradeEvolutionName(became, generation).uppercase()}!")
        }
    }

    /**
     * Pokémon just brought in that a trade would change, waiting to be asked
     * about. Held here rather than shown straight away: the transfer's own
     * result is on screen when they arrive, and a question that replaced it
     * would take away the only word the player gets that the transfer worked.
     */
    private var wantsEvolving = listOf<String>()

    /**
     * The transfer's own result, held back until every evolution has been
     * asked about and answered. See [transferMessage].
     */
    private var afterEvolutions: Prompt.Message? = null

    /** Remembers one that has just landed in the PC, if a trade would change it. */
    private fun noteEvolvable(uid: String?) {
        if (uid == null || !mutable.value.tradeEvolution) return
        val stored = storage.get(uid) ?: return
        if (tradeEvolves(stored.pokemon)) wantsEvolving = wantsEvolving + uid
    }

    /**
     * Asks about the next one, the way the cartridge asks after a trade.
     *
     * Skips any that are no longer in the PC or no longer evolve — they may
     * have been released or already put through the machine between the
     * transfer and the answer — so a stale entry costs a question rather than
     * an error.
     */
    private fun askNextEvolution() {
        if (!mutable.value.tradeEvolution) {
            wantsEvolving = emptyList()
            sayWhatWasHeldBack()
            return
        }
        while (wantsEvolving.isNotEmpty()) {
            val uid = wantsEvolving.first()
            wantsEvolving = wantsEvolving.drop(1)
            val stored = storage.get(uid) ?: continue
            if (!tradeEvolves(stored.pokemon)) continue
            val name = stored.pokemon.displayName.uppercase()
            mutable.update {
                it.copy(
                    prompt = Prompt.Confirm(
                        lines = listOf("Oh? $name wants to evolve!", "Evolve $name?"),
                        confirmLabel = "YES",
                        cancelLabel = "NO",
                        onConfirm = { tradeEvolve(uid) },
                    )
                )
            }
            return
        }
        sayWhatWasHeldBack()
    }

    /** The transfer's result, once the evolutions are out of the way. */
    private fun sayWhatWasHeldBack() {
        val said = afterEvolutions ?: return
        afterEvolutions = null
        mutable.update { it.copy(prompt = said) }
    }

    fun setBillsPc(on: Boolean) {
        settings.billsPc = on
        mutable.update { it.copy(billsPc = on) }
    }

    /**
     * The introduction is over, and stays over.
     *
     * Written to disk rather than held in memory: the whole point of it is
     * that a second run opens straight onto the menu.
     */
    fun finishTutorial() {
        settings.tutorialSeen = true
        mutable.update { it.copy(tutorialSeen = true) }
    }

    /**
     * Which cartridges are not on the device, by name.
     *
     * For the introduction, which says so in a sentence rather than leaving a
     * window of lists over what somebody is trying to read.
     */
    fun romsStillMissing(): List<String> =
        RomVersion.entries.filterNot { roms.has(it) }.map { it.label }

    /**
     * OPTIONS asking for it again.
     *
     * Back out to the main menu on the way, because the introduction is drawn
     * where a screen is drawn: left where it was, finishing it would drop the
     * player back into the OPTIONS drawer they started it from, which is the
     * one place a tour of the machine should not end.
     */
    fun replayTutorial() {
        settings.tutorialSeen = false
        mutable.update {
            it.copy(tutorialSeen = false, prompt = null, stack = listOf(Screen.Home))
        }
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

    /**
     * Each game's four colours, darkest first, keyed by its version id.
     *
     * The same palettes the shelf draws a cartridge's title card and a
     * trainer card in — see `paletteFor` — so a Pokemon out of Red is the red
     * of the card it came off, not a second red invented here.
     */
    private fun gameOwnRamps(): Map<String, IntArray> =
        GameVersion.entries.associate { version ->
            version.id.lowercase() to
                paletteFor(version.id).ramp.map { it.toArgb() }.toIntArray()
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

    /** Whether the shelf of games stands over the cards. See [AppSettings.gameSelection]. */
    fun setGameSelection(enabled: Boolean) {
        settings.gameSelection = enabled
        mutable.update { it.copy(gameSelection = enabled) }
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
    /**
     * The sets still worth fetching: every one this app downloads by default,
     * less any a ROM on this device already draws.
     *
     * Importing a cartridge is how a player stops downloading art. The sprite
     * store asks the ROM before it asks the sprite folder, so a downloaded
     * copy of a set the ROM covers is a few hundred files fetched to sit on
     * disk unread. See [RomStore.covers].
     */
    private fun setsWorthFetching(): List<SpriteSet> =
        SpriteSet.downloadable.filterNot { roms.covers(it.id) }

    fun downloadSprites() {
        if (spriteJob?.isActive == true) return
        spriteJob = downloadStage("SPRITES") {
            mutable.update { it.copy(prompt = null, spriteProgress = DownloadProgress(0, 1)) }
            val wanted = setsWorthFetching()
            if (wanted.isEmpty()) {
                // Every set is coming out of a cartridge, so there is nothing
                // to fetch and nothing to report but a finished bar.
                mutable.update { state ->
                    state.copy(spriteProgress = DownloadProgress(0, 0, finished = true))
                }
                return@downloadStage
            }
            val result = runCatching {
                spriteDownloader.download(wanted) { progress ->
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
    /**
     * What the cries cost on disk, which is a cache of this app's own
     * rendering rather than anything it fetched. See [CryStore].
     */
    fun cryBytesOnDisk(): Long = cries.bytesOnDisk()

    fun deleteCries() {
        cries.clear()
        mutable.update { it.copy(prompt = null) }
    }

    // ------- followers

    /** Downloads the overworld follower sheets the box grid draws. */
    fun downloadFollowers() {
        if (followerJob?.isActive == true) return
        followerJob = downloadStage("FOLLOWERS") {
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
    /**
     * @param dismissPrompt Clears whatever prompt is up when the download
     * starts - right for the menu row that asks "download everything?" and
     * wrong for the TRAINER picker's own quiet catch-up fetch, which is
     * itself a prompt: left at its default this closed the very picker that
     * triggered it the instant a tap opened it, which read as the app
     * crashing rather than as a fetch starting behind it.
     */
    fun downloadTrainers(dismissPrompt: Boolean = true) {
        if (trainerJob?.isActive == true) return
        trainerJob = downloadStage("TRAINERS") {
            mutable.update {
                it.copy(
                    prompt = if (dismissPrompt) null else it.prompt,
                    trainerProgress = DownloadProgress(0, 1),
                )
            }
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
     * What to open the game at for a save, best first.
     *
     * The slot id the server gave, then one learnt from a save's own fetch,
     * and failing both the position this app shows the card at.
     * `LaunchOptions.selectSlot` takes an id or a position and they mean the
     * same thing to it.
     *
     * The last one is not a guess. Both lists are the account's saves for one
     * game in the order the account lists them, so the card shelf here and
     * the game's save screen run in the same order — the third card along is
     * the third save along.
     */
    fun openAt(key: String): String? =
        mutable.value.remote(key)?.slot
            ?: settings.gameSlotId(key)
            ?: cardPosition(key)?.toString()

    /** Where this card sits among that game's cards, counting from one. */
    private fun cardPosition(key: String): Int? {
        val state = mutable.value
        val row = state.remote(key) ?: return null
        val among = state.saves.filter { it.version == row.version }
        return among.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.plus(1)
    }

    /**
     * Fetches all four sets, one after another rather than at once.
     *
     * Three downloads racing each other over one connection finish no sooner
     * and each report a percentage that stalls while the others have the
     * line. In order, each one's bar means what it says.
     */
    /**
     * What a brand new install fetches for itself, unasked and in the
     * background.
     *
     * Two of the four sets, not all of them. These are the two every screen
     * is drawn out of — the box is made of followers and everything else is
     * made of front sprites — so without them the app opens onto a grid of
     * bracketed question marks. The cries and the trainer art are additions
     * rather than the furniture, and stay in OPTIONS where someone can decide
     * to spend the bytes on them.
     *
     * Followers first, because the box is the screen a player is most likely
     * to be looking at while the rest of it lands.
     */
    fun downloadFirstRun() {
        downloadStage("FIRST RUN") {
            holdingDownloadService = true
            try {
                fetchTheHandfulFirst()
                downloadFollowers()
                followerJob?.join()
                downloadSprites()
                spriteJob?.join()
                // Left out of a first run before now, which is why a trainer
                // card's TRAINER picker came up a column of blank rows for
                // anyone who had never gone looking for DOWNLOAD ALL in
                // OPTIONS: every Pokémon this app draws is fetched up front,
                // and a trainer is the same kind of art.
                downloadTrainers()
                trainerJob?.join()
            } finally {
                holdingDownloadService = false
                runCatching { DownloadService.hide(getApplication()) }
            }
        }
    }

    /**
     * The dozen the introduction is about to put on screen, ahead of the rest.
     *
     * Two hundred and fifty followers and fifteen hundred sprites do not
     * arrive in the twenty seconds it takes to be shown round the app, and
     * the archive is fetched in whatever order the list happens to be in — so
     * without this the trainer card, the transfer and the box all came up as
     * bracketed question marks on the one run where a new player is looking
     * hardest. Naming the handful first costs a couple of seconds and about
     * thirty files, and every one of them is a file the full run would have
     * fetched anyway: it skips what is already on disk, so nothing is
     * downloaded twice.
     *
     * This is the one fetch anybody watches, and [UiState.openingProgress] is
     * it: nought to a hundred across both halves — the followers, then the
     * fronts — so the bar means "the introduction is nearly ready" rather than
     * "some proportion of an archive". It finishes whatever happens, a dead
     * network included, because a tour held behind a download that is never
     * coming is an app that does not open.
     */
    private suspend fun fetchTheHandfulFirst() {
        fun report(percent: Int) = mutable.update {
            it.copy(openingProgress = DownloadProgress(percent.coerceIn(0, 100), 100))
        }
        // Half the bar each. The second half is the one that can be empty —
        // a cartridge on the device covers its own fronts — and a half that
        // has nothing to do simply passes through.
        fun half(from: Int, progress: DownloadProgress) {
            val share = if (progress.total <= 0) 50 else (progress.done * 50) / progress.total
            report(from + share)
        }
        try {
            report(0)
            runCatching { followers.downloadAll(TutorialSamples.DEX_NUMBERS) { half(0, it) } }
            report(50)
            setsWorthFetching().takeIf { it.isNotEmpty() }?.let { sets ->
                runCatching { spriteDownloader.download(sets, TutorialSamples.SPECIES) { half(50, it) } }
            }
            // The player's own trainer card art, ahead of the tutorial that puts
            // one on screen. [TrainerStore.load] only ever reads what is
            // already on disk — nothing is there yet on a bare install — so
            // this has to be [TrainerStore.fetch], which pulls the file from
            // pokered (or pokecrystal, for the Generation II pics) the same
            // way [downloadTrainers] does for the rest of the set.
            withContext(Dispatchers.IO) {
                runCatching {
                    trainers.fetch(TrainerStore.PLAYER)
                    trainers.fetch(TrainerStore.GEN2_PLAYER_MALE)
                    trainers.fetch(TrainerStore.GEN2_PLAYER_FEMALE)
                }
            }
        } finally {
            mutable.update {
                it.copy(
                    followersInstalled = followers.count(),
                    spritesInstalled = sprites.installedSets().sumOf { set -> sprites.countIn(set) },
                    trainersInstalled = trainers.count(),
                    spriteRevision = it.spriteRevision + 1,
                    openingProgress = DownloadProgress(100, 100, finished = true),
                )
            }
        }
    }

    fun downloadEverything() {
        // Each stage in turn, and every one of them attempted: a set that
        // fails is a gap in the art, not a reason to leave the three after it
        // unfetched. Nothing here can throw — [downloadStage] sees to that —
        // so the sequence always reaches the end.
        downloadStage("DOWNLOAD ALL") {
            holdingDownloadService = true
            try {
                downloadSprites()
                spriteJob?.join()
                downloadFollowers()
                followerJob?.join()
                downloadTrainers()
                trainerJob?.join()
            } finally {
                holdingDownloadService = false
                runCatching { DownloadService.hide(getApplication()) }
            }
        }
    }

    // ------- the download, as one thing
    //
    // There are four sets and no reason for a player to be asked about them
    // one at a time: the app wants all of it, a set that is missing shows as
    // a gap wherever it was needed, and choosing to have three quarters of
    // the art is not a choice worth offering. So everything below reads the
    // four as one download.

    /** How many files this device still has any reason to fetch. */
    private fun downloadTotal(): Int = downloadTotalFor(setsWorthFetching())

    /** Everything already fetched, as a share of everything there is. */
    fun downloadedPercent(): Int {
        val have = mutable.value.let {
            it.spritesInstalled + it.followersInstalled + it.trainersInstalled
        }
        val total = downloadTotal()
        return if (total <= 0) 0 else ((have.coerceAtMost(total) * 100) / total)
    }

    /** How far the download in flight has got, or null when none is. */
    fun downloadProgress(): DownloadProgress? {
        val current = mutable.value
        val all = listOfNotNull(
            current.spriteProgress,
            current.followerProgress,
            current.trainerProgress,
        )
        if (all.isEmpty()) return null
        // One bar over the lot rather than four in a row: the sets run one
        // after another and a bar that restarts three times reads as three
        // downloads rather than as one that is three quarters done.
        //
        // Counted a file at a time rather than a set at a time. The installed
        // counts are only recounted off the disk when a set finishes, so a bar
        // reading them alone sat still through four hundred files and then
        // jumped a quarter — which is a bar that only says whether the thing
        // is done, four times. Whichever is further along counts: the set
        // running reports each file as it lands, and the three not running
        // report what is already here.
        val done = maxOf(current.spritesInstalled, current.spriteProgress?.done ?: 0) +
            maxOf(current.followersInstalled, current.followerProgress?.done ?: 0) +
            maxOf(current.trainersInstalled, current.trainerProgress?.done ?: 0)
        val running = all.any { !it.finished }
        return DownloadProgress(
            done = done.coerceAtMost(downloadTotal()),
            total = downloadTotal(),
            failed = all.sumOf { it.failed },
            finished = !running,
            error = all.firstNotNullOfOrNull { it.error },
        )
    }

    fun downloadBytesOnDisk(): Long =
        sprites.bytesOnDisk() + followers.bytesOnDisk() + trainers.bytesOnDisk() +
            // The cries are this app's own renders rather than a download, but
            // they are still bytes in its folder and DELETE should take them.
            cries.bytesOnDisk()

    fun cancelDownload() {
        cancelSpriteDownload()
        cancelFollowerDownload()
        cancelTrainerDownload()
    }

    fun dismissDownloadProgress() = mutable.update {
        it.copy(
            spriteProgress = null,
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

    // ------- ROMs

    /**
     * Saves [bytes] as [version]'s own ROM, once [RomStore.import] has
     * confirmed it really is one. Returns whether it was — a file this app
     * cannot find its own data inside is never kept, and never mistaken for
     * a reason to blame the player's copy.
     */
    fun importRom(version: RomVersion, bytes: ByteArray): Boolean {
        val accepted = roms.import(version, bytes)
        // A ROM changes what a sprite decodes to, the same as a download
        // finishing does, so it rides the same cache-busting revision.
        mutable.update { it.copy(spriteRevision = it.spriteRevision + 1) }
        message(
            if (accepted) "${version.label} IS IN."
            else "THAT DOES NOT LOOK LIKE A ${version.label} ROM."
        )
        return accepted
    }

    fun deleteRom(version: RomVersion) {
        roms.delete(version)
        mutable.update { it.copy(spriteRevision = it.spriteRevision + 1) }
    }

    /**
     * Imports every ROM [RomFolderImporter] can find and name under
     * [treeUri], a folder the player picked with the system's own chooser
     * rather than one file at a time.
     *
     * Returns the [Job] doing the work, for a caller that has to know when it
     * is actually done rather than only that it was asked for — the
     * introduction's own ROMS beat reports what turned up, and reporting it
     * before the import has run would just be reading last run's answer.
     */
    fun importRomsFolder(treeUri: Uri): Job {
        mutable.update { it.copy(busy = true) }
        return downloadStage("ROMS") {
            com.logie.gen1storage.rom.RomFolderImporter.import(getApplication<Application>(), treeUri, roms)
            mutable.update { it.copy(spriteRevision = it.spriteRevision + 1) }
            // Nothing is said about it here. A window listing what turned up
            // landed over whatever was on screen, which during the
            // introduction meant a window over Bill mid-sentence; and the
            // ROMS drawer already names every cartridge on the device, a row
            // each, for anyone who wants the list. The introduction reports
            // it in his own words instead: see [Gen1Tutorial.romsFound].
            mutable.update { it.copy(busy = false) }
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
                // The ball goes with it. Nothing is in the air any more, and
                // a scene left standing here would hold the controls shut on
                // a transfer that never started.
                mutable.update { it.copy(busy = false, transferScene = null) }
                return@launch message("THE TRANSFER COULD NOT START.", e.message.orEmpty().uppercase())
            }
            if (result is TransferResult.Success) noteEvolvable(result.storedUid)
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
                            gameVersionId = stored.spriteGameVersionId,
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
            var carrying: LoadedSave? = null
            for (uid in uids) {
                val loaded = carrying ?: freshSave(key)
                if (loaded == null) {
                    stopped = TransferResult.Refused("THE SAVE COULD NOT BE READ")
                    break
                }
                val result = runTransfer { engine.withdraw(loaded, uid, target) }
                if (result is TransferResult.Success) {
                    done++
                    carrying = result.after
                } else {
                    stopped = result
                    break
                }
            }
            keep(listOfNotNull(carrying))
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
            // What each cartridge looks like after the Pokémon before this
            // one left it. See [carrying].
            val carrying = mutableMapOf<String, LoadedSave>()
            for ((key, location) in ordered) {
                val loaded = carrying[key] ?: freshSave(key)
                if (loaded == null) {
                    stopped = TransferResult.Refused("THE SAVE COULD NOT BE READ")
                    break
                }
                val result = runTransfer { engine.deposit(loaded, location, targetBox) }
                if (result is TransferResult.Success) {
                    done++
                    noteEvolvable(result.storedUid)
                    result.after?.let { carrying[key] = it }
                } else {
                    stopped = result
                    break
                }
            }
            keep(carrying.values)
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

    /**
     * Holds a cartridge at what a transfer has just made of it.
     *
     * A write moves the save's revision, so the copy the app was holding is
     * dropped — and every screen that was reading it then has to wait on a
     * fresh download of a file this device has, this second, just uploaded.
     * The server took those exact bytes and answered with a revision, so the
     * app keeps them and the download does not happen.
     *
     * Nothing here decides a transfer. What is kept is only what the app shows
     * and what it offers the *next* transfer, and the engine re-reads and
     * compares fingerprints before it writes anything: a copy that has gone
     * stale in the meantime is refused there rather than acted on.
     */
    private fun keep(saves: Collection<LoadedSave>) {
        if (saves.isEmpty()) return
        mutable.update { state ->
            state.copy(loaded = state.loaded + saves.associateBy { it.key })
        }
        noticeElsewhere(saves)
    }

    // ------- coming back from a backup

    /**
     * Whether the boxes on this device arrived with the app or came back from
     * Android's backup.
     *
     * The marker is a file this app writes once and never lets into a backup.
     * A fresh install has neither it nor any boxes; a restore has boxes and no
     * marker, because the boxes came from the account and the marker could
     * not. That is the whole of the test, and it needs nothing from the
     * backup system itself.
     *
     * Everything a restore brought back is written down as unchecked. See
     * [AppSettings.restoredUids]: a backup is the boxes at one moment and the
     * cartridges have moved on since, so until each one has been held up
     * against the saves it is a Pokémon this app is not sure it still owns.
     */
    private fun noticeRestore() {
        val marker = File(getApplication<Application>().filesDir, "install.marker")
        if (marker.exists()) return
        val held = runCatching { storage.all() }.getOrDefault(emptyList())
        // Boxes, or a link, without the marker: this install did not start
        // on this device. The whole of it came back — the Pokemon, every
        // setting, and the link to the account — so there is nothing to
        // repair and one thing to ask.
        if (held.isNotEmpty() || credentials.isLinked) settings.restoreOffer = true
        runCatching { marker.writeText(System.currentTimeMillis().toString()) }
    }

    /** Keeps what the backup brought back, which is the whole of it. */
    fun useRestored() {
        settings.restoreOffer = false
        mutable.update { it.copy(restoreOffer = false) }
    }

    /**
     * Throws away what the backup brought back and starts this device clean.
     *
     * Everything: the boxes, the histories, the ledger, the items, every
     * setting, and the link to the account. A player who says no to a
     * restore is saying they want a new machine, and half a new machine —
     * empty boxes still signed in as somebody — is not a thing to hand them.
     * The introduction then plays, because this is now a first run.
     */
    fun discardRestored() = viewModelScope.launch {
        mutable.update { it.copy(busy = true, prompt = null) }
        withContext(Dispatchers.IO) {
            runCatching { api.unlink(credentials.deviceId) }
            runCatching { credentials.clear() }
            runCatching { storage.clear() }
            runCatching { itemStorage.clear() }
            runCatching { lineages.clear() }
            runCatching { ledger.clear() }
            runCatching { journal.clear() }
            runCatching { backups.clear() }
            // Last, because it is the one that puts restoreOffer back to
            // false: anything after it would be running against settings
            // that have already been wiped.
            runCatching { settings.clear() }
        }
        mutable.update {
            UiState(
                storage = storage.state(),
                restoreOffer = false,
                tutorialSeen = false,
            )
        }
    }

    /**
     * Holds the PC up against a cartridge that has just been read, and
     * notices a Pokémon that is in both.
     *
     * The app writes its own mark into every Pokémon it hands to a cartridge
     * (see [Lineage]), so a Pokémon found in a save wearing the mark of one
     * the PC still holds is not a coincidence and not a lookalike: it is the
     * same creature, and it got there by a route this app never saw — a save
     * restored from a backup of its own, a cartridge copied, a trade inside
     * the game, a withdrawal that landed after the app was reinstalled.
     *
     * This used to compare content fingerprints, which meant it could only
     * recognise a Pokémon that had not been played with since — and it acted
     * on the match by deleting the PC's copy without asking. It asks now, and
     * it recognises one that has spent a season levelling.
     *
     * Asked once per Pokémon per cartridge: the noticing is written onto the
     * Pokémon's own history, so a player who says no is not asked again every
     * time that save is read.
     */
    private fun noticeElsewhere(saves: Collection<LoadedSave>) {
        val held = storage.all().mapNotNull { stored ->
            stored.lineage?.let { it.tag to stored }
        }.toMap()
        if (held.isEmpty()) return

        val found = LinkedHashMap<String, Pair<StoredPokemon, LoadedSave>>()
        saves.forEach { loaded ->
            val save = loaded.save ?: return@forEach
            (save.party + save.boxes.flatten()).forEach { mon ->
                val tag = Lineage.tagOf(mon.raw) ?: return@forEach
                val stored = held[tag] ?: return@forEach
                // Already raised for this Pokémon in this cartridge. The
                // answer was no, and asking again every time the save is
                // read is how a warning becomes something to tap past.
                val last = stored.lineage?.hops?.lastOrNull()
                if (last?.kind == Hop.Kind.SEEN_ELSEWHERE && last.saveKey == loaded.key) {
                    return@forEach
                }
                found[tag] = stored to loaded
            }
        }
        if (found.isEmpty()) return

        // Written down before anything is asked, so the question is not
        // repeated whatever the answer turns out to be.
        found.values.forEach { (stored, loaded) ->
            val lineage = stored.lineage ?: return@forEach
            runCatching {
                storage.replace(
                    stored.copy(
                        lineage = lineage.then(
                            Hop(
                                kind = Hop.Kind.SEEN_ELSEWHERE,
                                atMillis = System.currentTimeMillis(),
                                gameVersion = loaded.remote.version.id,
                                saveKey = loaded.key,
                                trainerName = loaded.save?.trainerName,
                            )
                        )
                    )
                )
            }
        }

        val first = found.values.first()
        val name = first.first.pokemon.displayName.uppercase()
        val where = first.second.remote.version.label.uppercase()
        val uids = found.values.map { it.first.uid }
        mutable.update {
            it.copy(
                storage = storage.state(),
                prompt = Prompt.Confirm(
                    lines = if (uids.size == 1) {
                        listOf(
                            "$name IS IN $where TOO.",
                            "IT LEFT THE PC SOME OTHER WAY.",
                            "TAKE IT OUT OF THE PC?",
                        )
                    } else {
                        listOf(
                            "${uids.size} POKéMON ARE IN CARTRIDGES TOO.",
                            "THEY LEFT THE PC SOME OTHER WAY.",
                            "TAKE THEM OUT OF THE PC?",
                        )
                    },
                    confirmLabel = "YES",
                    cancelLabel = "NO",
                    onConfirm = { dropDuplicates(uids) },
                ),
            )
        }
    }

    /** Takes the PC's copies out, for a player who said yes to the above. */
    private fun dropDuplicates(uids: List<String>) {
        uids.forEach { uid -> runCatching { storage.withdraw(uid) } }
        mutable.update { it.copy(storage = storage.state(), prompt = null) }
        message(
            if (uids.size == 1) "THE CARTRIDGE KEPT IT."
            else "THE CARTRIDGES KEPT THEM.",
        )
    }

    /** Whether this app's data goes into the account's backup. */
    fun setCloudBackup(on: Boolean) {
        settings.cloudBackup = on
        mutable.update { it.copy(cloudBackup = on, backupNote = backupNote()) }
        backupWanted()
    }

    /**
     * What the system has actually done about backing this app up.
     *
     * There is no way to ask Android whether backup is on for a device, and
     * "I turned it on and nothing came back" has two completely different
     * causes — the system has never run a pass yet, or it ran and something
     * went wrong. So the agent writes down each time it is asked, and this is
     * that note put into words. See [StorageBackupAgent].
     */
    private fun backupNote(): String {
        val app = getApplication<Application>()
        val taken = StorageBackupAgent.read(app, StorageBackupAgent.MARKER)
            ?: return if (settings.cloudBackup) "NOT YET" else "OFF"
        val when_ = java.time.Instant.ofEpochMilli(taken.first)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
            .uppercase()
        return if (taken.second.startsWith("taken")) when_ else "$when_ (${taken.second.uppercase()})"
    }

    /** What the row above is actually saying, for anybody who taps it. */
    fun explainBackup() {
        val app = getApplication<Application>()
        val taken = StorageBackupAgent.read(app, StorageBackupAgent.MARKER)
        val bytes = runCatching { backupSizeBytes() }.getOrDefault(0L)
        val sizeLines = if (bytes >= BACKUP_WARN_BYTES) {
            listOf(
                "IT IS ABOUT ${bytes / (1024 * 1024)}MB, CLOSE",
                "TO GOOGLE'S 25MB LIMIT. OVER",
                "THAT, THE WHOLE BACKUP DROPS.",
            )
        } else emptyList()
        prompt(
            Prompt.Message(
                (if (taken == null) {
                    listOf(
                        "ANDROID HAS NOT ASKED FOR A",
                        "BACKUP YET.",
                        "IT TAKES ONE OVERNIGHT WHILE",
                        "CHARGING ON WI-FI, ONCE A DAY.",
                    )
                } else {
                    listOf(
                        "ANDROID LAST ASKED ON",
                        "${backupNote()}.",
                        "IT TAKES ONE OVERNIGHT WHILE",
                        "CHARGING ON WI-FI, ONCE A DAY.",
                    )
                }) + sizeLines
            )
        )
    }

    /** What a manual backup is named by default, so the picker opens with a name in it. */
    fun backupFileName(): String {
        val date = java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        return "pokestorage-backup-$date.pssbackup"
    }

    /**
     * Writes everything Auto Backup would carry into the file the player just
     * picked — the same set `backup_rules.xml` names, taken right now rather
     * than whenever Android next runs its own pass. See [BackupExport].
     */
    fun backUpNow(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out -> BackupExport.write(app, out) }
                    ?: error("THE CHOSEN LOCATION WOULD NOT OPEN")
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) message("BACKUP SAVED.")
                else message("COULD NOT SAVE THE BACKUP.", result.exceptionOrNull()?.message.orEmpty().uppercase())
            }
        }
    }

    /**
     * Puts a file [backUpNow] wrote back onto this device and restarts.
     *
     * The settings and boxes this overwrites are already cached in memory the
     * moment anything reads them this run, so a restart is the only way every
     * part of the app is guaranteed to pick up the new file rather than go on
     * holding the old one until something happens to reload it.
     */
    fun restoreFromFile(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input -> BackupExport.read(app, input) }
                    ?: error("THE CHOSEN FILE WOULD NOT OPEN")
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    message("BACKUP RESTORED.", "RESTARTING...")
                    delay(1200)
                    restartApp()
                } else {
                    message("COULD NOT READ THAT FILE.", result.exceptionOrNull()?.message.orEmpty().uppercase())
                }
            }
        }
    }

    /**
     * Uploads the same set [backUpNow] writes straight into this player's
     * Drive, replacing whatever this app already put there.
     *
     * The token is the caller's to get — see `withDriveAccess` in
     * `Screens.kt`, which is where an `Activity` exists to ask Google for
     * it. This just spends it.
     */
    fun backUpToDrive(accessToken: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val bytes = java.io.ByteArrayOutputStream().also { BackupExport.write(app, it) }.toByteArray()
                val existing = DriveBackup.findExisting(accessToken)
                DriveBackup.upload(accessToken, existing, bytes)
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) message("BACKUP SAVED TO DRIVE.")
                else message("COULD NOT REACH DRIVE.", result.exceptionOrNull()?.message.orEmpty().uppercase())
            }
        }
    }

    /** Reads the file [backUpToDrive] put on Drive back onto this device, and restarts. */
    fun restoreFromDrive(accessToken: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val fileId = DriveBackup.findExisting(accessToken) ?: error("NO BACKUP FOUND ON DRIVE")
                val bytes = DriveBackup.download(accessToken, fileId)
                BackupExport.read(app, java.io.ByteArrayInputStream(bytes))
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    message("BACKUP RESTORED.", "RESTARTING...")
                    delay(1200)
                    restartApp()
                } else {
                    message("COULD NOT RESTORE FROM DRIVE.", result.exceptionOrNull()?.message.orEmpty().uppercase())
                }
            }
        }
    }

    private fun restartApp() {
        val app = getApplication<Application>()
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) app.startActivity(intent)
        kotlin.system.exitProcess(0)
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
                    add("NOTHING WAS LOST. THE NEXT SYNC FINISHES IT.")
                }
            }
        }
        if (lines.isNotEmpty()) {
            if (done > 0) transferMessage(lines, touched.toTypedArray())
            else message(*lines.toTypedArray())
        }
        refreshTransferSources(*touched.toTypedArray())
        Unit
    }

    /**
     * After any transfer every cached blob and revision is stale, so the whole
     * account is re-read before the result is shown. Without that the next
     * transfer would be checked against a revision the server has moved past.
     */
    private suspend fun finish(result: TransferResult, vararg touched: String) = coroutineScope {
        // Whatever was written is held at its new revision before the account
        // is re-read, so the listing finds it current and nothing fetches it
        // back. See [keep].
        (result as? TransferResult.Success)?.after?.let { keep(listOf(it)) }
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
                transferMessage(listOf(result.message), touched)
            }
            is TransferResult.Refused -> message(result.reason)
            is TransferResult.NeedsRecovery -> message(
                result.reason,
                "NOTHING WAS LOST. THE NEXT SYNC FINISHES IT.",
            )
        }
        refreshTransferSources(*touched)
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
                        gameVersionId = gone.spriteGameVersionId,
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

    // ------- mail

    /**
     * `SEND MAIL TO PC`: the way past the deposit refusal for a Pokémon
     * holding mail. Same shape as [transferItem] — the save is re-read,
     * the engine checks it is still the save this screen was looking at,
     * and the result closes the same way any other transfer does.
     */
    fun sendMailToPc(key: String, partySlot: Int) {
        if (mutable.value.save(key) == null) return message("OPEN THE SAVE FIRST.")
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val loaded = freshSave(key)
            if (loaded == null) {
                mutable.update { it.copy(busy = false) }
                return@launch message("THE SAVE COULD NOT BE READ.")
            }
            val result = runTransfer { mailEngine.sendToPc(loaded, partySlot) }
            finish(result, key)
        }
    }

    /** `ATTACH MAIL`: a MAILBOX letter onto a chosen party Pokémon. */
    fun attachMailFromBox(key: String, mailboxIndex: Int, partySlot: Int) {
        if (mutable.value.save(key) == null) return message("OPEN THE SAVE FIRST.")
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val loaded = freshSave(key)
            if (loaded == null) {
                mutable.update { it.copy(busy = false) }
                return@launch message("THE SAVE COULD NOT BE READ.")
            }
            val result = runTransfer { mailEngine.attachFromMailbox(loaded, mailboxIndex, partySlot) }
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
        if (itemStorage.add(item, 1, stored.generation) <= 0) {
            message("THERE IS NO ROOM FOR IT.")
            return
        }
        if (storage.takeHeldItem(uid) == null) {
            itemStorage.remove(item, 1, stored.generation)
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

    /**
     * What the last download stage failed with, if one did.
     *
     * A failure that no longer closes the app is a failure with nothing left
     * behind it, so it is written down where the report can carry it — the
     * whole point of catching it was to keep the app alive, not to keep the
     * reason a secret.
     */
    private fun lastDownloadFailure(): String? =
        java.io.File(getApplication<Application>().filesDir, DOWNLOAD_FAILURE_FILE)
            .takeIf { it.isFile }
            ?.runCatching { readText() }?.getOrNull()?.takeIf { it.isNotBlank() }

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
            appendLine("Stored Pokémon: ${current.storage.total}")
            appendLine("Local backups: ${localBackups().size}")
            appendLine("Pending transfer: ${pendingTransferSummary() ?: "none"}")
            appendLine()
            // Everything that can take a Pokemon out of the boxes without
            // anybody asking for it to go, and everything that can leave one
            // out of the list it should be in.
            //
            // All of it was already written down and none of it was anywhere
            // a player could see: the load's own notes went to the
            // diagnostics list and nowhere else, and the two guards that
            // silently remove — the duplicate-uid sweep at load, and the
            // restore check — reported only to themselves. A Pokemon that is
            // not there is the one thing a report about this app has to be
            // able to answer, so it answers it here.
            appendLine("### The PC's own file")
            val pc = current.storage
            appendLine("- Spots taken: ${pc.total}")
            pc.boxes.forEach { box ->
                appendLine("- ${box.label}: ${box.contents.size} in, ${box.freeSlots} free")
            }
            val loadNotes = runCatching { storage.loadNotes }.getOrDefault(emptyList())
            if (loadNotes.isEmpty()) {
                appendLine("- Read with nothing dropped")
            } else {
                appendLine("- Dropped or moved while reading the file:")
                loadNotes.forEach { appendLine("  - $it") }
            }
            appendLine()
            appendLine("### Duplication guards")
            // Written down against a Pokemon's content rather than its name,
            // so a fingerprint's first few characters say which record is
            // which without saying anything about the Pokemon.
            val placements = runCatching { engine.placements() }.getOrDefault(emptyList())
            appendLine("- Handed out and not yet back: ${placements.size}")
            placements.take(GUARD_REPORT_LIMIT).forEach {
                appendLine("  - ${it.fingerprint.take(8)} in ${it.savePath}")
            }
            appendLine("- Waiting on the restore question: ${current.restoreOffer}")
            // Whether the platform has ever actually taken one. The only way
            // to tell "the system has not run yet" from "the system ran and
            // it failed", and the first thing worth knowing when a restore
            // comes back empty. See [StorageBackupAgent].
            val app2 = getApplication<Application>()
            appendLine("- BACKUP TO GOOGLE: ${if (current.cloudBackup) "on" else "off"}")
            appendLine(
                "- Backup size, roughly: " +
                    "${runCatching { backupSizeBytes() }.getOrDefault(0L) / (1024 * 1024)}MB of 25MB"
            )
            appendLine(
                "- Backup last asked for: " +
                    (StorageBackupAgent.read(app2, StorageBackupAgent.MARKER)
                        ?.let { "${Instant.ofEpochMilli(it.first)} (${it.second})" }
                        ?: "never")
            )
            appendLine(
                "- Restore last finished: " +
                    (StorageBackupAgent.read(app2, StorageBackupAgent.RESTORE_MARKER)
                        ?.let { Instant.ofEpochMilli(it.first).toString() }
                        ?: "never")
            )
            // The marks, which is how the app recognises a Pokemon it has
            // handed out before. Counted rather than listed: a tag says
            // nothing about a Pokemon, and there can be thousands.
            val marked = runCatching { storage.all() }.getOrDefault(emptyList())
            appendLine("- Marked in the PC: ${marked.count { it.lineage != null }} of ${marked.size}")
            val book = runCatching { lineages.all() }.getOrDefault(emptyList())
            appendLine("- Histories kept for ones that are out: ${book.count { it.isOut }}")
            appendLine("- Histories kept in all: ${book.size}")
            // The one list in the app that a Pokemon can be kept out of, and
            // why each one is being kept out.
            val roster = runCatching { timeCapsuleRoster() }.getOrDefault(emptyList())
            appendLine("- TIME CAPSULE can send: ${roster.count { it.second == null }}")
            roster.mapNotNull { it.second }.groupingBy { it }.eachCount()
                .forEach { (reason, held) -> appendLine("  - $held held back: $reason") }
            appendLine()
            appendLine("### ROMs imported")
            com.logie.gen1storage.rom.RomVersion.entries.forEach { version ->
                appendLine("- ${version.id}: ${if (roms.has(version)) "present" else "-"}")
            }
            appendLine()
            appendLine("### Trainer cards")
            // The cards themselves, and the two pictures each one draws: the
            // trainer it wears and the lead Pokemon standing with them. The
            // lead is the one nothing else in this report covers — it is not
            // in the PC, it is in the save's own party, and it is asked for
            // under the card's own game rather than under wherever a stored
            // Pokemon came from. A card whose lead comes out wrong is being
            // reported here or nowhere.
            current.saves.forEach { remote ->
                val save = current.save(remote.key)?.save
                val named = cartName(remote.key)?.uppercase()
                    ?: remote.summary.trainerName?.uppercase()
                    ?: "?"
                appendLine(
                    "- $named (${remote.version.id}, gen ${remote.version.generation}): " +
                        if (save == null) "save not read yet" else "save read"
                )
                appendLine("  wears: ${trainerSprite(remote.key) ?: "the game's own player"}")
                val lead = save?.party?.firstOrNull()
                val leadId = lead?.spriteSpeciesId()
                appendLine(
                    "  lead: " + when {
                        save == null -> "unknown until the save is read"
                        lead == null -> "none in the party"
                        leadId == null -> "no species id"
                        else -> "$leadId -> ${sprites.sourceOf(leadId, remote.version.id)}"
                    }
                )
            }
            appendLine()
            appendLine("### Where the sprites come from")
            // Every Pokemon actually in the PC, named with the cartridge its
            // art is being drawn from and what that source gave back. A
            // garbled sprite is a decode that went wrong somewhere between a
            // ROM and the screen, and this is the only place that says which
            // ROM was even asked.
            current.storage.boxes.flatMap { it.contents }.take(SPRITE_REPORT_LIMIT)
                .forEach { stored ->
                    val id = stored.pokemon.spriteSpeciesId()
                    val from = stored.spriteGameVersionId
                    appendLine("- ${id ?: "?"} (gen ${stored.generation}, art $from): " +
                        (id?.let { sprites.sourceOf(it, from) } ?: "no species id"))
                }
            appendLine()
            appendLine("### Account")
            current.diagnostics.forEach { appendLine("- $it") }
            if (current.recoveryNotes.isNotEmpty()) {
                appendLine()
                appendLine("### Recovery")
                current.recoveryNotes.forEach { appendLine("- $it") }
            }
            lastDownloadFailure()?.let { failure ->
                appendLine()
                appendLine("### Last download failure")
                appendLine("```")
                appendLine(failure.trim())
                appendLine("```")
            }
            com.logie.gen1storage.StorageApp.lastCrash(getApplication())?.let { crash ->
                appendLine()
                appendLine("### Last crash")
                appendLine("```")
                appendLine(crash.trim())
                appendLine("```")
            }
            appendLine()
            appendLine("No sync codes, account id, device token, Pokémon data,")
            appendLine("trainer names or save contents are included.")
        }.take(12000)
    }
}

/** Where the last download failure is kept, for the report under ABOUT. */
/**
 * How long the app waits before telling the platform its data changed.
 *
 * Long enough that a run of transfers, or an import of a whole box, is one
 * message rather than six hundred.
 */
private const val BACKUP_SIGNAL_MILLIS = 3_000L

/** Where the backup size warning kicks in — the platform's own limit is 25MB. */
private const val BACKUP_WARN_BYTES = 20L * 1024 * 1024

/** How many stored Pokémon the debug report names a sprite source for. */
private const val SPRITE_REPORT_LIMIT = 40

/** How many of the ledger's records the report lists before it stops. */
private const val GUARD_REPORT_LIMIT = 20

private const val DOWNLOAD_FAILURE_FILE = "last-download-failure.txt"

/**
 * Every file the download fetches, counted once, for a given list of sprite
 * sets.
 *
 * The sprite sets, the follower sheets, and the trainers with the sheets that
 * come down beside them. Not the cries: they are rendered on the device out of
 * tables that ship in the APK, so there is nothing to fetch. See [CrySynth].
 *
 * Takes the sets rather than assuming all of them, because a ROM on the
 * device takes its own set out of the download entirely. Counted with the
 * rest of it fixed: a player who has imported every cartridge still has the
 * cries, the followers and the trainer art to fetch, and the bar should
 * reach a hundred when those land rather than stopping at the share the
 * sprites would have been.
 */
private fun downloadTotalFor(sets: List<SpriteSet>): Int =
    // Each set over the species its own generation has — 151 for the
    // Generation I sets, 251 for Gold, Silver and Crystal — and, once, the
    // shiny colours the three Generation II sets share.
    sets.sumOf { if (it.generation == 2) Gen2Data.SPECIES_COUNT else 151 } +
        (if (sets.any { it.generation == 2 }) Gen2Data.SPECIES_COUNT else 0) +
        FollowerStore.LAST_SHEET +
        TrainerStore.ALL.size + TrainerStore.EXTRA_ART.size + TrainerStore.GEN2_ART.size +
        TrainerStore.GEN2_TRAINER_IDS.size
