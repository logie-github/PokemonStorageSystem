package com.logie.gen1storage.rom

/**
 * Decompresses a Generation I front or back sprite exactly as the cartridge
 * does, ported from pret/pokered's `home/uncompress.asm`
 * (`UncompressSpriteData` and everything it calls) rather than reconstructed
 * from memory of how the format "usually" works — this compression scheme
 * has a long history of ROM tools getting some corner of it subtly wrong.
 *
 * The compressed stream, once past its one size byte, is two back-to-back
 * **chunks**: each is a 1-bit-per-pixel plane, read as a bitstream of pixel
 * *pairs* (RLE-encoded runs of the pair `00`, or literal non-zero pairs) in a
 * traversal order that is column-by-tile, then four passes across that
 * column's rows, then the next tile-column — not the row-major order a
 * finished picture reads in. A one- or two-bit "unpack mode" between the two
 * chunks says how they combine: independently differential-decoded (mode 0),
 * or differential-decoded and XORed together (modes 1 and 2, which differ in
 * whether the first chunk is differential-decoded before the XOR). Whichever
 * chunk was decoded *first* is not necessarily the low bitplane of the final
 * picture — a single bit read right at the start decides which physical
 * buffer (and so which plane) it lands in.
 *
 * Deliberately out of scope: the mirrored ("flipped") sprite reuse some
 * species use for their back sprite. A dedicated front-sprite pointer is
 * never encoded that way, and a front sprite — [Gen1SpeciesTable]'s row —
 * is the only kind this app draws.
 */
object Gen1SpriteCodec {

    /** A decompressed sprite: `widthPx`x`heightPx` pixels, each a 2bpp value 0-3. */
    class DecodedSprite(val widthPx: Int, val heightPx: Int, val pixels: IntArray) {
        init {
            require(pixels.size == widthPx * heightPx) { "pixel count must match dimensions" }
        }
    }

    /** Thrown when the bitstream cannot be a real compressed sprite — never a licence to guess. */
    class MalformedSpriteException(message: String) : Exception(message)

    /**
     * Decompresses the sprite whose compressed bytes begin at [offset] in
     * [data]. Reads only as many bytes as the format needs; anything after
     * the sprite's own data (the next sprite, or the end of the bank) is
     * left alone.
     */
    fun decompress(data: ByteArray, offset: Int): DecodedSprite {
        val reader = BitReader(data, offset)
        val sizeByte = reader.readByte()
        // `_UncompressSpriteData`: high nybble is width in tiles, low is height.
        val widthTiles = (sizeByte shr 4) and 0xF
        val heightTiles = sizeByte and 0xF
        if (widthTiles !in 1..PIC_DIMENSION_MAX || heightTiles !in 1..PIC_DIMENSION_MAX) {
            throw MalformedSpriteException("implausible sprite size $widthTiles x $heightTiles tiles")
        }
        val heightPx = heightTiles * 8

        // The one bit that decides which physical buffer the first-decoded
        // chunk lands in — and so, later, which plane (low or high) it ends
        // up as once the buffers are interlaced back into a 2bpp picture.
        val firstChunkIsBufferTwo = reader.readBit() == 1

        val chunkOneRaw = decodeChunk(reader, widthTiles, heightPx)
        val mode = readUnpackMode(reader)
        val chunkTwoRaw = decodeChunk(reader, widthTiles, heightPx)

        // The chunk decoded *first* is always differential-decoded on its
        // own, whichever mode this is. The second chunk either gets the same
        // treatment (mode 0 or 2) or stays raw (mode 1), and is then combined
        // with the first chunk's decoded value — mode 0 by sitting beside it
        // unchanged, modes 1 and 2 by XOR — landing in the *second* chunk's
        // slot. The first chunk's own slot is never touched again.
        val chunkOneFinal = diffDecode(chunkOneRaw, widthTiles, heightPx)
        val chunkTwoFinal = when (mode) {
            0 -> diffDecode(chunkTwoRaw, widthTiles, heightPx)
            1 -> xorBuffers(chunkTwoRaw, chunkOneFinal)
            2 -> xorBuffers(diffDecode(chunkTwoRaw, widthTiles, heightPx), chunkOneFinal)
            else -> throw MalformedSpriteException("unpack mode $mode")
        }

        // buffer1 is always the low bitplane and buffer2 the high one, once
        // interlaced (`InterlaceMergeSpriteBuffers`) — whichever chunk that
        // physically is depends on the bit read right at the start.
        val lowPlane = if (firstChunkIsBufferTwo) chunkTwoFinal else chunkOneFinal
        val highPlane = if (firstChunkIsBufferTwo) chunkOneFinal else chunkTwoFinal

        val widthPx = widthTiles * 8
        val pixels = IntArray(widthPx * heightPx)
        for (row in 0 until heightPx) {
            for (col in 0 until widthTiles) {
                val byteIndex = col * heightPx + row
                val lowByte = lowPlane[byteIndex].toInt() and 0xFF
                val highByte = highPlane[byteIndex].toInt() and 0xFF
                for (bit in 0 until 8) {
                    val shift = 7 - bit
                    val lo = (lowByte shr shift) and 1
                    val hi = (highByte shr shift) and 1
                    pixels[row * widthPx + col * 8 + bit] = (hi shl 1) or lo
                }
            }
        }
        return DecodedSprite(widthPx, heightPx, pixels)
    }

