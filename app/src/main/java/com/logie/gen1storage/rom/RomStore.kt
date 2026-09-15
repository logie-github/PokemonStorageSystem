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

    /** Every ROM whose base-stats table this session has already found, so a sprite request never re-scans. */
    private val located = mutableMapOf<String, Gen1RomLocator.Located?>()

    fun file(version: RomVersion): File = File(directory, "${version.id}.gbc")

    fun has(version: RomVersion): Boolean = file(version).isFile

    fun sizeOf(version: RomVersion): Long = file(version).takeIf(File::isFile)?.length() ?: 0L

    /**
     * Saves [bytes] as [version]'s ROM once it has been confirmed to be one —
     * see [Gen1RomLocator.locate] — and returns that confirmation. Nothing is
     * written for bytes this app cannot find its own data inside, because a
     * file this app cannot read is not one it should keep.
     */
    fun import(version: RomVersion, bytes: ByteArray): Gen1RomLocator.Located? = synchronized(lock) {
        val found = Gen1RomLocator.locate(bytes) ?: return null
        directory.mkdirs()
        val staged = File(directory, "${version.id}.gbc.part")
        staged.writeBytes(bytes)
        val target = file(version)
        if (!staged.renameTo(target)) {
            staged.delete()
            error("Could not save the ROM")
        }
        located[version.id] = found
        found
    }

    fun delete(version: RomVersion) = synchronized(lock) {
        file(version).delete()
        located.remove(version.id)
    }

    /**
     * This species' front sprite, read out of [version]'s ROM — null when
     * there is no such ROM here, its base-stats table cannot be found (a
     * corrupted or unrelated file), or that species isn't in it.
     */
    fun frontSprite(version: RomVersion, speciesId: String): Gen1SpriteCodec.DecodedSprite? = synchronized(lock) {
        val bytes = file(version).takeIf(File::isFile)?.readBytes() ?: return null
        val found = located.getOrPut(version.id) { Gen1RomLocator.locate(bytes) } ?: return null
        Gen1RomLocator.frontSprite(bytes, found, speciesId)
    }
}

/** The Generation I ROMs this app knows how to read a sprite out of. Yellow is not one of them yet. */
enum class RomVersion(val id: String, val label: String) {
    RED("red", "RED"),
    BLUE("blue", "BLUE"),
}
