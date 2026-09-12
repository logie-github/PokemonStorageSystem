package com.logie.gen1storage

import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat
import com.logie.gen1storage.pokemon.Gen1Stats
import com.logie.gen1storage.pokemon.Gen1TradeEvolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gen1TradeEvolutionTest {

    @Test
    fun `the four are the four pokered marks EVOLVE_TRADE`() {
        assertEquals(
            mapOf(
                "KADABRA" to "ALAKAZAM",
                "MACHOKE" to "MACHAMP",
                "GRAVELER" to "GOLEM",
                "HAUNTER" to "GENGAR",
            ),
            Gen1TradeEvolution.BY_TRADE,
        )
        // Every one of them, and its result, is a species the tables know.
        Gen1TradeEvolution.BY_TRADE.forEach { (from, to) ->
            assertTrue(from, Gen1Data.species(from) != null)
            assertTrue(to, Gen1Data.species(to) != null)
        }
    }

    @Test
    fun `nothing else evolves by trading`() {
        assertFalse(Gen1TradeEvolution.evolves("PIKACHU"))
        assertFalse(Gen1TradeEvolution.evolves(null))
        assertNull(Gen1TradeEvolution.evolve(SaveFixtures.pokemon(species = "PIKACHU")))
    }

    @Test
    fun `a trade leaves everything but the species and what follows from it`() {
        val mon = SaveFixtures.pokemon(
            species = "MACHOKE",
            level = 40,
            nickname = "MUSCLES",
            ot = "ASH",
            otId = 12345,
            exp = 91125,
        )
        val before = Gen1Pokemon(mon.deepCopy())

        assertEquals("MACHAMP", Gen1TradeEvolution.evolve(mon))
        val after = Gen1Pokemon(mon)

        assertEquals("MACHAMP", after.speciesId)
        // The Pokémon is the same Pokémon.
        assertEquals("MUSCLES", after.nickname)
        assertEquals(before.otName, after.otName)
        assertEquals(before.otId, after.otId)
        assertEquals(before.exp, after.exp)
        assertEquals(before.level, after.level)
        assertEquals(before.dvs, after.dvs)
        assertEquals(before.statExp, after.statExp)
        assertEquals(before.moves, after.moves)
    }

    @Test
    fun `the stats are the new form's, worked out the same way the game does`() {
        val mon = SaveFixtures.pokemon(species = "GRAVELER", level = 37)
        val before = Gen1Pokemon(mon.deepCopy())
        assertEquals("GOLEM", Gen1TradeEvolution.evolve(mon))

        val golem = Gen1Data.species("GOLEM")!!
        val expected = Gen1Stats.calc(golem, 37, before.dvs, before.statExp)
        assertEquals(expected, Gen1Pokemon(mon).stats)
    }

    @Test
    fun `current HP moves by exactly what the maximum moved by`() {
        val mon = SaveFixtures.pokemon(species = "HAUNTER", level = 30, hp = 20)
        val beforeMax = Gen1Pokemon(mon).stats.getValue(Gen1Stat.HP)

        Gen1TradeEvolution.evolve(mon)
        val afterMax = Gen1Pokemon(mon).stats.getValue(Gen1Stat.HP)

        // Gengar and Haunter share a base HP of 45 at this size, so the gain
        // is whatever the tables say rather than a number assumed here.
        assertEquals(20 + (afterMax - beforeMax), mon["hp"].asInt())
        assertTrue(mon["hp"].asInt()!! <= afterMax)
    }

    @Test
    fun `a Pokemon that cannot evolve is not touched at all`() {
        val mon = SaveFixtures.pokemon(species = "PIKACHU")
        val before = LuaWriter.encodeValue(mon)
        Gen1TradeEvolution.evolve(mon)
        assertEquals(before, LuaWriter.encodeValue(mon))
    }
}
