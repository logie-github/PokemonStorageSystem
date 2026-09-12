package com.logie.gen1storage

import com.logie.gen1storage.pokemon.Gen1Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated Pokédex table, checked against what the cartridge prints.
 *
 * These are spot values read off pokered and pokeyellow rather than off a
 * wiki: if the generator ever mis-aligns the pointer table against the species
 * constants — which it did once, and silently — every entry shifts by one and
 * only a check like this notices.
 */
class Gen1DexEntryTest {

    @Test
    fun `every species has an entry`() {
        assertEquals(151, Gen1Data.dex.size)
        Gen1Data.species.forEach { species ->
            assertNotNull(species.id, Gen1Data.dexEntry(species.id))
        }
    }

    @Test
    fun `the classification, height and weight are the cartridge's`() {
        val pikachu = Gen1Data.dexEntry("PIKACHU")!!
        assertEquals("MOUSE", pikachu.category)
        assertEquals("1'04\"", pikachu.heightText)
        assertEquals("13.0lb", pikachu.weightText)

        val bulbasaur = Gen1Data.dexEntry("BULBASAUR")!!
        assertEquals("SEED", bulbasaur.category)
        assertEquals("2'04\"", bulbasaur.heightText)
        assertEquals("15.0lb", bulbasaur.weightText)
    }

    @Test
    fun `Yellow tells it differently from Red`() {
        val bulbasaur = Gen1Data.dexEntry("BULBASAUR")!!
        assertTrue(bulbasaur.red.startsWith("A strange seed"))
        assertTrue(bulbasaur.yellow!!.startsWith("It can go for days"))

        assertEquals(bulbasaur.red, bulbasaur.forGame("red"))
        assertEquals(bulbasaur.red, bulbasaur.forGame("blue"))
        assertEquals(bulbasaur.yellow, bulbasaur.forGame("yellow"))
        // A save whose version cannot be read gets the entry two of the three
        // cartridges print rather than nothing.
        assertEquals(bulbasaur.red, bulbasaur.forGame(null))
    }

    @Test
    fun `the entry keeps the Pokedex's own line breaks and page`() {
        val lines = Gen1Data.dexEntry("RHYDON")!!.lines("red")
        assertEquals(
            listOf(
                "Protected by an",
                "armor-like hide,",
                "it is capable of",
                "",
                "living in molten",
                "lava of 3,600",
                "degrees",
            ),
            lines,
        )
    }

    @Test
    fun `a species the tables never had has no entry rather than a wrong one`() {
        assertNull(Gen1Data.dexEntry("MISSINGNO"))
        assertNull(Gen1Data.dexEntry(null))
    }
}
