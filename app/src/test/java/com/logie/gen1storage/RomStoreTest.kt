package com.logie.gen1storage

import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.rom.RomStore
import com.logie.gen1storage.rom.RomVersion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RomStoreTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val allZeroTileSprite = byteArrayOf(17, -68, 19, -63)

    private val typeBytes: Map<String, Int> = mapOf(
        "NORMAL" to 0x00, "FIGHTING" to 0x01, "FLYING" to 0x02, "POISON" to 0x03,
        "GROUND" to 0x04, "ROCK" to 0x05, "BUG" to 0x07, "GHOST" to 0x08,
        "FIRE" to 0x14, "WATER" to 0x15, "GRASS" to 0x16, "ELECTRIC" to 0x17,
        "PSYCHIC" to 0x18, "ICE" to 0x19, "DRAGON" to 0x1A,
    )

    private fun buildFakeRom(tableOffset: Int, spritesBySpeciesId: Map<String, ByteArray>): ByteArray {
        val rom = ByteArray(0x40000) { (it * 37 + 11).toByte() }
        val bySpecies = Gen1Data.species.sortedBy { it.dexNumber }
        bySpecies.forEach { species ->
            val recordStart = tableOffset + (species.dexNumber - 1) * 28
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

            val sprite = spritesBySpeciesId[species.id] ?: return@forEach
            val bank = when {
                species.id == "MEW" -> 0x1
                species.internalIndex <= 30 -> 0x9
                species.internalIndex <= 73 -> 0xA
                species.internalIndex <= 115 -> 0xB
                species.internalIndex <= 152 -> 0xC
                else -> 0xD
            }
            val spriteAt = bank * 0x4000 + 0x100 + (species.internalIndex * 64)
            sprite.copyInto(rom, spriteAt)
            val pointer = 0x4000 + (spriteAt - bank * 0x4000)
            rom[recordStart + 11] = (pointer and 0xFF).toByte()
            rom[recordStart + 12] = ((pointer shr 8) and 0xFF).toByte()
        }
        return rom
    }

    @Test
    fun `a real-looking ROM is saved and a fake one is refused`() {
        val store = RomStore(temporaryFolder.newFolder())
        val rom = buildFakeRom(tableOffset = 0x1000, spritesBySpeciesId = mapOf("RHYDON" to allZeroTileSprite))

        val accepted = store.import(RomVersion.RED, rom)

        assertTrue(accepted)
        assertTrue(store.has(RomVersion.RED))
        assertEquals(rom.size.toLong(), store.sizeOf(RomVersion.RED))
        assertFalse(store.has(RomVersion.BLUE))

        assertFalse(store.import(RomVersion.BLUE, ByteArray(1000) { it.toByte() }))
        assertFalse("a file this app cannot read is not one it should keep", store.has(RomVersion.BLUE))
    }

    @Test
    fun `a sprite reads back out of a saved ROM`() {
        val store = RomStore(temporaryFolder.newFolder())
        val rom = buildFakeRom(tableOffset = 0x1000, spritesBySpeciesId = mapOf("RHYDON" to allZeroTileSprite))
        store.import(RomVersion.RED, rom)

        val decoded = store.frontSprite(RomVersion.RED, "RHYDON")

        assertNotNull(decoded)
        assertEquals(8, decoded!!.widthPx)
        assertArrayEquals(IntArray(64), decoded.pixels)
        assertNull(store.frontSprite(RomVersion.RED, "PIKACHU"))
        assertNull(store.frontSprite(RomVersion.BLUE, "RHYDON"))
    }

    @Test
    fun `deleting a ROM leaves nothing behind to read`() {
        val store = RomStore(temporaryFolder.newFolder())
        val rom = buildFakeRom(tableOffset = 0x1000, spritesBySpeciesId = mapOf("RHYDON" to allZeroTileSprite))
        store.import(RomVersion.RED, rom)

        store.delete(RomVersion.RED)

        assertFalse(store.has(RomVersion.RED))
        assertNull(store.frontSprite(RomVersion.RED, "RHYDON"))
    }

    @Test
    fun `a second store over the same directory reads what the first wrote`() {
        val directory = temporaryFolder.newFolder()
        val rom = buildFakeRom(tableOffset = 0x1000, spritesBySpeciesId = mapOf("RHYDON" to allZeroTileSprite))
        RomStore(directory).import(RomVersion.RED, rom)

        val reopened = RomStore(directory)

        assertTrue(reopened.has(RomVersion.RED))
        assertNotNull(reopened.frontSprite(RomVersion.RED, "RHYDON"))
    }
}
