package com.logie.gen1storage

import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen2Data
import com.logie.gen1storage.rom.Gen2RomLocator
import com.logie.gen1storage.rom.RomIdentifier
import com.logie.gen1storage.rom.RomVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fake ROMs shaped like [Gen1Data]/[Gen2Data] already-tested tables, at the
 * real size a genuine dump is — [RomIdentifier] gates on that before it ever
 * looks at a byte — with a title planted at the header's own offset, the
 * same as a real cartridge carries.
 */
class RomIdentifierTest {

    private val gen1TypeBytes: Map<String, Int> = mapOf(
        "NORMAL" to 0x00, "FIGHTING" to 0x01, "FLYING" to 0x02, "POISON" to 0x03,
        "GROUND" to 0x04, "ROCK" to 0x05, "BUG" to 0x07, "GHOST" to 0x08,
        "FIRE" to 0x14, "WATER" to 0x15, "GRASS" to 0x16, "ELECTRIC" to 0x17,
        "PSYCHIC" to 0x18, "ICE" to 0x19, "DRAGON" to 0x1A,
    )

    private val gen2TypeBytes: Map<String, Int> = mapOf(
        "NORMAL" to 0x00, "FIGHTING" to 0x01, "FLYING" to 0x02, "POISON" to 0x03,
        "GROUND" to 0x04, "ROCK" to 0x05, "BUG" to 0x07, "GHOST" to 0x08, "STEEL" to 0x09,
        "FIRE" to 0x14, "WATER" to 0x15, "GRASS" to 0x16, "ELECTRIC" to 0x17,
        "PSYCHIC" to 0x18, "ICE" to 0x19, "DRAGON" to 0x1A, "DARK" to 0x1B,
    )

    private fun withTitle(rom: ByteArray, title: String): ByteArray {
        title.forEachIndexed { i, c -> rom[0x134 + i] = c.code.toByte() }
        return rom
    }

    /** Red/Blue-shaped: every species but Mew, in dex order, at [tableOffset]. */
    private fun buildGen1RedBlueRom(title: String, tableOffset: Int = 0x1000): ByteArray {
        val rom = ByteArray(1_048_576) { (it * 37 + 11).toByte() }
        val bySpecies = Gen1Data.species.filter { it.id != "MEW" }.sortedBy { it.dexNumber }
        bySpecies.forEachIndexed { index, species ->
            val at = tableOffset + index * 28
            val type1 = gen1TypeBytes.getValue(species.primaryType)
            val type2 = species.secondaryType?.let { gen1TypeBytes.getValue(it) } ?: type1
            rom[at] = species.dexNumber.toByte()
            rom[at + 1] = species.baseHp.toByte()
            rom[at + 2] = species.baseAttack.toByte()
            rom[at + 3] = species.baseDefense.toByte()
            rom[at + 4] = species.baseSpeed.toByte()
            rom[at + 5] = species.baseSpecial.toByte()
            rom[at + 6] = type1.toByte()
            rom[at + 7] = type2.toByte()
            rom[at + 8] = species.catchRate.toByte()
            rom[at + 9] = species.baseExp.toByte()
        }
        return withTitle(rom, title)
    }

    /** Yellow-shaped: every species including Mew, in dex order, at [tableOffset]. */
    private fun buildGen1YellowRom(title: String, tableOffset: Int = 0x1000): ByteArray {
        val rom = ByteArray(1_048_576) { (it * 37 + 11).toByte() }
        val bySpecies = Gen1Data.species.sortedBy { it.dexNumber }
        bySpecies.forEachIndexed { index, species ->
            val at = tableOffset + index * 28
            val type1 = gen1TypeBytes.getValue(species.primaryType)
            val type2 = species.secondaryType?.let { gen1TypeBytes.getValue(it) } ?: type1
            val catchRate = when (species.id) {
                "DRAGONAIR" -> 27
                "DRAGONITE" -> 9
                else -> species.catchRate
            }
            rom[at] = species.dexNumber.toByte()
            rom[at + 1] = species.baseHp.toByte()
            rom[at + 2] = species.baseAttack.toByte()
            rom[at + 3] = species.baseDefense.toByte()
            rom[at + 4] = species.baseSpeed.toByte()
            rom[at + 5] = species.baseSpecial.toByte()
            rom[at + 6] = type1.toByte()
            rom[at + 7] = type2.toByte()
            rom[at + 8] = catchRate.toByte()
            rom[at + 9] = species.baseExp.toByte()
        }
        return withTitle(rom, title)
    }

    // A 5x5 all-zero picture, lz3-compressed — confirmed against
    // pret/pokecrystal's own lzcompress -u (see Gen2RomLocatorTest).
    private val zero5x5 = byteArrayOf(-19, -113, -1)

    private fun rawBankFor(realBank: Int, game: Gen2RomLocator.Gen2Game): Int = when (game) {
        Gen2RomLocator.Gen2Game.CRYSTAL -> realBank - 0x48 + 0x12
        Gen2RomLocator.Gen2Game.GOLD_SILVER -> realBank
    }

