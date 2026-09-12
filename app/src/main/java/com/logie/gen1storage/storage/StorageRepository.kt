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
import com.logie.gen1storage.pokemon.Gen1TradeEvolution
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
    private var boxes: MutableList<MutableList<StoredPokemon?>> = emptyBoxes()
    private var names: MutableMap<Int, String> = LinkedHashMap()
    private var revision: Long = 0
    private var loaded = false

    private var notes: List<String> = emptyList()

    /**
     * Non-fatal problems from the last load, surfaced on the diagnostics
     * screen.
     *
     * Reading it reads the file, because the alternative is worse: asked
     * before anything else had happened to load the PC it answered "no
     * problems", which is a different claim from "not looked yet" and the one
     * that gets believed.
     */
    val loadNotes: List<String>
        get() = synchronized(lock) {
            ensureLoaded()
            notes
        }

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
        boxes.any { box -> box.any { it?.uid == uid } }
    }

    fun get(uid: String): StoredPokemon? = synchronized(lock) {
        ensureLoaded()
        boxes.firstNotNullOfOrNull { box -> box.firstOrNull { it?.uid == uid } }
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
            val slot = boxes[index].indexOfFirst { it == null }
            if (slot >= 0) {
                val stored = StoredPokemon(uid, data, provenance)
                boxes[index][slot] = stored
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
            val position = box.indexOfFirst { it?.uid == uid }
            if (position >= 0) {
                val removed = box[position]
                // The spot is left empty rather than closed up: the rest of
                // the box is where the player put it, and a withdrawal is no
                // reason to rearrange it for them.
                box[position] = null
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

    /** Moves a stored Pokémon to the first free spot of another box. */
    fun moveTo(uid: String, targetBox: Int): Boolean = synchronized(lock) {
        ensureLoaded()
        val index = targetBox - 1
        if (index !in 0 until StorageLayout.BOX_COUNT) return false
        val free = boxes[index].indexOfFirst { it == null }
        if (free < 0) return false
        return moveToSlot(uid, targetBox, free)
    }

    /**
     * Puts a stored Pokémon on a named spot, which is what a drag across the
     * grid asks for.
     *
     * A spot that is already taken swaps rather than refusing: dropping one
     * Pokémon onto another is how a player rearranges a full box, and the
     * alternative is a move that silently does nothing. Nothing leaves the PC
     * either way — a swap is two placements, and both are written at once.
     */
    fun moveToSlot(uid: String, targetBox: Int, targetSlot: Int): Boolean = synchronized(lock) {
        ensureLoaded()
        val index = targetBox - 1
        if (index !in 0 until StorageLayout.BOX_COUNT) return false
        if (targetSlot !in 0 until StorageLayout.BOX_CAPACITY) return false

        var fromBox = -1
        var fromSlot = -1
        outer@ for (b in boxes.indices) {
            for (slot in boxes[b].indices) {
                if (boxes[b][slot]?.uid == uid) {
                    fromBox = b
                    fromSlot = slot
                    break@outer
                }
            }
        }
        if (fromBox < 0) return false
        if (fromBox == index && fromSlot == targetSlot) return true

        val moving = boxes[fromBox][fromSlot]
        val displaced = boxes[index][targetSlot]
        boxes[index][targetSlot] = moving
        boxes[fromBox][fromSlot] = displaced
        persist()
        true
    }

    /**
     * Gives a stored Pokémon a nickname, or takes its nickname away.
     *
     * Generation I spells "not nicknamed" as no `nickname` field at all, and
     * every display site upstream reads `mon.nickname or def.name`, so clearing
     * one removes the field rather than writing an empty string — a Pokémon
     * whose nickname is cleared goes back to being called what it is.
     *
     * Only that one field is touched. The rest of the table, including anything
     * this app has never heard of, is written back exactly as it was read.
     */
    fun rename(uid: String, nickname: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val cleaned = cleanNickname(nickname)
        for (box in boxes) {
            val position = box.indexOfFirst { it?.uid == uid }
            if (position < 0) continue
            val stored = box[position] ?: return false
            if (stored.pokemon.nickname.orEmpty() == cleaned) return true
            val data = stored.data.deepCopy()
            if (cleaned.isEmpty()) data.remove(LuaKey.Name("nickname"))
            else data["nickname"] = luaStr(cleaned)
            box[position] = stored.copy(data = data)
            persist()
            return true
        }
        false
    }

    /**
     * Trades a stored Pokémon with the machine, which is how the four that
     * need a trade evolve.
     *
     * Returns what it became, or null when it was not one of the four or was
     * not here — in which case nothing was written. The Pokémon stays in its
     * own spot: it is the same Pokémon, and rearranging the box around an
     * evolution would be the app moving something nobody asked it to move.
     */
    fun evolveByTrade(uid: String): String? = synchronized(lock) {
        ensureLoaded()
        for (box in boxes) {
            val position = box.indexOfFirst { it?.uid == uid }
            if (position < 0) continue
            val stored = box[position] ?: return null
            val data = stored.data.deepCopy()
            val became = Gen1TradeEvolution.evolve(data) ?: return null
            box[position] = stored.copy(data = data)
            persist()
            return became
        }
        null
    }

    fun renameBox(index: Int, name: String): Boolean = synchronized(lock) {
        ensureLoaded()
        if (index !in 1..StorageLayout.BOX_COUNT) return false
        val trimmed = name.trim().take(MAX_BOX_NAME)
        if (trimmed.isEmpty()) names.remove(index) else names[index] = trimmed
        persist()
        true
    }

    /**
     * Puts an exported archive back into the PC.
     *
     * A uid already here is the same Pokémon, so it is skipped rather than
     * placed again: importing a file twice, or importing one that overlaps
     * what is already stored, must never end with two of anything. Each one
     * goes back to the box it came from when there is room, and to the first
     * box with room when there is not, which is what a deposit does anyway.
     *
     * Written once, at the end, so a file that runs out of room partway still
     * leaves the PC in one state rather than several.
     */
    fun importArchive(archive: StorageArchive.Archive): ImportReport = synchronized(lock) {
        ensureLoaded()
        var added = 0
        var skipped = 0
        var unplaced = 0
        val held = HashSet<String>()
        boxes.forEach { box -> box.forEach { stored -> stored?.let { held += it.uid } } }

        val box = boxes[0]
        // An export from when the PC was twelve boxes numbers its spots within
        // each of them, so slot 3 appears twelve times over and no two of them
        // mean the same place. Those go back in the order they were exported
        // rather than by number, which is the order they were in.
        val poured = archive.entries.any { it.box > 1 }

        archive.entries.forEach { entry ->
            if (!held.add(entry.stored.uid)) {
                skipped++
                return@forEach
            }
            // Its own spot first, so the box comes back arranged as it was left.
            val own = (entry.slot - 1).takeIf { !poured }
            val slot = own?.takeIf { it in box.indices && box[it] == null }
                ?: box.indexOfFirst { it == null }
            if (slot < 0) {
                held.remove(entry.stored.uid)
                unplaced++
            } else {
                box[slot] = entry.stored
                added++
            }
        }

        // Only if the player has not named it themselves: their own name for
        // the box is theirs, and an import is not the place to overwrite it.
        // An old export carries up to twelve names and there is one box to put
        // one on, so the first is the one that lands.
        if (names[StorageLayout.THE_BOX] == null) {
            archive.boxNames.toSortedMap().values.firstOrNull()?.let {
                names[StorageLayout.THE_BOX] = it.take(MAX_BOX_NAME)
            }
        }

        if (added > 0 || archive.boxNames.isNotEmpty()) persist()
        ImportReport(added, skipped, unplaced, archive.unreadable)
    }

    /**
     * Takes what a stored Pokémon is carrying, at the player's request.
     *
     * Returns the item's id, or null when it was not holding one. The only
     * field touched is the one being removed: everything else about the
     * Pokémon, including anything this app has never heard of, is written
     * back exactly as it was read.
     */
    fun takeHeldItem(uid: String): String? = synchronized(lock) {
        ensureLoaded()
        for (box in boxes) {
            val position = box.indexOfFirst { it?.uid == uid }
            if (position < 0) continue
            val stored = box[position] ?: return null
            val item = stored.pokemon.heldItem ?: return null
            val data = stored.data.deepCopy()
            data.remove(com.logie.gen1storage.lua.LuaKey.Name("item"))
            box[position] = stored.copy(data = data)
            persist()
            return item
        }
        null
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
        val report = mutableListOf<String>()
        val root = readTable(file, report, "storage")
            ?: readTable(staged, report, "staged storage")
            ?: readTable(backup, report, "storage backup")
        notes = report
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
            val box = loadedBoxes[0]
            // Whatever boxes the file actually holds, in order — not the boxes
            // this version expects. A file written when the PC was twelve of
            // them still has twelve, and reading only the first would be a
            // silent loss of eleven boxes of Pokémon.
            val numbered = boxesTable.entries()
                .mapNotNull { (key, _) ->
                    (key as? com.logie.gen1storage.lua.LuaKey.Index)?.value?.toInt()
                }
                .filter { it >= 1 }
                .sorted()
            // More than one box in the file is a file from before this was one
            // box, so its spots are poured in one box after another in the
            // order they were kept. Their slot numbers cannot be honoured:
            // slot 3 meant a different spot in each of the twelve, and putting
            // them back by number would have them land on each other.
            val poured = numbered.any { it > 1 }
            if (poured) {
                report += "The old boxes were poured into the one box, in the order they were kept"
            }
            numbered.forEach { index ->
                boxesTable[index].asTable()?.array().orEmpty().forEach { entry ->
                    val table = entry.asTable()
                    val stored = table?.let(StoredPokemon::fromLua)
                    if (stored == null) {
                        report += "Dropped an unreadable entry in box $index"
                        return@forEach
                    }
                    // A file written before boxes were a grid has no slot, so
                    // its entries pack from the top in the order they were
                    // written — which is exactly the order they were shown in.
                    val written = if (poured) null else table["slot"].asInt()?.minus(1)
                    val slot = written?.takeIf { it in box.indices && box[it] == null }
                        ?: box.indexOfFirst { it == null }
                    if (slot < 0) {
                        report += "The box is full; ${stored.uid.take(8)} could not be placed"
                    } else box[slot] = stored
                }
            }
        }
        // A uid must be unique: it is what a transfer uses to prove the app
        // holds exactly one authoritative copy.
        val seen = HashSet<String>()
        loadedBoxes.forEach { box ->
            box.indices.forEach { slot ->
                val stored = box[slot] ?: return@forEach
                if (!seen.add(stored.uid)) {
                    report += "Removed a duplicate entry for ${stored.uid.take(8)}"
                    box[slot] = null
                }
            }
        }
        boxes = loadedBoxes
        names = LinkedHashMap()
        root["boxNames"].asTable()?.entries()?.forEach { (key, value) ->
            val index = (key as? com.logie.gen1storage.lua.LuaKey.Index)?.value?.toInt() ?: return@forEach
            value.asString()?.let { names[index] = it }
        }
        notes = report
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
            // Only what is there, each carrying where it sits. An array of
            // holes would not survive the Lua writer, and would say nothing
            // the slot number does not.
            val entries = boxes[index - 1].mapIndexedNotNull { slot, stored ->
                stored?.toLua()?.apply { this["slot"] = luaNum((slot + 1).toDouble()) }
            }
            boxesTable[index] = LuaValue.Table.ofArray(entries)
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

    private fun emptyBoxes(): MutableList<MutableList<StoredPokemon?>> =
        MutableList(StorageLayout.BOX_COUNT) {
            MutableList<StoredPokemon?>(StorageLayout.BOX_CAPACITY) { null }
        }

    companion object {
        const val FILE_NAME = "storage.lua"

        /**
         * Append-only, one `return { ... }` chunk per release, each readable on
         * its own by the same parser the saves use.
         */
        const val RELEASED_FILE_NAME = "released.lua.log"
        const val MAX_BOX_NAME = 10

        /** As long as a nickname can be on a Generation I cartridge. */
        const val MAX_NICKNAME = 10

        /**
         * The characters a Generation I cartridge can actually draw.
         *
         * A nickname written here can be withdrawn into a save and shown by
         * the game, so it has to stay inside the charmap upstream encodes
         * against. Anything else is dropped rather than substituted: a name
         * that silently became something else would be worse than a shorter
         * one.
         */
        fun cleanNickname(name: String): String =
            name.trim()
                .filter { it in NICKNAME_CHARACTERS }
                .take(MAX_NICKNAME)

        private const val NICKNAME_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                "abcdefghijklmnopqrstuvwxyz" +
                "0123456789" +
                " .,'!?-/♀♂é"
    }
}
