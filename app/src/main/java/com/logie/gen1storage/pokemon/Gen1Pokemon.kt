package com.logie.gen1storage.pokemon

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asBoolean
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
     * Defaulted to 1, since every save this app read before Generation II's
     * tables existed was one. A Pokémon deposited out of a Gold, Silver or
     * Crystal save carries a 2 here from the moment it lands in the PC — see
     * [com.logie.gen1storage.storage.StoredPokemon.generation] — and one
     * carried forward from Generation I gets it the one deliberate way:
     * [TimeCapsule.carry].
     */
    val generation: Int = 1,
) {

    val speciesId: String? get() = speciesId(raw)

    /**
     * This Pokémon's row, out of its own generation's table.
     *
     * Null for an egg, and that is the point of an egg: the species is
     * written on the record because the game has to know what will hatch,
     * and nothing is allowed to show it. The cartridge does the same by
     * swapping `wCurPartySpecies` for its `EGG` constant before anything
     * draws, so the dex number, the name, the types and the dex entry all
     * come back empty here for the same reason they are blank on screen.
     */
    val species: SpeciesInfo? get() =
        if (isEgg) null
        else if (generation >= 2) Gen2Data.species(speciesId) else Gen1Data.species(speciesId)

    /**
     * Gen1Recomp spells "not nicknamed" as `nickname == nil` and every display
     * site reads `mon.nickname or def.name` (see the note in
     * `src/save_convert/GenSave.lua` around `importedNickname`).
     */
    val nickname: String? get() = raw["nickname"].asString()?.let(LuaText::displayText)
    val displayName: String get() =
        if (isEgg) EGG_NAME else nickname ?: species?.displayName ?: speciesId.orEmpty()

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

    /**
     * Whether this is an egg rather than a Pokemon.
     *
     * Gen1Recomp writes a plain `isEgg` on the record (`src/core/gen2/Breeding.lua`),
     * and every screen that draws one checks it before it checks the species:
     * the cartridge shows an egg's pic and nothing else, no name, no level, no
     * gender and no held item, because the whole point is that you do not know
     * what is in it. `bills_pc.asm` returns early on `cp EGG` for exactly that
     * reason. Generation I had no eggs, so this is false there and always was.
     */
    val isEgg: Boolean get() = raw["isEgg"].asBoolean() == true

    /**
     * How many hatch cycles an egg has left, 256 steps each.
     *
     * On the cartridge this is the byte happiness lives in for everything
     * else, counted down by `DoEggStep`; Gen1Recomp keeps it as its own
     * `eggSteps` field, so that is read first and the happiness byte is the
     * fallback for a save that kept it where the cartridge had it.
     */
    val hatchCycles: Int
        get() = raw["eggSteps"].asInt() ?: raw["happiness"].asInt() ?: 0

    /**
     * Whether it is carrying a letter, which is a thing a Generation II
     * Pokemon can do and a reason it cannot be put in a PC. See [Gen2Mail].
     */
    val holdsMail: Boolean get() = Gen2Mail.isMail(heldItem)

    /**
     * Pokerus, as the status line reports it.
     *
     * The stats screen reads one byte: the low nybble is how many days of the
     * strain are left and the high nybble is which strain it was. Still going
     * and the STATUS line says #RUS instead of the condition; gone but once
     * had, and a dot sits beside the Pokemon's name. See `LoadPinkPage` in
     * `engine/pokemon/stats_screen.asm`.
     */
    val pokerusDaysLeft: Int get() = (raw["pokerus"].asInt() ?: 0) and 0x0F
    val hasPokerus: Boolean get() = pokerusDaysLeft > 0
    val curedOfPokerus: Boolean
        get() = !hasPokerus && ((raw["pokerus"].asInt() ?: 0) and 0xF0) != 0

    /** OT name and 16-bit trainer id, as `mon.ot` / `mon.otId`. */
    val otName: String? get() = raw["ot"].asString()?.let(LuaText::displayText)
    val otId: Int? get() = raw["otId"].asInt()

    val dvs: Map<Gen1Stat, Int> get() = statMap(raw["dvs"].asTable())
    val statExp: Map<Gen1Stat, Int> get() = statMap(raw["statExp"].asTable())
    val stats: Map<Gen1Stat, Int> get() = statMap(raw["stats"].asTable())

    val maxHp: Int? get() = stats[Gen1Stat.HP]
    val hasStats: Boolean get() = Gen1Stats.hasCompleteStats(raw, generation)
    val isShiny: Boolean get() = Gen1Stats.isShiny(dvs)

    /**
     * MALE, FEMALE, or null - no gender at all, or a Generation I Pokémon,
     * which the games never asked this of. [Gen2Species.genderOf] is what
     * decides, off this Pokémon's own Attack and Speed DVs.
     */
    val gender: Gender?
        get() {
            if (generation < 2) return null
            val species = species as? Gen2Species ?: return null
            return species.genderOf(dvs[Gen1Stat.ATTACK] ?: 0, dvs[Gen1Stat.SPEED] ?: 0)
        }

    val moves: List<MoveSlot>
        get() = raw["moves"].asTable()?.array().orEmpty().mapNotNull { entry ->
            val table = entry.asTable() ?: return@mapNotNull null
            val id = table["id"].asString() ?: return@mapNotNull null
            MoveSlot(id, table["pp"].asInt() ?: 0, table["ppUps"].asInt(), generation)
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
     * This Pokémon's Pokédex page, in the words of the cartridge it is being
     * read on — out of its own generation's table.
     *
     * Generation II's three games agree about almost nothing here: each one
     * rewrote the prose, GOLD and SILVER call NATU a LITTLEBIRD where CRYSTAL
     * gives it the space, and GOLD has ENTEI four inches taller than CRYSTAL
     * does. [gameVersionId] is which of them is doing the printing.
     */
    fun dexPage(gameVersionId: String?): DexPage? =
        dexPageOf(speciesId, generation, gameVersionId)

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

    data class MoveSlot(
        val id: String,
        val pp: Int,
        val ppUps: Int?,
        /** The generation of the Pokémon that knows it; see [MoveInfo]. */
        val generation: Int = 1,
    ) {
        val move: MoveInfo? get() =
            if (generation >= 2) Gen2Data.move(id) else Gen1Data.move(id)

        val displayName: String get() = move?.displayName ?: id

        /** `AddBonusPP`: each PP Up adds a fifth of the base PP. */
        val maxPp: Int?
            get() = move?.let { it.basePp + (ppUps ?: 0) * (it.basePp / 5) }
    }

    companion object {
        /** What the games print in place of a name that has not been earned. */
        const val EGG_NAME = "EGG"

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