    /** Gold/Silver- or Crystal-shaped: a base-stats table plus a matching `PokemonPicPointers`, both of which [Gen2RomLocator] requires. */
    private fun buildGen2Rom(
        title: String,
        game: Gen2RomLocator.Gen2Game,
        tableOffset: Int = 0x1000,
        pointersOffset: Int = 0x30000,
    ): ByteArray {
        val rom = ByteArray(2_097_152) { (it * 37 + 11).toByte() }
        val bySpecies = Gen2Data.species.sortedBy { it.dexNumber }
        val firstPicsBank = if (game == Gen2RomLocator.Gen2Game.CRYSTAL) 0x48 else 0x15
        bySpecies.forEach { species ->
            val at = tableOffset + (species.dexNumber - 1) * 32
            val type1 = gen2TypeBytes.getValue(species.primaryType)
            val type2 = species.secondaryType?.let { gen2TypeBytes.getValue(it) } ?: type1
            rom[at] = species.dexNumber.toByte()
            rom[at + 1] = species.baseHp.toByte()
            rom[at + 2] = species.baseAttack.toByte()
            rom[at + 3] = species.baseDefense.toByte()
            rom[at + 4] = species.baseSpeed.toByte()
            rom[at + 5] = species.baseSpecialAttack.toByte()
            rom[at + 6] = species.baseSpecialDefense.toByte()
            rom[at + 7] = type1.toByte()
            rom[at + 8] = type2.toByte()
            rom[at + 9] = species.catchRate.toByte()
            rom[at + 10] = species.baseExp.toByte()

            val entryAt = pointersOffset + (species.dexNumber - 1) * 6
            if (species.dexNumber == 201) {
                rom[at + 17] = 0
                for (i in 0 until 6) rom[entryAt + i] = 0xFF.toByte()
                return@forEach
            }
            rom[at + 17] = 5
            val bank = firstPicsBank + (species.dexNumber / 40)
            val spriteAt = bank * 0x4000 + 0x100 + (species.dexNumber % 40) * 32
            zero5x5.copyInto(rom, spriteAt)
            val pointer = 0x4000 + (spriteAt - bank * 0x4000)
            rom[entryAt] = rawBankFor(bank, game).toByte()
            rom[entryAt + 1] = (pointer and 0xFF).toByte()
            rom[entryAt + 2] = ((pointer shr 8) and 0xFF).toByte()
        }
        return withTitle(rom, title)
    }

    @Test
    fun `Red and Blue are told apart by title alone, since their structure is identical`() {
        assertEquals(RomVersion.RED, RomIdentifier.identify(buildGen1RedBlueRom("POKEMON RED")))
        assertEquals(RomVersion.BLUE, RomIdentifier.identify(buildGen1RedBlueRom("POKEMON BLUE")))
    }

    @Test
    fun `a Red-Blue-shaped ROM with neither title is not identified as either`() {
        assertNull(RomIdentifier.identify(buildGen1RedBlueRom("SOMETHING ELSE")))
    }

    @Test
    fun `a Blue ROM is never accepted as Red just because the structure matches`() {
        // The whole point of a title check: uploading Blue's own bytes must
        // never come back as RomVersion.RED.
        assertEquals(RomVersion.BLUE, RomIdentifier.identify(buildGen1RedBlueRom("POKEMON BLUE")))
    }

    @Test
    fun `Yellow identifies as itself, not Red or Blue`() {
        assertEquals(RomVersion.YELLOW, RomIdentifier.identify(buildGen1YellowRom("POKEMON YELLOW")))
    }

    @Test
    fun `Gold and Silver are told apart by title alone, since their structure is identical`() {
        val goldSilver = Gen2RomLocator.Gen2Game.GOLD_SILVER
        assertEquals(RomVersion.GOLD, RomIdentifier.identify(buildGen2Rom("POKEMON_GLDAAUE", goldSilver)))
        assertEquals(RomVersion.SILVER, RomIdentifier.identify(buildGen2Rom("POKEMON_SLVAAXE", goldSilver)))
    }

    @Test
    fun `a Gold-Silver-shaped ROM with neither title is not identified as either`() {
        assertNull(RomIdentifier.identify(buildGen2Rom("SOMETHING ELSE", Gen2RomLocator.Gen2Game.GOLD_SILVER)))
    }

    @Test
    fun `Crystal identifies as itself, not Gold or Silver`() {
        assertEquals(
            RomVersion.CRYSTAL,
            RomIdentifier.identify(buildGen2Rom("PM_CRYSTAL", Gen2RomLocator.Gen2Game.CRYSTAL)),
        )
    }

    @Test
    fun `a wrong-size file is never identified as anything`() {
        assertNull(RomIdentifier.identify(ByteArray(1000) { it.toByte() }))
    }

    @Test
    fun `a real-sized but unstructured file is never identified as anything`() {
        assertNull(RomIdentifier.identify(withTitle(ByteArray(1_048_576) { (it * 7 + 3).toByte() }, "POKEMON RED")))
        assertNull(RomIdentifier.identify(withTitle(ByteArray(2_097_152) { (it * 7 + 3).toByte() }, "POKEMON_GLD")))
    }
}
