package com.logie.gen1storage

import com.logie.gen1storage.saveaccess.SaveNode
import com.logie.gen1storage.saveaccess.SaveVolume

/**
 * An in-memory [SaveVolume] with the failure modes a real one has.
 *
 * Tests use this so a fixture is never a file on disk that another test can
 * mutate, and so an interrupted write can be reproduced exactly rather than
 * approximated.
 */
class FakeVolume(
    override val label: String = "test",
    override val canWrite: Boolean = true,
) : SaveVolume {

    private val files = LinkedHashMap<String, ByteArray>()
    private val directories = LinkedHashSet<String>(listOf(ROOT))
    private var clock = 1_000L

    /** Fails the next write to this exact path, as a full volume would. */
    var failWriteTo: String? = null

    /** Fails reads of this exact path, standing in for a backend error. */
    var failReadTo: String? = null

    /** Fails lookups of this exact path, standing in for a failed stat. */
    var failLookupTo: String? = null

    /** Records every path written, in order, for asserting the write sequence. */
    val writes = mutableListOf<String>()

    fun root(): SaveNode = SaveNode(ROOT, "", isDirectory = true, size = 0, lastModified = clock)

    fun putFile(path: String, contents: String) {
        val normalised = path.trimStart('/')
        var parent = ROOT
        normalised.split('/').dropLast(1).forEach { segment ->
            parent = "$parent/$segment"
            directories += parent
        }
        files["$ROOT/$normalised"] = contents.toByteArray(Charsets.ISO_8859_1)
        clock++
    }

    fun readFile(path: String): String? =
        files["$ROOT/${path.trimStart('/')}"]?.toString(Charsets.ISO_8859_1)

    fun exists(path: String): Boolean = files.containsKey("$ROOT/${path.trimStart('/')}")

    fun deleteFile(path: String) {
        files.remove("$ROOT/${path.trimStart('/')}")
    }

    override suspend fun children(node: SaveNode): List<SaveNode> {
        if (node.key !in directories) error("Directory missing: ${node.key}")
        val prefix = "${node.key}/"
        val direct = LinkedHashMap<String, SaveNode>()
        directories.filter { it.startsWith(prefix) }.forEach { path ->
            val name = path.removePrefix(prefix).substringBefore('/')
            if (name.isNotEmpty()) {
                direct["$prefix$name"] = SaveNode("$prefix$name", name, true, 0, clock)
            }
        }
        files.filter { it.key.startsWith(prefix) }.forEach { (path, bytes) ->
            val rest = path.removePrefix(prefix)
            if (!rest.contains('/')) direct[path] = SaveNode(path, rest, false, bytes.size.toLong(), clock)
        }
        return direct.values.toList()
    }

    override suspend fun child(node: SaveNode, name: String): SaveNode? {
        val path = "${node.key}/$name"
        if (failLookupTo == path.removePrefix("$ROOT/")) error("Shizuku rejected the lookup of $path")
        files[path]?.let { return SaveNode(path, name, false, it.size.toLong(), clock) }
        if (path in directories) return SaveNode(path, name, true, 0, clock)
        return null
    }

    override suspend fun readBytes(node: SaveNode): ByteArray {
        if (failReadTo == node.key.removePrefix("$ROOT/")) error("read failed: permission denied")
        return files[node.key]?.copyOf() ?: error("No such file: ${node.key}")
    }

    override suspend fun writeBytes(parent: SaveNode, name: String, bytes: ByteArray): SaveNode {
        val path = "${parent.key}/$name"
        writes += path.removePrefix("$ROOT/")
        if (failWriteTo == path.removePrefix("$ROOT/")) error("Simulated write failure for $name")
        files[path] = bytes.copyOf()
        clock++
        return SaveNode(path, name, false, bytes.size.toLong(), clock)
    }

    override suspend fun delete(node: SaveNode): Boolean = files.remove(node.key) != null

    override suspend fun ensureDirectory(parent: SaveNode, name: String): SaveNode {
        val path = "${parent.key}/$name"
        directories += path
        return SaveNode(path, name, true, 0, clock)
    }

    override suspend fun refresh(node: SaveNode): SaveNode? {
        files[node.key]?.let { return SaveNode(node.key, node.name, false, it.size.toLong(), clock) }
        return if (node.key in directories) node else null
    }

    override suspend fun parentOf(node: SaveNode): SaveNode? {
        val path = node.key.substringBeforeLast('/', "")
        if (path.isEmpty()) return null
        return SaveNode(path, path.substringAfterLast('/'), true, 0, clock)
    }

    companion object {
        const val ROOT = "/root"
    }
}
