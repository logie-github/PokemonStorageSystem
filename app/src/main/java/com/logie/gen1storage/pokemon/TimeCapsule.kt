package com.logie.gen1storage.pokemon

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr

/**
 * Carrying a Pokémon forward from Generation I to Generation II.
 *
 * Every rule here is read out of pret/pokecrystal rather than remembered:
 * `engine/link/link.asm` `Link_ConvertPartyStruct1to2` is the conversion,
 * `engine/link/time_capsule_2.asm` `ConvertMon_1to2` is the species remap,
 * `data/items/catch_rate_items.asm` is the held item, and
 * `engine/pokemon/move_mon.asm` `CalcMonStatC` is the stat maths. What that
 * code does, and only what it does:
 *
 *  - **The species is renumbered.** Generation I's internal order becomes
 *    Generation II's Pokédex order. This app stores a species by name, so the
 *    renumbering is invisible except for the two names the decompilations
 *    spell differently — see [Gen2Data.idOf].
 *  - **The catch rate becomes a held item.** Byte seven of a Generation I
 *    party struct is the catch rate; Generation II reads it as the held item,
 *    so a Pokémon arrives holding whatever item sits at that index. Eleven
 *    catch rates are rewritten by `TimeCapsule_ReplaceTeruSama` — a catch
 *    rate of 25 is LEFTOVERS, 45 is a BITTER BERRY, 50 a GOLD BERRY, and
 *    seven more are a plain BERRY. This is the famous one, and it is a
 *    feature of the games rather than a bug in them.
 *  - **HP, Attack, Defense and Speed are not recalculated.** They are copied
 *    across as the numbers Generation I worked out from Generation I's base
 *    stats. They stay wrong, by Generation II's arithmetic, until the Pokémon
 *    next levels up.
 *  - **Both Special stats are recalculated**, from Generation II's base
 *    Special Attack and Special Defense, the one Generation I Special DV, and
 *    the one Special stat experience word. `CalcMonStatC` reads the same DV
 *    and the same word for both, which is why a traded Pokémon's two Special
 *    stats share everything but their bases.
 *  - **Happiness is set to 70**, `BASE_HAPPINESS`.
 *  - **Pokérus and the caught data are zeroed.**
 *  - Everything else — DVs, stat experience, experience, moves, PP, the OT's
 *    name and id, the nickname, the level, the current HP and status — is
 *    copied unchanged. Generation II's DV and stat experience tables are
 *    Generation I's five, under the same names, so nothing has to be moved.
 *
 * What the games do NOT do, and neither does this: nothing is re-rolled,
 * nothing is renamed, and no Pokémon is refused. Generation I's own side
 * refuses to accept what it cannot describe, but that is the journey back.
 */
object TimeCapsule {

    /** `constants/pokemon_data_constants.asm`: what a traded Pokémon feels. */
    const val BASE_HAPPINESS = 70

    /**
     * What a Pokémon was before it went forward.
     *
     * Two things the conversion spends and cannot give back: the catch rate,
     * which becomes an item, and the single Special stat, which becomes two.
     * They are written down here so what a Pokémon was is still a fact about
     * it rather than something the app quietly overwrote.
     */
    data class Record(
        val carriedAtEpochMillis: Long,
        /** The species under Generation I's spelling of its name. */
        val speciesId: String,
        val level: Int,
        /** The five Generation I stats, under `Gen1Stat`'s keys. */
        val stats: Map<String, Int>,
        /** The catch rate that was spent, if it had one. */
        val catchRate: Int?,
        /** What that catch rate turned into. */
        val heldItem: String?,
    ) {
        fun toLua(): LuaValue.Table = LuaValue.Table().apply {
            this["carriedAt"] = luaNum(carriedAtEpochMillis.toDouble())
            this["species"] = luaStr(speciesId)
            this["level"] = luaNum(level)
            this["stats"] = LuaValue.Table().apply {
                stats.forEach { (key, value) -> this[key] = luaNum(value) }
            }
            catchRate?.let { this["catchRate"] = luaNum(it) }
            heldItem?.let { this["item"] = luaStr(it) }
        }

        companion object {
            fun fromLua(table: LuaValue.Table): Record? {
                val species = table["species"].asString() ?: return null
                val stats = table["stats"].asTable()
                return Record(
                    carriedAtEpochMillis = (table["carriedAt"] as? LuaValue.Num)?.value?.toLong()
                        ?: 0L,
                    speciesId = species,
                    level = table["level"].asInt() ?: 1,
                    stats = Gen1Stat.ORDER.mapNotNull { stat ->
                        stats?.get(stat.key).asInt()?.let { stat.key to it }
                    }.toMap(),
                    catchRate = table["catchRate"].asInt(),
                    heldItem = table["item"].asString(),
                )
            }
        }
    }

    /** A Pokémon that went forward, and what it was on the way in. */
    data class Carried(val data: LuaValue.Table, val record: Record)

