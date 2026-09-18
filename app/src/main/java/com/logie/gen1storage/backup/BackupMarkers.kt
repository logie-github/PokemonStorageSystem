package com.logie.gen1storage.backup

import android.content.Context
import java.io.File

/**
 * A note this app leaves for itself about a push it made on its own.
 *
 * There is no way to ask afterwards whether a push actually happened, and
 * that is the first thing worth knowing when a folder's backup turns out to
 * be missing or stale — so a push writes down when it ran and what it did,
 * and OPTIONS and the debug report both read it back. Kept out of anything
 * this app itself writes into a backup folder, because a note is a fact
 * about this device rather than about the data.
 */
object BackupMarkers {

    /** When this app last pushed into a linked backup folder on its own, and what happened. */
    const val FOLDER_MARKER = "folder-backup.marker"

    fun note(context: Context, name: String, what: String) {
        runCatching {
            File(context.filesDir, name).writeText("${System.currentTimeMillis()} $what")
        }
    }

    /** What one of those notes says: when, and what happened, or null if nothing has run yet. */
    fun read(context: Context, name: String): Pair<Long, String>? {
        val text = File(context.filesDir, name).takeIf { it.isFile }
            ?.runCatching { readText() }?.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val at = text.substringBefore(' ').toLongOrNull() ?: return null
        return at to text.substringAfter(' ', "")
    }
}
