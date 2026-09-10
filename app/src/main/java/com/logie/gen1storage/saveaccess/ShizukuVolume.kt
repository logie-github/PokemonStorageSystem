package com.logie.gen1storage.saveaccess

import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The app-process half of the Shizuku backend. Talks to [ShizukuFileService]
 * over a raw binder transaction and presents it as a [SaveVolume].
 */
class ShizukuVolume(private val binder: IBinder) : SaveVolume {

    override val label = "Shizuku"
    override val canWrite = true

    fun nodeFor(path: String): SaveNode =
        SaveNode(path, path.substringAfterLast('/'), isDirectory = true, size = 0, lastModified = 0)

    override suspend fun children(node: SaveNode): List<SaveNode> =
        call(node.key, ShizukuFileService.LIST) { reply ->
            List(reply.readInt()) { readEntry(reply) }
        }

    override suspend fun child(node: SaveNode, name: String): SaveNode? =
        stat("${node.key.trimEnd('/')}/$name")

    override suspend fun readBytes(node: SaveNode): ByteArray {
        require(node.size <= MAX_SAVE_BYTES) { "${node.name} is larger than a Gen1Recomp save can be" }
        val out = ByteArrayOutputStream(node.size.toInt().coerceAtLeast(16))
        var offset = 0L
        while (offset < node.size) {
            val chunk = call(node.key, ShizukuFileService.READ, { parcel ->
                parcel.writeLong(offset)
                parcel.writeInt(minOf(ShizukuFileService.CHUNK_SIZE.toLong(), node.size - offset).toInt())
            }) { it.createByteArray() ?: ByteArray(0) }
            check(chunk.isNotEmpty()) { "Unexpected end of ${node.name}" }
            out.write(chunk)
            offset += chunk.size
        }
        return out.toByteArray()
    }

    override suspend fun writeBytes(parent: SaveNode, name: String, bytes: ByteArray): SaveNode {
        val path = "${parent.key.trimEnd('/')}/$name"
        // Chunked so a large payload never trips the 1 MB binder limit.
        var offset = 0
        var appended = false
        var size = 0L
        var modified = 0L
        do {
            val end = minOf(bytes.size, offset + ShizukuFileService.CHUNK_SIZE)
            val slice = bytes.copyOfRange(offset, end)
            val append = appended
            val result = call(path, ShizukuFileService.WRITE, { parcel ->
                parcel.writeByteArray(slice)
                parcel.writeInt(if (append) 1 else 0)
            }) { it.readLong() to it.readLong() }
            size = result.first
            modified = result.second
            offset = end
            appended = true
        } while (offset < bytes.size)
        return SaveNode(path, name, isDirectory = false, size = size, lastModified = modified)
    }

    override suspend fun delete(node: SaveNode): Boolean =
        call(node.key, ShizukuFileService.DELETE) { it.readInt() != 0 }

    override suspend fun ensureDirectory(parent: SaveNode, name: String): SaveNode =
        call("${parent.key.trimEnd('/')}/$name", ShizukuFileService.MKDIRS) { readEntry(it) }

    override suspend fun refresh(node: SaveNode): SaveNode? = stat(node.key)

    override suspend fun parentOf(node: SaveNode): SaveNode? {
        val path = node.key.substringBeforeLast('/', "")
        return if (path.isEmpty()) null else nodeFor(path)
    }

    suspend fun stat(path: String): SaveNode? =
        call(path, ShizukuFileService.STAT) { reply ->
            if (reply.readInt() == 0) null else readEntry(reply)
        }

    /** Field order matches [ShizukuFileService.writeEntry]: name, path, dir, size, mtime. */
    private fun readEntry(reply: Parcel): SaveNode {
        val name = reply.readString().orEmpty()
        val path = reply.readString().orEmpty()
        return SaveNode(
            key = path,
            name = name,
            isDirectory = reply.readInt() != 0,
            size = reply.readLong(),
            lastModified = reply.readLong(),
        )
    }

    private suspend fun <T> call(
        path: String,
        code: Int,
        args: ((Parcel) -> Unit)? = null,
        read: (Parcel) -> T,
    ): T = withContext(Dispatchers.IO) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuFileService.DESCRIPTOR)
            data.writeString(path)
            args?.invoke(data)
            check(binder.isBinderAlive && binder.transact(code, data, reply, 0)) {
                "Shizuku service disconnected"
            }
            reply.readException()
            read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
