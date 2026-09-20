package com.logie.gen1storage.mods

import java.io.InputStream
import java.util.zip.ZipFile

/**
 * A mod zip, read end to end, before any of it is trusted.
 *
 * Two passes over the same [ZipFile]: first the archive itself, the way
 * [validateMetadata] alone can tell a zip bomb or a path escaping wherever
 * this gets extracted to without reading a single byte of any entry's own
 * content; then every entry in turn, through [ModAssetScanner]. Nothing
 * here decodes an image itself — [decodeImage] is the one seam
 * `ModImportPipeline` (the Android side) fills with
 * `BitmapFactory` — so the whole safety pass and the whole scan can run,
 * and be tested, on a plain JVM `ZipFile` with nothing further installed.
 */
object ModImportScanner {

    data class Limits(
        val maxFileCount: Int = 20_000,
        val maxUnpackedBytes: Long = 200L * 1024 * 1024,
        val maxCompressionRatio: Double = 100.0,
        /** Below this, even a wild ratio is not worth rejecting a file over — a tiny file cannot bomb anything. */
        val ratioCheckMinBytes: Long = 5L * 1024 * 1024,
    )

    /** The archive itself is the problem — too many files, a zip bomb, or a path trying to escape. */
    class ArchiveRejected(message: String) : Exception(message)

    /**
     * Checks entry counts, per-entry compression ratios, total unpacked
     * size, and every path, without decompressing anything. Throws
     * [ArchiveRejected] the moment one of them fails.
     */
    fun validateMetadata(zipFile: ZipFile, limits: Limits = Limits()) {
        val entries = zipFile.entries().toList()
        if (entries.size > limits.maxFileCount) {
            throw ArchiveRejected(
                "This archive has ${entries.size} files, over the limit of ${limits.maxFileCount}."
            )
        }
        var totalUnpacked = 0L
        for (entry in entries) {
            if (entry.isDirectory) continue
            if (normalizePath(entry.name) == null) {
                throw ArchiveRejected("Suspicious path in archive: '${entry.name}'.")
            }
            val size = entry.size
            val compressed = entry.compressedSize
            if (size > limits.ratioCheckMinBytes && compressed > 0) {
                val ratio = size.toDouble() / compressed.toDouble()
                if (ratio > limits.maxCompressionRatio) {
                    throw ArchiveRejected(
                        "'${entry.name}' would unpack ${"%.1f".format(ratio)}:1, over the limit of ${limits.maxCompressionRatio}:1."
                    )
                }
            }
            if (size >= 0) totalUnpacked += size
            if (totalUnpacked > limits.maxUnpackedBytes) {
                throw ArchiveRejected(
                    "This archive would unpack to over ${limits.maxUnpackedBytes / (1024 * 1024)} MB."
                )
            }
        }
    }

    /**
     * Every violation found across the archive; empty means clean. Call
     * [validateMetadata] first — this trusts that the archive itself has
     * already been judged safe to read.
     */
    fun scanEntries(
        zipFile: ZipFile,
        referenceDb: ModReferenceDatabase,
        decodeImage: (ByteArray) -> IntArray?,
    ): List<ModViolation> {
        val violations = mutableListOf<ModViolation>()
        for (entry in zipFile.entries()) {
            if (entry.isDirectory) continue
            val name = entry.name
            val bytes = zipFile.getInputStream(entry).use { input ->
                if (entry.size in 0..Int.MAX_VALUE.toLong()) readExactly(input, entry.size.toInt())
                else input.readBytes()
            }
            val header = bytes.copyOfRange(0, minOf(bytes.size, ModAssetScanner.HEADER_CHUNK_BYTES))
            val binaryViolation = ModAssetScanner.checkBinary(name, header)
            if (binaryViolation != null) {
                violations += binaryViolation
                continue
            }
            if (ModAssetScanner.isImage(name)) {
                val luminance = decodeImage(bytes)
                if (luminance != null) {
                    ModAssetScanner.checkImage(name, luminance, referenceDb)?.let { violations += it }
                }
            }
        }
        return violations
    }

    /**
     * Reads exactly [size] bytes, never more — a defence of its own
     * against an entry whose actual compressed stream keeps going past
     * what its own header declared, the way a zip bomb's would.
     */
    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(out, read, size - read)
            if (n < 0) return out.copyOf(read)
            read += n
        }
        return out
    }

    /**
     * A path resolved the way `../` and a leading slash would need to be
     * for this entry to land outside wherever the archive is extracted to
     * — null for anything that tries. The same ZipSlip guard
     * [com.logie.gen1storage.backup.BackupExport] already runs on a
     * restored backup's own entries, applied here before a mod's own
     * files are ever written to disk.
     */
    fun normalizePath(name: String): String? {
        if (name.startsWith("/") || name.startsWith("\\")) return null
        val stack = mutableListOf<String>()
        for (part in name.split('/', '\\')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else return null
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }
}
