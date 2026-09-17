package com.logie.gen1storage.sprites

import java.util.zip.CRC32

/**
 * Whether [bytes] is a PNG every one of whose chunks arrived intact.
 *
 * A transfer cut short by a dropped connection does not always throw — see
 * the download functions that call this — so a truncated file can still
 * decode, as far as the bytes that arrived go, and show as a clean top and
 * noise or blank space for the rest. Content-Length catches that when a
 * server sends one, but not every response does (chunked transfer encoding
 * carries no such header at all).
 *
 * An earlier version of this only checked that the file's last twelve bytes
 * were a genuine IEND chunk, which is exactly right for a transfer that stops
 * early — but it says nothing about one that arrives at the correct length
 * with a stretch of it corrupted in the middle: a resent packet landing out
 * of order, a proxy rewriting a chunk boundary, anything that leaves the byte
 * count and the closing IEND untouched while scrambling what sits between
 * them. That decodes too, to a picture with a clean top and pixel noise
 * further down — the tail check saw a valid IEND and called it complete.
 *
 * Every PNG chunk carries its own CRC-32 over its own type and data, written
 * once and never meant to change; walking the whole file and recomputing each
 * one catches corruption anywhere in it, not only at the end.
 */
internal fun isCompletePng(bytes: ByteArray): Boolean {
    if (bytes.size < PNG_SIGNATURE.size) return false
    for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return false

    var at = PNG_SIGNATURE.size
    while (at + 12 <= bytes.size) {
        val length = readUInt32(bytes, at)
        val typeStart = at + 4
        val dataStart = typeStart + 4
        // A chunk longer than what is left in the file is not a chunk this
        // file actually has — whether that is a corrupted length field or a
        // transfer that stopped mid-chunk, the file is not complete either way.
        if (length < 0 || length > bytes.size - dataStart - 4) return false
        val crcStart = dataStart + length.toInt()
        val chunkEnd = crcStart + 4

        val crc = CRC32()
        crc.update(bytes, typeStart, 4 + length.toInt())
        if (crc.value != readUInt32(bytes, crcStart)) return false

        if (isType(bytes, typeStart, "IEND")) return chunkEnd == bytes.size
        at = chunkEnd
    }
    return false
}

private fun isType(bytes: ByteArray, at: Int, type: String): Boolean =
    bytes[at] == type[0].code.toByte() && bytes[at + 1] == type[1].code.toByte() &&
        bytes[at + 2] == type[2].code.toByte() && bytes[at + 3] == type[3].code.toByte()

/** A big-endian uint32, as every length, and every CRC, is stored in a PNG. */
private fun readUInt32(bytes: ByteArray, at: Int): Long =
    ((bytes[at].toLong() and 0xFF) shl 24) or
        ((bytes[at + 1].toLong() and 0xFF) shl 16) or
        ((bytes[at + 2].toLong() and 0xFF) shl 8) or
        (bytes[at + 3].toLong() and 0xFF)

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
    0x0D, 0x0A, 0x1A, 0x0A,
)
