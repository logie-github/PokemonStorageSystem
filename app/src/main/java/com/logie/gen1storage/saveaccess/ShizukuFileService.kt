package com.logie.gen1storage.saveaccess

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.io.RandomAccessFile

/**
 * The privileged half of the Shizuku backend: a plain [Binder] that Shizuku
 * hosts in its own process so it can reach `Android/data/<package>/files`,
 * which an ordinary app cannot on Android 11+.
 *
 * Every transaction re-checks the requested path against [isAllowed]. The
 * service is deliberately dumb — list, read, write, delete, mkdir — so all
 * transactional reasoning stays in the app process where it can be tested.
 */
class ShizukuFileService : Binder() {

    init { attachInterface(null, DESCRIPTOR) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
        data.enforceInterface(DESCRIPTOR)
        val requestedPath = data.readString() ?: return false
        return runCatching {
            val file = File(requestedPath)
            require(isAllowed(file.path)) { "Path is outside approved save roots" }
            when (code) {
                LIST -> {
                    val children = file.listFiles()?.sortedBy { it.name.lowercase() }
                        ?: error(
                            if (file.exists()) "Directory access denied: $requestedPath"
                            else "Directory missing: $requestedPath"
                        )
                    reply?.writeNoException()
                    reply?.writeInt(children.size)
                    children.forEach { child -> writeEntry(reply, child) }
                }
                STAT -> {
                    reply?.writeNoException()
                    if (!file.exists()) reply?.writeInt(0)
                    else { reply?.writeInt(1); writeEntry(reply, file) }
                }
                READ -> {
                    require(file.isFile) { "Not a file: $requestedPath" }
                    val offset = data.readLong()
                    val wanted = data.readInt().coerceIn(1, CHUNK_SIZE)
                    require(offset >= 0) { "Invalid offset $offset" }
                    // An offset at or past the end answers with no bytes; the
                    // client uses that as the end-of-file signal.
                    val count = minOf(wanted.toLong(), (file.length() - offset).coerceAtLeast(0)).toInt()
                    val bytes = ByteArray(count)
                    if (count > 0) {
                        RandomAccessFile(file, "r").use { source ->
                            source.seek(offset)
                            source.readFully(bytes)
                        }
                    }
                    reply?.writeNoException()
                    reply?.writeByteArray(bytes)
                }
                WRITE -> {
                    val bytes = data.createByteArray() ?: ByteArray(0)
                    val append = data.readInt() != 0
                    file.parentFile?.mkdirs()
                    RandomAccessFile(file, "rw").use { sink ->
                        if (append) sink.seek(sink.length()) else sink.setLength(0)
                        sink.write(bytes)
                        // Flush through the page cache: a save that survives a
                        // crash only counts if the bytes reached the volume.
                        sink.fd.sync()
                    }
                    reply?.writeNoException()
                    reply?.writeLong(file.length())
                    reply?.writeLong(file.lastModified())
                }
                DELETE -> {
                    val deleted = !file.exists() || file.delete()
                    reply?.writeNoException()
                    reply?.writeInt(if (deleted) 1 else 0)
                }
                MKDIRS -> {
                    val made = file.isDirectory || file.mkdirs()
                    require(made) { "Could not create directory: $requestedPath" }
                    reply?.writeNoException()
                    writeEntry(reply, file)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            true
        }.getOrElse { error ->
            reply?.writeException(marshalable(code, error))
            true
        }
    }

    /**
     * `Parcel.writeException` marshals only a fixed set of exception classes.
     * Anything else maps to code 0, where it rethrows instead of writing the
     * message — that throw escapes onTransact, the framework's own handler
     * fails to marshal the resulting RuntimeException too, and the whole
     * transaction fails. The caller then sees a dead transaction rather than
     * the filesystem error, so every failure here looked like a disconnect.
     *
     * The types below are the ones Parcel actually understands; everything
     * else is carried as IllegalStateException so the message survives.
     */
    private fun marshalable(code: Int, error: Throwable): Exception {
        val what = when (code) {
            LIST -> "list"
            READ -> "read"
            WRITE -> "write"
            DELETE -> "delete"
            MKDIRS -> "mkdirs"
            STAT -> "stat"
            else -> "transaction $code"
        }
        val message = "$what failed: ${error.message ?: error.javaClass.simpleName}"
        return when (error) {
            is SecurityException -> SecurityException(message)
            is IllegalArgumentException -> IllegalArgumentException(message)
            is NullPointerException -> NullPointerException(message)
            is UnsupportedOperationException -> UnsupportedOperationException(message)
            else -> IllegalStateException(message)
        }
    }

    // Parcel's boolean helpers arrived in API 29 and this app supports 26,
    // so every flag crosses the binder as an int.
    private fun writeEntry(reply: Parcel?, file: File) {
        reply?.writeString(file.name)
        reply?.writeString(file.absolutePath)
        reply?.writeInt(if (file.isDirectory) 1 else 0)
        reply?.writeLong(if (file.isFile) file.length() else 0L)
        reply?.writeLong(file.lastModified())
    }

    @Suppress("unused") // Shizuku's user-service contract calls this on unbind.
    fun destroy() = Unit

    companion object {
        const val DESCRIPTOR = "com.logie.gen1storage.SaveFiles"
        const val DATA_ROOT = "/storage/emulated/0/Android/data"
        const val LIST = IBinder.FIRST_CALL_TRANSACTION
        const val READ = LIST + 1
        const val WRITE = LIST + 2
        const val DELETE = LIST + 3
        const val MKDIRS = LIST + 4
        const val STAT = LIST + 5
        const val CHUNK_SIZE = 192 * 1024

        /**
         * The service will only touch the app-data roots a Gen1Recomp install
         * can live under. A privileged binder with an unrestricted path
         * parameter would be a general-purpose filesystem for anything that
         * could reach it.
         */
        @android.annotation.SuppressLint("SdCardPath")
        private fun isAllowed(path: String): Boolean {
            val roots = listOf(DATA_ROOT, "/data/user/0", "/data/user_de/0", "/data/data")
            if (path.contains("/../") || path.endsWith("/..")) return false
            return roots.any { path == it || path.startsWith("$it/") }
        }
    }
}
