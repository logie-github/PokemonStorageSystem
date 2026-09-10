package com.logie.gen1storage.pokemon

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asDouble
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Generation I stat maths, ported from upstream `src/pokemon/Stats.lua`, which
 * in turn mirrors pokered `home/move_mon.asm` CalcStat:
 *
 *     stat = floor(((base + DV) * 2 + floor(ceil(sqrt(statExp)) / 4)) * level / 100) + 5
 *
 * with HP adding `level + 10` instead of `5`.
 *
 * This app runs the calculation in exactly one situation: a stored Pokémon that
 * carries no complete stat block is withdrawn into a party, which is the moment
 * upstream `BoxMenu.withdraw` calls `Stats.ensure` (add_mon.asm `_MoveMon`'s
 * tail). A Pokémon that already has a complete stat block is never recomputed.
 */
object Gen1Stats {

    fun calcOne(base: Int, dv: Int, statExp: Int, level: Int, isHp: Boolean): Int {
        val ev = floor(min(255.0, ceil(sqrt(statExp.coerceAtLeast(0).toDouble()))) / 4.0)
        val value = floor(((base + dv) * 2 + ev) * level / 100.0).toInt()
        return if (isHp) value + level + 10 else value + 5
    }

    fun calc(species: Gen1Species, level: Int, dvs: Map<Gen1Stat, Int>, statExp: Map<Gen1Stat, Int>): Map<Gen1Stat, Int> =
        Gen1Stat.ORDER.associateWith { stat ->
            calcOne(species.baseStat(stat), dvs[stat] ?: 0, statExp[stat] ?: 0, level, stat == Gen1Stat.HP)
        }

    /**
     * The HP DV is not stored in Gen I: it is the low bit of each other DV,
     * ordered attack/defense/speed/special (`Stats.randomDVs`). A save written
     * by the game always carries a consistent `dvs.hp`; this derives it when a
     * Pokémon arrives without one.
     */
    fun derivedHpDv(dvs: Map<Gen1Stat, Int>): Int =
        (dvs[Gen1Stat.ATTACK] ?: 0) % 2 * 8 +
            (dvs[Gen1Stat.DEFENSE] ?: 0) % 2 * 4 +
            (dvs[Gen1Stat.SPEED] ?: 0) % 2 * 2 +
            (dvs[Gen1Stat.SPECIAL] ?: 0) % 2

    /**
     * `Stats.ensure`, applied to the raw Lua table so no other field is touched.
     * Returns true when a stat block was written. A complete block passes
     * through untouched, exactly as upstream does — this app must not "fix"
     * numbers the game is content with.
     */
    fun ensureStats(mon: LuaValue.Table): Boolean {
        val existing = mon["stats"].asTable()
        if (existing != null && Gen1Stat.ORDER.all { existing[it.key].asDouble() != null }) return false
        val species = Gen1Data.species(Gen1Pokemon.speciesId(mon)) ?: return false
        val level = mon["level"].asInt() ?: 1
        val dvs = Gen1Pokemon.statMap(mon["dvs"].asTable())
        val statExp = Gen1Pokemon.statMap(mon["statExp"].asTable())
        val stats = calc(species, level, dvs, statExp)
        val table = LuaValue.Table()
        for (stat in Gen1Stat.ORDER) table[stat.key] = luaNum(stats.getValue(stat))
        mon["stats"] = table
        val maxHp = stats.getValue(Gen1Stat.HP)
        val hp = mon["hp"].asInt() ?: maxHp
        mon["hp"] = luaNum(hp.coerceIn(0, maxHp))
        return true
    }

    /** True when `mon.stats` already holds all five stats. */
    fun hasCompleteStats(mon: LuaValue.Table): Boolean {
        val stats = mon["stats"].asTable() ?: return false
        return Gen1Stat.ORDER.all { stats[it.key].asDouble() != null }
    }

    /** Gen II's shiny test applied to Gen I DVs, as upstream `Stats.isShiny` does. */
    fun isShiny(dvs: Map<Gen1Stat, Int>): Boolean =
        dvs[Gen1Stat.DEFENSE] == 10 && dvs[Gen1Stat.SPEED] == 10 && dvs[Gen1Stat.SPECIAL] == 10 &&
            (dvs[Gen1Stat.ATTACK] ?: 0) in setOf(2, 3, 6, 7, 10, 11, 14, 15)
}