    /**
     * The item a Generation I catch rate arrives holding.
     *
     * Null where the byte is zero or names no item — a Pokémon with nothing
     * in that slot arrives holding nothing, which is what the game shows.
     */
    fun heldItemFor(catchRate: Int?): String? {
        val rate = catchRate?.takeIf { it in 1..255 } ?: return null
        GEN2_CATCH_RATE_ITEMS[rate]?.let { return it }
        return GEN2_ITEM_ORDER.getOrNull(rate)
    }

    /**
     * Whether this one is a Generation I Pokémon with somewhere to go.
     *
     * A Pokémon already in Generation II has made the trip, and one whose
     * species neither table knows is a mod's and not something to guess at.
     */
    fun canCarry(pokemon: Gen1Pokemon): Boolean =
        pokemon.generation == 1 &&
            !hasCrossed(pokemon.raw) &&
            pokemon.speciesId?.let { Gen2Data.species(Gen2Data.idOf(it)) } != null

    /**
     * Whether this table has already been through, read off the table itself.
     *
     * Which generation a Pokémon is in is the app's bookkeeping and lives
     * beside the table rather than in it, so a table handed over on its own
     * cannot be asked. These three fields can: a Generation I Pokémon has no
     * caught data, no Pokérus and no Special Attack, and a converted one has
     * all three. Belt as well as braces — the storage checks the generation
     * it recorded — because converting twice would spend a catch rate that
     * has already been spent.
     */
    fun hasCrossed(table: LuaValue.Table): Boolean =
        table[LuaKey.Name("caughtData")] != null ||
            table[LuaKey.Name("pokerus")] != null ||
            table["stats"].asTable()?.get(LuaKey.Name("specialAttack")) != null

    /**
     * The same Pokémon, as Generation II holds it, and a record of what it
     * was. Null for one that has no business making the trip.
     *
     * The raw table is copied rather than edited: the Pokémon that went in is
     * still the Pokémon that went in, and anything that has to roll this back
     * has something to roll back to.
     */
    fun carry(source: LuaValue.Table, at: Long): Carried? {
        val before = Gen1Pokemon(source, generation = 1)
        if (!canCarry(before)) return null
        val speciesId = before.speciesId ?: return null
        val gen2Species = Gen2Data.species(Gen2Data.idOf(speciesId)) ?: return null

        val record = Record(
            carriedAtEpochMillis = at,
            speciesId = speciesId,
            level = before.level,
            stats = before.stats.map { (stat, value) -> stat.key to value }.toMap(),
            catchRate = before.catchRate,
            heldItem = heldItemFor(before.catchRate),
        )

        val data = source.deepCopy()
        data["species"] = luaStr(gen2Species.id)

        // The catch rate is spent on the item and stops being a field: in a
        // Generation II save that byte is the item and nothing else.
        record.heldItem?.let { data["item"] = luaStr(it) }
        data.remove(LuaKey.Name("catchRate"))

        // `exp` in Generation I, `experience` in Generation II — the same
        // number under the name that generation's save writes.
        before.exp.takeIf { source[LuaKey.Name("exp")] != null }?.let {
            data["experience"] = luaNum(it)
            data.remove(LuaKey.Name("exp"))
        }

        // Special becomes two stats, each from its own base and both from the
        // one Special DV and the one Special stat experience word.
        val dv = before.dvs[Gen1Stat.SPECIAL] ?: 0
        val statExp = before.statExp[Gen1Stat.SPECIAL] ?: 0
        val stats = data["stats"].asTable()
        if (stats != null) {
            stats["specialAttack"] = luaNum(
                Gen1Stats.calcOne(gen2Species.baseSpecialAttack, dv, statExp, before.level, false)
            )
            stats["specialDefense"] = luaNum(
                Gen1Stats.calcOne(gen2Species.baseSpecialDefense, dv, statExp, before.level, false)
            )
            stats.remove(LuaKey.Name("special"))
            // A Generation II party Pokémon carries its maximum beside the
            // stat block as well; the grid and the status screen read it.
            stats[Gen1Stat.HP.key].asInt()?.let { data["maxHp"] = luaNum(it) }
        }

        data["happiness"] = luaNum(BASE_HAPPINESS)
        data["pokerus"] = luaNum(0)
        data["caughtData"] = luaNum(0)

        // Generation II keeps a move's maximum PP beside it. Read out of
        // Generation II's table, because that is the generation the Pokémon
        // is in now and seventeen of the originals changed.
        data["moves"].asTable()?.array()?.forEach { entry ->
            val move = entry.asTable() ?: return@forEach
            val id = move["id"].asString() ?: return@forEach
            val base = Gen2Data.move(id)?.basePp ?: return@forEach
            val ups = move["ppUps"].asInt() ?: 0
            move["maxPp"] = luaNum(base + ups * (base / 5))
        }

        return Carried(data, record)
    }
}
