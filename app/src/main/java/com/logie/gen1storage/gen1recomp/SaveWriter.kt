package com.logie.gen1storage.gen1recomp

import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.saveaccess.SaveNode
import com.logie.gen1storage.saveaccess.SaveVolume

/** Why a write refused to run, or how it ended. */
sealed interface WriteOutcome {
    data class Committed(val node: SaveNode, val fingerprint: String, val backupWritten: Boolean) : WriteOutcome
    data class Refused(val reason: String) : WriteOutcome
    /**
     * The staged copy is on disk and the main file was not replaced. This is
     * the state upstream's own loader understands: `SaveData.load` promotes a
     * `.tmp` witness when the main file is missing or corrupt.
     */
    data class FailedButRecoverable(val reason: String) : WriteOutcome
}

/**
 * Commits a modified save with the same staged-write discipline the game uses,
 * and refuses to run at all when the file changed underneath the app.
 *
 * Upstream `SaveData.save` rolls the previous main file into `.bak`, stages the
 * new bytes as `.tmp`, then replaces the main file and clears the witness. This
 * reproduces that sequence, and adds the two checks a companion app needs that
 * the game does not: the file is confirmed unchanged since it was read, and the
 * committed bytes are read back and re-validated before the witness is cleared.
 */
class SaveWriter(private val volume: SaveVolume) {

    suspend fun commit(
        source: SaveSource,
        root: LuaValue.Table,
        expectedFingerprint: String,
        allowRepairOfCorruptMain: Boolean = false,
    ): WriteOutcome {
        if (!volume.canWrite) return WriteOutcome.Refused("THIS SAVE FOLDER IS READ-ONLY")

        val encoded = LuaText.encode(LuaWriter.encode(root))

        // Validate what is about to be written before anything on disk moves.
        val check = SaveClassifier.classify(encoded)
        if (check.save == null) {
            return WriteOutcome.Refused("REFUSED: NEW SAVE DATA IS INVALID (${check.summary})")
        }

        val mainNode = volume.child(source.directory, source.mainName)?.takeIf { !it.isDirectory }
        val currentBytes = mainNode?.let { runCatching { volume.readBytes(it) }.getOrNull() }
        val currentFingerprint = currentBytes?.let(SaveDiscovery::sha256)
        val mainIsReadable = currentBytes?.let { SaveClassifier.classify(it).save != null } == true

        when {
            // The bytes that were read are the bytes still on disk: safe.
            currentFingerprint == expectedFingerprint -> Unit
            // Nothing was read from the main file (a backup supplied the data).
            source.origin != SaveOrigin.MAIN && !mainIsReadable && allowRepairOfCorruptMain -> Unit
            currentFingerprint == null ->
                return WriteOutcome.Refused("REFUSED: ${source.mainName} IS GONE")
            else ->
                return WriteOutcome.Refused("REFUSED: THE GAME CHANGED THIS SAVE SINCE IT WAS READ")
        }

        // 1. Stage. A crash from here on leaves the witness upstream reads.
        val staged = try {
            volume.writeBytes(source.directory, source.stagedName, encoded)
        } catch (e: Exception) {
            return WriteOutcome.Refused("COULD NOT STAGE THE WRITE: ${e.message}")
        }
        val stagedBack = runCatching { volume.readBytes(staged) }.getOrNull()
        if (stagedBack == null || !stagedBack.contentEquals(encoded)) {
            runCatching { volume.delete(staged) }
            return WriteOutcome.Refused("STAGED COPY DID NOT READ BACK INTACT; SAVE UNTOUCHED")
        }

        // 2. Roll the previous main into .bak — but only when it is itself
        //    readable. Overwriting a good backup with a corrupt main would
        //    destroy the last recoverable copy of the playthrough.
        var backupWritten = false
        if (currentBytes != null && mainIsReadable) {
            backupWritten = runCatching {
                volume.writeBytes(source.directory, source.backupName, currentBytes)
            }.isSuccess
            if (!backupWritten) {
                runCatching { volume.delete(staged) }
                return WriteOutcome.Refused("COULD NOT WRITE THE BACKUP; SAVE UNTOUCHED")
            }
        }

        // 3. Replace the main file.
        try {
            volume.writeBytes(source.directory, source.mainName, encoded)
        } catch (e: Exception) {
            return WriteOutcome.FailedButRecoverable(
                "WRITE FAILED (${e.message}); THE STAGED COPY AND BACKUP ARE INTACT"
            )
        }

        // 4. Read the committed file back and re-validate it as a save.
        val committed = volume.child(source.directory, source.mainName)
            ?: return WriteOutcome.FailedButRecoverable("SAVE VANISHED AFTER WRITING")
        val committedBytes = runCatching { volume.readBytes(committed) }.getOrNull()
            ?: return WriteOutcome.FailedButRecoverable("SAVE COULD NOT BE READ BACK")
        if (!committedBytes.contentEquals(encoded)) {
            return WriteOutcome.FailedButRecoverable("SAVE DID NOT READ BACK BYTE FOR BYTE")
        }
        if (SaveClassifier.classify(committedBytes).save == null) {
            return WriteOutcome.FailedButRecoverable("SAVE READ BACK BUT NO LONGER PARSES")
        }

        // 5. Only now clear the witness.
        runCatching { volume.delete(staged) }

        return WriteOutcome.Committed(committed, SaveDiscovery.sha256(committedBytes), backupWritten)
    }

    /** Restores a slot's main file from its `.bak`, for the RESTORE screen. */
    suspend fun restoreFromBackup(source: SaveSource): WriteOutcome {
        if (!volume.canWrite) return WriteOutcome.Refused("THIS SAVE FOLDER IS READ-ONLY")
        val backup = volume.child(source.directory, source.backupName)?.takeIf { !it.isDirectory }
            ?: return WriteOutcome.Refused("NO BACKUP FOR ${source.mainName}")
        val bytes = runCatching { volume.readBytes(backup) }.getOrNull()
            ?: return WriteOutcome.Refused("BACKUP COULD NOT BE READ")
        if (SaveClassifier.classify(bytes).save == null) {
            return WriteOutcome.Refused("BACKUP IS NOT A VALID SAVE; NOTHING CHANGED")
        }
        // Keep whatever is in the main file now as the new backup's peer: the
        // player may want it back, so it is staged rather than discarded.
        volume.child(source.directory, source.mainName)?.let { current ->
            runCatching {
                volume.writeBytes(source.directory, "${source.mainName}.prev", volume.readBytes(current))
            }
        }
        val node = volume.writeBytes(source.directory, source.mainName, bytes)
        return WriteOutcome.Committed(node, SaveDiscovery.sha256(bytes), backupWritten = false)
    }
}
