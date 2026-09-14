package com.logie.gen1storage

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat
import com.logie.gen1storage.pokemon.Gen1Stats
import com.logie.gen1storage.pokemon.Gen2Data
import com.logie.gen1storage.pokemon.TimeCapsule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Going forward, exactly as pret/pokecrystal goes forward.
 *
 * `Link_ConvertPartyStruct1to2` in engine/link/link.asm is the whole of the
 * conversion and every assertion here is one of its lines. The two that cost
 * something — the catch rate spent on an item, and the one Special stat split
 * in two — are also the two the app writes down, so they are pinned twice:
 * once in the Pokémon that comes out and once in the record beside it.
 */
class TimeCapsuleTest {

    private fun gen1(
        species: String = "GENGAR",
        level: Int = 50,
        catchRate: Int? = 190,
        dvs: List<Int> = listOf(15, 12, 10, 9),
        statExp: List<Int> = listOf(0, 0, 0, 0, 0),
    ) = SaveFixtures.pokemon(
        species = species,
        level = level,
        catchRate = catchRate,
        dvs = dvs,
        statExp = statExp,
    )

    @Test
    fun `the species keeps its name, under the later game's spelling`() {
        val gengar = TimeCapsule.carry(gen1("GENGAR"), at = 1L)!!
        assertEquals("GENGAR", gengar.data["species"].asString())

        // pokered writes MR_MIME; pokecrystal writes MR__MIME.
        val mime = TimeCapsule.carry(gen1("MR_MIME"), at = 1L)!!
        assertEquals("MR__MIME", mime.data["species"].asString())
        assertEquals("the record keeps what it was", "MR_MIME", mime.record.speciesId)
    }

    @Test
    fun `the catch rate becomes the held item`() {
        // TimeCapsule_ReplaceTeruSama, data/items/catch_rate_items.asm: the
        // eleven rewrites, and everything else read as an item index.
        assertEquals("LEFTOVERS", TimeCapsule.heldItemFor(25))
        assertEquals("BITTER_BERRY", TimeCapsule.heldItemFor(45))
        assertEquals("GOLD_BERRY", TimeCapsule.heldItemFor(50))
        assertEquals("BERRY", TimeCapsule.heldItemFor(90))
        assertEquals("BERRY", TimeCapsule.heldItemFor(255))
        // Not rewritten: the item that simply sits at that index.
        assertEquals("MASTER_BALL", TimeCapsule.heldItemFor(1))
        assertEquals("MOON_STONE", TimeCapsule.heldItemFor(8))
        // Nothing in the slot is nothing held.
        assertNull(TimeCapsule.heldItemFor(0))
        assertNull(TimeCapsule.heldItemFor(null))
    }

    @Test
    fun `the catch rate is spent and stops being a field`() {
        val carried = TimeCapsule.carry(gen1(catchRate = 25), at = 1L)!!
        assertEquals("LEFTOVERS", carried.data["item"].asString())
        assertNull("the byte is the item now", carried.data[LuaKey.Name("catchRate")])
        assertEquals(25, carried.record.catchRate)
        assertEquals("LEFTOVERS", carried.record.heldItem)
    }

    @Test
    fun `both Special stats come from Generation II's bases and one DV`() {
        // engine/pokemon/move_mon.asm CalcMonStatC: STAT_SATK and STAT_SDEF
        // both branch to .Special, so the one Special DV and the one Special
        // stat experience word feed both.
        val level = 50
        val specialDv = 9
        val source = gen1(species = "GENGAR", level = level, dvs = listOf(15, 12, 10, specialDv))
        val carried = TimeCapsule.carry(source, at = 1L)!!
        val gengar = Gen2Data.species("GENGAR")!!

        val stats = carried.data["stats"].asTable()!!
        assertEquals(
            Gen1Stats.calcOne(gengar.baseSpecialAttack, specialDv, 0, level, false),
            stats["specialAttack"].asInt(),
        )
        assertEquals(
            Gen1Stats.calcOne(gengar.baseSpecialDefense, specialDv, 0, level, false),
            stats["specialDefense"].asInt(),
        )
        // GENGAR is 130 / 75 in Generation II, so the two differ.
        assertTrue(stats["specialAttack"].asInt()!! > stats["specialDefense"].asInt()!!)
        assertNull("the single stat is gone", stats[LuaKey.Name("special")])
    }

    @Test
    fun `HP, Attack, Defense and Speed are carried over, not recalculated`() {
        // Link_ConvertPartyStruct1to2 copies MON_MAXHP through MON_SAT
        // verbatim. They stay Generation I's numbers until the next level.
        val source = gen1()
        val before = Gen1Pokemon(source).stats
        val carried = TimeCapsule.carry(source, at = 1L)!!
        val after = carried.data["stats"].asTable()!!

        listOf(Gen1Stat.HP, Gen1Stat.ATTACK, Gen1Stat.DEFENSE, Gen1Stat.SPEED).forEach { stat ->
            assertEquals(stat.name, before[stat], after[stat.key].asInt())
        }
        assertEquals("and the record has them too", before[Gen1Stat.HP], carried.record.stats["hp"])
    }

