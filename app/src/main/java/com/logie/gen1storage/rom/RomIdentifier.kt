package com.logie.gen1storage.rom

/**
 * Which specific game a ROM's bytes are — not just which engine family, which
 * [Gen1RomLocator] and [Gen2RomLocator] already confirm on their own, but
 * which of that family's own games this particular one is. Red and Blue
 * share one identical base-stats table and sprite bank layout; so do Gold
 * and Silver. Structure alone cannot tell those pairs apart, so this also
 * reads the Game Boy header's own title — `POKEMON RED`, `POKEMON BLUE`,
 * `POKEMON YELLOW`, the `GLD`/`SLV` Gold and Silver's own titles are built
 * from, `CRYSTAL` — the one thing in the file that says which cartridge it
 * was dumped from, confirmed against a real dump of each of the six.
 *
 * A ROM answers to a [RomVersion] only once both agree: the engine this app
 * already trusts finds its own data at the shape and stride that engine
 * uses, *and* the header names the same game that shape belongs to. A file
 * renamed to hide what it is still identifies correctly, because nothing
 * here reads its name; a file that is neither — or is missing its title —
 * identifies as nothing rather than a guess.
 */
object RomIdentifier {

    /** [bytes]' own [RomVersion], or null when nothing this app checks agrees it is one. */
    fun identify(bytes: ByteArray): RomVersion? = when (bytes.size) {
        GEN1_ROM_SIZE -> identifyGen1(bytes)
        GEN2_ROM_SIZE -> identifyGen2(bytes)
        else -> null
    }

    private fun identifyGen1(bytes: ByteArray): RomVersion? {
        val title = titleOf(bytes)
        if (title.contains("YELLOW") && Gen1RomLocator.locate(bytes, Gen1RomLocator.Gen1Game.YELLOW) != null) {
            return RomVersion.YELLOW
        }
        if (Gen1RomLocator.locate(bytes, Gen1RomLocator.Gen1Game.RED_BLUE) == null) return null
        return when {
            title.contains("RED") -> RomVersion.RED
            title.contains("BLUE") -> RomVersion.BLUE
            else -> null
        }
    }

    private fun identifyGen2(bytes: ByteArray): RomVersion? {
        val title = titleOf(bytes)
        if (title.contains("CRYSTAL") && Gen2RomLocator.locate(bytes, Gen2RomLocator.Gen2Game.CRYSTAL) != null) {
            return RomVersion.CRYSTAL
        }
        if (Gen2RomLocator.locate(bytes, Gen2RomLocator.Gen2Game.GOLD_SILVER) == null) return null
        return when {
            title.contains("GLD") -> RomVersion.GOLD
            title.contains("SLV") -> RomVersion.SILVER
            else -> null
        }
    }

    /** The cartridge header's own title, 0x134-0x143 — confirmed against real Red, Blue, Yellow, Gold, Silver, and Crystal dumps. */
    private fun titleOf(bytes: ByteArray): String {
        if (bytes.size < TITLE_END) return ""
        return bytes.copyOfRange(TITLE_START, TITLE_END)
            .takeWhile { it.toInt() != 0 }
            .map { (it.toInt() and 0xFF).toChar() }
            .joinToString("")
    }

    private const val TITLE_START = 0x134
    private const val TITLE_END = 0x144

    private const val GEN1_ROM_SIZE = 1_048_576
    private const val GEN2_ROM_SIZE = 2_097_152
}
