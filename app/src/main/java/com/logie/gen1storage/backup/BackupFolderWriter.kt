package com.logie.gen1storage.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/**
 * The one file this app keeps inside a folder the player picked with the
 * system's own folder chooser — a folder inside the Google Drive app is
 * exactly as good a pick as one in local storage, since neither this class
 * nor the player has to do anything different either way; whichever app
 * answers for that folder is the one moving the bytes onto an account, and
 * this only ever talks to the folder itself.
 *
 * Found by name rather than by a uri kept from last time, since the tree
 * uri is the only thing [AppSettings.backupFolderUri] persists — the file
 * inside it can be recreated the first time, and found the same way every
 * time after.
 */
object BackupFolderWriter {

    const val FILE_NAME = "PokeStorage backup.pssbackup"
    private const val MIME_TYPE = "application/octet-stream"

    /** The file's uri, making it under [treeUri] first if it is not there yet. */
    fun findOrCreate(resolver: ContentResolver, treeUri: Uri): Uri {
        find(resolver, treeUri)?.let { return it }
        val parent = rootDocumentUri(treeUri)
        return DocumentsContract.createDocument(resolver, parent, MIME_TYPE, FILE_NAME)
            ?: throw IOException("Could not create the backup file in that folder.")
    }

    /** The file's uri if this folder already has one, or null if nothing has pushed here yet. */
    fun find(resolver: ContentResolver, treeUri: Uri): Uri? {
        val parent = rootDocumentUri(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parent),
        )
        val cursor = runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )
        }.getOrNull() ?: return null
        cursor.use {
            while (it.moveToNext()) {
                if (it.getString(1) == FILE_NAME) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, it.getString(0))
                }
            }
        }
        return null
    }

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
}
