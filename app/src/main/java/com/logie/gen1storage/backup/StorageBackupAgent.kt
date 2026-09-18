package com.logie.gen1storage.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import com.logie.gen1storage.ui.AppSettings
import java.io.File

/**
 * Whether this app goes into the player's Google account backup, and what of
 * it does.
 *
 * Android's own backup is the mechanism — the same one that carries an app's
 * data to a new phone when someone signs in — and the only thing this class
 * adds is the switch. `allowBackup` is a manifest flag and cannot be changed
 * while the app is running, so the decision has to be made here, at the
 * moment the system asks for the data: with BACKUP TO GOOGLE off, nothing is
 * written and the account holds nothing of this app at all.
 *
 * What is included when it is on is `res/xml/backup_rules.xml`: the boxes, the
 * histories, every setting and the link to the account — and not the ROMs, the
 * sprites, the cries, the trainer art or the copies kept of a cartridge before
 * each write, all of which are either re-fetchable or about this device, and
 * together are far past the twenty-five megabytes the platform allows.
 *
 * **Both ends are written down.** Whether a backup has ever actually been
 * taken is not something an app can ask the system, and it is the first thing
 * anyone wants to know when a restore turns up empty — so this leaves a note
 * each time the system asks, and another each time a restore finishes. Both
 * notes are kept out of the backup themselves ([MARKER], [RESTORE_MARKER]),
 * because they are facts about a device rather than about the data. OPTIONS
 * reads them and so does the report.
 *
 * The restore side is otherwise deliberately not here. A backup is the boxes
 * at one moment and the cartridges have moved on since, so what comes back is
 * offered rather than trusted — see `RestoreScreen`, and the marks the app
 * keeps on Pokémon themselves, which is how one that left by some other route
 * is recognised.
 */
class StorageBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        // The switch, read defensively: a preferences read that threw would
        // otherwise take the whole backup down with it, and an app that
        // silently stops being backed up is the exact failure this class is
        // trying not to have.
        val wanted = runCatching { AppSettings(this).cloudBackup }.getOrDefault(false)
        note(this, MARKER, if (wanted) "asked" else "asked, switched off")
        if (!wanted) return
        super.onFullBackup(data)
        note(this, MARKER, "taken")
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        note(this, RESTORE_MARKER, "restored")
    }

    // Key/value backup is not used: the manifest asks for full-data backup
    // only, and these two exist because the base class is abstract.
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) = Unit

    companion object {
        /** When the system last asked this app for a backup, and what happened. */
        const val MARKER = "backup.marker"

        /** When a restore last finished putting this app back. */
        const val RESTORE_MARKER = "restore.marker"

        private fun note(context: Context, name: String, what: String) {
            runCatching {
                File(context.filesDir, name).writeText("${System.currentTimeMillis()} $what")
            }
        }

        /**
         * What one of those notes says: when, and what happened, or null if
         * the system has never asked.
         *
         * "Never asked" is the answer that matters. Android takes a backup on
         * its own schedule — overnight, charging, on an unmetered network, at
         * most once a day — so an app switched on an hour ago and reinstalled
         * has genuinely never been backed up, and nothing in this app is
         * wrong. Without this note there is no way to tell that apart from a
         * backup that is failing.
         */
        fun read(context: Context, name: String): Pair<Long, String>? {
            val text = File(context.filesDir, name).takeIf { it.isFile }
                ?.runCatching { readText() }?.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) return null
            val at = text.substringBefore(' ').toLongOrNull() ?: return null
            return at to text.substringAfter(' ', "")
        }
    }
}
