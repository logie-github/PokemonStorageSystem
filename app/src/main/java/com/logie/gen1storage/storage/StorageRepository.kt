package com.logie.gen1storage.storage

import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** The whole of this app's PC at one moment. */
data class StorageState(
    val boxes: List<StorageBox>,
    val revision: Long,
) {
    val total: Int get() = boxes.sumOf { it.contents.size }
    fun find(uid: String): Pair<Int, StoredPokemon>? {
        boxes.forEach { box ->
            box.contents.firstOrNull { it.uid == uid }?.let { return box.index to it }
        }
        return null
    }
}

/**
 * This app's own Pokémon storage.
 *
 * Persistence is a single Lua-source file written with the same serializer the
 * saves use. That choice is deliberate: the format already has a parser and a
 * writer this project tests to byte equality, it needs no schema migrations or
 * code generation, it is human-readable when something goes wrong, and it runs
 * unchanged under plain JVM unit tests where a database would need a device.
 *
 * Every write is staged and renamed, and the previous good file is kept as a
 * `.bak`, so a process death mid-write can never leave the PC unreadable.
 */
class StorageRepository(private val directory: File) {

    private val file = File(directory, FILE_NAME)
    private val backup = File(directory, "$FILE_NAME.bak")
    private val staged = File(directory, "$FILE_NAME.tmp")
    private val releasedLog = File(directory, RELEASED_FILE_NAME)

    private val lock = Any()
    private var boxes: MutableList<MutableList<StoredPokemon>> = emptyBoxes()
    private var names: MutableMap<Int, String> = LinkedHashMap()
    private var revision: Long = 0
    private var loaded = false

    /** Non-fatal problems from the last load, surfaced on the diagnostics screen. */
    var loadNotes: List<String> = emptyList()
        private set

    fun state(): StorageState = synchronized(lock) {
        ensureLoaded()
        StorageState(
            boxes = (1..StorageLayout.BOX_COUNT).map { index ->
                StorageBox(index, names[index], boxes[index - 1].toList())
            },
            revision = revision,
        )
    }

    fun contains(uid: String): Boolean = synchronized(lock) {
        ensureLoaded()
        boxes.any { box -> box.any { it.uid == uid } }
    }

    fun get(uid: String): StoredPokemon? = synchronized(lock) {
        ensureLoaded()
        boxes.firstNotNullOfOrNull { box -> box.firstOrNull { it.uid == uid } }
    }

    /**
     * Deposits [data] into the first box with room, starting at [preferredBox] —
     * the same wrap-around upstream `Boxes.deposit` uses. Returns null when the
     * whole PC is full; nothing is written in that case.
     */
    fun deposit(
        data: LuaValue.Table,
        provenance: Provenance,
        preferredBox: Int = 1,
        uid: String = UUID.randomUUID().toString(),
    ): StoredPokemon? = synchronized(lock) {
        ensureLoaded()
        val start = (preferredBox - 1).coerceIn(0, StorageLayout.BOX_COUNT - 1)
        for (offset in 0 until StorageLayout.BOX_COUNT) {
            val index = (start + offset) % StorageLayout.BOX_COUNT
            if (boxes[index].size < StorageLayout.BOX_CAPACITY) {
                val stored = StoredPokemon(uid, data, provenance)
                boxes[index].add(stored)
                persist()
                return stored
            }
        }
        null
    }

    /** Removes a Pokémon by uid. Returns it, or null when it was not here. */
    fun withdraw(uid: String): StoredPokemon? = synchronized(lock) {
        ensureLoaded()
        for (box in boxes) {
            val position = box.indexOfFirst { it.uid == uid }
            if (position >= 0) {
                val removed = box.removeAt(position)
                persist()
                return removed
            }
        }
        null
    }

    /**
     * Releases a stored Pokémon: it leaves the PC, at the player's request.
     *
     * The app's rule is that it never loses a Pokémon, and a release is the one
     * place a player deliberately asks it to — so the entry is appended to a
     * plain-text log beside the storage file before it goes. Nothing reads that
     * log back; it exists so a release is recoverable by hand rather than gone.
     * Returns what was released, or null when the uid was not here.
     */
    fun release(uid: String): StoredPokemon? = synchronized(lock) {
        ensureLoaded()
        val stored = get(uid) ?: return null
        val record = LuaValue.Table()
        record["releasedAtEpochMillis"] = luaNum(System.currentTimeMillis().toDouble())
        record["pokemon"] = stored.toLua()
        // Best effort: a log that cannot be written is not a reason to refuse
        // the release the player asked for, and it is reported through the
        // load notes the next time the file is read.
        runCatching {
            directory.mkdirs()
            releasedLog.appendBytes(LuaText.encode(LuaWriter.encode(record)))
        }
        withdraw(uid)
    }

    /** How many releases the log holds, so the save files screen can say so. */
    fun releasedCount(): Int = synchronized(lock) {
        if (!releasedLog.isFile) return 0
        runCatching {
            LuaText.decode(releasedLog.readBytes()).lineSequence().count { it.startsWith("return ") }
        }.getOrDefault(0)
    }

