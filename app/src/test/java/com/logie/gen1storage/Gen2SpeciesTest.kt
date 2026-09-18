package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Species
import com.logie.gen1storage.pokemon.Gen1Stat
import com.logie.gen1storage.pokemon.Gender
import com.logie.gen1storage.pokemon.Gen2Data
import com.logie.gen1storage.pokemon.Gen2Species
import com.logie.gen1storage.sprites.gen2SpriteFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Pokémon is read with its own generation's numbers.
 *
 * Generation II rebalanced most of the original 151 — it split Special in two
 * and gave MAGNEMITE a second type — so the two tables disagree about a great
 * many Pokémon whose names are identical. Which table answers is decided by
 * where the Pokémon came from and by nothing else: a MAGNEMITE out of a Red
 * save is the Generation I one however many Gold saves are on the shelf beside
 * it.
 */
class Gen2SpeciesTest {

    private fun mon(species: String): LuaValue.Table =
        SaveFixtures.pokemon(species = species)

    @Test
    fun `the table is the whole of Generation II, in Pokedex order`() {
        assertEquals(Gen2Data.SPECIES_COUNT, Gen2Data.species.size)
        Gen2Data.species.forEachIndexed { index, species ->
            assertEquals(index + 1, species.dexNumber)
        }
        assertEquals(Gen2Data.species.size, Gen2Data.species.map { it.id }.toSet().size)
        assertEquals("BULBASAUR", Gen2Data.species.first().id)
        assertEquals("CELEBI", Gen2Data.species.last().id)
    }

    @Test
    fun `pokecrystal spells three of the originals differently`() {
        // pokered writes MR_MIME and FARFETCHD; pokecrystal writes MR__MIME
        // and FARFETCH_D, and a Generation II save carries pokecrystal's
        // spelling. Two tables keyed by the same id would have had to pick one.
        assertNotNull(Gen2Data.species("MR__MIME"))
        assertNotNull(Gen2Data.species("FARFETCH_D"))
        assertNotNull(Gen2Data.species("HO_OH"))
        assertNotNull(Gen1Data.species("MR_MIME"))
        assertNotNull(Gen1Data.species("FARFETCHD"))
        assertNull(Gen2Data.species("MR_MIME"))
        assertNull(Gen2Data.species("FARFETCHD"))
    }

    @Test
    fun `MAGNEMITE gains STEEL only for the Pokemon that came from Generation II`() {
        val fromRed = Gen1Pokemon(mon("MAGNEMITE"), generation = 1)
        val fromGold = Gen1Pokemon(mon("MAGNEMITE"), generation = 2)

        assertEquals(listOf("ELECTRIC"), fromRed.species?.types)
        assertEquals(listOf("ELECTRIC", "STEEL"), fromGold.species?.types)
        assertEquals(1, fromRed.species?.generation)
        assertEquals(2, fromGold.species?.generation)
    }

    @Test
    fun `Special is one stat in Generation I and two in Generation II`() {
        val gen1 = Gen1Data.species("GENGAR") as Gen1Species
        val gen2 = Gen2Data.species("GENGAR") as Gen2Species

        assertEquals(130, gen1.baseSpecial)
        assertEquals(130, gen2.baseSpecialAttack)
        assertEquals(75, gen2.baseSpecialDefense)
    }

    @Test
    fun `Johto is unknown to Generation I and every original is known to both`() {
        assertNull(Gen1Data.species("CHIKORITA"))
        assertNotNull(Gen2Data.species("CHIKORITA"))
        assertNull(Gen1Pokemon(mon("TYPHLOSION"), generation = 1).species)
        assertEquals(157, Gen1Pokemon(mon("TYPHLOSION"), generation = 2).species?.dexNumber)

        // Every Generation I species is in Generation II's table too, under
        // whichever name that game gives it.
        val renamed = mapOf("MR_MIME" to "MR__MIME", "FARFETCHD" to "FARFETCH_D")
        Gen1Data.species.forEach { species ->
            val gen2Id = renamed[species.id] ?: species.id
            assertNotNull("no Generation II row for ${species.id}", Gen2Data.species(gen2Id))
        }
    }

    @Test
    fun `dex numbers agree for the first 151`() {
        val renamed = mapOf("MR_MIME" to "MR__MIME", "FARFETCHD" to "FARFETCH_D")
        Gen1Data.species.forEach { species ->
            val gen2 = Gen2Data.species(renamed[species.id] ?: species.id)!!
            assertEquals(species.id, species.dexNumber, gen2.dexNumber)
        }
    }

