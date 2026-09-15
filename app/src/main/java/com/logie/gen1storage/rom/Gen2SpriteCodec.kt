package com.logie.gen1storage.rom

/**
 * Decompresses a Generation II front sprite exactly as the cartridge does,
 * ported from pret/pokecrystal's `home/decompress.asm` (`Decompress`, the
 * general-purpose "lz3" scheme the disassembly's own comment names) —
 * a different algorithm from [Gen1SpriteCodec]'s, not a variant of it.
 *
 * Where Generation I reads a sprite as two 1-bit-per-pixel planes, bit by
 * bit, lz3 works a byte at a time and is much closer to a textbook LZ77: each
 * control byte names a command (read literal bytes, repeat one byte, repeat
 * two bytes alternately, write zeroes, or copy already-decompressed output
 * from an earlier offset — copied forwards, bit-reversed, or backwards) and
 * a run length, optionally extended to a ten-bit length by a second command
 * form. The two-byte "rewind" commands even distinguish a small negative
 * offset from the current output position (self-overlapping copies allowed,
 * the classic LZ77 trick) from a larger positive one counted from the start
 * of this decompression rather than from wherever the cursor now is.
 *
 * The decompressed bytes are then a plain Game Boy 2bpp tile sheet — no
 * second decoding pass, no XOR, no unpack mode. Generation II's own sprites
 * are square by construction (`tools/png_dimensions.c` only ever accepts 40,
 * 48, or 56 pixels and writes the same tile count into both the width and
 * height nibble of `BASE_PIC_SIZE`), so a decompressed sprite's own byte
 * count already says which of the three it is — 25, 36, or 49 tiles' worth
 * — without this app needing to be told in advance.
 */
object Gen2SpriteCodec {

    /** Thrown when the byte stream cannot be a real compressed sprite — never a licence to guess. */
    class MalformedSpriteException(message: String) : Exception(message)

    /** One Game Boy tile: 8x8 pixels, 16 bytes apiece. */
    private const val TILE_BYTES = 16
    private const val TILE_PIXELS = 8

    /** The only three square sprite sizes Generation II's own tooling ever emits. */
    private val VALID_TILE_DIMENSIONS = intArrayOf(5, 6, 7)

    /**
     * Decompresses the lz3 stream beginning at [offset] in [data] and
     * reshapes it into a square picture, or throws when the result is not
     * one of Generation II's three known sprite sizes — the same "never
     * silently accept a coincidence" stance [Gen1SpriteCodec] takes.
     */
    fun decompress(data: ByteArray, offset: Int): Gen1SpriteCodec.DecodedSprite {
        val raw = decompressRaw(data, offset, MAX_OUTPUT_BYTES)
        val tiles = raw.size / TILE_BYTES
        if (raw.size % TILE_BYTES != 0 || VALID_TILE_DIMENSIONS.none { it * it == tiles }) {
            throw MalformedSpriteException("decompressed to ${raw.size} bytes, not a square Gen II sprite")
        }
        val dimensionTiles = VALID_TILE_DIMENSIONS.first { it * it == tiles }
        return tilesToPixels(raw, dimensionTiles)
    }

    /**
     * The lz3 byte stream itself, decompressed to plain bytes — a Game Boy
     * 2bpp tile sheet for a sprite, but also how Generation II compresses
     * tilesets, fonts, and everything else `Decompress` is asked to unpack.
     * Exposed on its own so the ROM locator can use "does this decompress to
     * a plausible size" as a self-check without caring what shape the result
     * takes.
     */
    fun decompressRaw(data: ByteArray, offset: Int, maxOutputBytes: Int): ByteArray {
        val reader = Lz3Reader(data, offset)
        val out = ByteArray(maxOutputBytes)
        var outLen = 0

        fun put(byte: Int) {
            if (outLen >= maxOutputBytes) throw MalformedSpriteException("decompressed past $maxOutputBytes bytes")
            out[outLen] = byte.toByte()
            outLen++
        }

        while (true) {
            val control = reader.peekByte()
            if (control == LZ_END) {
                reader.skip(1)
                break
            }
            val shortCommand = (control and LZ_CMD) ushr 5
            if (shortCommand == LZ_LONG_CMD) {
                // The next three bits (originally bits 2-4 of the control
                // byte) become the real command; the length becomes ten
                // bits, spread across the low two bits of this byte and the
                // whole of the next one.
                reader.skip(1)
                val realCommand = (control shl 3) and LZ_CMD ushr 5
                val lengthHi = control and LZ_LONG_HI
                val lengthLo = reader.readByte()
                runCommand(realCommand, ((lengthHi shl 8) or lengthLo) + 1, reader, out, outLen, ::put)
            } else {
                reader.skip(1)
                val length = (control and LZ_LEN) + 1
                runCommand(shortCommand, length, reader, out, outLen, ::put)
            }
        }
        return out.copyOf(outLen)
    }

