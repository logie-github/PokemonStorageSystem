package com.logie.gen1storage

import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StoredPokemon
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which game's art a stored Pokémon's sprite is drawn under.
 *
 * Ordinarily that is just where it was deposited from. A Time Capsule
 * crossing is the one thing that moves a Pokémon's generation without
 * touching its provenance — see [StoredPokemon.spriteGameVersionId] — and
 * these pin that the sprite lookup follows the generation, not the stale
 * record of the cartridge it left.
 */
class StoredPokemonSpriteVersionTest {

    private fun provenance(gameVersion: String) = Provenance(
        gameVersion = gameVersion,
        saveId = "test::save.lua",
        savePath = "save.lua",
        slotId = "slot1",
        trainerName = "ASH",
        trainerId = 12345,
        playthroughId = "quiet-forest-dawn",
        sourceKind = Provenance.KIND_PARTY,
        sourceIndex = 0,
        depositedAtEpochMillis = 0L,
    )

    private fun stored(gameVersion: String, generation: Int) = StoredPokemon(
        uid = "uid",
        data = SaveFixtures.pokemon(),
        provenance = provenance(gameVersion),
        generation = generation,
    )

    @Test
    fun `an untouched Generation I Pokemon is drawn under the game it was deposited from`() {
        assertEquals("red", stored("red", generation = 1).spriteGameVersionId)
    }

    @Test
    fun `a native Generation II Pokemon keeps its own game's art`() {
        assertEquals("silver", stored("silver", generation = 2).spriteGameVersionId)
        assertEquals("crystal", stored("crystal", generation = 2).spriteGameVersionId)
    }

    @Test
    fun `a Pokemon carried forward through the Time Capsule is drawn under Generation II art`() {
        // The provenance still names the Generation I cartridge it left —
        // that never changes — but its species field is spelled Generation
        // II's way now, which Generation I's own sprite sets have never
        // heard, so the lookup has to move on with it.
        assertEquals("gold", stored("red", generation = 2).spriteGameVersionId)
        assertEquals("gold", stored("yellow", generation = 2).spriteGameVersionId)
    }
}