    @Test
    fun `happiness is seventy and the rest is zeroed`() {
        val carried = TimeCapsule.carry(gen1(), at = 1L)!!
        assertEquals(70, carried.data["happiness"].asInt())
        assertEquals(TimeCapsule.BASE_HAPPINESS, carried.data["happiness"].asInt())
        assertEquals(0, carried.data["pokerus"].asInt())
        assertEquals(0, carried.data["caughtData"].asInt())
    }

    @Test
    fun `everything else crosses untouched`() {
        val source = gen1(dvs = listOf(15, 12, 10, 9), statExp = listOf(11, 22, 33, 44, 55))
        val before = Gen1Pokemon(source)
        val carried = TimeCapsule.carry(source, at = 1L)!!
        val after = Gen1Pokemon(carried.data, generation = 2)

        assertEquals(before.level, after.level)
        assertEquals(before.currentHp, after.currentHp)
        assertEquals(before.otName, after.otName)
        assertEquals(before.otId, after.otId)
        assertEquals(before.nickname, after.nickname)
        assertEquals(before.dvs, after.dvs)
        assertEquals(before.statExp, after.statExp)
        assertEquals(before.moves.map { it.id }, after.moves.map { it.id })
        assertEquals(before.moves.map { it.pp }, after.moves.map { it.pp })
        // `exp` in Generation I, `experience` in Generation II.
        assertEquals(before.exp, carried.data["experience"].asInt())
        assertNull(carried.data[LuaKey.Name("exp")])
    }

    @Test
    fun `the Pokemon that went in is not the one that was changed`() {
        val source = gen1(catchRate = 25)
        TimeCapsule.carry(source, at = 1L)
        assertEquals("still Generation I", 25, Gen1Pokemon(source).catchRate)
        assertNull(source[LuaKey.Name("happiness")])
    }

    @Test
    fun `a move is read against the generation the Pokemon is now in`() {
        // KARATE CHOP is NORMAL in Generation I and FIGHTING in Generation II.
        val source = SaveFixtures.pokemon(
            species = "MACHOP",
            moves = listOf(Triple("KARATE_CHOP", 25, null)),
        )
        assertEquals("NORMAL", Gen1Pokemon(source).moves.single().move?.type)
        val carried = TimeCapsule.carry(source, at = 1L)!!
        assertEquals("FIGHTING", Gen1Pokemon(carried.data, generation = 2).moves.single().move?.type)
        assertEquals("KARATE CHOP", Gen1Pokemon(carried.data, generation = 2).moves.single().displayName)
    }

    @Test
    fun `the tables disagree about seventeen of the originals and no more`() {
        val changed = Gen1Data.moves.count { gen1 ->
            val gen2 = Gen2Data.move(gen1.id)
            gen2 == null || gen1.type != gen2.type || gen1.power != gen2.power ||
                gen1.accuracy != gen2.accuracy || gen1.basePp != gen2.basePp
        }
        assertEquals(17, changed)
        // Every Generation I move is still there, at the same number.
        Gen1Data.moves.forEach { move ->
            val gen2 = Gen2Data.move(move.id)
            assertNotNull(move.id, gen2)
            assertEquals(move.id, move.internalIndex, gen2!!.internalIndex)
        }
    }

    @Test
    fun `one that has already gone cannot go again`() {
        val carried = TimeCapsule.carry(gen1(catchRate = 25), at = 1L)!!
        assertTrue(TimeCapsule.hasCrossed(carried.data))
        assertFalse(TimeCapsule.canCarry(Gen1Pokemon(carried.data, generation = 2)))
        // And read off the table alone, which is all a caller may have: a
        // second trip would spend a catch rate that is already an item.
        assertFalse(TimeCapsule.canCarry(Gen1Pokemon(carried.data, generation = 1)))
        assertNull(TimeCapsule.carry(carried.data, at = 2L))
        assertFalse("a Generation I one has not", TimeCapsule.hasCrossed(gen1()))
    }

    @Test
    fun `a species neither table knows is left alone`() {
        assertNull(TimeCapsule.carry(gen1(species = "MODDEDMON"), at = 1L))
    }

    @Test
    fun `the record survives being written down and read back`() {
        val carried = TimeCapsule.carry(gen1(catchRate = 45), at = 1234L)!!
        val again = TimeCapsule.Record.fromLua(carried.record.toLua())
        assertEquals(carried.record, again)
    }
}
