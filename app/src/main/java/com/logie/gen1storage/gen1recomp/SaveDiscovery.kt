package com.logie.gen1storage.gen1recomp

import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.saveaccess.SaveNode
import com.logie.gen1storage.saveaccess.SaveVolume
import java.security.MessageDigest

/** Which of a slot's three files supplied the data that was read. */
enum class SaveOrigin { MAIN, STAGED, BACKUP }

/**
 * One Gen1Recomp playthrough this app can see, with everything needed to name
 * it, show it and safely write it back.
 */
data class SaveSource(
    val id: String,
    val volumeLabel: String,
    val saveRoot: SaveNode,
    val directory: SaveNode,
    val mainName: String,
    val relativePath: String,
    val version: GameVersion,
    val slotId: String?,
    val slotLabel: String?,
    val isActiveSlot: Boolean,
    val classification: SaveClassification,
    val origin: SaveOrigin,
    val writable: Boolean,
    val lastModified: Long,
    /** SHA-256 of the exact bytes that were read; the concurrency guard. */
    val fingerprint: String,
    val sizeBytes: Long,
) {
    val save: Gen1RecompSave? get() = classification.save
    val isUsable: Boolean get() = save != null

    val trainerName: String get() = save?.trainerName ?: "-"
    val trainerId: Int? get() = save?.trainerId
    val partyCount: Int get() = save?.partyCount ?: 0
    val storedCount: Int get() = save?.storedCount ?: 0
    val playthroughId: String? get() = save?.playthroughId

    /** `SLOT 1` / a player-chosen label from `options.lua`, else the filename. */
    val slotDisplay: String
        get() = slotLabel
            ?: slotId?.replaceFirstChar { it }?.uppercase()?.replace("SLOT", "SLOT ")
            ?: mainName.removeSuffix(".lua").uppercase()

    val backupName: String get() = "$mainName.bak"
    val stagedName: String get() = "$mainName.tmp"
}

/** Everything a scan turned up, plus a trace of where it looked. */
data class ScanResult(
    val sources: List<SaveSource>,
    val diagnostics: List<String>,
)

/**
 * Finds Gen1Recomp saves on a volume and works out which playthrough each file
 * belongs to.
 *
 * The layout comes from upstream `src/core/SaveData.lua`: a version's
 * playthroughs live in `saves/<version>/slotN.lua` with `.bak` and `.tmp`
 * companions, a pre-slots install has flat `save.lua` / `save_blue.lua` /
 * `save_yellow.lua`, and `options.lua` holds the slot registry (`saveSlots`)
 * that names the active slot and any label the player gave it.
 *
 * Files are never identified by name alone: a name only makes a file a
 * candidate, and the classification of its contents decides what it is.
 */
class SaveDiscovery(private val volume: SaveVolume) {

    suspend fun scan(roots: List<SaveNode>): ScanResult {
        val diagnostics = mutableListOf<String>()
        val sources = mutableListOf<SaveSource>()
        val seenSaveRoots = mutableSetOf<String>()

        for (root in roots) {
            val found = try {
                findSaveRoots(root, diagnostics)
            } catch (e: Exception) {
                diagnostics += "${root.key}: ${e.message ?: "unreadable"}"
                continue
            }
            if (found.isEmpty()) diagnostics += "${root.key}: no Gen1Recomp save folder inside"
            for (saveRoot in found) {
                if (!seenSaveRoots.add(saveRoot.key)) continue
                sources += readSaveRoot(saveRoot, diagnostics)
            }
        }
        return ScanResult(sources.sortedWith(SOURCE_ORDER), diagnostics)
    }

