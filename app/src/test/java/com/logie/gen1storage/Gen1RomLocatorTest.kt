package com.logie.gen1storage

import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.rom.Gen1RomLocator
import com.logie.gen1storage.rom.Gen1SpriteCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A fake ROM built from this app's own already-tested [Gen1Data] table —
 * never a real cartridge dump, which this app has no way to obtain or
 * legitimately distribute. It only has to be shaped enough like the real
 * thing for the locator's own logic (find the struct, do the bank
 * arithmetic) to be exercised honestly; the sprite bytes it plants are the
 * same synthetic, [Gen1SpriteCodecTest] fixtures, not a real Pokémon's.
 */
class Gen1RomLocatorTest {

    // Confirmed against the app's own `pkmncompress`-round-tripped fixtures.
    private val allZeroTileSprite = byteArrayOf(17, -68, 19, -63)

    private val typeBytes: Map<String, Int> = mapOf(
        "NORMAL" to 0x00, "FIGHTING" to 0x01, "FLYING" to 0x02, "POISON" to 0x03,
        "GROUND" to 0x04, "ROCK" to 0x05, "BUG" to 0x07, "GHOST" to 0x08,
        "FIRE" to 0x14, "WATER" to 0x15, "GRASS" to 0x16, "ELECTRIC" to 0x17,
        "PSYCHIC" to 0x18, "ICE" to 0x19, "DRAGON" to 0x1A,
    )

    private fun buildFakeRom(tableOffset: Int, spritesBySpeciesId: Map<String, ByteArray> = emptyMap()): ByteArray {
        // Large enough to cover every bank UncompressMonSprite ever names (up
        // to bank $D) plus room for the table itself.
        val rom = ByteArray(0x40000) { (it * 37 + 11).toByte() } // filler, deliberately not zero
        // BaseStats is ordered by Pokédex number (`GetMonHeader`'s
        // `IndexToPokedex` conversion) — a different order from the internal
        // index the sprite bank table below still branches on.
        val bySpecies = Gen1Data.species.sortedBy { it.dexNumber }
        bySpecies.forEachIndexed { index, species ->
            val recordStart = tableOffset + index * 28
            val type1 = typeBytes.getValue(species.primaryType)
            val type2 = species.secondaryType?.let { typeBytes.getValue(it) } ?: type1
            rom[recordStart] = species.dexNumber.toByte()
            rom[recordStart + 1] = species.baseHp.toByte()
            rom[recordStart + 2] = species.baseAttack.toByte()
            rom[recordStart + 3] = species.baseDefense.toByte()
            rom[recordStart + 4] = species.baseSpeed.toByte()
            rom[recordStart + 5] = species.baseSpecial.toByte()
            rom[recordStart + 6] = type1.toByte()
            rom[recordStart + 7] = type2.toByte()
            rom[recordStart + 8] = species.catchRate.toByte()
            rom[recordStart + 9] = species.baseExp.toByte()

            val sprite = spritesBySpeciesId[species.id] ?: return@forEachIndexed
            val bank = when {
                species.id == "MEW" -> 0x1
                species.internalIndex <= 30 -> 0x9
                species.internalIndex <= 73 -> 0xA
                species.internalIndex <= 115 -> 0xB
                species.internalIndex <= 152 -> 0xC
                else -> 0xD
            }
            // Placed well clear of any bank's own table copy, and clear of
            // every other planted sprite in this fake ROM.
            val spriteAt = bank * 0x4000 + 0x100 + (species.internalIndex * 64)
            sprite.copyInto(rom, spriteAt)
            val pointer = 0x4000 + (spriteAt - bank * 0x4000)
            rom[recordStart + 11] = (pointer and 0xFF).toByte()
            rom[recordStart + 12] = ((pointer shr 8) and 0xFF).toByte()
        }
        return rom
    }

    @Test
    fun `the table is found by the data this app already trusts`() {
        val rom = buildFakeRom(tableOffset = 0x1000)

        val located = Gen1RomLocator.locate(rom)

        assertNotNull(located)
        assertEquals(0x1000, located!!.tableOffset)
    }

    @Test
    fun `a ROM without the real struct is never mistaken for one`() {
        val rom = ByteArray(0x40000) { (it * 7 + 3).toByte() }

        assertNull(Gen1RomLocator.locate(rom))
    }

    @Test
    fun `a species from each bank threshold decodes its planted sprite`() {
        // RHYDON (index 1, bank 9), MEW (the named exception, bank 1), and
        // STARMIE (index 152, the last of bank C) between them exercise the
        // ordinary range table and the one hand-named exception.
        val species = listOf("RHYDON", "MEW", "STARMIE")
        val rom = buildFakeRom(
            tableOffset = 0x2000,
            spritesBySpeciesId = species.associateWith { allZeroTileSprite },
        )
        val located = Gen1RomLocator.locate(rom)!!

        species.forEach { id ->
            val decoded = Gen1RomLocator.frontSprite(rom, located, id)
            assertNotNull(id, decoded)
            assertEquals(id, 8, decoded!!.widthPx)
            assertEquals(id, 8, decoded.heightPx)
            assertArrayEquals(id, IntArray(64), decoded.pixels)
        }
    }

    @Test
    fun `a species the table never heard of decodes nothing`() {
        val rom = buildFakeRom(tableOffset = 0x2000)
        val located = Gen1RomLocator.locate(rom)!!

        assertNull(Gen1RomLocator.frontSprite(rom, located, "CHIKORITA"))
    }
}
