package com.logie.gen1storage.pokemon

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import java.security.MessageDigest

/**
 * A read-only view over one Pokémon's raw Lua table.
 *
 * The raw table is the Pokémon. Every accessor here reads through to it and
 * nothing ever rewrites a field the app did not have to change, so a Pokémon
 * that carries data this app has never heard of — a mod's field, an importer's
 * `typeBytes`, a future upstream addition — survives a deposit and withdraw
 * completely intact.
 */
class Gen1Pokemon(val raw: LuaValue.Table) {

    val speciesId: String? get() = speciesId(raw)
    val species: Gen1Species? get() = Gen1Data.species(speciesId)

    /**
     * Gen1Recomp spells "not nicknamed" as `nickname == nil` and every display
     * site reads `mon.nickname or def.name` (see the note in
     * `src/save_convert/GenSave.lua` around `importedNickname`).
     */
    val nickname: String? get() = raw["nickname"].asString()?.let(LuaText::displayText)
    val displayName: String get() = nickname ?: Gen1Data.speciesName(speciesId)

    val level: Int get() = raw["level"].asInt() ?: 1
    val exp: Int get() = raw["exp"].asInt() ?: 0
    val currentHp: Int get() = raw["hp"].asInt() ?: 0
    val status: String? get() = raw["status"].asString()
    val catchRate: Int? get() = raw["catchRate"].asInt()

    /** OT name and 16-bit trainer id, as `mon.ot` / `mon.otId`. */
    val otName: String? get() = raw["ot"].asString()?.let(LuaText::displayText)
    val otId: Int? get() = raw["otId"].asInt()

    val dvs: Map<Gen1Stat, Int> get() = statMap(raw["dvs"].asTable())
    val statExp: Map<Gen1Stat, Int> get() = statMap(raw["statExp"].asTable())
    val stats: Map<Gen1Stat, Int> get() = statMap(raw["stats"].asTable())

    val maxHp: Int? get() = stats[Gen1Stat.HP]
    val hasStats: Boolean get() = Gen1Stats.hasCompleteStats(raw)
    val isShiny: Boolean get() = Gen1Stats.isShiny(dvs)

    val moves: List<MoveSlot>
        get() = raw["moves"].asTable()?.array().orEmpty().mapNotNull { entry ->
            val table = entry.asTable() ?: return@mapNotNull null
            val id = table["id"].asString() ?: return@mapNotNull null
            MoveSlot(id, table["pp"].asInt() ?: 0, table["ppUps"].asInt())
        }

    /**
     * A stable content fingerprint: the SHA-256 of this Pokémon's canonical
     * serialization. Two tables with the same contents fingerprint identically
     * regardless of the order their keys were inserted, because [LuaWriter]
     * sorts. This is how a transfer proves the Pokémon it is about to remove
     * from a save is the one it read, and how recovery recognises a stored copy.
     */
    val fingerprint: String by lazy {
        val bytes = LuaText.encode(LuaWriter.encodeValue(raw))
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    fun copy(): Gen1Pokemon = Gen1Pokemon(raw.deepCopy())

    data class MoveSlot(val id: String, val pp: Int, val ppUps: Int?) {
        val move: Gen1Move? get() = Gen1Data.move(id)
        val displayName: String get() = Gen1Data.moveName(id)

        /** `AddBonusPP`: each PP Up adds a fifth of the base PP. */
        val maxPp: Int?
            get() = move?.let { it.basePp + (ppUps ?: 0) * (it.basePp / 5) }
    }

    companion object {
        fun speciesId(mon: LuaValue.Table): String? = mon["species"].asString()

        fun statMap(table: LuaValue.Table?): Map<Gen1Stat, Int> {
            if (table == null) return emptyMap()
            val out = LinkedHashMap<Gen1Stat, Int>()
            for (stat in Gen1Stat.ORDER) {
                table[stat.key].asInt()?.let { out[stat] = it }
            }
            return out
        }

        /**
         * Whether a Lua table is shaped like a Generation I Pokémon at all.
         * Deliberately permissive about which optional fields are present — a
         * `.sav`-imported box Pokémon legitimately carries no stat block, and a
         * modded species is still a Pokémon.
         */
        fun looksLikePokemon(value: LuaValue?): Boolean {
            val table = value.asTable() ?: return false
            if (table["species"].asString().isNullOrEmpty()) return false
            return table[LuaKey.Name("level")] != null || table[LuaKey.Name("exp")] != null
        }
    }
}
