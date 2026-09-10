package com.logie.gen1storage.saveaccess

/**
 * One file or directory inside a [SaveVolume].
 *
 * [key] is the volume's own opaque handle — an absolute path for the Shizuku
 * backend, a document URI for the Storage Access Framework one. Nothing outside
 * a volume interprets it.
 */
data class SaveNode(
    val key: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * Read/write access to a place Gen1Recomp saves can live.
 *
 * Android gives two very different routes to `Android/data/<package>/files`:
 * Shizuku, which hands back a privileged file API, and the Storage Access
 * Framework, where the player grants a persisted tree permission. Both are
 * modelled here so nothing above this layer has to care which one is in play.
 */
interface SaveVolume {

    /** Shown to the player when a save's origin has to be named. */
    val label: String

    /** False for a volume that can be scanned but not written to. */
    val canWrite: Boolean

    suspend fun children(node: SaveNode): List<SaveNode>

    suspend fun child(node: SaveNode, name: String): SaveNode?

    suspend fun readBytes(node: SaveNode): ByteArray

    /** Creates or replaces `parent/name`. Returns the resulting node. */
    suspend fun writeBytes(parent: SaveNode, name: String, bytes: ByteArray): SaveNode

    suspend fun delete(node: SaveNode): Boolean

    suspend fun ensureDirectory(parent: SaveNode, name: String): SaveNode

    /** Re-reads a node's metadata, or null when it has disappeared. */
    suspend fun refresh(node: SaveNode): SaveNode?

    /** The parent directory of [node], needed for staged writes beside a file. */
    suspend fun parentOf(node: SaveNode): SaveNode?
}

/** Refuses to read anything larger than a plausible save. */
const val MAX_SAVE_BYTES = 32L * 1024 * 1024
