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
class Gen1Pokemon(
    val raw: LuaValue.Table,
    /**
     * Which generation's tables describe this Pokémon.
     *
     * Generation II rebalanced a number of the original 151, so PIDGEY out of
     * a Gold save and PIDGEY out of a Red save are not the same row: each is
     * read with the numbers of the game it came from, and the save it was read
     * out of is what says which. It is held here rather than in [raw] because
     * the raw table is the cartridge's and this app does not write fields into
     * one to keep track of its own business.
     *
     * Defaulted to 1, which is what every Pokémon in this app's own PC is:
     * Generation II saves are read and never written, so nothing from one can
     * be deposited yet. When a Pokémon can be carried forward deliberately —
     * the Time Capsule's job, not a side effect of a table lookup — this is
     * the field that would change, and it would have to be stored alongside a
     * deposited Pokémon to survive the trip.
     */
    val generation: Int = 1,
) {

    val speciesId: String? get() = speciesId(raw)

    /** This Pokémon's row, out of its own generation's table. */
    val species: SpeciesInfo? get() =
        if (generation >= 2) Gen2Data.species(speciesId) else Gen1Data.species(speciesId)

    /**
     * Gen1Recomp spells "not nicknamed" as `nickname == nil` and every display
     * site reads `mon.nickname or def.name` (see the note in
     * `src/save_convert/GenSave.lua` around `importedNickname`).
     */
    val nickname: String? get() = raw["nickname"].asString()?.let(LuaText::displayText)
    val displayName: String get() = nickname ?: species?.displayName ?: speciesId.orEmpty()

    val level: Int get() = raw["level"].asInt() ?: 1
    val exp: Int get() = raw["exp"].asInt() ?: 0
    val currentHp: Int get() = raw["hp"].asInt() ?: 0
    val status: String? get() = raw["status"].asString()
    val catchRate: Int? get() = raw["catchRate"].asInt()

    /**
     * What it is carrying, if anything.
     *
     * Generation I has no held items, so this is empty for every Pokémon from
     * a Red, Blue or Yellow save. It is read anyway because Gen1Recomp saves
     * a Pokémon as a table rather than as the cartridge's fixed bytes: a
     * Generation II save, or a mod that gives one something to hold, writes
     * the field and the app should see it rather than step over it.
     */
    val heldItem: String? get() = raw["item"].asString()?.takeIf { it.isNotBlank() }

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

    /**
     * The stats the status screen prints under HP: four in Generation I and
     * five in Generation II, where Special is two stats and the save stores
     * them under their own keys.
     */
    val battleStats: List<StatLine>
        get() = if (generation >= 2) {
            val table = raw["stats"].asTable()
            Gen2Stat.BATTLE.map { StatLine(it.label, table?.get(it.key).asInt()) }
        } else {
            val here = stats
            GEN1_BATTLE_STATS.map { StatLine(it.label, here[it]) }
        }

    fun copy(): Gen1Pokemon = Gen1Pokemon(raw.deepCopy(), generation)

    /** One stat as it is shown: what it is called, and what it is. */
    data class StatLine(val label: String, val value: Int?)

    data class MoveSlot(val id: String, val pp: Int, val ppUps: Int?) {
        val move: Gen1Move? get() = Gen1Data.move(id)
        val displayName: String get() = Gen1Data.moveName(id)

        /** `AddBonusPP`: each PP Up adds a fifth of the base PP. */
        val maxPp: Int?
            get() = move?.let { it.basePp + (ppUps ?: 0) * (it.basePp / 5) }
    }

    companion object {
        private val GEN1_BATTLE_STATS =
            listOf(Gen1Stat.ATTACK, Gen1Stat.DEFENSE, Gen1Stat.SPEED, Gen1Stat.SPECIAL)

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
