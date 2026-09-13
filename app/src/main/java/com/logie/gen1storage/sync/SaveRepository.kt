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
    /**
     * The write landed, at [rev].
     *
     * [after] is the save as it now stands: the bytes that were just uploaded,
     * at the revision the server gave them. It is exact rather than a guess —
     * the server took these bytes and answered with this revision — and it is
     * here so that nothing has to download what it has this moment sent. A
     * transfer used to finish by fetching the whole save back again, which on
     * a phone is the difference between a transfer that takes a moment and one
     * the player watches.
     */
    data class Committed(val rev: Long, val after: LoadedSave) : CommitOutcome
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
            // A minute ahead of the truth, and only in the index. See
            // [aMinuteAhead].
            summary = summary.aMinuteAhead(),
        )
        return when (result) {
            is SyncResult.Ok -> CommitOutcome.Committed(
                rev = result.value,
                after = LoadedSave(
                    // The summary that went up with the bytes, so the card
                    // reads right without waiting for the account listing.
                    remote = loaded.remote.copy(rev = result.value, summary = summary),
                    blob = encoded,
                    rev = result.value,
                    // Already worked out above, off these same bytes.
                    classification = check,
                ),
            )
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
    /**
     * The same summary, reporting one more minute of play than the save holds.
     *
     * A deliberate misstatement, in the index the server keeps and nowhere
     * else: the save itself goes up untouched, and the summary this app holds
     * for its own screens is the true one. What reads the index is the game's
     * sync, and this is the field it decides by.
     *
     * Gen1Recomp settles a save that changed in two places at once by asking
     * whether both sides are at the same point in the playthrough, and the
     * first thing it compares is the play time in whole minutes
     * (`SyncEngine.samePlaytime`): *"Same minute of playtime means the same
     * point in the playthrough, so there is no fork."* True of one device
     * saving twice. Not true of this app, which changes what is in a save
     * without moving its clock — so every transfer looked to the game like
     * the same point in the playthrough, and it kept the copy on the phone
     * running the game and discarded ours, without asking. A Pokémon moved
     * into a cartridge that way is in neither place afterwards.
     *
     * One minute is enough to make that test fail, which sends the game to
     * the question it should have asked: this copy or that one. It does not
     * decide the answer, and it cannot: nothing over there compares play
     * times for which is later, only for whether they are equal.
     *
     * What it does not close is the case where the player goes on to save at
     * exactly the minute claimed here — then the two match again and the
     * write is dropped silently as before. That hole shuts when Gen1Recomp
     * checks which device wrote the copy on the server instead, at which
     * point this belongs in a delete rather than a comment.
     */
    private fun SaveSummary.aMinuteAhead(): SaveSummary {
        val seconds = playTimeSeconds ?: return this
        val ahead = seconds + 60.0
        val total = ahead.toLong()
        return copy(
            playTimeSeconds = ahead,
            timeText = "%d:%02d".format(total / 3600, (total / 60) % 60),
        )
    }

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
