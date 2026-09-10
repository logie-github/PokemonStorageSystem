package com.logie.gen1storage.sync

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassification
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import java.io.File
import java.security.MessageDigest

/** A save fetched from the account, with the revision it was read at. */
data class LoadedSave(
    val remote: RemoteSave,
    val blob: String,
    val rev: Long,
    val classification: SaveClassification,
) {
    val save: Gen1RecompSave? get() = classification.save
    val isUsable: Boolean get() = save != null
    val key: String get() = remote.key

    /** SHA-256 of the blob. The evidence crash recovery reasons about. */
    val fingerprint: String by lazy { SaveRepository.sha256(blob) }
}

sealed interface CommitOutcome {
    data class Committed(val rev: Long) : CommitOutcome
    data class Refused(val reason: String) : CommitOutcome

    /** The game saved over this playthrough since it was read. */
    data class Conflict(val reason: String) : CommitOutcome

    /**
     * The request failed in a way that leaves the result genuinely unknown — a
     * timeout or a dropped connection after the body went out. The write may
     * or may not have landed, which is precisely what the transfer journal
     * exists to settle on the next launch.
     */
    data class Unknown(val reason: String) : CommitOutcome
}

/**
 * Reading and writing Gen1Recomp saves through the account's save sync.
 *
 * Every write is preceded by a local copy of the blob being replaced, which is
 * this app's equivalent of the `.bak` the game keeps beside a local save: the
 * previous state of the playthrough is always recoverable from this device,
 * even though the save itself lives on the server.
 *
 * Concurrency is the server's `baseRev`. A save is read at a revision, written
 * against that revision, and refused with 409 if anything else moved it in
 * between. `force` is never sent.
 */
class SaveRepository(
    private val api: SyncApi,
    private val backups: SaveBackups,
) {

    suspend fun listSaves(): SyncResult<AccountState> = api.state()

    /** Fetches a save and classifies it. A bad blob classifies, never throws. */
    suspend fun load(remote: RemoteSave): SyncResult<LoadedSave> =
        when (val result = api.getSave(remote.version, remote.playthroughId)) {
            is SyncResult.Ok -> {
                val blob = result.value.blob
                SyncResult.Ok(
                    LoadedSave(
                        remote = remote.copy(rev = result.value.rev.takeIf { it > 0 } ?: remote.rev),
                        blob = blob,
                        rev = result.value.rev.takeIf { it > 0 } ?: remote.rev,
                        classification = SaveClassifier.classify(blob),
                    )
                )
            }
            is SyncResult.Conflict -> SyncResult.Failed("The server rejected the read")
            SyncResult.Unauthorized -> SyncResult.Unauthorized
            is SyncResult.Failed -> result
        }

    /**
     * Writes [root] back over [loaded]'s playthrough.
     *
     * The new bytes are validated as a save before anything leaves the device,
     * the bytes being replaced are backed up locally, and the upload carries
     * the revision the save was read at.
     */
    suspend fun commit(loaded: LoadedSave, root: LuaValue.Table): CommitOutcome {
        val encoded = LuaWriter.encode(root)

        val check = SaveClassifier.classify(encoded)
        val save = check.save
            ?: return CommitOutcome.Refused("REFUSED: THE NEW SAVE DATA IS INVALID (${check.summary})")

        if (encoded.toByteArray(Charsets.UTF_8).size > SyncApi.MAX_BLOB_BYTES) {
            return CommitOutcome.Refused("REFUSED: THE RESULTING SAVE IS TOO LARGE TO SYNC")
        }

        // The copy that is about to be replaced, kept before the write goes out.
        runCatching { backups.store(loaded.key, loaded.rev, loaded.blob) }

        val summary = summaryOf(save, loaded.remote.summary)
        val result = api.putSave(
            version = loaded.remote.version,
            playthroughId = loaded.remote.playthroughId,
            slot = loaded.remote.slot,
            blob = encoded,
            baseRev = loaded.rev,
            summary = summary,
        )
        return when (result) {
            is SyncResult.Ok -> CommitOutcome.Committed(result.value)
            is SyncResult.Conflict ->
                CommitOutcome.Conflict("THE GAME SAVED OVER THIS PLAYTHROUGH. REFRESH AND TRY AGAIN.")
            SyncResult.Unauthorized ->
                CommitOutcome.Refused("THIS DEVICE IS NO LONGER LINKED.")
            is SyncResult.Failed ->
                // A refusal the server explained is decided; anything else may
                // have landed, and must be treated as unknown rather than as a
                // failure that can be rolled back.
                if (result.code != null && result.code in 400..499) {
                    CommitOutcome.Refused(result.message.uppercase())
                } else {
                    CommitOutcome.Unknown(result.message)
                }
        }
    }

    /** Re-reads a save to settle what a previous write actually did. */
    suspend fun currentFingerprint(remote: RemoteSave): String? =
        (api.getSave(remote.version, remote.playthroughId) as? SyncResult.Ok)?.value?.blob?.let(::sha256)

    /**
     * The sync row's metadata, rebuilt from the save being written the same way
     * upstream `SyncEngine.defaultSaves().list` builds it. `savedAt` is carried
     * across from the row rather than invented: this app did not play the game,
     * and claiming a save time it did not produce would corrupt the game's own
     * change detection.
     */
    private fun summaryOf(save: Gen1RecompSave, previous: SaveSummary?): SaveSummary = SaveSummary(
        trainerName = save.trainerName,
        badges = save.badgeCount,
        timeText = save.playTimeText,
        dexCount = save.dexOwnedCount,
        savedAtEpochSeconds = previous?.savedAtEpochSeconds,
        playTimeSeconds = save.playTimeSeconds,
        format = save.saveFormat,
    )

    companion object {
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Local copies of every save blob this app has replaced.
 *
 * The server keeps revisions, but a player who wants their save back should
 * not have to depend on that, or on being online. These files are plain save
 * source: if everything else fails they can be copied into the game by hand.
 */
class SaveBackups(private val directory: File) {

    data class Entry(val key: String, val rev: Long, val file: File, val savedAtMillis: Long)

    fun store(key: String, rev: Long, blob: String) {
        val folder = File(directory, safe(key)).apply { mkdirs() }
        File(folder, "$rev-${System.currentTimeMillis()}.lua")
            .writeText(blob, Charsets.ISO_8859_1)
        prune(folder)
    }

    fun list(key: String): List<Entry> {
        val folder = File(directory, safe(key))
        return folder.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".lua") }
            .mapNotNull { file ->
                val rev = file.name.substringBefore('-').toLongOrNull() ?: return@mapNotNull null
                Entry(key, rev, file, file.lastModified())
            }
            .sortedByDescending { it.savedAtMillis }
    }

    fun read(entry: Entry): String? =
        runCatching { entry.file.readText(Charsets.ISO_8859_1) }.getOrNull()

    fun all(): List<Entry> =
        directory.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { list(unsafe(it.name)) }
            .sortedByDescending { it.savedAtMillis }

    private fun prune(folder: File) {
        val files = folder.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        files.drop(KEEP_PER_SAVE).forEach { it.delete() }
    }

    private fun safe(key: String) = key.replace('/', '~')
    private fun unsafe(name: String) = name.replace('~', '/')

    private companion object {
        const val KEEP_PER_SAVE = 10
    }
}
