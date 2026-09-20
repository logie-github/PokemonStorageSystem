package com.logie.gen1storage.mods

/**
 * What a mod is never allowed to carry, whatever it looks like.
 *
 * A recoloured sprite is a matter of taste; a dumped cartridge, or another
 * game's own packed art format, is somebody else's copyrighted work along
 * for the ride. This is the same table
 * https://github.com/1Jamie/mod-scanner checks a decompilation mod's own
 * release against, trimmed to nothing this app cannot run into: every
 * console ROM/disc format still applies (a mod that ships one is exactly as
 * unwelcome whether or not this app could read it), and the deep-signature
 * scan is limited to formats a Game Boy mod would plausibly hide one of
 * these inside.
 */
object ModBinaryRules {

    data class MagicByteRule(val name: String, val offset: Int, val bytes: ByteArray)
    data class ContainedSignature(val name: String, val bytes: ByteArray)

    /** File extensions no legitimate cosmetic mod ever has a reason to carry. */
    val BLACKLISTED_EXTENSIONS: Set<String> = setOf(
        // Retro Nintendo ROM formats.
        ".nes", ".fds", ".sfc", ".smc", ".swc", ".fig", ".gb", ".gbc", ".sgb",
        ".gba", ".z64", ".n64", ".v64",
        // GameCube, Wii & Wii U disc/image formats.
        ".iso", ".gcm", ".tgp", ".gcz", ".rvz", ".wia", ".wbfs", ".ciso",
        ".wud", ".wux", ".wua", ".wad", ".dol", ".rel", ".rpx", ".rpl", ".nus",
        // Nintendo DS & 3DS formats.
        ".nds", ".dsi", ".srl", ".3ds", ".3dsx", ".cia", ".cxi", ".app", ".smdh",
        // Nintendo Switch formats.
        ".nsp", ".xci", ".nsz", ".xcz", ".nca", ".nro", ".nso", ".kip",
        // Proprietary container & asset formats.
        ".fsys", ".rarc", ".tpl", ".bti", ".brres", ".garc", ".darc", ".dsp",
        ".brstm", ".bcstm", ".bfstm", ".hps", ".musyx", ".sdat",
    )

    /** Extensions a "does this binary blob contain a known signature" scan would only waste time on. */
    val TEXT_SOURCE_EXTENSIONS: Set<String> = setOf(
        ".lua", ".py", ".md", ".txt", ".json", ".yml", ".yaml", ".toml", ".ini",
        ".c", ".h", ".cpp", ".hpp", ".rs", ".go", ".js", ".ts", ".html", ".css",
        ".patch", ".diff", ".log", ".csv", ".tsv", ".xml", ".svg",
    )

