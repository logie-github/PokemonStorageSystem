package com.logie.gen1storage.pokemon

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr

/**
 * Every Pokémon a Generation II cartridge will only evolve by trading.
 *
 * Read off `data/pokemon/evos_attacks.asm` at the commit this project pins:
 * the four Generation I already had — trading alone is still enough for
 * them, the same as ever — and six new ones Generation II gates behind a
 * trade **and** a held item, which the item is spent confirming
 * (`engine/pokemon/evolve.asm` `.trade`: a match clears
 * `wTempMonItem` before the evolution proceeds; the four that need no item
 * carry `-1` in that slot and never touch it). An Everstone held through
 * either kind refuses the trade outright, the same as it does in-game.
 *
 * This is [Gen1TradeEvolution]'s Generation II half: the same trick — this
 * app is already both ends of a cable — applied with Generation II's own
 * data, since a Kadabra that came from a Gold save has five stats to work
 * out, not four, and Politoed's kind never had a Generation I table to read
 * at all.
 */
object Gen2TradeEvolution {

    /** What a trade evolves this species into, and what it must be holding, if anything. */
    data class Evolution(val to: String, val item: String?)

    /** Every one, in the order the Pokédex lists the originals and then adds the rest. */
    val BY_TRADE: Map<String, Evolution> = linkedMapOf(
        "KADABRA" to Evolution("ALAKAZAM", null),
        "MACHOKE" to Evolution("MACHAMP", null),
        "GRAVELER" to Evolution("GOLEM", null),
        "HAUNTER" to Evolution("GENGAR", null),
        "POLIWHIRL" to Evolution("POLITOED", "KINGS_ROCK"),
        "SLOWPOKE" to Evolution("SLOWKING", "KINGS_ROCK"),
        "SEADRA" to Evolution("KINGDRA", "DRAGON_SCALE"),
        "ONIX" to Evolution("STEELIX", "METAL_COAT"),
        "SCYTHER" to Evolution("SCIZOR", "METAL_COAT"),
        "PORYGON" to Evolution("PORYGON2", "UP_GRADE"),
    )

    /**
     * What this one would become on being traded while holding [heldItem], or
     * null. An Everstone refuses every trade evolution, item-gated or not —
     * `IsMonHoldingEverstone` is checked before anything else in `.trade`.
     */
    fun evolutionOf(speciesId: String?, heldItem: String?): String? {
        if (heldItem == "EVERSTONE") return null
        val evolution = speciesId?.let { BY_TRADE[it] } ?: return null
        return if (evolution.item == null || evolution.item == heldItem) evolution.to else null
    }

    fun evolves(speciesId: String?, heldItem: String?): Boolean = evolutionOf(speciesId, heldItem) != null

    /**
     * Evolves a Generation II Pokémon in place, the way `EvolveMon` does.
     *
     * All six stats are worked out again from the new species' bases, with
     * the one Special DV and stat experience word feeding both Special
     * Attack and Special Defense — [Gen1Stats.ensureStats]'s rule, and
     * [TimeCapsule.carry]'s. An item that was required is spent: `.trade`
     * zeroes `wTempMonItem` on a match, so a Poliwhirl trades its King's Rock
     * away the moment it becomes a Politoed. A Kadabra-style evolution that
     * needs no item leaves whatever is being held untouched, exactly as the
     * cartridge does.
     *
     * Returns the new species id, or null when this one does not trade-evolve
     * this way, is holding an Everstone, or the tables cannot account for it —
     * in which case nothing was changed.
     */
    fun evolve(mon: LuaValue.Table): String? {
        val from = Gen1Pokemon.speciesId(mon) ?: return null
        val heldItem = mon["item"].asString()?.takeIf { it.isNotBlank() }
        val toId = evolutionOf(from, heldItem) ?: return null
        val to = Gen2Data.species(toId) ?: return null

        val level = mon["level"].asInt() ?: return null
        val dvs = Gen1Pokemon.statMap(mon["dvs"].asTable())
        val statExp = Gen1Pokemon.statMap(mon["statExp"].asTable())

        val beforeHp = mon["stats"].asTable()?.get(Gen2Stat.HP.key).asInt()
        val special = dvs[Gen1Stat.SPECIAL] ?: 0
        val specialExp = statExp[Gen1Stat.SPECIAL] ?: 0
        val stats = LuaValue.Table()
        Gen2Stat.ORDER.forEach { stat ->
            val isSpecial = stat == Gen2Stat.SPECIAL_ATTACK || stat == Gen2Stat.SPECIAL_DEFENSE
            val dv = if (isSpecial) special else dvs[Gen1Stat.byKey(stat.key)] ?: 0
            val exp = if (isSpecial) specialExp else statExp[Gen1Stat.byKey(stat.key)] ?: 0
            stats[stat.key] = luaNum(Gen1Stats.calcOne(to.baseStat(stat), dv, exp, level, stat == Gen2Stat.HP))
        }

        mon["species"] = luaStr(toId)
        mon["stats"] = stats
        val maxHp = stats[Gen2Stat.HP.key].asInt() ?: 1
        mon["maxHp"] = luaNum(maxHp)

        val hp = mon["hp"].asInt()
        if (hp != null) {
            // `EvolveMon` adds the gain to what it is carrying rather than
            // healing it, so a Pokémon that evolved hurt is still hurt.
            val gain = if (beforeHp != null) maxHp - beforeHp else 0
            mon["hp"] = luaNum((hp + gain).coerceIn(0, maxHp))
        }

        val required = BY_TRADE[from]?.item
        if (required != null && heldItem == required) mon.remove(LuaKey.Name("item"))

        return toId
    }
}

/**
 * Whether trading this stored Pokémon would evolve it, and what it becomes —
 * [Gen1TradeEvolution] for one out of Generation I, [Gen2TradeEvolution] for
 * one out of Generation II, since a Generation II Kadabra has a held item to
 * check for an Everstone and five stats to recompute where a Generation I
 * one has neither.
 */
fun tradeEvolutionOf(pokemon: Gen1Pokemon): String? =
    if (pokemon.generation >= 2) Gen2TradeEvolution.evolutionOf(pokemon.speciesId, pokemon.heldItem)
    else Gen1TradeEvolution.evolutionOf(pokemon.speciesId)

fun tradeEvolves(pokemon: Gen1Pokemon): Boolean = tradeEvolutionOf(pokemon) != null

/** The species' display name under its own generation's table. */
fun tradeEvolutionName(toId: String, generation: Int): String =
    if (generation >= 2) Gen2Data.speciesName(toId) else Gen1Data.speciesName(toId)

/** Evolves a stored Pokémon's raw table in place, under the generation it is known to be. */
fun tradeEvolveMon(mon: LuaValue.Table, generation: Int): String? =
    if (generation >= 2) Gen2TradeEvolution.evolve(mon) else Gen1TradeEvolution.evolve(mon)
