package com.logie.gen1storage.rom

import java.io.File

/**
 * The player's own ROM dumps, kept only on this device.
 *
 * A ROM is never fetched, never uploaded, never part of a backup this app
 * writes anywhere else — it is the one kind of file here that is squarely
 * the player's own property, in a sense a downloaded sprite sheet never is,
 * and this app's job is only to read it, not to hold on to it any harder
 * than the player asked.
 *
 * Kept as plain files rather than folded into any other store: a ROM is
 * bytes with nothing else this app tracks about them, and its own directory
 * is one a player could point at and understand ("that's my ROM, sitting
 * where I can see it") if they ever went looking.
 */
class RomStore(private val directory: File) {

    private val lock = Any()

    /** Every ROM whose tables this session has already found, so a sprite request never re-scans. */
    private val located = mutableMapOf<String, RomLocation?>()

    fun file(version: RomVersion): File = File(directory, "${version.id}.gbc")

    fun has(version: RomVersion): Boolean = file(version).isFile

    fun sizeOf(version: RomVersion): Long = file(version).takeIf(File::isFile)?.length() ?: 0L

    /**
     * Saves [bytes] as [version]'s ROM once it has been confirmed to be one —
     * see [Gen1RomLocator.locate] and [Gen2RomLocator.locate] — and returns
     * whether it was. Nothing is written for bytes this app cannot find its
     * own data inside, because a file this app cannot read is not one it
     * should keep.
     */
    fun import(version: RomVersion, bytes: ByteArray): Boolean = synchronized(lock) {
        val found = locate(version, bytes) ?: return false
        directory.mkdirs()
        val staged = File(directory, "${version.id}.gbc.part")
        staged.writeBytes(bytes)
        val target = file(version)
        if (!staged.renameTo(target)) {
            staged.delete()
            error("Could not save the ROM")
        }
        located[version.id] = found
        true
    }

    fun delete(version: RomVersion): Unit = synchronized(lock) {
        file(version).delete()
        located.remove(version.id)
        Unit
    }

    /**
     * This species' front sprite, read out of [version]'s ROM — null when
     * there is no such ROM here, its tables cannot be found (a corrupted or
     * unrelated file), or that species isn't in them (Mew in Red/Blue's
     * standalone record still resolves; Unown in Generation II does not —
     * see [Gen1RomLocator] and [Gen2RomLocator]).
     */
    fun frontSprite(version: RomVersion, speciesId: String): Gen1SpriteCodec.DecodedSprite? = synchronized(lock) {
        val bytes = file(version).takeIf(File::isFile)?.readBytes() ?: return null
        val found = located.getOrPut(version.id) { locate(version, bytes) } ?: return null
        when (found) {
            is RomLocation.Gen1 -> Gen1RomLocator.frontSprite(bytes, found.located, speciesId, version.gen1Game!!)
            is RomLocation.Gen2 -> Gen2RomLocator.frontSprite(bytes, found.located, speciesId, version.gen2Game!!)
        }
    }

    private fun locate(version: RomVersion, bytes: ByteArray): RomLocation? = when (version.generation) {
        1 -> Gen1RomLocator.locate(bytes, version.gen1Game!!)?.let { RomLocation.Gen1(it) }
        else -> Gen2RomLocator.locate(bytes, version.gen2Game!!)?.let { RomLocation.Gen2(it) }
    }
}

/** Where a ROM's tables were found — one shape per generation's own locator. */
private sealed interface RomLocation {
    data class Gen1(val located: Gen1RomLocator.Located) : RomLocation
    data class Gen2(val located: Gen2RomLocator.Located) : RomLocation
}

/**
 * The ROMs this app knows how to read a sprite out of directly, without a
 * download — Crystal is not one of them yet; see [Gen2RomLocator]'s own doc.
 */
enum class RomVersion(
    val id: String,
    val label: String,
    val generation: Int,
    val gen1Game: Gen1RomLocator.Gen1Game? = null,
    val gen2Game: Gen2RomLocator.Gen2Game? = null,
) {
    RED("red", "RED", 1, gen1Game = Gen1RomLocator.Gen1Game.RED_BLUE),
    BLUE("blue", "BLUE", 1, gen1Game = Gen1RomLocator.Gen1Game.RED_BLUE),
    YELLOW("yellow", "YELLOW", 1, gen1Game = Gen1RomLocator.Gen1Game.YELLOW),
    GOLD("gold", "GOLD", 2, gen2Game = Gen2RomLocator.Gen2Game.GOLD_SILVER),
    SILVER("silver", "SILVER", 2, gen2Game = Gen2RomLocator.Gen2Game.GOLD_SILVER),
}
