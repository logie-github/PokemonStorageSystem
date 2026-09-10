package com.logie.gen1storage

import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Stat
import com.logie.gen1storage.pokemon.Gen1Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The species and move tables are generated from pret/pokered, so these tests
 * guard the generator's output rather than restating it: the counts, a few
 * fixed points, and the ids Gen1Recomp actually writes into a save.
 */
class Gen1DataTest {

    @Test
    fun `all 151 species and 165 moves are present`() {
        assertEquals(151, Gen1Data.species.size)
        assertEquals(165, Gen1Data.moves.size)
        assertEquals(151, Gen1Data.species.map { it.dexNumber }.distinct().size)
        assertEquals((1..151).toList(), Gen1Data.species.map { it.dexNumber }.sorted())
    }

    @Test
    fun `internal indices match the cartridge order`() {
        // pokemon_constants.asm: RHYDON is $01, MEW is $15, MR_MIME is $2A.
        assertEquals(1, Gen1Data.species("RHYDON")!!.internalIndex)
        assertEquals(0x15, Gen1Data.species("MEW")!!.internalIndex)
        assertEquals(0x2A, Gen1Data.species("MR_MIME")!!.internalIndex)
        assertEquals(0x40, Gen1Data.species("FARFETCHD")!!.internalIndex)
    }

    @Test
    fun `display names come from the cartridge name table`() {
        assertEquals("NIDORAN♂", Gen1Data.speciesName("NIDORAN_M"))
        assertEquals("NIDORAN♀", Gen1Data.speciesName("NIDORAN_F"))
        assertEquals("MR.MIME", Gen1Data.speciesName("MR_MIME"))
        assertEquals("FARFETCH'D", Gen1Data.speciesName("FARFETCHD"))
    }

    @Test
    fun `base stats match pokered`() {
        val pikachu = Gen1Data.species("PIKACHU")!!
        assertEquals(25, pikachu.dexNumber)
        assertEquals(35, pikachu.baseHp)
        assertEquals(55, pikachu.baseAttack)
        assertEquals(30, pikachu.baseDefense)
        assertEquals(90, pikachu.baseSpeed)
        assertEquals(50, pikachu.baseSpecial)
        assertEquals("ELECTRIC", pikachu.primaryType)
        assertNull(pikachu.secondaryType)

        val charizard = Gen1Data.species("CHARIZARD")!!
        assertEquals("FIRE", charizard.primaryType)
        assertEquals("FLYING", charizard.secondaryType)
    }

    @Test
    fun `move base PP matches pokered`() {
        assertEquals(15, Gen1Data.move("THUNDERBOLT")!!.basePp)
        assertEquals(5, Gen1Data.move("HYPER_BEAM")!!.basePp)
        assertEquals(40, Gen1Data.move("GROWL")!!.basePp)
        assertEquals(10, Gen1Data.move("STRUGGLE")!!.basePp)
        assertEquals("DOUBLESLAP", Gen1Data.moveName("DOUBLESLAP"))
    }

    @Test
    fun `an unknown id degrades to its raw name rather than failing`() {
        assertNull(Gen1Data.species("SOME_MOD_MON"))
        assertEquals("SOME_MOD_MON", Gen1Data.speciesName("SOME_MOD_MON"))
        assertEquals("", Gen1Data.speciesName(null))
    }

    @Test
    fun `CalcStat matches the cartridge formula`() {
        // A level 100 Mewtwo with perfect DVs and maxed stat experience is the
        // standard worked example of home/move_mon.asm CalcStat.
        val mewtwo = Gen1Data.species("MEWTWO")!!
        val perfect = Gen1Stat.ORDER.associateWith { 15 }
        val maxed = Gen1Stat.ORDER.associateWith { 65535 }
        val stats = Gen1Stats.calc(mewtwo, 100, perfect, maxed)
        // base 106/110/90/130/154, DV 15, stat-exp term floor(255/4) = 63:
        // HP  = ((106+15)*2 + 63) * 100/100 + 100 + 10
        // rest= ((base+15)*2 + 63) * 100/100 + 5
        assertEquals(415, stats[Gen1Stat.HP])
        assertEquals(318, stats[Gen1Stat.ATTACK])
        assertEquals(278, stats[Gen1Stat.DEFENSE])
        assertEquals(358, stats[Gen1Stat.SPEED])
        assertEquals(406, stats[Gen1Stat.SPECIAL])
    }

    @Test
    fun `stat experience uses a ceiling square root, quartered and capped`() {
        // ev = floor(min(255, ceil(sqrt(statExp))) / 4)
        assertEquals(0 + 5, Gen1Stats.calcOne(0, 0, 0, 1, false).let { it })
        val withNoExp = Gen1Stats.calcOne(100, 15, 0, 50, false)
        val withSomeExp = Gen1Stats.calcOne(100, 15, 10000, 50, false)
        assertTrue(withSomeExp > withNoExp)
        // 65535 and the 255 cap give the same result: the cap is real.
        assertEquals(
            Gen1Stats.calcOne(100, 15, 65025, 50, false),
            Gen1Stats.calcOne(100, 15, 65535, 50, false),
        )
    }

    @Test
    fun `HP adds level plus ten instead of five`() {
        val hp = Gen1Stats.calcOne(60, 15, 0, 50, true)
        val other = Gen1Stats.calcOne(60, 15, 0, 50, false)
        assertEquals(hp - other, 50 + 10 - 5)
    }

    @Test
    fun `the HP DV is derived from the low bits of the other four`() {
        val dvs = mapOf(
            Gen1Stat.ATTACK to 15,
            Gen1Stat.DEFENSE to 12,
            Gen1Stat.SPEED to 10,
            Gen1Stat.SPECIAL to 9,
        )
        // odd, even, even, odd -> 1000 | 0000 | 0000 | 0001 = 9
        assertEquals(9, Gen1Stats.derivedHpDv(dvs))
    }

    @Test
    fun `ensureStats fills a missing block and leaves a complete one alone`() {
        val statless = SaveFixtures.pokemon(species = "GYARADOS", level = 30, withStats = false, hp = 40)
        assertTrue(Gen1Stats.ensureStats(statless))
        assertNotNull(statless["stats"])
        // A second call is a no-op: a complete block is never recomputed.
        assertTrue(!Gen1Stats.ensureStats(statless))
    }

    @Test
    fun `ensureStats clamps current HP to the recalculated maximum`() {
        val statless = SaveFixtures.pokemon(species = "CATERPIE", level = 5, withStats = false, hp = 999)
        Gen1Stats.ensureStats(statless)
        val pokemon = com.logie.gen1storage.pokemon.Gen1Pokemon(statless)
        assertEquals(pokemon.maxHp, pokemon.currentHp)
    }

    @Test
    fun `an unknown species gets no derived stats rather than wrong ones`() {
        val modded = SaveFixtures.pokemon(species = "MOD_MON", withStats = false)
        assertTrue(!Gen1Stats.ensureStats(modded))
        assertNull(modded["stats"])
    }
}
