package com.logie.gen1storage.rom

import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Species

/**
 * Finds a species' base-stats record — and from it, its front sprite — in a
 * Red or Blue ROM the player has supplied, without needing to know in
 * advance at what address either landed when that particular copy was
 * built.
 *
 * There is no fixed address this app could simply trust: this app cannot
 * link pret/pokered itself to find out (that would mean running an
 * unreviewed third party build), and a number quoted from memory is a guess
 * this app's own rule is never to make. What it can trust is the data this
 * app already carries and has already tested — [Gen1Data]'s own base stats,
 * read off the same disassembly — and search the ROM's bytes for wherever
 * they actually appear, the same struct in every real Red or Blue ROM
 * (`constants/pokemon_data_constants.asm`'s `BASE_DATA_SIZE`, 28 bytes: dex
 * number, five stats, two types, catch rate, base experience, sprite
 * dimensions, then the front and back sprite pointers). One species' worth
 * of that struct is already a very unlikely coincidence to find twice in a
 * megabyte of ROM; requiring every one of the 151 to be exactly 28 bytes
 * from the last is not a coincidence at all — it is the table, found.
 *
 * Once found, a species' own front-sprite pointer is a 16-bit address in
 * whichever ROM bank the cartridge would have paged in for it — a fact
 * `home/pics.asm`'s `UncompressMonSprite` decides purely from the species'
 * *internal* index number, which is exactly why [Gen1Species.internalIndex]
 * is a field this app already carries rather than one invented for this.
 * The record itself, though, is found by Pokédex number: `home/pokemon.asm`'s
 * `GetMonHeader` converts a species' internal index to its Pokédex number
 * (`IndexToPokedex`) before it ever multiplies by [BASE_DATA_SIZE] — the two
 * tables are ordered differently, and BaseStats is ordered by Pokédex number,
 * one entry per original 151.
 */
object Gen1RomLocator {

    /** Where in the ROM file the base-stats table starts, once confirmed. */
    data class Located(val tableOffset: Int)

    /**
     * Confirms this looks like a real Red or Blue ROM by finding its own
     * base-stats table, or returns null — never a location this app is not
     * sure of.
     */
    fun locate(rom: ByteArray): Located? {
        val bySpecies = Gen1Data.species.sortedBy { it.dexNumber }
        if (bySpecies.isEmpty() || bySpecies.first().dexNumber != 1) return null
        val signatures = bySpecies.map { signatureOf(it) }
        val firstSignature = signatures.first()

        var at = 0
        while (true) {
            val candidate = indexOf(rom, firstSignature, at)
            if (candidate < 0) return null
            if (matchesWholeTable(rom, candidate, signatures)) return Located(candidate)
            at = candidate + 1
        }
    }

    /**
     * This species' front sprite, decompressed — null when [located] does
     * not (or no longer) account for it, or the tables have never heard of
     * the species at all.
     */
    fun frontSprite(rom: ByteArray, located: Located, speciesId: String): Gen1SpriteCodec.DecodedSprite? {
        val species = Gen1Data.species(speciesId) ?: return null
        val recordStart = located.tableOffset + (species.dexNumber - 1) * BASE_DATA_SIZE
        val pointerAt = recordStart + BASE_FRONTPIC_OFFSET
        if (pointerAt + 1 !in rom.indices) return null
        val pointer = (rom[pointerAt].toInt() and 0xFF) or ((rom[pointerAt + 1].toInt() and 0xFF) shl 8)
        if (pointer !in 0x4000..0x7FFF) return null
        val bank = bankFor(species.internalIndex)
        val fileOffset = bank * 0x4000 + (pointer - 0x4000)
        return runCatching { Gen1SpriteCodec.decompress(rom, fileOffset) }.getOrNull()
    }

    /**
     * `UncompressMonSprite`'s bank table, by internal index — Mew and the
     * Fossil Kabutops are named exceptions; nothing this app stores a
     * Pokémon under is ever the fossil pic, so only Mew's is worth carrying.
     */
    private fun bankFor(internalIndex: Int): Int = when {
        internalIndex == MEW_INDEX -> 0x1
        internalIndex <= TANGELA_INDEX -> 0x9
        internalIndex <= MOLTRES_INDEX -> 0xA
        internalIndex <= BEEDRILL_INDEX + 1 -> 0xB
        internalIndex <= STARMIE_INDEX -> 0xC
        else -> 0xD
    }

    /** The ten struct bytes this app can already name for a species without reading a ROM. */
    private fun signatureOf(species: Gen1Species): ByteArray {
        val type1 = TYPE_BYTES[species.primaryType] ?: error("unknown type ${species.primaryType}")
        val type2 = species.secondaryType?.let { TYPE_BYTES[it] ?: error("unknown type $it") } ?: type1
        return byteArrayOf(
            species.dexNumber.toByte(),
            species.baseHp.toByte(),
            species.baseAttack.toByte(),
            species.baseDefense.toByte(),
            species.baseSpeed.toByte(),
            species.baseSpecial.toByte(),
            type1.toByte(),
            type2.toByte(),
            species.catchRate.toByte(),
            species.baseExp.toByte(),
        )
    }

    /** Every one of [signatures], each exactly [BASE_DATA_SIZE] bytes after the last, starting at [tableOffset]. */
    private fun matchesWholeTable(rom: ByteArray, tableOffset: Int, signatures: List<ByteArray>): Boolean {
        signatures.forEachIndexed { index, signature ->
            val at = tableOffset + index * BASE_DATA_SIZE
            if (at + signature.size > rom.size) return false
            for (i in signature.indices) {
                if (rom[at + i] != signature[i]) return false
            }
        }
        return true
    }

    private fun indexOf(rom: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from < 0) return -1
        val last = rom.size - needle.size
        outer@ for (start in from..last) {
            for (i in needle.indices) {
                if (rom[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }

    // constants/type_constants.asm: PHYSICAL types 0-8, then a gap, then
    // SPECIAL types from $14. BIRD ($06) is defined but no species in the
    // base-stats table actually carries it.
    private val TYPE_BYTES: Map<String, Int> = mapOf(
        "NORMAL" to 0x00, "FIGHTING" to 0x01, "FLYING" to 0x02, "POISON" to 0x03,
        "GROUND" to 0x04, "ROCK" to 0x05, "BUG" to 0x07, "GHOST" to 0x08,
        "FIRE" to 0x14, "WATER" to 0x15, "GRASS" to 0x16, "ELECTRIC" to 0x17,
        "PSYCHIC" to 0x18, "ICE" to 0x19, "DRAGON" to 0x1A,
    )

    // The internal indices `UncompressMonSprite`'s bank table branches on —
    // read off Gen1Data itself rather than quoted as bare numbers, so a
    // mistake in either place would show up as a mismatch instead of
    // quietly agreeing with itself.
    private val MEW_INDEX = Gen1Data.species("MEW")!!.internalIndex
    private val TANGELA_INDEX = Gen1Data.species("TANGELA")!!.internalIndex
    private val MOLTRES_INDEX = Gen1Data.species("MOLTRES")!!.internalIndex
    private val BEEDRILL_INDEX = Gen1Data.species("BEEDRILL")!!.internalIndex
    private val STARMIE_INDEX = Gen1Data.species("STARMIE")!!.internalIndex

    /** `BASE_DATA_SIZE`: one species' whole record, pret/pokered's `constants/pokemon_data_constants.asm`. */
    private const val BASE_DATA_SIZE = 28

    /** `BASE_FRONTPIC`'s byte offset within that record. */
    private const val BASE_FRONTPIC_OFFSET = 11
}
