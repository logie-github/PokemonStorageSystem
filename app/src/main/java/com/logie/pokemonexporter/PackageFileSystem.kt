package com.logie.packageexporter

import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

data class PackageEntry(val name: String, val path: String, val directory: Boolean, val size: Long)

class PackageFileSystem(private val binder: IBinder) {
    suspend fun readText(entry: PackageEntry): String {
        require(entry.size <= 32L * 1024 * 1024) { "Save exceeds 32 MB reader limit" }
        return java.io.ByteArrayOutputStream().use { output ->
            copy(entry, output)
            output.toString("UTF-8")
        }
    }
    suspend fun list(path: String): List<PackageEntry> = call(path, PackageFileService.LIST) { reply ->
        List(reply.readInt()) { PackageEntry(reply.readString().orEmpty(), reply.readString().orEmpty(), reply.readBoolean(), reply.readLong()) }
    }

    suspend fun copy(entry: PackageEntry, output: OutputStream) = withContext(Dispatchers.IO) {
        var offset = 0L
        while (offset < entry.size) {
            val bytes = call(entry.path, PackageFileService.READ, { parcel ->
                parcel.writeLong(offset)
                parcel.writeInt(minOf(PackageFileService.CHUNK_SIZE.toLong(), entry.size - offset).toInt())
            }) { it.createByteArray() ?: ByteArray(0) }
            check(bytes.isNotEmpty()) { "Unexpected end of ${entry.name}" }
            output.write(bytes)
            offset += bytes.size
        }
    }

    private suspend fun <T> call(path: String, code: Int, args: ((Parcel) -> Unit)? = null, read: (Parcel) -> T): T = withContext(Dispatchers.IO) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PackageFileService.DESCRIPTOR)
            data.writeString(path)
            args?.invoke(data)
            check(binder.isBinderAlive && binder.transact(code, data, reply, 0)) { "Shizuku service disconnected" }
            reply.readException()
            read(reply)
        } finally { data.recycle(); reply.recycle() }
    }
}
