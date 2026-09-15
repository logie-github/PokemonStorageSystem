package com.logie.gen1storage

import com.logie.gen1storage.pokemon.Gen2Data
import com.logie.gen1storage.rom.Gen2RomLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A fake ROM built from this app's own already-tested [Gen2Data] table —
 * never a real cartridge dump. The planted sprites are `tools/lzcompress.c`
 * output for an all-zero picture (round-tripped through that same tool's
 * own decompressor first), not a real Pokémon's.
 */
class Gen2RomLocatorTest {

    // A 5x5 all-zero picture, lz3-compressed — confirmed against
    // pret/pokecrystal's own lzcompress -u.
    private val zero5x5 = bytesOf(-19, -113, -1)
    private val zero6x6 = bytesOf(-18, 63, -1)
    private val zero7x7 = bytesOf(-17, 15, -1)

    private fun bytesOf(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

    private val typeBytes: Map<String, Int> = mapOf(
        "NORMAL" to 0x00, "FIGHTING" to 0x01, "FLYING" to 0x02, "POISON" to 0x03,
        "GROUND" to 0x04, "ROCK" to 0x05, "BUG" to 0x07, "GHOST" to 0x08, "STEEL" to 0x09,
        "FIRE" to 0x14, "WATER" to 0x15, "GRASS" to 0x16, "ELECTRIC" to 0x17,
        "PSYCHIC" to 0x18, "ICE" to 0x19, "DRAGON" to 0x1A, "DARK" to 0x1B,
    )

    private val BASE_DATA_SIZE = 32
    private val PIC_SIZE_OFFSET = 17
    private val ENTRY_SIZE = 6
    private val UNOWN_DEX = 201

    /** The raw byte a real ROM would store for [realBank], inverting `fixPicBank`. */
    private fun rawBankFor(realBank: Int, game: Gen2RomLocator.Gen2Game): Int = when (game) {
        Gen2RomLocator.Gen2Game.CRYSTAL -> realBank - 0x48 + 0x12
        Gen2RomLocator.Gen2Game.GOLD_SILVER -> when (realBank) {
            0x1F -> 0x13
            0x20 -> 0x14
            0x2E -> 0x1F
            else -> realBank
        }
    }

    /**
     * Plants the base-stats table at [tableOffset] and `PokemonPicPointers`
     * at [pointersOffset], with every species but Unown given a sprite —
     * [tileSize] tiles square unless [oddSizes] names this species a
     * different one, to also exercise more than one bank.
     */
    private fun buildFakeRom(
        tableOffset: Int,
        pointersOffset: Int,
        game: Gen2RomLocator.Gen2Game,
        oddSizes: Map<String, Int> = emptyMap(),
        skip: Set<String> = emptySet(),
        romSize: Int = 0x200000,
    ): ByteArray {
        val rom = ByteArray(romSize) { (it * 37 + 11).toByte() }
        val bySpecies = Gen2Data.species.sortedBy { it.dexNumber }
        val firstPicsBank = if (game == Gen2RomLocator.Gen2Game.CRYSTAL) 0x48 else 0x15

        bySpecies.forEach { species ->
            val recordStart = tableOffset + (species.dexNumber - 1) * BASE_DATA_SIZE
            val type1 = typeBytes.getValue(species.primaryType)
            val type2 = species.secondaryType?.let { typeBytes.getValue(it) } ?: type1
            rom[recordStart] = species.dexNumber.toByte()
            rom[recordStart + 1] = species.baseHp.toByte()
            rom[recordStart + 2] = species.baseAttack.toByte()
            rom[recordStart + 3] = species.baseDefense.toByte()
            rom[recordStart + 4] = species.baseSpeed.toByte()
            rom[recordStart + 5] = species.baseSpecialAttack.toByte()
            rom[recordStart + 6] = species.baseSpecialDefense.toByte()
            rom[recordStart + 7] = type1.toByte()
            rom[recordStart + 8] = type2.toByte()
            rom[recordStart + 9] = species.catchRate.toByte()
            rom[recordStart + 10] = species.baseExp.toByte()

            val entryAt = pointersOffset + (species.dexNumber - 1) * ENTRY_SIZE
            if (species.dexNumber == UNOWN_DEX) {
                rom[recordStart + PIC_SIZE_OFFSET] = 0
                for (i in 0 until ENTRY_SIZE) rom[entryAt + i] = 0xFF.toByte()
                return@forEach
            }
            if (species.id in skip) {
                rom[recordStart + PIC_SIZE_OFFSET] = 5
                for (i in 0 until ENTRY_SIZE) rom[entryAt + i] = 0
                return@forEach
            }

            val tileSize = oddSizes[species.id] ?: 5
            val sprite = when (tileSize) {
                5 -> zero5x5
                6 -> zero6x6
                else -> zero7x7
            }
            rom[recordStart + PIC_SIZE_OFFSET] = tileSize.toByte()

            // One bank per roughly forty species, well clear of the tables.
            val bank = firstPicsBank + (species.dexNumber / 40)
            val spriteAt = bank * 0x4000 + 0x100 + (species.dexNumber % 40) * 32
            sprite.copyInto(rom, spriteAt)
            val pointer = 0x4000 + (spriteAt - bank * 0x4000)
            rom[entryAt] = rawBankFor(bank, game).toByte()
            rom[entryAt + 1] = (pointer and 0xFF).toByte()
            rom[entryAt + 2] = ((pointer shr 8) and 0xFF).toByte()
        }
        return rom
    }

    @Test
    fun `Crystal's table and pic pointers are both found`() {
        val rom = buildFakeRom(tableOffset = 0x10000, pointersOffset = 0x30000, game = Gen2RomLocator.Gen2Game.CRYSTAL)

        val located = Gen2RomLocator.locate(rom, Gen2RomLocator.Gen2Game.CRYSTAL)

        assertNotNull(located)
        assertEquals(0x10000, located!!.tableOffset)
        assertEquals(0x30000, located.picPointersOffset)
    }

    @Test
    fun `Gold and Silver's own bank-fix history is honoured`() {
        val rom = buildFakeRom(
            tableOffset = 0x10000,
            pointersOffset = 0x30000,
            game = Gen2RomLocator.Gen2Game.GOLD_SILVER,
        )

        val located = Gen2RomLocator.locate(rom, Gen2RomLocator.Gen2Game.GOLD_SILVER)

        assertNotNull(located)
        val sprite = Gen2RomLocator.frontSprite(rom, located!!, "BULBASAUR", Gen2RomLocator.Gen2Game.GOLD_SILVER)
        assertNotNull(sprite)
        assertEquals(40, sprite!!.widthPx)
    }

    @Test
    fun `a ROM without the real struct is never mistaken for one`() {
        val rom = ByteArray(0x200000) { (it * 7 + 3).toByte() }
        assertNull(Gen2RomLocator.locate(rom, Gen2RomLocator.Gen2Game.CRYSTAL))
    }

    @Test
    fun `species of different pic sizes and Unown all resolve correctly`() {
        val rom = buildFakeRom(
            tableOffset = 0x10000,
            pointersOffset = 0x30000,
            game = Gen2RomLocator.Gen2Game.CRYSTAL,
            oddSizes = mapOf("RHYDON" to 7, "MAGIKARP" to 6),
        )
        val located = Gen2RomLocator.locate(rom, Gen2RomLocator.Gen2Game.CRYSTAL)!!

        val bulbasaur = Gen2RomLocator.frontSprite(rom, located, "BULBASAUR", Gen2RomLocator.Gen2Game.CRYSTAL)
        assertNotNull(bulbasaur)
        assertEquals(40, bulbasaur!!.widthPx)

        val rhydon = Gen2RomLocator.frontSprite(rom, located, "RHYDON", Gen2RomLocator.Gen2Game.CRYSTAL)
        assertNotNull(rhydon)
        assertEquals(56, rhydon!!.widthPx)

        val magikarp = Gen2RomLocator.frontSprite(rom, located, "MAGIKARP", Gen2RomLocator.Gen2Game.CRYSTAL)
        assertNotNull(magikarp)
        assertEquals(48, magikarp!!.widthPx)

        // Unown has no ordinary entry to find.
        assertNull(Gen2RomLocator.frontSprite(rom, located, "UNOWN", Gen2RomLocator.Gen2Game.CRYSTAL))
    }
}