    /**
     * Decodes one chunk's raw bitstream into its column-major byte buffer —
     * `UncompressSpriteDataLoop`'s `.startDecompression` through
     * `.allColumnsDone`, before any differential decode or XOR.
     *
     * The buffer is `widthTiles * heightPx` bytes, one byte per (tile
     * column, pixel row): byte `col * heightPx + row` holds that row's 8
     * pixels for that column, one bit each, high bit leftmost. Bits arrive
     * in an unusual order for that reason — not row by row, but in four
     * passes over the column's rows, each pass filling one 2-bit-wide slice
     * of every byte in the column (bits 7:6 first, then 5:4, 3:2, 1:0) —
     * because the stream is read two pixels at a time.
     */
    private fun decodeChunk(reader: BitReader, widthTiles: Int, heightPx: Int): ByteArray {
        val buffer = ByteArray(widthTiles * heightPx)
        val totalPairs = widthTiles * 4 * heightPx

        fun writePairAt(position: Int, pair: Int) {
            val tileColumn = position / (4 * heightPx)
            val phase = (position / heightPx) % 4
            val row = position % heightPx
            val shift = 6 - phase * 2
            val index = tileColumn * heightPx + row
            buffer[index] = (buffer[index].toInt() or (pair shl shift)).toByte()
        }

        fun readZeroRunLength(): Int {
            // The number of leading 1-bits (read until a terminating 0)
            // says how many more bits form the run length, offset so every
            // length is uniquely representable: `LengthEncodingOffsetList`.
            var leadingOnes = 0
            while (reader.readBit() == 1) leadingOnes++
            val offset = (1 shl (leadingOnes + 1)) - 1
            var value = 0
            repeat(leadingOnes + 1) { value = (value shl 1) or reader.readBit() }
            return offset + value
        }

        var pos = 0
        // The very first pair of the chunk is the one case where "starts
        // with zeroes" is signalled by a single bit rather than by reading
        // a pair and finding it is `00`.
        var pendingZeroRun: Int? = if (reader.readBit() == 0) readZeroRunLength() else null

        while (pos < totalPairs) {
            val run = pendingZeroRun
            if (run != null) {
                var remaining = run
                while (remaining > 0 && pos < totalPairs) {
                    writePairAt(pos, 0)
                    pos++
                    remaining--
                }
                pendingZeroRun = null
                continue
            }
            if (pos >= totalPairs) break
            val high = reader.readBit()
            val low = reader.readBit()
            val pair = (high shl 1) or low
            if (pair == 0) {
                pendingZeroRun = readZeroRunLength()
            } else {
                writePairAt(pos, pair)
                pos++
            }
        }
        return buffer
    }

    /** `0` -> mode 0; `10` -> mode 1; `11` -> mode 2. */
    private fun readUnpackMode(reader: BitReader): Int {
        if (reader.readBit() == 0) return 0
        return reader.readBit() + 1
    }

    /**
     * `SpriteDifferentialDecode`, walking the column-major buffer in
     * row-major order: bit 0 of the previous decoded nibble decides which
     * of the two lookup tables the next nibble reads from, so decoding one
     * row runs left to right across every tile column before moving to the
     * next row.
     */
    private fun diffDecode(raw: ByteArray, widthTiles: Int, heightPx: Int): ByteArray {
        val out = raw.copyOf()
        for (row in 0 until heightPx) {
            // The last-decoded-nibble state starts over at 0 for every row:
            // it tracks horizontal continuity across one row's tile columns,
            // not down a column.
            var state = 0
            for (col in 0 until widthTiles) {
                val index = col * heightPx + row
                val byte = out[index].toInt() and 0xFF
                val decodedHigh = decodeNibble((byte shr 4) and 0xF, state)
                state = decodedHigh
                val decodedLow = decodeNibble(byte and 0xF, state)
                state = decodedLow
                out[index] = (((decodedHigh shl 4) or decodedLow) and 0xFF).toByte()
            }
        }
        return out
    }

    /** `DifferentialDecodeNybble`, not-flipped tables only — see the class doc. */
    private fun decodeNibble(nibble: Int, previousDecoded: Int): Int {
        val tableIndex = nibble shr 1
        val entry = if ((previousDecoded and 1) == 1) DECODE_TABLE_1[tableIndex] else DECODE_TABLE_0[tableIndex]
        return if ((nibble and 1) == 1) entry and 0xF else (entry shr 4) and 0xF
    }

    private fun xorBuffers(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size)
        for (i in a.indices) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }

    /** Reads a compressed sprite's bitstream MSB-first, one bit at a time, refilling a byte at a time. */
    private class BitReader(private val data: ByteArray, offset: Int) {
        private var pos = offset
        private var curByte = 0
        private var bitsLeft = 0

        fun readByte(): Int {
            if (pos !in data.indices) throw MalformedSpriteException("read past the end of the ROM")
            return data[pos++].toInt() and 0xFF
        }

        fun readBit(): Int {
            if (bitsLeft == 0) {
                curByte = readByte()
                bitsLeft = 8
            }
            bitsLeft--
            val bit = (curByte shr 7) and 1
            curByte = (curByte shl 1) and 0xFF
            return bit
        }
    }

    /** `DecodeNybble0Table`, each entry `(hi shl 4) or lo`. */
    private val DECODE_TABLE_0 = intArrayOf(0x01, 0x32, 0x76, 0x45, 0xFE, 0xCD, 0x89, 0xBA)

    /** `DecodeNybble1Table` — the same table, rotated by half. */
    private val DECODE_TABLE_1 = intArrayOf(0xFE, 0xCD, 0x89, 0xBA, 0x01, 0x32, 0x76, 0x45)

    /** `PIC_WIDTH`/`PIC_HEIGHT`: the sprite buffer is at most 7x7 tiles. */
    private const val PIC_DIMENSION_MAX = 7
}
