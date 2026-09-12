package com.logie.gen1storage.transfer

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import java.io.File
import java.io.RandomAccessFile

/** Where the app last put a particular Pokémon, and when. */
data class Placement(
    /** The Pokémon's content fingerprint, as [com.logie.gen1storage.pokemon.Gen1Pokemon.fingerprint]. */
    val fingerprint: String,
    val saveKey: String,
    /** How that save reads on screen, so a refusal can name it. */
    val savePath: String,
    val monName: String,
    val atMillis: Long,
) {
    fun toLua(): LuaValue.Table = LuaValue.Table().apply {
        this["saveKey"] = luaStr(saveKey)
        this["savePath"] = luaStr(savePath)
        this["monName"] = luaStr(monName)
        this["at"] = luaNum(atMillis.toDouble())
    }

    companion object {
        fun fromLua(fingerprint: String, table: LuaValue.Table): Placement? {
            val saveKey = table["saveKey"].asString() ?: return null
            return Placement(
                fingerprint = fingerprint,
                saveKey = saveKey,
                savePath = table["savePath"].asString().orEmpty(),
                monName = table["monName"].asString().orEmpty(),
                atMillis = (table["at"] as? LuaValue.Num)?.value?.toLong() ?: 0L,
            )
        }
    }
}

/**
 * Which cartridge each Pokémon the app has handed out is currently in.
 *
 * The app's whole reason to exist is that a Pokémon is never in two places at
 * once, and the one operation that can break that is a withdrawal: a save that
 * has been restored from a backup, copied, or rolled back by the server can
 * hold a Pokémon the PC also believes it is holding, and writing it into a
 * second cartridge would be the moment there are two.
 *
 * So every withdrawal is written down here against the Pokémon's own content
 * fingerprint, and cleared again when that Pokémon is deposited back out. A
 * withdrawal into a *different* cartridge while an entry stands is refused and
 * the entry says which cartridge to look in.
 *
 * The fingerprint is content, not identity, so an entry goes stale the moment
 * the Pokémon is trained, healed or renamed in-game — which is exactly the
 * right behaviour: a Pokémon the player has actually used has clearly not been
 * duplicated by this app, and its entry stops matching anything. Stale entries
 * are pruned by age rather than kept forever, and a player who knows an entry
 * is wrong can drop it from SAVE FILES.
 */
class PlacementLedger(private val directory: File) {

    private val file = File(directory, FILE_NAME)
    private val lock = Any()

    private var entries: MutableMap<String, Placement> = LinkedHashMap()
    private var loaded = false

    /** The Pokémon with this fingerprint, if the app has placed one. */
    fun placement(fingerprint: String): Placement? = synchronized(lock) {
        ensureLoaded()
        entries[fingerprint]
    }

    fun all(): List<Placement> = synchronized(lock) {
        ensureLoaded()
        entries.values.sortedByDescending { it.atMillis }
    }

    fun record(placement: Placement) = synchronized(lock) {
        ensureLoaded()
        entries[placement.fingerprint] = placement
        prune(keep = placement.fingerprint)
        persist()
    }

    /** The Pokémon has left that save, so the app is no longer holding it there. */
    fun forget(fingerprint: String) = synchronized(lock) {
        ensureLoaded()
        if (entries.remove(fingerprint) != null) persist()
    }

    /**
     * Clears the record only if it named [saveKey].
     *
     * A deposit out of the cartridge the record points at means the Pokémon is
     * no longer there, and the record should go. A deposit of something that
     * merely fingerprints the same out of a *different* cartridge means no such
     * thing — the recorded copy is still sitting where it was put, and clearing
     * on that would hand back the duplicate this exists to refuse.
     */
    fun forgetFrom(fingerprint: String, saveKey: String) = synchronized(lock) {
        ensureLoaded()
        if (entries[fingerprint]?.saveKey == saveKey) {
            entries.remove(fingerprint)
            persist()
        }
    }

    /** Everything the app thinks it put in one cartridge, dropped at once. */
    fun forgetSave(saveKey: String) = synchronized(lock) {
        ensureLoaded()
        val before = entries.size
        entries.values.removeAll { it.saveKey == saveKey }
        if (entries.size != before) persist()
    }

    fun clear() = synchronized(lock) {
        entries = LinkedHashMap()
        file.delete()
        loaded = true
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        entries = LinkedHashMap()
        if (!file.isFile || file.length() == 0L) return
        val root = runCatching { LuaParser.parse(LuaText.decode(file.readBytes())) }.getOrNull() ?: return
        val placements = root["placements"].asTable() ?: return
        for ((key, value) in placements.entries()) {
            val fingerprint = (key as? LuaKey.Name)?.value ?: continue
            val table = value.asTable() ?: continue
            Placement.fromLua(fingerprint, table)?.let { entries[fingerprint] = it }
        }
    }

    /**
     * Old entries go, and the file is capped.
     *
     * An entry only ever matters while the Pokémon is untouched in the save it
     * was written to; one that has sat for months is either long since trained
     * past recognition or belongs to a cartridge nobody is playing. Keeping
     * them forever would turn a safety check into a file that only grows.
     */
    private fun prune(keep: String) {
        val cutoff = System.currentTimeMillis() - KEEP_MILLIS
        // The record just written is never a candidate, whatever its clock
        // says. A transfer engine given a fixed clock — a test's, or a device
        // whose date is wrong — would otherwise drop the entry it is making
        // and hand the duplicate straight back.
        entries.entries.removeAll { it.key != keep && it.value.atMillis in 1 until cutoff }
        if (entries.size <= KEEP_MOST) return
        val survivors = entries.entries
            .sortedByDescending { it.value.atMillis }
            .take(KEEP_MOST)
            .mapTo(HashSet()) { it.key }
        survivors += keep
        entries.keys.retainAll(survivors)
    }

    private fun persist() {
        directory.mkdirs()
        val placements = LuaValue.Table()
        entries.forEach { (fingerprint, placement) -> placements[fingerprint] = placement.toLua() }
        val root = LuaValue.Table().apply { this["placements"] = placements }
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
        const val FILE_NAME = "placements.lua"

        /** Half a year. Long enough to cover a cartridge left alone a while. */
        private const val KEEP_MILLIS = 180L * 24 * 60 * 60 * 1000

        private const val KEEP_MOST = 2_000
    }
}