    @Test
    fun `a Generation II save hands out Generation II Pokemon`() {
        val save = Gen1RecompSave(
            LuaParser.parse(
                LuaWriter.encode(
                    LuaValue.Table().apply {
                        this["version"] = luaStr("gold")
                        this["generation"] = luaNum(2)
                        this["party"] = LuaValue.Table().apply {
                            setArray(listOf(mon("MAGNEMITE")))
                        }
                        this["boxes"] = LuaValue.Table().apply {
                            this[1] = LuaValue.Table().apply { setArray(listOf(mon("CHIKORITA"))) }
                        }
                    }
                )
            )
        )

        assertTrue(save.isGen2)
        assertEquals(2, save.party.single().generation)
        assertEquals(listOf("ELECTRIC", "STEEL"), save.party.single().species?.types)

        val stored = save.boxes.first().single()
        assertEquals(2, stored.generation)
        assertEquals("CHIKORITA", stored.displayName)
        assertEquals(152, stored.species?.dexNumber)
    }

    @Test
    fun `a Generation I save is untouched by any of this`() {
        val save = Gen1RecompSave(
            LuaParser.parse(
                LuaWriter.encode(
                    LuaValue.Table().apply {
                        this["version"] = luaStr("red")
                        this["party"] = LuaValue.Table().apply {
                            setArray(listOf(mon("MAGNEMITE")))
                        }
                    }
                )
            )
        )

        val magnemite = save.party.single()
        assertEquals(1, magnemite.generation)
        assertEquals(listOf("ELECTRIC"), magnemite.species?.types)
        assertTrue(magnemite.species is Gen1Species)
    }

    @Test
    fun `a stored Pokemon is Generation I until something deliberately says otherwise`() {
        // The default before anything else says otherwise, since Generation
        // I is what every save this app read before Generation II's tables
        // existed was.
        assertEquals(1, Gen1Pokemon(mon("PIKACHU")).generation)
        assertEquals(2, Gen1Pokemon(mon("PIKACHU"), generation = 2).copy().generation)
    }

    @Test
    fun `a species' gender ratio matches what the games are known to show`() {
        assertEquals(127, Gen2Data.species("PIKACHU")!!.genderRatio)
        assertEquals(254, Gen2Data.species("NIDORAN_F")!!.genderRatio)
        assertEquals(0, Gen2Data.species("NIDORAN_M")!!.genderRatio)
        assertEquals(Gen2Species.GENDER_UNKNOWN, Gen2Data.species("MAGNEMITE")!!.genderRatio)
        assertEquals(Gen2Species.GENDER_UNKNOWN, Gen2Data.species("DITTO")!!.genderRatio)
    }

    @Test
    fun `gender comes from the Attack and Speed DVs, weighed against the ratio`() {
        val pikachu = Gen2Data.species("PIKACHU")!! // 50-50, ratio 127
        assertEquals(Gender.FEMALE, pikachu.genderOf(attackDv = 0, speedDv = 0))
        assertEquals(Gender.FEMALE, pikachu.genderOf(attackDv = 7, speedDv = 15))
        assertEquals(Gender.MALE, pikachu.genderOf(attackDv = 8, speedDv = 0))
        assertEquals(Gender.MALE, pikachu.genderOf(attackDv = 15, speedDv = 15))

        // Always one or the other regardless of DVs.
        assertEquals(Gender.FEMALE, Gen2Data.species("NIDORAN_F")!!.genderOf(15, 15))
        assertEquals(Gender.MALE, Gen2Data.species("NIDORAN_M")!!.genderOf(0, 0))

        // No gender at all, whatever the DVs say.
        assertNull(Gen2Data.species("MAGNEMITE")!!.genderOf(0, 0))
        assertNull(Gen2Data.species("MAGNEMITE")!!.genderOf(15, 15))
    }

    @Test
    fun `a Pokemon's own gender is read off its species and its DVs`() {
        val gen1Pikachu = Gen1Pokemon(mon("PIKACHU"), generation = 1)
        assertNull("Generation I never asked this", gen1Pikachu.gender)

        val gen2Pikachu = Gen1Pokemon(
            SaveFixtures.pokemon(species = "PIKACHU", dvs = listOf(15, 15, 15, 15)),
            generation = 2,
        )
        assertEquals(
            Gen2Data.species("PIKACHU")!!.genderOf(
                gen2Pikachu.dvs.getValue(Gen1Stat.ATTACK),
                gen2Pikachu.dvs.getValue(Gen1Stat.SPEED),
            ),
            gen2Pikachu.gender,
        )

        val magnemite = Gen1Pokemon(mon("MAGNEMITE"), generation = 2)
        assertNull(magnemite.gender)
    }

    @Test
    fun `every Generation II species maps to art upstream has`() {
        // The folder names in pokegold and pokecrystal, checked against the
        // whole table rather than the handful that needed special-casing.
        Gen2Data.speciesIds.forEach { id ->
            val folder = gen2SpriteFolder(id)
            assertTrue("$id -> $folder", folder.isNotEmpty())
            assertTrue("$id -> $folder", folder.all { it.isLetterOrDigit() || it == '_' })
        }
        assertEquals("mr__mime", gen2SpriteFolder("MR__MIME"))
        assertEquals("farfetch_d", gen2SpriteFolder("FARFETCH_D"))
        assertEquals("ho_oh", gen2SpriteFolder("HO_OH"))
        assertEquals("porygon2", gen2SpriteFolder("PORYGON2"))
    }
}
