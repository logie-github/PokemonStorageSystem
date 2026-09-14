package com.logie.gen1storage

import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen2Data
import com.logie.gen1storage.sound.CryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Generation II Pokédex, in each cartridge's own words.
 *
 * Gold, Silver and Crystal wrote three different entries for every species,
 * and they disagree about more than the prose, so a Pokémon is read in the
 * words of the game it came out of — the same rule Red and Yellow already
 * follow in [Gen1DexEntryTest].
 */
class Gen2DexEntryTest {

    @Test
    fun `there is an entry for every species, and they line up`() {
        assertEquals(Gen2Data.SPECIES_COUNT, Gen2Data.dex.size)
        assertEquals(Gen2Data.speciesIds, Gen2Data.dex.map { it.speciesId })
    }

    @Test
    fun `the three cartridges each print their own`() {
        val bulbasaur = Gen2Data.dexEntry("BULBASAUR")!!
        assertNotEquals(bulbasaur.gold.text, bulbasaur.silver.text)
        assertNotEquals(bulbasaur.gold.text, bulbasaur.crystal.text)
        assertNotEquals(bulbasaur.silver.text, bulbasaur.crystal.text)

        assertEquals(bulbasaur.gold, bulbasaur.forGame("gold"))
        assertEquals(bulbasaur.silver, bulbasaur.forGame("silver"))
        assertEquals(bulbasaur.crystal, bulbasaur.forGame("crystal"))
        // Nothing to go on falls to GOLD, the first of the three.
        assertEquals(bulbasaur.gold, bulbasaur.forGame(null))
        assertEquals(bulbasaur.gold, bulbasaur.forGame("red"))
    }

    @Test
    fun `the cartridges disagree about more than the words`() {
        // Which is why each game keeps a whole page rather than three texts
        // hung off one set of facts. These six are the whole of it.
        val natu = Gen2Data.dexEntry("NATU")!!
        assertEquals("LITTLEBIRD", natu.gold.category)
        assertEquals("LITTLEBIRD", natu.silver.category)
        assertEquals("LITTLE BIRD", natu.crystal.category)

        val teddiursa = Gen2Data.dexEntry("TEDDIURSA")!!
        assertEquals("LITTLEBEAR", teddiursa.gold.category)
        assertEquals("LITTLE BEAR", teddiursa.crystal.category)

        val entei = Gen2Data.dexEntry("ENTEI")!!
        assertEquals(11, entei.gold.heightInches)
        assertEquals(7, entei.crystal.heightInches)

        val tyranitar = Gen2Data.dexEntry("TYRANITAR")!!
        assertEquals(11, tyranitar.silver.heightInches)
        assertEquals(7, tyranitar.crystal.heightInches)
    }

    @Test
    fun `sizes read as the cartridge prints them`() {
        val onix = Gen2Data.dexEntry("ONIX")!!.crystal
        assertEquals("28'10\"", onix.heightText)
        assertEquals("463.0lb", onix.weightText)

        val chikorita = Gen2Data.dexEntry("CHIKORITA")!!.crystal
        assertEquals("LEAF", chikorita.category)
        assertEquals("2'11\"", chikorita.heightText)
        assertEquals("14.0lb", chikorita.weightText)
    }

    @Test
    fun `the text is the cartridge's, with its own control codes spent`() {
        Gen2Data.dex.forEach { entry ->
            listOf(entry.gold, entry.silver, entry.crystal).forEach { page ->
                assertFalse(page.speciesText(), page.text.contains("@"))
                assertFalse(page.speciesText(), page.text.contains("#"))
                assertFalse(page.speciesText(), page.text.contains("<"))
                assertTrue(page.speciesText(), page.text.isNotBlank())
                // Two pages of three lines, with the break kept as a blank
                // one. Eight where the cartridge wrote a blank line of its
                // own: SILVER's TYPHLOSION is the only one in all 753.
                assertTrue(page.speciesText(), page.lines.size in 7..8)
                assertTrue(page.speciesText(), page.lines.any { it.isEmpty() })
                // Whatever the breaks, the prose runs as one line.
                assertFalse(page.speciesText(), page.flowing.contains("\n"))
                assertFalse(page.speciesText(), page.flowing.contains("  "))
            }
        }
        assertEquals(
            8,
            Gen2Data.dexEntry("TYPHLOSION")!!.silver.lines.size,
        )
        // The one escape that had to be spent: # is POKé.
        assertTrue(Gen2Data.dexEntry("ENTEI")!!.silver.text.startsWith("A POKéMON that"))
    }

    private fun com.logie.gen1storage.pokemon.Gen2DexPage.speciesText() =
        "${category}: ${text.take(30)}"

    @Test
    fun `a Pokemon is given its own generation's page`() {
        val fromGold = Gen1Pokemon(SaveFixtures.pokemon(species = "PIKACHU"), generation = 2)
        val fromRed = Gen1Pokemon(SaveFixtures.pokemon(species = "PIKACHU"), generation = 1)

        val gold = fromGold.dexPage("gold")!!
        val crystal = fromGold.dexPage("crystal")!!
        val red = fromRed.dexPage("red")!!

        assertEquals(Gen2Data.dexEntry("PIKACHU")!!.gold.flowing, gold.flowing)
        assertNotEquals(gold.flowing, crystal.flowing)
        assertEquals(Gen1Data.dexEntry("PIKACHU")!!.flowing("red"), red.flowing)
        assertNotEquals(red.flowing, gold.flowing)
    }

    @Test
    fun `Johto has a page only at Generation II`() {
        val chikorita = SaveFixtures.pokemon(species = "CHIKORITA")
        assertNull(Gen1Pokemon(chikorita, generation = 1).dexPage("red"))
        assertNotNull(Gen1Pokemon(chikorita, generation = 2).dexPage("gold"))
        assertNull(Gen1Data.dexEntry("CHIKORITA"))
    }

    @Test
    fun `the cries run to the end of Johto`() {
        // The PokeAPI archive's legacy folder is the Game Boy recordings and
        // it holds every generation's; 251 is this app's cap, not its.
        assertEquals(251, CryStore.LAST_CRY)
    }
}
