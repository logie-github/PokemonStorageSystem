package com.logie.gen1storage.rom

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Walks a folder the player picked with the system's own folder chooser and
 * imports whichever ROMs, among however many files are in it, [RomIdentifier]
 * can name — never by filename, only by content, so a file called
 * `hfrhshehrhekaks.gb` imports exactly as readily as one called `red.gb` does
 * as long as its bytes are Red's.
 *
 * A version already on the device is left alone: re-running this over a
 * folder never overwrites a ROM already imported, only fills in whichever of
 * the six a player has not supplied yet. When more than one file in the
 * folder identifies as the same version, the first one found wins and the
 * rest are left where they are — the player can always delete the one this
 * kept and re-scan if it was not the copy they wanted.
 */
object RomFolderImporter {

    /** One [RomVersion]'s own outcome from this scan. */
    data class Outcome(val version: RomVersion, val imported: Boolean)

    /** How deep into subfolders this will look, so a folder of folders of folders cannot hang the app. */
    private const val MAX_DEPTH = 4

    private val PLAUSIBLE_SIZES = setOf(1_048_576L, 2_097_152L)

    /**
     * Imports every [RomVersion] this scan can find and this device does not
     * already have, and returns one [Outcome] per version in enum order —
     * [Outcome.imported] true for one already on the device beforehand too,
     * since that is still true of it, not only of what this call itself did.
     */
    fun import(context: Context, treeUri: Uri, store: RomStore): List<Outcome> {
        val claimed = RomVersion.entries.filter { store.has(it) }.toMutableSet()
        if (claimed.size < RomVersion.entries.size) {
            val startId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            if (startId != null) walk(context, treeUri, startId, 0, claimed, store)
        }
        return RomVersion.entries.map { Outcome(it, it in claimed) }
    }

    private fun walk(
        context: Context,
        treeUri: Uri,
        documentId: String,
        depth: Int,
        claimed: MutableSet<RomVersion>,
        store: RomStore,
    ) {
        if (depth > MAX_DEPTH || claimed.size == RomVersion.entries.size) return
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )
        }.getOrNull() ?: return

        cursor.use {
            while (it.moveToNext()) {
                if (claimed.size == RomVersion.entries.size) return
                val childId = it.getString(0)
                val mime = it.getString(1)
                val size = it.getLong(2)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(context, treeUri, childId, depth + 1, claimed, store)
                    continue
                }
                if (size !in PLAUSIBLE_SIZES) continue

                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                val bytes = runCatching {
                    resolver.openInputStream(childUri)?.use { input -> input.readBytes() }
                }.getOrNull() ?: continue

                val version = RomIdentifier.identify(bytes) ?: continue
                if (version in claimed) continue
                if (store.import(version, bytes)) claimed += version
            }
        }
    }
}
