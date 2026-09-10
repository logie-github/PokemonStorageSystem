package com.logie.gen1storage.saveaccess

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Storage Access Framework backend: the player grants a persisted tree
 * permission once and the app scans and writes inside it on every later launch.
 *
 * This is the route that needs no Shizuku. On Android 11+ the system file
 * picker will not hand out `Android/data` itself, but it does hand out a
 * Gen1Recomp save folder the player navigates to, and a persisted grant makes
 * that a one-time step.
 */
class DocumentTreeVolume(
    private val context: Context,
    private val treeUri: Uri,
) : SaveVolume {

    private val resolver = context.contentResolver
    private val cache = HashMap<String, DocumentFile>()

    override val label: String = treeUri.lastPathSegment?.substringAfterLast('/') ?: "Selected folder"

    override val canWrite: Boolean by lazy {
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission } ||
            root()?.canWrite() == true
    }

    fun rootNode(): SaveNode? = root()?.let(::toNode)

    private fun root(): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    private fun toNode(file: DocumentFile): SaveNode {
        cache[file.uri.toString()] = file
        return SaveNode(
            key = file.uri.toString(),
            name = file.name.orEmpty(),
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            lastModified = file.lastModified(),
        )
    }

    private fun resolve(node: SaveNode): DocumentFile =
        cache[node.key]
            ?: DocumentFile.fromTreeUri(context, Uri.parse(node.key))
            ?: error("${node.name} is no longer reachable")

    override suspend fun children(node: SaveNode): List<SaveNode> = withContext(Dispatchers.IO) {
        resolve(node).listFiles().map(::toNode)
    }

    override suspend fun child(node: SaveNode, name: String): SaveNode? = withContext(Dispatchers.IO) {
        resolve(node).findFile(name)?.let(::toNode)
    }

    override suspend fun readBytes(node: SaveNode): ByteArray = withContext(Dispatchers.IO) {
        require(node.size <= MAX_SAVE_BYTES) { "${node.name} is larger than a Gen1Recomp save can be" }
        resolver.openInputStream(Uri.parse(node.key))?.use { it.readBytes() }
            ?: error("Cannot open ${node.name}")
    }

    override suspend fun writeBytes(parent: SaveNode, name: String, bytes: ByteArray): SaveNode =
        withContext(Dispatchers.IO) {
            val directory = resolve(parent)
            val target = directory.findFile(name)
                ?: createExactly(directory, name)
            // "wt" truncates; a plain "w" on some providers leaves a longer
            // previous file's tail in place and produces trailing garbage.
            resolver.openOutputStream(target.uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("Could not write $name")
            toNode(
                directory.findFile(name) ?: error("$name vanished immediately after writing")
            )
        }

    override suspend fun delete(node: SaveNode): Boolean = withContext(Dispatchers.IO) {
        runCatching { resolve(node).delete() }.getOrDefault(false)
    }

    override suspend fun ensureDirectory(parent: SaveNode, name: String): SaveNode =
        withContext(Dispatchers.IO) {
            val directory = resolve(parent)
            val existing = directory.findFile(name)
            if (existing != null && existing.isDirectory) return@withContext toNode(existing)
            toNode(directory.createDirectory(name) ?: error("Could not create folder $name"))
        }

    override suspend fun refresh(node: SaveNode): SaveNode? = withContext(Dispatchers.IO) {
        cache.remove(node.key)
        val file = DocumentFile.fromSingleUri(context, Uri.parse(node.key))
        if (file == null || !file.exists()) null
        else SaveNode(node.key, file.name ?: node.name, file.isDirectory, file.length(), file.lastModified())
    }

    override suspend fun parentOf(node: SaveNode): SaveNode? = withContext(Dispatchers.IO) {
        cache[node.key]?.parentFile?.let(::toNode)
    }

    /**
     * `createFile` is allowed to adjust the display name to match the MIME
     * type, and providers do: a `text/plain` document called `slot1.lua`
     * comes back as `slot1.lua.txt`, which Gen1Recomp would never find again.
     * Create, then insist on the exact name, and give up cleanly rather than
     * leave a wrongly-named file behind.
     */
    private fun createExactly(directory: DocumentFile, name: String): DocumentFile {
        val created = directory.createFile(MIME_TYPE, name) ?: error("Could not create $name")
        if (created.name == name) return created
        if (created.renameTo(name) && created.name == name) return created
        created.delete()
        error("This folder renamed $name to ${created.name}; the game could not read it back")
    }

    private companion object {
        // Deliberately not text/*: providers map those onto an extension and
        // append it. An opaque type leaves the name the app asked for alone.
        const val MIME_TYPE = "application/octet-stream"
    }
}
