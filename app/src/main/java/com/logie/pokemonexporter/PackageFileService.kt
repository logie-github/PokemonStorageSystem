package com.logie.packageexporter

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.io.RandomAccessFile

class PackageFileService : Binder() {
    init { attachInterface(null, DESCRIPTOR) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
        data.enforceInterface(DESCRIPTOR)
        val requestedPath = data.readString() ?: return false
        return runCatching {
            val file = File(requestedPath).canonicalFile
            require(file.path == DATA_ROOT || file.path.startsWith("$DATA_ROOT/")) { "Path is outside Android/data" }
            reply?.writeNoException()
            when (code) {
                LIST -> {
                    val children = file.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
                    reply?.writeInt(children.size)
                    children.forEach { child ->
                        reply?.writeString(child.name)
                        reply?.writeString(child.canonicalPath)
                        reply?.writeBoolean(child.isDirectory)
                        reply?.writeLong(if (child.isFile) child.length() else 0L)
                    }
                }
                READ -> {
                    require(file.isFile) { "Not a file: $requestedPath" }
                    val offset = data.readLong()
                    val wanted = data.readInt().coerceIn(1, CHUNK_SIZE)
                    require(offset in 0..file.length()) { "Invalid offset" }
                    val count = minOf(wanted.toLong(), file.length() - offset).toInt()
                    val bytes = ByteArray(count)
                    if (count > 0) RandomAccessFile(file, "r").use { source -> source.seek(offset); source.readFully(bytes) }
                    reply?.writeByteArray(bytes)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            true
        }.getOrElse { error ->
            reply?.setDataSize(0)
            reply?.writeException(Exception(error.message ?: "Filesystem error"))
            true
        }
    }

    fun destroy() = Unit

    companion object {
        const val DESCRIPTOR = "com.logie.packageexporter.PackageFiles"
        const val DATA_ROOT = "/storage/emulated/0/Android/data"
        const val LIST = IBinder.FIRST_CALL_TRANSACTION
        const val READ = LIST + 1
        const val CHUNK_SIZE = 192 * 1024
    }
}
