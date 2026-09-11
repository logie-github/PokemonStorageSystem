package com.logie.gen1storage.storage

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr

/**
 * The whole PC as one Lua file, out and back in again.
 *
 * The same serializer the saves and the storage file use, so an export is
 * readable by the same parser this project tests to byte equality and by a
 * person with a text editor. Nothing about a Pokémon is summarised or
 * rewritten on the way out: the Generation I data goes across exactly as it is
 * held, which is what makes an import a restoration rather than a rebuild.
 *
 * The uid travels with each Pokémon, and that is what makes a re-import safe.
 * A uid already in the PC is the same Pokémon, so it is skipped rather than
 * placed again — importing the same file twice leaves one of each.
 */
object StorageArchive {

    const val FORMAT = "gen1storage-export"
    const val VERSION = 1

    /** One Pokémon in an archive, and the spot it was on. */
    data class Entry(val box: Int, val slot: Int, val stored: StoredPokemon)

    data class Archive(
        val version: Int,
        val exportedAtEpochMillis: Long?,
        val entries: List<Entry>,
        val boxNames: Map<Int, String>,
        /** Entries the file held that could not be read back. */
        val unreadable: Int,
    )

    fun encode(state: StorageState, now: Long): ByteArray {
        val root = LuaValue.Table()
        root["format"] = luaStr(FORMAT)
        root["version"] = luaNum(VERSION.toDouble())
        root["exportedAtEpochMillis"] = luaNum(now.toDouble())

        val entries = mutableListOf<LuaValue>()
        val names = LuaValue.Table()
        var named = false
        state.boxes.forEach { box ->
            box.name?.takeIf { it.isNotBlank() }?.let { names[box.index] = luaStr(it); named = true }
            box.slots.forEachIndexed { slot, stored ->
                if (stored == null) return@forEachIndexed
                entries += stored.toLua().apply {
                    this["box"] = luaNum(box.index.toDouble())
                    this["slot"] = luaNum((slot + 1).toDouble())
                }
            }
        }
        root["pokemon"] = LuaValue.Table.ofArray(entries)
        if (named) root["boxNames"] = names
        return LuaText.encode(LuaWriter.encode(root))
    }

    /**
     * Reads an archive back.
     *
     * Refuses a file that is not one of ours rather than guessing: a Lua table
     * from somewhere else could parse cleanly and still mean nothing here, and
     * an import that half-understands its input is exactly the way a Pokémon
     * gets quietly altered.
     */
    fun decode(bytes: ByteArray): Result<Archive> {
        val root = try {
            LuaParser.parse(LuaText.decode(bytes))
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("THAT FILE COULD NOT BE READ"))
        }
        if (root["format"].asString() != FORMAT) {
            return Result.failure(IllegalArgumentException("THAT IS NOT A STORAGE EXPORT"))
        }
        val version = root["version"].asInt() ?: 0
        if (version > VERSION) {
            return Result.failure(
                IllegalArgumentException("THAT EXPORT IS NEWER THAN THIS APP")
            )
        }

        var unreadable = 0
        val entries = root["pokemon"].asTable()?.array().orEmpty().mapNotNull { value ->
            val table = value.asTable()
            val stored = table?.let(StoredPokemon::fromLua)
            if (table == null || stored == null) {
                unreadable++
                null
            } else {
                Entry(
                    box = (table["box"].asInt() ?: 1).coerceIn(1, StorageLayout.BOX_COUNT),
                    // An export from before boxes were a grid has no slot; 0
                    // is out of range, so it falls through to the first free
                    // spot rather than landing somewhere arbitrary.
                    slot = (table["slot"].asInt() ?: 0).coerceIn(0, StorageLayout.BOX_CAPACITY),
                    stored = stored,
                )
            }
        }

        val names = LinkedHashMap<Int, String>()
        root["boxNames"].asTable()?.entries()?.forEach { (key, value) ->
            val index = (key as? LuaKey.Index)?.value?.toInt() ?: return@forEach
            value.asString()?.takeIf { it.isNotBlank() }?.let { names[index] = it }
        }

        return Result.success(
            Archive(
                version = version,
                exportedAtEpochMillis = (root["exportedAtEpochMillis"] as? LuaValue.Num)
                    ?.value?.toLong(),
                entries = entries,
                boxNames = names,
                unreadable = unreadable,
            )
        )
    }
}

/** What an import did, said plainly enough to put in a message window. */
data class ImportReport(
    val added: Int,
    /** Already in the PC, by uid. */
    val skipped: Int,
    /** Read fine, but the PC had no room left. */
    val unplaced: Int,
    /** Entries the file held that could not be read back. */
    val unreadable: Int,
)
