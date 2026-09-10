package com.logie.gen1storage

import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Growth
import com.logie.gen1storage.pokemon.Gen1Pokemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The experience curves behind "LEVEL UP nn to :L5" on the status screen's
 * second page, ported from upstream Growth.lua / pokered experience.asm.
 */
class Gen1GrowthTest {

    @Test
    fun `each curve matches its published coefficients at level 100`() {
        assertEquals(1_000_000, Gen1Growth.expForLevel("MEDIUM_FAST", 100))
        assertEquals(800_000, Gen1Growth.expForLevel("FAST", 100))
        assertEquals(1_250_000, Gen1Growth.expForLevel("SLOW", 100))
        assertEquals(1_059_860, Gen1Growth.expForLevel("MEDIUM_SLOW", 100))
    }

    @Test
    fun `level one is what each curve evaluates to, clamped at zero`() {
        // Not a blanket zero: MEDIUM_FAST is 1^3 and SLOW is floor(5/4), both
        // of which are 1. The three curves with negative constant terms go
        // below zero at n=1 and clamp, exactly as upstream does.
        assertEquals(1, Gen1Growth.expForLevel("MEDIUM_FAST", 1))
        assertEquals(1, Gen1Growth.expForLevel("SLOW", 1))
        assertEquals(0, Gen1Growth.expForLevel("FAST", 1))
        assertEquals(0, Gen1Growth.expForLevel("MEDIUM_SLOW", 1))
        assertEquals(0, Gen1Growth.expForLevel("SLIGHTLY_FAST", 1))
        assertEquals(0, Gen1Growth.expForLevel("SLIGHTLY_SLOW", 1))
    }

    @Test
    fun `an unknown curve falls back to medium fast rather than mis-levelling`() {
        assertEquals(
            Gen1Growth.expForLevel("MEDIUM_FAST", 40),
            Gen1Growth.expForLevel("A_MOD_CURVE", 40),
        )
    }

    @Test
    fun `experience owed is the gap to the next level`() {
        // RATTATA is MEDIUM_FAST: level 5 costs 125, so a level 4 with 64
        // points owes 61 - the figure the screenshot shows.
        val rattata = SaveFixtures.pokemon(species = "RATTATA", level = 4, exp = 64)
        assertEquals("MEDIUM_FAST", Gen1Data.species("RATTATA")!!.growthRate)
        assertEquals(61, Gen1Growth.expToNextLevel(Gen1Pokemon(rattata)))
    }

    @Test
    fun `a level 100 Pokemon owes nothing and says so`() {
        val maxed = SaveFixtures.pokemon(species = "RATTATA", level = 100, exp = 1_000_000)
        assertNull(Gen1Growth.expToNextLevel(Gen1Pokemon(maxed)))
    }

    @Test
    fun `an unknown species has no curve to quote`() {
        val modded = SaveFixtures.pokemon(species = "MOD_MON", level = 5, withStats = false)
        assertNull(Gen1Growth.expToNextLevel(Gen1Pokemon(modded)))
    }
}
