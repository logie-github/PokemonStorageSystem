package com.logie.gen1storage.sprites

/**
 * Whether [bytes] ends the way every PNG must: an IEND chunk, the one the
 * format requires to be last.
 *
 * A transfer cut short by a dropped connection does not always throw — see
 * the download functions that call this — so a truncated file can still
 * decode, as far as the bytes that arrived go, and show as a clean top and
 * noise or blank space for the rest. Content-Length catches that when a
 * server sends one, but not every response does (chunked transfer encoding
 * carries no such header at all). Reading the bytes' own structure catches
 * it either way, since it never depends on the transport saying how much
 * there was supposed to be.
 */
internal fun isCompletePng(bytes: ByteArray): Boolean {
    if (bytes.size < IEND_CHUNK.size) return false
    val tail = bytes.size - IEND_CHUNK.size
    return IEND_CHUNK.indices.all { bytes[tail + it] == IEND_CHUNK[it] }
}

/** Length (0x00000000, IEND carries no data) then the type, "IEND". */
private val IEND_CHUNK = byteArrayOf(0, 0, 0, 0, 'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
