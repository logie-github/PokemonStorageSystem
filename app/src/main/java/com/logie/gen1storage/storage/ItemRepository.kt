package com.logie.gen1storage.storage

import com.logie.gen1storage.gen1recomp.Gen1Items
import com.logie.gen1storage.gen1recomp.ItemStack
import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asTable
import java.io.File
import java.io.RandomAccessFile

/**
 * This app's own item PC.
 *
 * The same shape as a save's item table — a count against a name — written
 * with the same serializer and staged the same way as the Pokémon file, so a
 * process death mid-write cannot leave it unreadable.
 *
 * Kept separate from [StorageRepository] rather than folded into it: a
 * Pokémon is a thing with a uid whose identity has to be tracked across a
 * transfer, and an item is a number. Sharing a file would only mean one lock
 * over two unrelated things.
 */
class ItemRepository(private val directory: File) {

    private val file = File(directory, FILE_NAME)
    private val backup = File(directory, "$FILE_NAME.bak")
    private val staged = File(directory, "$FILE_NAME.tmp")

    private val lock = Any()
    private var items = LuaValue.Table()
    private var loaded = false

    fun state(): List<ItemStack> = synchronized(lock) {
        ensureLoaded()
        Gen1Items.read(items)
    }

    fun count(id: String): Int = synchronized(lock) {
        ensureLoaded()
        Gen1Items.read(items).firstOrNull { it.id == id }?.count ?: 0
    }

    val total: Int get() = state().sumOf { it.count }

    /** Adds what it can, and says how many that was. */
    fun add(id: String, count: Int): Int = synchronized(lock) {
        ensureLoaded()
        val moved = Gen1Items.add(items, id, count)
        if (moved > 0) persist()
        moved
    }

    /** Takes what is there, and says how many that was. */
    fun remove(id: String, count: Int): Int = synchronized(lock) {
        ensureLoaded()
        val taken = Gen1Items.remove(items, id, count)
        if (taken > 0) persist()
        taken
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val root = read(file) ?: read(staged) ?: read(backup)
        items = root?.get("items").asTable() ?: LuaValue.Table()
    }

    private fun read(source: File): LuaValue.Table? {
        if (!source.isFile || source.length() == 0L) return null
        return runCatching { LuaParser.parse(LuaText.decode(source.readBytes())) }.getOrNull()
    }

    private fun persist() {
        val root = LuaValue.Table()
        root["items"] = items
        val bytes = LuaText.encode(LuaWriter.encode(root))
        directory.mkdirs()
        RandomAccessFile(staged, "rw").use { sink ->
            sink.setLength(0)
            sink.write(bytes)
            sink.fd.sync()
        }
        if (file.isFile) {
            backup.delete()
            file.copyTo(backup, overwrite = true)
        }
        if (!staged.renameTo(file)) {
            file.delete()
            check(staged.renameTo(file)) { "Could not commit the item file" }
        }
    }

    companion object {
        const val FILE_NAME = "items.lua"
    }
}
