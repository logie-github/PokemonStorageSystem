package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat
import com.logie.gen1storage.pokemon.Gen1Stats
import com.logie.gen1storage.sprites.Gen2Sprites
import com.logie.gen1storage.sprites.SpriteSet
import com.logie.gen1storage.sprites.gen2SpriteFolder
import com.logie.gen1storage.sprites.spriteFileName
import com.logie.gen1storage.ui.spriteSpeciesId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The species-to-filename mapping, checked against the names the archive
 * actually uses. Every one of the 151 has to resolve, because a species that
 * maps wrong is a sprite that silently never appears.
 */
class SpriteMappingTest {

    @Test
    fun `punctuation is dropped, as the archive names its files`() {
        assertEquals("mrmime", spriteFileName("MR_MIME"))
        assertEquals("nidoranm", spriteFileName("NIDORAN_M"))
        assertEquals("nidoranf", spriteFileName("NIDORAN_F"))
        assertEquals("farfetchd", spriteFileName("FARFETCHD"))
        assertEquals("pikachu", spriteFileName("PIKACHU"))
    }

    @Test
    fun `every species maps to a distinct, plausible file name`() {
        val names = Gen1Data.species.map { spriteFileName(it.id) }
        assertEquals(151, names.size)
        assertEquals("no two species may share a file", 151, names.distinct().size)
        assertTrue(names.all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } })
    }

    @Test
    fun `a save's game decides which art its Pokemon are shown in`() {
        assertEquals(SpriteSet.RED_BLUE, SpriteSet.forGame(GameVersion.RED))
        assertEquals(SpriteSet.RED_BLUE, SpriteSet.forGame(GameVersion.BLUE))
        assertEquals(SpriteSet.YELLOW, SpriteSet.forGame(GameVersion.YELLOW))
        // A Pokémon with no known origin still gets art rather than nothing.
        assertEquals(SpriteSet.RED_BLUE, SpriteSet.forGame(null))
        assertEquals(SpriteSet.YELLOW, SpriteSet.forGameId("yellow"))
    }

    @Test
    fun `green is defined but not part of the default download`() {
        assertEquals(
            listOf(
                SpriteSet.RED_BLUE, SpriteSet.YELLOW,
                SpriteSet.GOLD, SpriteSet.SILVER, SpriteSet.CRYSTAL,
            ),
            SpriteSet.downloadable,
        )
        assertTrue(SpriteSet.entries.contains(SpriteSet.GREEN))
    }

    /**
     * pret names a species' folder after the name with its punctuation turned
     * into underscores rather than dropped, which is why this is not
     * [spriteFileName]. Every one of the 151 was checked against
     * pokecrystal's own folders; these are the four that are not simply the
     * name in lower case.
     */
    @Test
    fun `pret's folder names keep the punctuation as underscores`() {
        assertEquals("mr__mime", gen2SpriteFolder("MR_MIME"))
        assertEquals("farfetch_d", gen2SpriteFolder("FARFETCH'D"))
        assertEquals("nidoran_f", gen2SpriteFolder("NIDORAN_F"))
        assertEquals("nidoran_m", gen2SpriteFolder("NIDORAN_M"))
        assertEquals("pikachu", gen2SpriteFolder("PIKACHU"))
    }

    @Test
    fun `a Generation II save's Pokemon are drawn in that game's art`() {
        assertEquals(SpriteSet.GOLD, SpriteSet.forGameId("gold"))
        assertEquals(SpriteSet.SILVER, SpriteSet.forGameId("silver"))
        assertEquals(SpriteSet.CRYSTAL, SpriteSet.forGameId("crystal"))
        assertEquals(2, SpriteSet.generationOf("gold"))
        assertEquals(1, SpriteSet.generationOf("red"))
        // Nothing known about where it came from is Generation I, which is
        // what this app reads.
        assertEquals(1, SpriteSet.generationOf(null))
    }

    @Test
    fun `the two generations' sets are told apart`() {
        assertEquals(
            listOf(SpriteSet.GOLD, SpriteSet.SILVER, SpriteSet.CRYSTAL),
            SpriteSet.gen2,
        )
        SpriteSet.gen2.forEach {
            assertEquals(2, it.generation)
            assertTrue(it.id, it.repo != null && it.frontFile != null)
        }
        SpriteSet.entries.filter { it !in SpriteSet.gen2 }.forEach {
            assertEquals(1, it.generation)
        }
    }

    @Test
    fun `an Unown's letter comes from the middle two bits of four DVs`() {
        // GetUnownLetter packs atk/def/spd/spc's middle two bits into one
        // byte and divides by ten: 0 is A, 255 is Z.
        fun dvs(atk: Int, def: Int, spd: Int, spc: Int) = mapOf(
            Gen1Stat.ATTACK to atk, Gen1Stat.DEFENSE to def,
            Gen1Stat.SPEED to spd, Gen1Stat.SPECIAL to spc,
        )
        assertEquals('A', Gen1Stats.unownLetter(dvs(0, 0, 0, 0)))
        assertEquals('Z', Gen1Stats.unownLetter(dvs(15, 15, 15, 15)))
        // Only bits 2 and 1 count: the top and bottom bit of every DV here
        // differ from the all-zero case, and the letter does not move.
        assertEquals('A', Gen1Stats.unownLetter(dvs(9, 9, 9, 9)))
    }

    @Test
    fun `an Unown Pokemon's sprite id names its own letter`() {
        val mon = SaveFixtures.pokemon(species = "UNOWN", dvs = listOf(0, 0, 0, 0), withStats = false)
        assertEquals("UNOWN_A", Gen1Pokemon(mon).spriteSpeciesId())

        val other = SaveFixtures.pokemon(species = "UNOWN", dvs = listOf(15, 15, 15, 15), withStats = false)
        assertEquals("UNOWN_Z", Gen1Pokemon(other).spriteSpeciesId())

        // Anything else is shown under its own id, unchanged.
        assertEquals("PIKACHU", Gen1Pokemon(SaveFixtures.pokemon(species = "PIKACHU")).spriteSpeciesId())
    }

    @Test
    fun `a Gold or Silver sprite falls back to the drawing the two games share`() {
        // Most species were drawn twice and pokegold keeps both, so the
        // versioned name is asked for first.
        val pikachu = Gen2Sprites.urls(SpriteSet.GOLD, "PIKACHU")
        assertEquals(2, pikachu.size)
        assertTrue(pikachu[0].endsWith("/pikachu/front_gold.png"))
        assertTrue(pikachu[1].endsWith("/pikachu/front.png"))

        // Eight were drawn once for both games, and neither versioned name
        // exists at all — which was sixteen files every download reported as
        // lost. The fallback is what finds them.
        val shared = Gen2Sprites.urls(SpriteSet.SILVER, "SUICUNE")
        assertTrue(shared[0].endsWith("/suicune/front_silver.png"))
        assertTrue(shared[1].endsWith("/suicune/front.png"))

        // Crystal redrew every one of them and files them all the same way,
        // so it has nothing to fall back to.
        assertEquals(
            listOf("https://raw.githubusercontent.com/pret/pokecrystal/master/gfx/pokemon/suicune/front.png"),
            Gen2Sprites.urls(SpriteSet.CRYSTAL, "SUICUNE"),
        )

        // Unown was never given a per-version drawing by either repository.
        assertEquals(1, Gen2Sprites.urls(SpriteSet.GOLD, "UNOWN_F").size)
        assertTrue(Gen2Sprites.urls(SpriteSet.GOLD, "UNOWN_F").single().endsWith("/unown_f/front.png"))
    }

    @Test
    fun `every Unown letter folds through the ordinary folder rule`() {
        Gen2Sprites.UNOWN_FORM_IDS.forEachIndexed { index, id ->
            val letter = ('A' + index)
            assertEquals("UNOWN_$letter", id)
            assertEquals("unown_${letter.lowercaseChar()}", gen2SpriteFolder(id))
        }
        assertEquals(26, Gen2Sprites.UNOWN_FORM_IDS.distinct().size)
    }
}
