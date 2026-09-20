package com.logie.gen1storage.mods

/**
 * A 256-bit difference hash, and how far apart two of them are.
 *
 * The same algorithm https://github.com/1Jamie/mod-scanner uses to tell a
 * mod's own artwork apart from the cartridge's: resize to one pixel wider
 * than tall, and for every pixel ask only "is the one to my right darker
 * than I am" — sixteen rows of sixteen such questions is 256 bits, and two
 * pictures that look alike answer most of them the same way regardless of
 * being recoloured, rescaled, or saved through a different encoder. A
 * pixel-for-pixel comparison would call a recolour a mismatch; this calls
 * it what it is.
 *
 * Kept apart from anything that touches [android.graphics.Bitmap] on
 * purpose, so the bit-twiddling this app's whole case for trusting a scan
 * rests on can be tested on the JVM without an emulator. See
 * [ModAssetScanner] for the Android-side adapter that feeds this real
 * pixels.
 */
object DHash {
    /** 16x16 = 256 bits: enough detail for a Game Boy sprite, no more than that. */
    const val HASH_SIZE = 16

    /** Below this many differing bits out of 256, a mod's own image is the reference sprite. */
    const val REJECT_THRESHOLD = 14

    /** Below this many, close enough to be a recolour or a light demake, worth a second look. */
    const val FLAG_THRESHOLD = 30

    /** How something scanned against the reference set came out. */
    enum class Verdict { CLEAN, FLAGGED, REJECT }

    fun verdictFor(hammingDistance: Int): Verdict = when {
        hammingDistance <= REJECT_THRESHOLD -> Verdict.REJECT
        hammingDistance <= FLAG_THRESHOLD -> Verdict.FLAGGED
        else -> Verdict.CLEAN
    }

    /**
     * The hash, from a grayscale image already resized to [HASH_SIZE] + 1
     * wide by [HASH_SIZE] tall, row-major, one luminance value (0-255) per
     * pixel — exactly what [android.graphics.Bitmap.createScaledBitmap]
     * plus a luminance read gives, and exactly what
     * `tools/generate_mod_scanner_hashes.py` feeds Pillow's own resize.
     *
     * The bit for row `r`, column `c` is 1 when the pixel to its right is
     * the darker of the two, and it lands at position `r * 16 + c` counting
     * from the most significant bit of the 256-bit value — the same
     * placement `int(bit_string, 2)` gives the Python generator, which is
     * what lets a hash computed here be compared directly against one this
     * class never computed.
     */
    fun fromResizedLuminance(luminance: IntArray): LongArray {
        val width = HASH_SIZE + 1
        require(luminance.size == width * HASH_SIZE) {
            "expected a ${width}x$HASH_SIZE luminance grid, got ${luminance.size} values"
        }
        val bits = LongArray(4)
        for (row in 0 until HASH_SIZE) {
            val rowOffset = row * width
            for (col in 0 until HASH_SIZE) {
                if (luminance[rowOffset + col + 1] > luminance[rowOffset + col]) {
                    val bitIndex = row * HASH_SIZE + col
                    val chunk = bitIndex / 64
                    val shift = 63 - (bitIndex % 64)
                    bits[chunk] = bits[chunk] or (1L shl shift)
                }
            }
        }
        return bits
    }

    /** The Hamming distance between two hashes: how many of the 256 bits disagree. */
    fun hammingDistance(a: LongArray, b: LongArray): Int {
        require(a.size == 4 && b.size == 4) { "a dHash is always four 64-bit words" }
        var total = 0
        for (i in a.indices) total += java.lang.Long.bitCount(a[i] xor b[i])
        return total
    }

    /** A hash as the same 64 lowercase hex characters the reference database stores. */
    fun toHex(hash: LongArray): String {
        require(hash.size == 4) { "a dHash is always four 64-bit words" }
        val builder = StringBuilder(64)
        for (word in hash) {
            val hex = java.lang.Long.toHexString(word)
            repeat(16 - hex.length) { builder.append('0') }
            builder.append(hex)
        }
        return builder.toString()
    }

    /** The reverse of [toHex]. Null for anything that isn't 64 hex characters. */
    fun fromHex(hex: String): LongArray? {
        if (hex.length != 64) return null
        val bits = LongArray(4)
        for (i in 0 until 4) {
            val chunk = hex.substring(i * 16, i * 16 + 16)
            bits[i] = runCatching { java.lang.Long.parseUnsignedLong(chunk, 16) }.getOrElse { return null }
        }
        return bits
    }
}