    private fun hex(hexString: String): ByteArray =
        ByteArray(hexString.length / 2) { i -> hexString.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    val MAGIC_BYTES: List<MagicByteRule> = listOf(
        MagicByteRule("NES ROM Header (iNES)", 0x0000, hex("4e45531a")),
        MagicByteRule("Famicom Disk System Header", 0x0000, hex("4644531a")),
        MagicByteRule("Game Boy / GBC Nintendo Logo", 0x0104, hex("ceed6666cc0d000b03730083000c000d0008111f8889000eaccf")),
        MagicByteRule("GBA Nintendo Logo Header", 0x0004, hex("24ffae51699aa2213d84820a84e409ad")),
        MagicByteRule("N64 ROM (Big Endian)", 0x0000, hex("80371240")),
        MagicByteRule("N64 ROM (Byte-swapped)", 0x0000, hex("37804012")),
        MagicByteRule("N64 ROM (Little Endian)", 0x0000, hex("40123780")),
        MagicByteRule("GameCube Disc Magic", 0x001C, hex("c2339f3d")),
        MagicByteRule("Wii Disc Magic", 0x0018, hex("5d1c9ea3")),
        MagicByteRule("Dolphin RVZ Compressed Disc Container", 0x0000, ascii("RVZ\u0001")),
        MagicByteRule("Wii WIA Compressed Disc Container", 0x0000, ascii("WIA\u0001")),
        MagicByteRule("GameCube GCZ Compressed Container", 0x0000, ascii("GCZ")),
        MagicByteRule("Wii WBFS Disc Container", 0x0000, ascii("WBFS")),
        MagicByteRule("GameCube Genius Sonority FSYS Container", 0x0000, ascii("FSYS")),
        MagicByteRule("GameCube / Wii RARC Archive", 0x0000, ascii("RARC")),
        MagicByteRule("Nintendo U8 Archive", 0x0000, hex("55aa382d")),
        MagicByteRule("GameCube Texture Palette Library (TPL)", 0x0000, hex("0020af30")),
        MagicByteRule("GameCube HPS Audio Stream", 0x0000, ascii("HPS1")),
        MagicByteRule("Nintendo DS Logo Header", 0x00C0, hex("24ffae51699aa2213d84820a84e409ad")),
        MagicByteRule("Nintendo DS Logo CRC", 0x015C, hex("cf56")),
        MagicByteRule("Nintendo DS Sound Data (SDAT)", 0x0000, ascii("SDAT")),
        MagicByteRule("3DS NCCH Partition", 0x0100, ascii("NCCH")),
        MagicByteRule("3DS NCSD Header", 0x0100, ascii("NCSD")),
        MagicByteRule("3DS Executable (3DSX)", 0x0000, ascii("3DSX")),
        MagicByteRule("3DS System Menu Data Header (SMDH)", 0x0000, ascii("SMDH")),
        MagicByteRule("3DS General Archive (GARC)", 0x0000, ascii("GARC")),
        MagicByteRule("Nintendo Switch NSP / PFS0 Container", 0x0000, ascii("PFS0")),
        MagicByteRule("Nintendo Switch XCI / HFS0 Container", 0x0000, ascii("HFS0")),
        MagicByteRule("Nintendo Switch Content Archive (NCA)", 0x0200, ascii("NCA")),
    )

    val CONTAINED_SIGNATURES: List<ContainedSignature> = listOf(
        ContainedSignature("NKit GameCube/Wii Recovery Data", ascii("NKIT")),
        ContainedSignature("Dolphin RVZ Disc Container", ascii("RVZ\u0001")),
        ContainedSignature("Wii WBFS Disc Container", ascii("WBFS")),
        ContainedSignature("GameCube Genius Sonority FSYS Container", ascii("FSYS")),
        ContainedSignature("GameCube / Wii RARC Archive", ascii("RARC")),
        ContainedSignature("GameCube / Wii Binary Resource (BRRES)", ascii("bres")),
        ContainedSignature("MusyX Audio Engine / Soundbank", ascii("MusyX")),
        ContainedSignature("Nintendo 3DS Binary Resource (.bcres)", ascii(".bcres")),
        ContainedSignature("Nintendo 3DS Binary Model (.bcmdl)", ascii(".bcmdl")),
        ContainedSignature("Nintendo 3DS Binary Texture (.bctex)", ascii(".bctex")),
        ContainedSignature("Nintendo 3DS CGFX Model Header", ascii("CGFX")),
        ContainedSignature("Nintendo 3DS BCH Model Header", hex("42434800")),
        ContainedSignature("NintendoWare for CTR (3DS)", ascii("NW4C")),
        ContainedSignature("Nintendo Switch Binary Texture (BNTX)", ascii("BNTX")),
        ContainedSignature("Nintendo Switch / Wii U Binary Resource (.bfres)", ascii(".bfres")),
        ContainedSignature("Nintendo SARC Archive", ascii("SARC")),
        ContainedSignature("Nintendo Yaz0 Compressed Stream", ascii("Yaz0")),
        ContainedSignature("Nintendo DS NARC Archive", ascii("NARC")),
        ContainedSignature("Nintendo DS Character Graphics (NCGR)", ascii("NCGR")),
        ContainedSignature("Nintendo DS Color Palette (NCLR)", ascii("NCLR")),
        ContainedSignature("Nintendo DS Screen Resource (NSCR)", ascii("NSCR")),
        ContainedSignature("Nintendo 3DS RomFS IVFC", ascii("IVFC")),
    )

    fun extensionOf(filename: String): String {
        val dot = filename.lastIndexOf('.')
        return if (dot < 0) "" else filename.substring(dot).lowercase()
    }

    /**
     * The blacklist, the header table, and — for anything that is not
     * plainly source or config text — the deep signature scan, against the
     * first [headerChunk] of a file. Null when none of them have anything
     * to say about it.
     *
     * Takes just the leading bytes rather than a whole file on purpose: a
     * console ROM's own header lives in its first kilobyte at most, and a
     * mod's own scan should not have to hold a multi-megabyte file in
     * memory to clear it.
     */
    fun checkHeader(filename: String, headerChunk: ByteArray): ModViolation? {
        val ext = extensionOf(filename)
        if (ext in BLACKLISTED_EXTENSIONS) {
            return ModViolation(
                filename,
                "Blacklisted Extension",
                "'$filename' has a prohibited console ROM/container extension '$ext'.",
            )
        }
        if (headerChunk.isEmpty()) return null

        for (rule in MAGIC_BYTES) {
            val end = rule.offset + rule.bytes.size
            if (headerChunk.size < end) continue
            if (regionMatches(headerChunk, rule.offset, rule.bytes)) {
                return ModViolation(
                    filename,
                    rule.name,
                    "'$filename' matches the console ROM header signature '${rule.name}'.",
                )
            }
        }

        if (ext !in TEXT_SOURCE_EXTENSIONS) {
            for (signature in CONTAINED_SIGNATURES) {
                if (indexOf(headerChunk, signature.bytes) >= 0) {
                    return ModViolation(
                        filename,
                        signature.name,
                        "'$filename' contains the prohibited proprietary asset signature '${signature.name}'.",
                    )
                }
            }
        }
        return null
    }

    private fun regionMatches(haystack: ByteArray, offset: Int, needle: ByteArray): Boolean {
        for (i in needle.indices) {
            if (haystack[offset + i] != needle[i]) return false
        }
        return true
    }

    /** A plain byte-string search; the header chunk this runs against is never more than tens of KB. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }
}

/** One file a scan would not let through, and why. */
data class ModViolation(val filename: String, val ruleName: String, val reason: String)
