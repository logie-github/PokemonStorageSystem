package com.logie.gen1storage.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import com.logie.gen1storage.ui.AppSettings

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
 * What is included when it is on is `res/xml/backup_rules.xml`: the boxes and
 * the settings, and not the sprites, the cries or the copies kept of a
 * cartridge before each write. Those are all re-fetchable and together they
 * are far past the twenty-five megabytes the platform allows.
 *
 * The restore side is deliberately not here. A backup is the boxes at one
 * moment and the cartridges have moved on since, so what comes back has to be
 * checked against them rather than trusted — see `AppSettings.restoredUids`,
 * which the app sets for itself by noticing that its boxes arrived without
 * the marker file that never leaves the device.
 */
class StorageBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        if (!AppSettings(this).cloudBackup) return
        super.onFullBackup(data)
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
}