    /** Dispatches one already-parsed command against the output built up so far. */
    private inline fun runCommand(
        command: Int,
        length: Int,
        reader: Lz3Reader,
        out: ByteArray,
        currentOutLen: Int,
        put: (Int) -> Unit,
    ) {
        when (command) {
            LZ_LITERAL -> repeat(length) { put(reader.readByte()) }
            LZ_ITERATE -> {
                val value = reader.readByte()
                repeat(length) { put(value) }
            }
            LZ_ALTERNATE -> {
                val a = reader.readByte()
                val b = reader.readByte()
                for (i in 0 until length) put(if (i % 2 == 0) a else b)
            }
            LZ_ZERO -> repeat(length) { put(0) }
            LZ_REPEAT, LZ_FLIP, LZ_REVERSE -> {
                val source = reader.readRewindOffset(currentOutLen)
                var src = source
                for (i in 0 until length) {
                    if (src !in out.indices) throw MalformedSpriteException("rewind offset $src out of range")
                    val byte = out[src].toInt() and 0xFF
                    val value = when (command) {
                        LZ_FLIP -> reverseBits(byte)
                        else -> byte
                    }
                    put(value)
                    src = if (command == LZ_REVERSE) src - 1 else src + 1
                }
            }
            else -> throw MalformedSpriteException("unknown lz3 command $command")
        }
    }

    private fun reverseBits(byte: Int): Int {
        var b = byte
        var result = 0
        repeat(8) {
            result = (result shl 1) or (b and 1)
            b = b ushr 1
        }
        return result
    }

    /** Turns [tileDimension] x [tileDimension] tiles of plain 2bpp bytes into row-major pixel indices 0-3. */
    private fun tilesToPixels(raw: ByteArray, tileDimension: Int): Gen1SpriteCodec.DecodedSprite {
        val sidePx = tileDimension * TILE_PIXELS
        val pixels = IntArray(sidePx * sidePx)
        for (row in 0 until sidePx) {
            val tileRow = row / TILE_PIXELS
            val rowInTile = row % TILE_PIXELS
            for (col in 0 until sidePx) {
                val tileCol = col / TILE_PIXELS
                val tileIndex = tileRow * tileDimension + tileCol
                val rowOffset = tileIndex * TILE_BYTES + rowInTile * 2
                val loByte = raw[rowOffset].toInt() and 0xFF
                val hiByte = raw[rowOffset + 1].toInt() and 0xFF
                val shift = 7 - (col % TILE_PIXELS)
                val lo = (loByte shr shift) and 1
                val hi = (hiByte shr shift) and 1
                pixels[row * sidePx + col] = (hi shl 1) or lo
            }
        }
        return Gen1SpriteCodec.DecodedSprite(sidePx, sidePx, pixels)
    }

    /** A cursor over the compressed input, plus the rewrite commands' own addressing rules. */
    private class Lz3Reader(private val data: ByteArray, offset: Int) {
        private var pos = offset

        fun peekByte(): Int {
            if (pos !in data.indices) throw MalformedSpriteException("read past end of ROM")
            return data[pos].toInt() and 0xFF
        }

        fun skip(count: Int) {
            pos += count
        }

        fun readByte(): Int {
            val b = peekByte()
            pos++
            return b
        }

        /**
         * `.rewrite`: a negative (7-bit magnitude) offset counts back from
         * the current output length; a positive (15-bit) one counts forward
         * from the very start of this decompression's output — not from
         * wherever the cursor is now, which is what lets an early command
         * reference bytes that later commands have not written yet at the
         * time this one runs but that this decompression's start already
         * fixes the meaning of.
         */
        fun readRewindOffset(currentOutLen: Int): Int {
            val first = readByte()
            return if (first and 0x80 != 0) {
                // The one's-complement-without-a-final-increment trick the
                // real code uses to turn "subtract" into "add" bakes in an
                // extra -1: a magnitude of 0 means the byte just before the
                // current position, not the current position itself.
                currentOutLen - (first and 0x7F) - 1
            } else {
                val second = readByte()
                // Positive offsets count from the very start of this
                // decompression's output, which is always index 0 here.
                (first shl 8) or second
            }
        }
    }

    // home/decompress.asm's own constants, unchanged.
    private const val LZ_END = 0xFF
    private const val LZ_CMD = 0b1110_0000
    private const val LZ_LEN = 0b0001_1111
    private const val LZ_LITERAL = 0
    private const val LZ_ITERATE = 1
    private const val LZ_ALTERNATE = 2
    private const val LZ_ZERO = 3
    private const val LZ_REPEAT = 4
    private const val LZ_FLIP = 5
    private const val LZ_REVERSE = 6
    private const val LZ_LONG_CMD = 7
    private const val LZ_LONG_HI = 0b0000_0011

    /** Comfortably past the largest real sprite (49 tiles, 784 bytes); anything past this is not one. */
    private const val MAX_OUTPUT_BYTES = 4096
}