    /**
     * A LÖVE save directory is the folder that directly holds `options.lua`,
     * a flat `save*.lua`, or a `saves/` tree. Walking to it rather than
     * hard-coding `files/save/pokemon-love2d` means the app copes with a
     * player handing over any ancestor folder, and with an install whose
     * identity or layout upstream later changes.
     */
    private suspend fun findSaveRoots(root: SaveNode, diagnostics: MutableList<String>): List<SaveNode> {
        val found = mutableListOf<SaveNode>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(root to 0))
        var directoriesSeen = 0

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (!visited.add(node.key) || depth > MAX_DEPTH || directoriesSeen > MAX_DIRECTORIES) continue
            directoriesSeen++
            val children = try {
                volume.children(node)
            } catch (e: Exception) {
                if (depth == 0) diagnostics += "${node.key}: ${e.message ?: "unreadable"}"
                continue
            }
            val names = children.map { it.name }.toSet()
            val looksLikeSaveRoot = names.any { it == OPTIONS_FILE || FLAT_SAVE_NAMES.contains(it) } ||
                (names.contains(SLOTS_DIR) && children.any { it.name == SLOTS_DIR && it.isDirectory })
            if (looksLikeSaveRoot) {
                found += node
                // Its `saves/` subtree belongs to this root; do not descend
                // into it looking for another one.
                continue
            }
            for (child in children) {
                if (child.isDirectory) queue.addLast(child to depth + 1)
            }
        }
        return found
    }

    private suspend fun readSaveRoot(saveRoot: SaveNode, diagnostics: MutableList<String>): List<SaveSource> {
        val registry = readSlotRegistry(saveRoot, diagnostics)
        val sources = mutableListOf<SaveSource>()

        for (version in GameVersion.entries) {
            val slotDir = volume.child(saveRoot, SLOTS_DIR)
                ?.takeIf { it.isDirectory }
                ?.let { volume.child(it, version.id) }
                ?.takeIf { it.isDirectory }

            val slotIds = LinkedHashSet<String>()
            registry[version.id]?.order?.let(slotIds::addAll)
            if (slotDir != null) {
                // Upstream's own disk recovery (`scanDiskSlots`): a slot file
                // with no registry entry is still a real playthrough.
                volume.children(slotDir)
                    .mapNotNull { SLOT_FILE.matchEntire(it.name)?.groupValues?.get(1) }
                    .sortedBy { it.removePrefix("slot").toIntOrNull() ?: Int.MAX_VALUE }
                    .forEach(slotIds::add)
            }

            for (slotId in slotIds) {
                if (slotDir == null) continue
                val source = buildSource(
                    saveRoot = saveRoot,
                    directory = slotDir,
                    mainName = "$slotId.lua",
                    relativePath = "$SLOTS_DIR/${version.id}/$slotId.lua",
                    expectedVersion = version,
                    slotId = slotId,
                    slotLabel = registry[version.id]?.labels?.get(slotId),
                    isActiveSlot = registry[version.id]?.active == slotId,
                    diagnostics = diagnostics,
                )
                if (source != null) sources += source
            }

            // The flat pre-slots path. Upstream migrates it into slot1 the
            // first time the game loads it, so it can legitimately coexist
            // with slots for other versions.
            val flatName = "save${version.saveSuffix}.lua"
            val source = buildSource(
                saveRoot = saveRoot,
                directory = saveRoot,
                mainName = flatName,
                relativePath = flatName,
                expectedVersion = version,
                slotId = null,
                slotLabel = null,
                isActiveSlot = false,
                diagnostics = diagnostics,
            )
            if (source != null) sources += source
        }
        diagnostics += "${saveRoot.key}: ${sources.size} save file(s)"
        return sources
    }

    /**
     * Reads a slot's bytes in upstream's own recovery order — main, then the
     * `.tmp` staged witness, then the `.bak` rolling copy (`SaveData.load` and
     * `decodeSlot`). A slot whose main file is corrupt but whose backup is good
     * is reported as readable *from the backup*, and this app never promotes
     * the backup over the main file on its own.
     */
    private suspend fun buildSource(
        saveRoot: SaveNode,
        directory: SaveNode,
        mainName: String,
        relativePath: String,
        expectedVersion: GameVersion,
        slotId: String?,
        slotLabel: String?,
        isActiveSlot: Boolean,
        diagnostics: MutableList<String>,
    ): SaveSource? {
        val attempts = listOf(
            SaveOrigin.MAIN to mainName,
            SaveOrigin.STAGED to "$mainName.tmp",
            SaveOrigin.BACKUP to "$mainName.bak",
        )
        var firstFailure: Pair<SaveOrigin, SaveClassification>? = null
        var anyPresent: Pair<SaveNode, SaveOrigin>? = null

        for ((origin, name) in attempts) {
            val node = volume.child(directory, name)?.takeIf { !it.isDirectory } ?: continue
            if (anyPresent == null) anyPresent = node to origin
            val bytes = try {
                volume.readBytes(node)
            } catch (e: Exception) {
                val failure = SaveClassification.Inaccessible(e.message ?: "Unreadable")
                if (firstFailure == null) firstFailure = origin to failure
                continue
            }
            val classification = SaveClassifier.classify(bytes)
            val save = classification.save
            if (save == null) {
                if (firstFailure == null) firstFailure = origin to classification
                continue
            }
            // Filenames are a hint, not an identity: what the save says it is
            // wins, and a mismatch is reported rather than silently accepted.
            val declared = save.version ?: expectedVersion
            val warnings = if (declared != expectedVersion) {
                listOf("FILE SAYS ${expectedVersion.label}, SAVE SAYS ${declared.label}")
            } else {
                emptyList()
            }
            val finalClassification = when {
                warnings.isEmpty() -> classification
                classification is SaveClassification.Valid ->
                    SaveClassification.Valid(save, classification.warnings + warnings)
                else -> classification
            }
            return SaveSource(
                id = "${volume.label}::$relativePath",
                volumeLabel = volume.label,
                saveRoot = saveRoot,
                directory = directory,
                mainName = mainName,
                relativePath = relativePath,
                version = declared,
                slotId = slotId,
                slotLabel = slotLabel,
                isActiveSlot = isActiveSlot,
                classification = finalClassification,
                origin = origin,
                writable = volume.canWrite,
                lastModified = node.lastModified,
                fingerprint = sha256(bytes),
                sizeBytes = node.size,
            )
        }

        val present = anyPresent ?: return null
        val (origin, failure) = firstFailure ?: (present.second to SaveClassification.Inaccessible("Unreadable"))
        diagnostics += "$relativePath: ${failure.summary}"
        return SaveSource(
            id = "${volume.label}::$relativePath",
            volumeLabel = volume.label,
            saveRoot = saveRoot,
            directory = directory,
            mainName = mainName,
            relativePath = relativePath,
            version = expectedVersion,
            slotId = slotId,
            slotLabel = slotLabel,
            isActiveSlot = isActiveSlot,
            classification = failure,
            origin = origin,
            writable = volume.canWrite,
            lastModified = present.first.lastModified,
            fingerprint = "",
            sizeBytes = present.first.size,
        )
    }

    private data class SlotRegistry(
        val order: List<String>,
        val active: String?,
        val labels: Map<String, String>,
    )

    /**
     * `options.saveSlots[version] = { list = { "slot1", ... }, active = "slot1",
     * names = { slot1 = "..." } }`, written by upstream `putRegistry` /
     * `renameSlotIn`. Purely decorative here: a missing or unreadable
     * `options.lua` only costs the app the labels, never a save.
     */
    private suspend fun readSlotRegistry(
        saveRoot: SaveNode,
        diagnostics: MutableList<String>,
    ): Map<String, SlotRegistry> {
        val node = volume.child(saveRoot, OPTIONS_FILE)?.takeIf { !it.isDirectory } ?: return emptyMap()
        val root = try {
            LuaParser.parse(LuaText.decode(volume.readBytes(node)))
        } catch (e: Exception) {
            diagnostics += "$OPTIONS_FILE: ${e.message ?: "unreadable"} (slot names unavailable)"
            return emptyMap()
        }
        val saveSlots = root["saveSlots"].asTable() ?: return emptyMap()
        val out = LinkedHashMap<String, SlotRegistry>()
        for ((key, value) in saveSlots.entries()) {
            val versionId = (key as? com.logie.gen1storage.lua.LuaKey.Name)?.value ?: continue
            val entry = value.asTable() ?: continue
            val list = entry["list"].asTable()?.array().orEmpty().mapNotNull { it.asString() }
            val labels = entry["names"].asTable()?.entries().orEmpty().mapNotNull { (labelKey, labelValue) ->
                val slot = (labelKey as? com.logie.gen1storage.lua.LuaKey.Name)?.value ?: return@mapNotNull null
                val text = labelValue.asString()?.let(LuaText::displayText) ?: return@mapNotNull null
                slot to text
            }.toMap()
            out[versionId] = SlotRegistry(list, entry["active"].asString(), labels)
        }
        return out
    }

    companion object {
        const val OPTIONS_FILE = "options.lua"
        const val SLOTS_DIR = "saves"

        private const val MAX_DEPTH = 8
        private const val MAX_DIRECTORIES = 400

        val FLAT_SAVE_NAMES: Set<String> =
            GameVersion.entries.map { "save${it.saveSuffix}.lua" }.toSet()

        private val SLOT_FILE = Regex("^(slot\\d+)\\.lua$")

        private val SOURCE_ORDER = compareBy<SaveSource>(
            { it.version.ordinal },
            { it.slotId?.removePrefix("slot")?.toIntOrNull() ?: Int.MAX_VALUE },
            { it.relativePath },
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Package ids a Gen1Recomp build has shipped under, newest first. */
object Gen1RecompPackages {
    val KNOWN = listOf(
        "com.theboisclub.pokemonred.androidfixes",
        "com.theboisclub.pokemonred",
    )

    /**
     * LÖVE's Android save directory with `t.externalstorage = true` (see
     * upstream `conf.lua`): `<external files>/save/<t.identity>`.
     */
    const val LOVE_IDENTITY = "pokemon-love2d"

    @android.annotation.SuppressLint("SdCardPath")
    fun candidateRoots(packageId: String): List<String> = listOf(
        "/storage/emulated/0/Android/data/$packageId/files",
        "/data/user/0/$packageId/files",
        "/data/data/$packageId/files",
    )
}
