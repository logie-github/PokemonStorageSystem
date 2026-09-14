package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.sprites.SpriteSet
import com.logie.gen1storage.sprites.gen2SpriteFolder
import com.logie.gen1storage.sprites.spriteFileName
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
}
