package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.sprites.SpriteSet
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
        assertEquals(listOf(SpriteSet.RED_BLUE, SpriteSet.YELLOW), SpriteSet.downloadable)
        assertTrue(SpriteSet.entries.contains(SpriteSet.GREEN))
    }
}
