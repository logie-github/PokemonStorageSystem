package com.logie.gen1storage.storage

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asTable
import java.io.File
import java.io.RandomAccessFile

/**
 * Where a Pokémon has been, kept for the ones that are not in the PC.
 *
 * A Pokémon's history lives on the Pokémon while the app is holding it — see
 * [StoredPokemon.lineage] — and there is nowhere to keep it the moment that
 * Pokémon is written into a cartridge and taken out of the boxes. So it is
 * copied here on the way out and read back on the way in, which is what makes
 * a Pokémon that has been to Red, then Crystal, then Gold arrive in the PC the
 * third time still knowing about the first two.
 *
 * Keyed on the tag the app wrote into the Pokémon itself, with the derived
 * identity ([Lineage.identityOf]) as a second key for one that comes back
 * without it — a save restored from a backup taken before the tag was written,
 * a cartridge the tag did not survive.
 *
 * This is written down rather than worked out, and it is small: a name, a
 * game and a date per stop. It travels in the account backup with the boxes,
 * because a history that did not survive a new phone would not be a history.
 */
class LineageBook(private val directory: File) {

    private val file = File(directory, FILE_NAME)
    private val lock = Any()

    private var entries: MutableMap<String, Lineage> = LinkedHashMap()

    /** Derived identity -> tag, for a Pokémon that came back unmarked. */
    private var byIdentity: MutableMap<String, String> = LinkedHashMap()
    private var loaded = false

    /** The history kept under this tag, if the app has ever written one. */
    fun of(tag: String): Lineage? = synchronized(lock) {
        ensureLoaded()
        entries[tag]
    }

    /** The history of a Pokémon that arrived without its tag. */
    fun byIdentity(identity: String): Lineage? = synchronized(lock) {
        ensureLoaded()
        byIdentity[identity]?.let { entries[it] }
    }

    fun all(): List<Lineage> = synchronized(lock) {
        ensureLoaded()
        entries.values.toList()
    }

    /**
     * Writes one down, and remembers what it looks like without its tag.
     *
     * [identity] is the Pokémon as it was at this moment, so a later arrival
     * that has since levelled up still matches: the derived identity is built
     * only from things a Pokémon cannot change about itself.
     */
    fun record(lineage: Lineage, identity: String?) = synchronized(lock) {
        ensureLoaded()
        entries[lineage.tag] = lineage
        identity?.let { byIdentity[it] = lineage.tag }
        prune(keep = lineage.tag)
        persist()
    }

    /** Drops one, for a Pokémon that is back in the PC and carrying its own. */
    fun forget(tag: String) = synchronized(lock) {
        ensureLoaded()
        val went = entries.remove(tag) != null
        val unnamed = byIdentity.values.removeAll { it == tag }
        if (went || unnamed) persist()
    }

    fun clear() = synchronized(lock) {
        entries = LinkedHashMap()
        byIdentity = LinkedHashMap()
        file.delete()
        loaded = true
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        entries = LinkedHashMap()
        byIdentity = LinkedHashMap()
        if (!file.isFile || file.length() == 0L) return
        val root = runCatching { LuaParser.parse(LuaText.decode(file.readBytes())) }
            .getOrNull() ?: return
        root["lineages"].asTable()?.entries()?.forEach { (key, value) ->
            val tag = (key as? LuaKey.Name)?.value ?: return@forEach
            val table = value.asTable() ?: return@forEach
            Lineage.fromLua(table)?.let { entries[tag] = it }
        }
        root["identities"].asTable()?.entries()?.forEach { (key, value) ->
            val identity = (key as? LuaKey.Name)?.value ?: return@forEach
            (value as? LuaValue.Str)?.value?.let { byIdentity[identity] = it }
        }
    }

    /**
     * The file is capped rather than pruned by age.
     *
     * A history is worth more the older it is — "where did this one come
     * from" is a question about its first hop, not its last — so nothing is
     * dropped for being old. What is dropped, once there are more records
     * than anybody has Pokémon, is whatever was written longest ago.
     */
    private fun prune(keep: String) {
        if (entries.size <= KEEP_MOST) return
        val survivors = entries.keys.toList().takeLast(KEEP_MOST).toMutableSet()
        survivors += keep
        entries.keys.retainAll(survivors)
        byIdentity.values.retainAll(survivors)
    }

    private fun persist() {
        directory.mkdirs()
        val lineages = LuaValue.Table()
        entries.forEach { (tag, lineage) -> lineages[tag] = lineage.toLua() }
        val identities = LuaValue.Table()
        byIdentity.forEach { (identity, tag) -> identities[identity] = LuaValue.Str(tag) }
        val root = LuaValue.Table().apply {
            this["lineages"] = lineages
            this["identities"] = identities
        }
        val bytes = LuaText.encode(LuaWriter.encode(root))
        runCatching {
            RandomAccessFile(file, "rw").use { sink ->
                sink.setLength(0)
                sink.write(bytes)
                sink.fd.sync()
            }
        }
    }

    companion object {
        const val FILE_NAME = "lineages.lua"

        /** More than anyone will ever have, and small enough to carry. */
        private const val KEEP_MOST = 5_000
    }
}
