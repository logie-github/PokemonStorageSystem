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
 *
 * Mew is the one species this does not hold in Red or Blue: `GetMonHeader`
 * checks for it (`cp MEW`) *before* that conversion and copies its record
 * from a standalone `MewBaseStats` instead — confirmed against a real Red
 * ROM, where the other 150 records sit exactly 28 bytes apart as expected and
 * Mew's is nowhere near them. It is looked for on its own, by the same kind
 * of signature, and kept as its own answer rather than folded into
 * [Located.tableOffset].
 *
 * Yellow dropped that exception: its own `GetMonHeader` and
 * `UncompressMonSprite` have no `cp MEW` branch at all (confirmed against
 * pret/pokeyellow, which also asserts its table is the full 151 rather than
 * "NUM_POKEMON - 1"), so Mew sits in the main table at its ordinary Pokédex
 * position and takes the ordinary bank thresholds like everything else. See
 * [Gen1Game].
 */
object Gen1RomLocator {

    /** Which Generation I engine this ROM's data follows — see the class doc. */
    enum class Gen1Game { RED_BLUE, YELLOW }

    /** Where in the ROM file the base-stats table starts, and Mew's own separate record, when [Gen1Game.RED_BLUE]. */
    data class Located(val tableOffset: Int, val mewOffset: Int?)

    /**
     * Confirms this looks like a real ROM of [game] by finding its own
     * base-stats table, or returns null — never a location this app is not
     * sure of. In [Gen1Game.RED_BLUE], Mew's own record is found the same way
     * but is allowed to be missing without failing the rest: a ROM this app
     * can otherwise read fine is not refused over one species' sprite.
     */
    fun locate(rom: ByteArray, game: Gen1Game = Gen1Game.RED_BLUE): Located? {
        // Every species but Mew in Red/Blue — see the class doc. Yellow keeps
        // Mew in the ordinary Pokédex-number order along with everyone else.
        val bySpecies = when (game) {
            Gen1Game.RED_BLUE -> Gen1Data.species.filter { it.id != "MEW" }
            Gen1Game.YELLOW -> Gen1Data.species
        }.sortedBy { it.dexNumber }
        if (bySpecies.isEmpty() || bySpecies.first().dexNumber != 1) return null
        val signatures = bySpecies.map { signatureOf(it, game) }
        val firstSignature = signatures.first()

        var at = 0
        while (true) {
            val candidate = indexOf(rom, firstSignature, at)
            if (candidate < 0) return null
            if (matchesWholeTable(rom, candidate, signatures)) {
                val mewOffset = if (game == Gen1Game.RED_BLUE) locateMew(rom) else null
                return Located(candidate, mewOffset)
            }
            at = candidate + 1
        }
    }

    /** Mew's own standalone record, wherever it is — its five stats all being 100 makes it its own signature. */
    private fun locateMew(rom: ByteArray): Int? {
        val mew = Gen1Data.species("MEW") ?: return null
        val at = indexOf(rom, signatureOf(mew, Gen1Game.RED_BLUE), 0)
        return at.takeIf { it >= 0 }
    }

    /**
     * This species' front sprite, decompressed — null when [located] does
     * not (or no longer) account for it, or the tables have never heard of
     * the species at all.
     */
    fun frontSprite(
        rom: ByteArray,
        located: Located,
        speciesId: String,
        game: Gen1Game = Gen1Game.RED_BLUE,
    ): Gen1SpriteCodec.DecodedSprite? {
        val species = Gen1Data.species(speciesId) ?: return null
        val recordStart = if (game == Gen1Game.RED_BLUE && species.id == "MEW") {
            located.mewOffset ?: return null
        } else {
            located.tableOffset + (species.dexNumber - 1) * BASE_DATA_SIZE
        }
        val pointerAt = recordStart + BASE_FRONTPIC_OFFSET
        if (pointerAt + 1 !in rom.indices) return null
        val pointer = (rom[pointerAt].toInt() and 0xFF) or ((rom[pointerAt + 1].toInt() and 0xFF) shl 8)
        if (pointer !in 0x4000..0x7FFF) return null
        val bank = bankFor(species.internalIndex, game)
        val fileOffset = bank * 0x4000 + (pointer - 0x4000)
        return runCatching { Gen1SpriteCodec.decompress(rom, fileOffset) }.getOrNull()
    }

    /**
     * `UncompressMonSprite`'s bank table, by internal index. Red/Blue name
     * Mew as an exception (bank $1, alongside its standalone base-stats
     * record); Yellow has no such branch, so Mew's own low internal index
     * ($15) simply lands in the first ordinary bracket like everyone else
     * near it. The Fossil Kabutops is a second named exception in both, but
     * nothing this app stores a Pokémon under is ever the fossil pic.
     */
    private fun bankFor(internalIndex: Int, game: Gen1Game): Int {
        if (game == Gen1Game.RED_BLUE && internalIndex == MEW_INDEX) return 0x1
        return when {
            internalIndex <= TANGELA_INDEX -> 0x9
            internalIndex <= MOLTRES_INDEX -> 0xA
            internalIndex <= BEEDRILL_INDEX + 1 -> 0xB
            internalIndex <= STARMIE_INDEX -> 0xC
            else -> 0xD
        }
    }

    /**
     * The ten struct bytes this app can already name for a species without
     * reading a ROM.
     *
     * Two of them move in Yellow: pret/pokeyellow's own data lowers
     * Dragonair's catch rate from 45 to 27 and Dragonite's from 45 to 9 (an
     * intentional balance change — Yellow is the one where the player's
     * rival ends up with a Dragonite. confirmed against pret/pokeyellow's
     * `data/pokemon/base_stats/dragonair.asm` and `dragonite.asm`, the only
     * two files in the whole table whose stat bytes differ from Red/Blue's).
     * Every other stat, both types, and base experience are unchanged.
     */
    private fun signatureOf(species: Gen1Species, game: Gen1Game): ByteArray {
        val type1 = TYPE_BYTES[species.primaryType] ?: error("unknown type ${species.primaryType}")
        val type2 = species.secondaryType?.let { TYPE_BYTES[it] ?: error("unknown type $it") } ?: type1
        val catchRate = if (game == Gen1Game.YELLOW) {
            YELLOW_CATCH_RATE[species.id] ?: species.catchRate
        } else species.catchRate
        return byteArrayOf(
            species.dexNumber.toByte(),
            species.baseHp.toByte(),
            species.baseAttack.toByte(),
            species.baseDefense.toByte(),
            species.baseSpeed.toByte(),
            species.baseSpecial.toByte(),
            type1.toByte(),
            type2.toByte(),
            catchRate.toByte(),
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

    /** Yellow's own catch rates for the two species it changed — see [signatureOf]. */
    private val YELLOW_CATCH_RATE: Map<String, Int> = mapOf(
        "DRAGONAIR" to 27,
        "DRAGONITE" to 9,
    )

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