    /** Moves a stored Pokémon to another box, for reorganising the PC. */
    fun moveTo(uid: String, targetBox: Int): Boolean = synchronized(lock) {
        ensureLoaded()
        val index = targetBox - 1
        if (index !in 0 until StorageLayout.BOX_COUNT) return false
        if (boxes[index].size >= StorageLayout.BOX_CAPACITY) return false
        for (box in boxes) {
            val position = box.indexOfFirst { it.uid == uid }
            if (position >= 0) {
                if (box === boxes[index]) return true
                boxes[index].add(box.removeAt(position))
                persist()
                return true
            }
        }
        false
    }

    fun renameBox(index: Int, name: String): Boolean = synchronized(lock) {
        ensureLoaded()
        if (index !in 1..StorageLayout.BOX_COUNT) return false
        val trimmed = name.trim().take(MAX_BOX_NAME)
        if (trimmed.isEmpty()) names.remove(index) else names[index] = trimmed
        persist()
        true
    }

    /** Forces a re-read from disk; used after an external repair. */
    fun reload() = synchronized(lock) {
        loaded = false
        ensureLoaded()
    }

    // ------- persistence

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val notes = mutableListOf<String>()
        val root = readTable(file, notes, "storage")
            ?: readTable(staged, notes, "staged storage")
            ?: readTable(backup, notes, "storage backup")
        loadNotes = notes
        if (root == null) {
            boxes = emptyBoxes()
            names = LinkedHashMap()
            revision = 0
            return
        }
        revision = (root["revision"] as? LuaValue.Num)?.value?.toLong() ?: 0
        val loadedBoxes = emptyBoxes()
        val boxesTable = root["boxes"].asTable()
        if (boxesTable != null) {
            for (index in 1..StorageLayout.BOX_COUNT) {
                boxesTable[index].asTable()?.array().orEmpty().forEach { entry ->
                    val stored = entry.asTable()?.let(StoredPokemon::fromLua)
                    if (stored == null) notes += "Dropped an unreadable entry in box $index"
                    else if (loadedBoxes[index - 1].size >= StorageLayout.BOX_CAPACITY) {
                        notes += "Box $index held more than ${StorageLayout.BOX_CAPACITY}; the overflow was moved"
                        spill(loadedBoxes, stored, notes)
                    } else loadedBoxes[index - 1].add(stored)
                }
            }
        }
        // A uid must be unique: it is what a transfer uses to prove the app
        // holds exactly one authoritative copy.
        val seen = HashSet<String>()
        loadedBoxes.forEach { box ->
            box.retainAll { stored ->
                seen.add(stored.uid).also { fresh ->
                    if (!fresh) notes += "Removed a duplicate entry for ${stored.uid.take(8)}"
                }
            }
        }
        boxes = loadedBoxes
        names = LinkedHashMap()
        root["boxNames"].asTable()?.entries()?.forEach { (key, value) ->
            val index = (key as? com.logie.gen1storage.lua.LuaKey.Index)?.value?.toInt() ?: return@forEach
            value.asString()?.let { names[index] = it }
        }
        loadNotes = notes
    }

    private fun spill(target: List<MutableList<StoredPokemon>>, stored: StoredPokemon, notes: MutableList<String>) {
        val slot = target.firstOrNull { it.size < StorageLayout.BOX_CAPACITY }
        if (slot != null) slot.add(stored) else notes += "Storage is full; ${stored.uid.take(8)} could not be placed"
    }

    private fun readTable(source: File, notes: MutableList<String>, label: String): LuaValue.Table? {
        if (!source.isFile || source.length() == 0L) return null
        return try {
            LuaParser.parse(LuaText.decode(source.readBytes()))
        } catch (e: Exception) {
            notes += "Could not read $label (${e.message}); trying the next copy"
            null
        }
    }

    private fun persist() {
        revision++
        val root = LuaValue.Table()
        root["revision"] = luaNum(revision.toDouble())
        val boxesTable = LuaValue.Table()
        for (index in 1..StorageLayout.BOX_COUNT) {
            boxesTable[index] = LuaValue.Table.ofArray(boxes[index - 1].map { it.toLua() })
        }
        root["boxes"] = boxesTable
        if (names.isNotEmpty()) {
            val nameTable = LuaValue.Table()
            names.forEach { (index, name) -> nameTable[index] = luaStr(name) }
            root["boxNames"] = nameTable
        }
        writeAtomically(LuaText.encode(LuaWriter.encode(root)))
    }

    /**
     * Stage, flush, keep the previous file as `.bak`, then rename into place.
     * Rename within one directory is atomic, so a reader only ever sees a
     * complete file — and if the process dies before the rename, the previous
     * copy is still the one on disk.
     */
    private fun writeAtomically(bytes: ByteArray) {
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
            // Some filesystems refuse a rename onto an existing file.
            file.delete()
            check(staged.renameTo(file)) { "Could not commit the storage file" }
        }
    }

    private fun emptyBoxes(): MutableList<MutableList<StoredPokemon>> =
        MutableList(StorageLayout.BOX_COUNT) { mutableListOf() }

    companion object {
        const val FILE_NAME = "storage.lua"

        /**
         * Append-only, one `return { ... }` chunk per release, each readable on
         * its own by the same parser the saves use.
         */
        const val RELEASED_FILE_NAME = "released.lua.log"
        const val MAX_BOX_NAME = 10
    }
}
