package com.logie.gen1storage.pokemon

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr

/**
 * The four Pokémon a cartridge will only evolve by trading.
 *
 * Read off `data/pokemon/evos_moves.asm` at the commit this project pins:
 * every `EVOLVE_TRADE` entry Generation I has, and there are exactly four.
 * A minimum level of 1 is attached to each, which is to say there is no
 * condition beyond the trade itself.
 *
 * This is the one thing a storage system on a phone can do that a lone
 * cartridge cannot, and it is the reason these four exist at all.
 */
object Gen1TradeEvolution {

    /** What each becomes, in the order the Pokédex lists them. */
    val BY_TRADE: Map<String, String> = linkedMapOf(
        "KADABRA" to "ALAKAZAM",
        "MACHOKE" to "MACHAMP",
        "GRAVELER" to "GOLEM",
        "HAUNTER" to "GENGAR",
    )

    /** What this one would become on being traded, or null. */
    fun evolutionOf(speciesId: String?): String? = speciesId?.let { BY_TRADE[it] }

    fun evolves(speciesId: String?): Boolean = evolutionOf(speciesId) != null

    /**
     * Evolves a Pokémon in place, the way `EvolveMon` does.
     *
     * The species changes and the stats are worked out again from the new base
     * stats — same level, same DVs, same stat experience — and the current HP
     * moves by exactly what the maximum moved by, which is what the cartridge
     * does rather than refilling it. Everything else about the Pokémon is left
     * alone: the nickname stays whatever it was, the original trainer stays,
     * experience stays, and any field this app has never heard of is not
     * touched at all.
     *
     * One deliberate difference from the cartridge: Generation I offers a move
     * when the evolved form learns one at the level it evolved at, and nothing
     * here teaches a move. Adding a move to a Pokémon is a bigger claim than
     * this is willing to make on the player's behalf, and a Pokémon is never
     * worse off for having missed the offer — it can still be learnt in-game.
     *
     * Returns the new species id, or null when this one does not trade-evolve
     * or the tables cannot account for it, in which case nothing was changed.
     */
    fun evolve(mon: LuaValue.Table): String? {
        val from = Gen1Pokemon.speciesId(mon) ?: return null
        val toId = BY_TRADE[from] ?: return null
        val to = Gen1Data.species(toId) ?: return null

        val level = mon["level"].asInt() ?: return null
        val dvs = Gen1Pokemon.statMap(mon["dvs"].asTable())
        val statExp = Gen1Pokemon.statMap(mon["statExp"].asTable())

        val before = Gen1Pokemon.statMap(mon["stats"].asTable())[Gen1Stat.HP]
        val after = Gen1Stats.calc(to, level, dvs, statExp)

        mon["species"] = luaStr(toId)
        val stats = LuaValue.Table()
        for (stat in Gen1Stat.ORDER) stats[stat.key] = luaNum(after.getValue(stat))
        mon["stats"] = stats

        val maxHp = after.getValue(Gen1Stat.HP)
        val hp = mon["hp"].asInt()
        if (hp != null) {
            // `EvolveMon` adds the gain to what it is carrying rather than
            // healing it, so a Pokémon that evolved hurt is still hurt.
            val gain = if (before != null) maxHp - before else 0
            mon["hp"] = luaNum((hp + gain).coerceIn(0, maxHp))
        }
        return toId
    }
}
