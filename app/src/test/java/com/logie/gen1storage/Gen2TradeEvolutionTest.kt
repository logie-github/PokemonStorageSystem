package com.logie.gen1storage

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat
import com.logie.gen1storage.pokemon.Gen2Data
import com.logie.gen1storage.pokemon.Gen2Stat
import com.logie.gen1storage.pokemon.Gen2TradeEvolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gen2TradeEvolutionTest {

    private fun mon(
        species: String,
        level: Int = 40,
        item: String? = null,
        dvs: List<Int> = listOf(15, 12, 10, 9),
        statExp: List<Int> = listOf(0, 0, 0, 0, 0),
        hp: Int? = null,
    ) = SaveFixtures.pokemon(
        species = species,
        level = level,
        dvs = dvs,
        statExp = statExp,
        hp = hp,
        extraFields = item?.let { mapOf("item" to luaStr(it)) } ?: emptyMap(),
    )

    @Test
    fun `the ten are the four plus six new ones, each behind a trade`() {
        assertEquals(
            setOf("KADABRA", "MACHOKE", "GRAVELER", "HAUNTER", "POLIWHIRL", "SLOWPOKE", "SEADRA", "ONIX", "SCYTHER", "PORYGON"),
            Gen2TradeEvolution.BY_TRADE.keys,
        )
        Gen2TradeEvolution.BY_TRADE.forEach { (from, evolution) ->
            assertTrue(from, Gen2Data.species(from) != null)
            assertTrue(evolution.to, Gen2Data.species(evolution.to) != null)
        }
        // Only the six new ones need an item; the original four still need
        // nothing but the trade itself.
        val needsItem = Gen2TradeEvolution.BY_TRADE.filterValues { it.item != null }.keys
        assertEquals(setOf("POLIWHIRL", "SLOWPOKE", "SEADRA", "ONIX", "SCYTHER", "PORYGON"), needsItem)
    }

    @Test
    fun `the four that need no item evolve holding anything but an Everstone`() {
        assertEquals("ALAKAZAM", Gen2TradeEvolution.evolutionOf("KADABRA", heldItem = null))
        assertEquals("ALAKAZAM", Gen2TradeEvolution.evolutionOf("KADABRA", heldItem = "LEFTOVERS"))
        assertNull(Gen2TradeEvolution.evolutionOf("KADABRA", heldItem = "EVERSTONE"))
    }

    @Test
    fun `the six new ones only evolve holding their own item`() {
        assertEquals("POLITOED", Gen2TradeEvolution.evolutionOf("POLIWHIRL", heldItem = "KINGS_ROCK"))
        assertNull(Gen2TradeEvolution.evolutionOf("POLIWHIRL", heldItem = null))
        assertNull(Gen2TradeEvolution.evolutionOf("POLIWHIRL", heldItem = "METAL_COAT"))

        assertEquals("SLOWKING", Gen2TradeEvolution.evolutionOf("SLOWPOKE", heldItem = "KINGS_ROCK"))
        assertEquals("KINGDRA", Gen2TradeEvolution.evolutionOf("SEADRA", heldItem = "DRAGON_SCALE"))
        assertEquals("STEELIX", Gen2TradeEvolution.evolutionOf("ONIX", heldItem = "METAL_COAT"))
        assertEquals("SCIZOR", Gen2TradeEvolution.evolutionOf("SCYTHER", heldItem = "METAL_COAT"))
        assertEquals("PORYGON2", Gen2TradeEvolution.evolutionOf("PORYGON", heldItem = "UP_GRADE"))
    }

    @Test
    fun `nothing else evolves by trading`() {
        assertFalse(Gen2TradeEvolution.evolves("PIKACHU", heldItem = null))
        assertFalse(Gen2TradeEvolution.evolves(null, heldItem = null))
        assertNull(Gen2TradeEvolution.evolve(mon("PIKACHU")))
    }

    @Test
    fun `an evolution needing no item leaves whatever is held untouched`() {
        val data = mon("KADABRA", item = "LEFTOVERS")
        assertEquals("ALAKAZAM", Gen2TradeEvolution.evolve(data))
        assertEquals("LEFTOVERS", Gen1Pokemon(data, generation = 2).heldItem)
    }

    @Test
    fun `the required item is spent on a successful evolution`() {
        val data = mon("ONIX", item = "METAL_COAT")
        assertEquals("STEELIX", Gen2TradeEvolution.evolve(data))
        assertNull(Gen1Pokemon(data, generation = 2).heldItem)
    }

    @Test
    fun `holding the wrong item changes nothing`() {
        val data = mon("ONIX", item = "KINGS_ROCK")
        val before = LuaWriter.encodeValue(data)
        assertNull(Gen2TradeEvolution.evolve(data))
        assertEquals(before, LuaWriter.encodeValue(data))
    }

    @Test
    fun `an Everstone refuses every trade evolution, item or not`() {
        assertNull(Gen2TradeEvolution.evolve(mon("KADABRA", item = "EVERSTONE")))
        assertNull(Gen2TradeEvolution.evolve(mon("ONIX", item = "EVERSTONE")))
    }

    @Test
    fun `a trade leaves everything but the species and what follows from it`() {
        val data = SaveFixtures.pokemon(
            species = "SCYTHER", level = 30, nickname = "SNIPPY", ot = "ASH",
            dvs = listOf(15, 12, 10, 9), statExp = listOf(11, 22, 33, 44, 55),
            extraFields = mapOf("item" to luaStr("METAL_COAT")),
        )
        val before = Gen1Pokemon(data.deepCopy(), generation = 2)

        assertEquals("SCIZOR", Gen2TradeEvolution.evolve(data))
        val after = Gen1Pokemon(data, generation = 2)

        assertEquals("SCIZOR", after.speciesId)
        assertEquals("SNIPPY", after.nickname)
        assertEquals(before.otName, after.otName)
        assertEquals(before.level, after.level)
        assertEquals(before.dvs, after.dvs)
        assertEquals(before.statExp, after.statExp)
    }

    @Test
    fun `all five stats are the new form's, with Special split by its own bases`() {
        val data = mon("PORYGON", item = "UP_GRADE", level = 25)
        assertEquals("PORYGON2", Gen2TradeEvolution.evolve(data))

        val porygon2 = Gen2Data.species("PORYGON2")!!
        val after = Gen1Pokemon(data, generation = 2)
        val stats = data["stats"] as LuaValue.Table

        Gen2Stat.ORDER.forEach { stat ->
            assertTrue(stat.key, stats[stat.key].asInt() != null)
        }
        // Special Attack and Special Defense differ, so the split really
        // happened rather than one value being copied into both.
        assertTrue(
            stats[Gen2Stat.SPECIAL_ATTACK.key].asInt() != stats[Gen2Stat.SPECIAL_DEFENSE.key].asInt() ||
                porygon2.baseSpecialAttack == porygon2.baseSpecialDefense,
        )
        assertEquals(data["maxHp"].asInt(), after.maxHp)
    }

    @Test
    fun `current HP moves by exactly what the maximum moved by`() {
        val data = mon("SEADRA", item = "DRAGON_SCALE", level = 30, hp = 20)
        val beforeMax = Gen1Pokemon(data).stats.getValue(Gen1Stat.HP)

        Gen2TradeEvolution.evolve(data)
        val afterMax = data["maxHp"].asInt()!!

        assertEquals(20 + (afterMax - beforeMax), data["hp"].asInt())
        assertTrue(data["hp"].asInt()!! <= afterMax)
    }

    @Test
    fun `a Pokemon that cannot evolve is not touched at all`() {
        val data = mon("PIKACHU")
        val before = LuaWriter.encodeValue(data)
        Gen2TradeEvolution.evolve(data)
        assertEquals(before, LuaWriter.encodeValue(data))
    }
}
