package com.logie.gen1storage.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.logie.gen1storage.gen1recomp.Gen1RecompPackages
import com.logie.gen1storage.gen1recomp.SaveDiscovery
import com.logie.gen1storage.gen1recomp.SaveSource
import com.logie.gen1storage.gen1recomp.SaveWriter
import com.logie.gen1storage.gen1recomp.WriteOutcome
import com.logie.gen1storage.saveaccess.AccessState
import com.logie.gen1storage.saveaccess.DocumentTreeVolume
import com.logie.gen1storage.saveaccess.SaveNode
import com.logie.gen1storage.saveaccess.SaveVolume
import com.logie.gen1storage.saveaccess.ShizukuConnection
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.storage.StorageState
import com.logie.gen1storage.transfer.SaveLocation
import com.logie.gen1storage.transfer.TransferEngine
import com.logie.gen1storage.transfer.RecoveryReport
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
    data object SaveList : Screen
    data class SaveMenu(val sourceId: String) : Screen
    data class SaveParty(val sourceId: String) : Screen
    data class SaveBox(val sourceId: String, val box: Int) : Screen
    data class StorageBoxes(val box: Int) : Screen
    data object Transfer : Screen
    data object SaveFiles : Screen
    data object Options : Screen
}

/** A modal the Generation I menus would draw as a window over everything. */
sealed interface Prompt {
    data class Message(val lines: List<String>) : Prompt
    data class Confirm(
        val lines: List<String>,
        val confirmLabel: String,
        val onConfirm: () -> Unit,
    ) : Prompt

    data class ChooseBox(
        val title: String,
        val onChoose: (Int) -> Unit,
    ) : Prompt

    data class ChooseWithdrawTarget(
        val uid: String,
        val sourceId: String,
    ) : Prompt

    data class ChooseSave(
        val title: String,
        val onChoose: (String) -> Unit,
    ) : Prompt
}

data class UiState(
    val stack: List<Screen> = listOf(Screen.Home),
    val access: AccessState = AccessState.Connecting,
    val folderChosen: Boolean = false,
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val sources: List<SaveSource> = emptyList(),
    val storage: StorageState = StorageState(emptyList(), 0),
    val diagnostics: List<String> = emptyList(),
    val recoveryNotes: List<String> = emptyList(),
    val prompt: Prompt? = null,
    val footer: String? = null,
) {
    val screen: Screen get() = stack.last()
    fun source(id: String?): SaveSource? = sources.firstOrNull { it.id == id }
}

class StorageViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("gen1storage", Context.MODE_PRIVATE)
    private val storageDir = File(application.filesDir, "pc")
    private val storage = StorageRepository(storageDir)
    private val journal = TransferJournal(storageDir)
    private val shizuku = ShizukuConnection(application)

    /**
     * Both access routes can be live at once — Shizuku for the game's own
     * data directory and a granted folder for anything outside it — so the
     * volume a transfer runs on is chosen per save, not globally.
     */
    private val volumes = LinkedHashMap<String, SaveVolume>()

    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()

    init {
        mutable.update { it.copy(storage = storage.state(), folderChosen = savedTreeUri() != null) }
        shizuku.start()
        viewModelScope.launch {
            shizuku.state.collect { access ->
                mutable.update { it.copy(access = access) }
                if (access == AccessState.Ready && mutable.value.sources.isEmpty()) scan()
            }
        }
        // The SAF grant is the route that needs no Shizuku at all, so it is
        // tried immediately rather than only as a fallback.
        savedTreeUri()?.let { scanTree(it, persist = false) }
    }

    override fun onCleared() {
        shizuku.stop()
        super.onCleared()
    }

    // ------- navigation

    fun open(screen: Screen) = mutable.update { it.copy(stack = it.stack + screen, footer = null) }

    fun back(): Boolean {
        val current = mutable.value
        if (current.prompt != null) {
            mutable.update { it.copy(prompt = null) }
            return true
        }
        if (current.stack.size <= 1) return false
        mutable.update { it.copy(stack = it.stack.dropLast(1), footer = null) }
        return true
    }

    /** Swaps the top of the stack, for stepping sideways between boxes. */
    fun replace(screen: Screen) =
        mutable.update { it.copy(stack = it.stack.dropLast(1) + screen, footer = null) }

    fun home() = mutable.update { it.copy(stack = listOf(Screen.Home), prompt = null, footer = null) }

    fun dismissPrompt() = mutable.update { it.copy(prompt = null) }

    fun prompt(prompt: Prompt) = mutable.update { it.copy(prompt = prompt) }

    private fun message(vararg lines: String) =
        mutable.update { it.copy(prompt = Prompt.Message(lines.toList()), busy = false) }

    // ------- access

    fun requestShizukuPermission() = shizuku.requestPermission()

    fun retryShizuku() = shizuku.refresh()

    fun savedTreeUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun onFolderChosen(uri: Uri) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        mutable.update { it.copy(folderChosen = true) }
        scanTree(uri, persist = true)
    }

    fun forgetFolder() {
        savedTreeUri()?.let { uri ->
            runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
        mutable.update { it.copy(folderChosen = false, sources = emptyList()) }
    }

    // ------- scanning

    /**
     * Scans through Shizuku when it is available, otherwise through the granted
     * folder. Shizuku is preferred because it can reach
     * `Android/data/<package>/files` directly, which is where Gen1Recomp keeps
     * its saves and which the system picker will not hand out on Android 11+.
     */
    fun scan() = viewModelScope.launch {
        if (mutable.value.scanning) return@launch
        val targets = mutableListOf<Pair<SaveVolume, List<SaveNode>>>()

        // Shizuku reaches Android/data directly, which is where Gen1Recomp
        // actually keeps its saves; the granted folder covers everywhere else.
        shizuku.volume?.let { shizukuVolume ->
            targets += shizukuVolume to Gen1RecompPackages.KNOWN
                .flatMap(Gen1RecompPackages::candidateRoots)
                .map(shizukuVolume::nodeFor)
        }
        savedTreeUri()?.let { uri ->
            val treeVolume = DocumentTreeVolume(getApplication(), uri)
            val root = treeVolume.rootNode()
            if (root == null) forgetFolder() else targets += treeVolume to listOf(root)
        }

        if (targets.isEmpty()) {
            mutable.update {
                it.copy(diagnostics = listOf("No save access yet: allow Shizuku, or choose the save folder."))
            }
            return@launch
        }
        runScan(targets)
    }

    private fun scanTree(uri: Uri, persist: Boolean) = viewModelScope.launch {
        if (mutable.value.scanning) return@launch
        val treeVolume = DocumentTreeVolume(getApplication(), uri)
        val root = treeVolume.rootNode()
        if (root == null) {
            mutable.update {
                it.copy(diagnostics = listOf("The chosen folder is no longer available. Choose it again."))
            }
            if (persist) forgetFolder()
            return@launch
        }
        val targets = mutableListOf<Pair<SaveVolume, List<SaveNode>>>()
        shizuku.volume?.let { shizukuVolume ->
            targets += shizukuVolume to Gen1RecompPackages.KNOWN
                .flatMap(Gen1RecompPackages::candidateRoots)
                .map(shizukuVolume::nodeFor)
        }
        targets += treeVolume to listOf(root)
        runScan(targets)
    }

    private suspend fun runScan(targets: List<Pair<SaveVolume, List<SaveNode>>>) {
        mutable.update { it.copy(scanning = true) }
        try {
            val sources = mutableListOf<SaveSource>()
            val diagnostics = mutableListOf<String>()
            for ((target, roots) in targets) {
                val result = withContext(Dispatchers.IO) { SaveDiscovery(target).scan(roots) }
                volumes[target.label] = target
                // Two routes can reach one file; keep the first sighting.
                result.sources.forEach { source ->
                    if (sources.none { it.relativePath == source.relativePath && it.fingerprint == source.fingerprint }) {
                        sources += source
                    }
                }
                diagnostics += result.diagnostics.map { "${target.label}: $it" }
            }
            val recovery = withContext(Dispatchers.IO) { runRecovery(sources) }
            mutable.update {
                it.copy(
                    sources = sources,
                    diagnostics = diagnostics + storage.loadNotes,
                    recoveryNotes = recovery.resolved + recovery.unresolved,
                    storage = storage.state(),
                    scanning = false,
                )
            }
            if (recovery.unresolved.isNotEmpty()) {
                mutable.update { it.copy(prompt = Prompt.Message(recovery.unresolved)) }
            }
        } catch (e: Exception) {
            mutable.update {
                it.copy(scanning = false, diagnostics = listOf(e.message ?: "Scan failed"))
            }
        }
    }

    /**
     * Resolves an interrupted transfer on the volume that owns the save it
     * touched, so a two-route setup cannot resolve it against the wrong file.
     */
    private suspend fun runRecovery(sources: List<SaveSource>): RecoveryReport {
        val entry = journal.read() ?: return RecoveryReport(emptyList(), emptyList())
        val source = sources.firstOrNull { it.id == entry.saveId }
        val target = source?.let { volumes[it.volumeLabel] }
            ?: return RecoveryReport(
                emptyList(),
                listOf(
                    "A ${entry.kind.name.lowercase()} on ${entry.savePath} is unresolved: " +
                        "that save is not reachable right now, so both copies were kept."
                ),
            )
        return engine(target).recover(sources)
    }

    private fun engine(target: SaveVolume) = TransferEngine(target, storage, journal)

    private fun volumeFor(source: SaveSource): SaveVolume? = volumes[source.volumeLabel]

    // ------- transfers

    fun depositFromSave(sourceId: String, location: SaveLocation, targetBox: Int) {
        val source = mutable.value.source(sourceId) ?: return message("THAT SAVE IS GONE.")
        val target = volumeFor(source) ?: return message("NO ACCESS TO THAT SAVE.")
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val result = withContext(Dispatchers.IO) {
                engine(target).deposit(source, location, source.fingerprint, targetBox)
            }
            finish(result)
        }
    }

    fun withdrawToSave(uid: String, sourceId: String, target: WithdrawTarget) {
        val source = mutable.value.source(sourceId) ?: return message("THAT SAVE IS GONE.")
        val access = volumeFor(source) ?: return message("NO ACCESS TO THAT SAVE.")
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val result = withContext(Dispatchers.IO) {
                engine(access).withdraw(source, uid, target, source.fingerprint)
            }
            finish(result)
        }
    }

    private suspend fun finish(result: TransferResult) {
        // A transfer changed the save on disk, so every fingerprint the app is
        // holding is now stale. Rescanning is what keeps the concurrency guard
        // meaningful rather than a formality.
        rescanKnownRoots()
        mutable.update { it.copy(storage = storage.state(), busy = false) }
        when (result) {
            is TransferResult.Success -> message(result.message)
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

    fun renameStorageBox(index: Int, name: String) {
        storage.renameBox(index, name)
        mutable.update { it.copy(storage = storage.state(), prompt = null) }
    }

    // ------- save file maintenance

    fun restoreBackup(sourceId: String) {
        val source = mutable.value.source(sourceId) ?: return message("THAT SAVE IS GONE.")
        val target = volumeFor(source) ?: return message("NO ACCESS TO THAT SAVE.")
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, prompt = null) }
            val outcome = withContext(Dispatchers.IO) { SaveWriter(target).restoreFromBackup(source) }
            rescanKnownRoots()
            mutable.update { it.copy(busy = false) }
            when (outcome) {
                is WriteOutcome.Committed -> message("${source.mainName.uppercase()} WAS RESTORED FROM ITS BACKUP.")
                is WriteOutcome.Refused -> message(outcome.reason)
                is WriteOutcome.FailedButRecoverable -> message(outcome.reason)
            }
        }
    }

    fun clearPendingTransferRecord() {
        journal.clear()
        mutable.update { it.copy(recoveryNotes = emptyList(), prompt = null) }
    }

    fun pendingTransferSummary(): String? {
        val entry = journal.read() ?: return null
        return "${entry.kind.name} of ${entry.savePath} started ${Instant.ofEpochMilli(entry.startedAtEpochMillis)}"
    }

    /**
     * Re-reads every save root already known. A write invalidates every cached
     * fingerprint, and a stale fingerprint would turn the concurrency guard
     * into a formality.
     */
    private suspend fun rescanKnownRoots() {
        val byVolume = mutable.value.sources.groupBy { it.volumeLabel }
        if (byVolume.isEmpty()) return
        val sources = mutableListOf<SaveSource>()
        for ((label, group) in byVolume) {
            val target = volumes[label] ?: continue
            val roots = group.map { it.saveRoot }.distinctBy { it.key }
            sources += withContext(Dispatchers.IO) { SaveDiscovery(target).scan(roots) }.sources
        }
        mutable.update { it.copy(sources = sources) }
    }

    // ------- diagnostics

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
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Shizuku: ${current.access}")
            appendLine("Folder granted: ${current.folderChosen}")
            appendLine("Saves found: ${current.sources.size}")
            appendLine("Stored Pokemon: ${current.storage.total}")
            appendLine("Pending transfer: ${pendingTransferSummary() ?: "none"}")
            appendLine()
            appendLine("### Saves")
            current.sources.forEach { source ->
                appendLine(
                    "- ${source.version.label} ${source.slotDisplay}: path=${source.relativePath}, " +
                        "origin=${source.origin}, writable=${source.writable}, " +
                        "party=${source.partyCount}, stored=${source.storedCount}, " +
                        "status=${source.classification.summary}"
                )
            }
            appendLine()
            appendLine("### Paths checked")
            current.diagnostics.forEach { appendLine("- $it") }
            if (current.recoveryNotes.isNotEmpty()) {
                appendLine()
                appendLine("### Recovery")
                current.recoveryNotes.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("No Pokemon data, trainer names or save contents are included.")
        }.take(12000)
    }

    private companion object {
        const val KEY_TREE_URI = "tree-uri"
    }
}
