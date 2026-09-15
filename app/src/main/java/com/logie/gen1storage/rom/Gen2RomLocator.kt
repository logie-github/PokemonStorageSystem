package com.logie.gen1storage.rom

import com.logie.gen1storage.pokemon.Gen2Data
import com.logie.gen1storage.pokemon.Gen2Species

/**
 * Finds a Generation II species' base-stats record, and from it its front
 * sprite, in a Gold, Silver, or Crystal ROM the player has supplied — the
 * same "never trust a memorized address, only what this app can already
 * name and verify" stance [Gen1RomLocator] takes, extended to a struct
 * `constants/pokemon_data_constants.asm` shows is 32 bytes here rather than
 * Generation I's 28: a dex number, six stats (Special split into attack and
 * defense), two types, catch rate, base experience — the eleven bytes this
 * app can already name from [Gen2Data] — then two held items, a gender
 * ratio, two unlabelled bytes real cartridges still fill in, egg cycles, a
 * pic-size nibble, front and back pic *fields* (present but unused — see
 * below), growth rate, egg groups, and a TM/HM bitfield.
 *
 * That last part matters: pret/pokecrystal and pret/pokegold both mark the
 * base-stats struct's own front/back pic fields "unused (beta front/back
 * pics)" — the real pointer lives in a separate table, `PokemonPicPointers`
 * (`data/pokemon/pic_pointers.asm`), ordered the same way as the base-stats
 * table but not itself made of data this app already knows the bytes of.
 * A pointer table has nothing to find by signature; what it has is a shape
 * every one of its entries obeys — a two-byte address in $4000-$7FFF, and a
 * bank byte one call to [fixPicBank] turns into the real bank number, ported
 * unchanged from `FixPicBank` (Crystal's is a subtract-then-index into 24
 * sequential "Pics N" banks; Gold and Silver's is the raw byte unchanged
 * except three specific values history left behind, `engine/gfx/
 * load_pics.asm`'s own `.FixPicBankTable`) — and the strongest shape of all,
 * that what it points to decompresses ([Gen2SpriteCodec]) into exactly the
 * square picture [Located.tableOffset]'s own `BASE_PIC_SIZE` byte already
 * said it would be. A candidate table only stands once every species this
 * app can check agrees with the table it is already sure of.
 *
 * Unown is the one species this cannot hold: real Gold, Silver, and Crystal
 * ROMs give it no entry at all in `PokemonPicPointers` (six bytes of `$FF`,
 * `dba_pics` called with no pic to name — the real cartridge answers Unown's
 * sprite from a wholly separate table, indexed by letter rather than by
 * species). It is excluded from the table search the same way Mew is
 * excluded from Red and Blue's.
 */
object Gen2RomLocator {

    /** Which bank-fixup rule applies — see the class doc and [fixPicBank]. */
    enum class Gen2Game { GOLD_SILVER, CRYSTAL }

    /** Where the base-stats table starts, and where `PokemonPicPointers` was confirmed to start. */
    data class Located(val tableOffset: Int, val picPointersOffset: Int)

    private const val UNOWN_DEX = 201

    /**
     * Confirms this looks like a real ROM of [game] by finding its base-stats
     * table, then confirming `PokemonPicPointers` against it — or returns
     * null, never a location this app is not sure of.
     */
    fun locate(rom: ByteArray, game: Gen2Game): Located? {
        val bySpecies = Gen2Data.species.sortedBy { it.dexNumber }
        if (bySpecies.isEmpty() || bySpecies.first().dexNumber != 1) return null
        val signatures = bySpecies.map { signatureOf(it) }
        val firstSignature = signatures.first()

        var at = 0
        while (true) {
            val candidate = indexOf(rom, firstSignature, at)
            if (candidate < 0) return null
            if (matchesWholeTable(rom, candidate, signatures)) {
                val picPointers = locatePicPointers(rom, candidate, bySpecies, game) ?: return null
                return Located(candidate, picPointers)
            }
            at = candidate + 1
        }
    }

    /**
     * This species' front sprite, decompressed — null when [located] does
     * not (or no longer) account for it, the species is Unown (see the class
     * doc), or the tables have never heard of the species at all.
     */
    fun frontSprite(rom: ByteArray, located: Located, speciesId: String, game: Gen2Game): Gen1SpriteCodec.DecodedSprite? {
        val species = Gen2Data.species(speciesId) ?: return null
        if (species.dexNumber == UNOWN_DEX) return null
        val entryAt = located.picPointersOffset + (species.dexNumber - 1) * PIC_POINTER_ENTRY_SIZE
        return runCatching { readSprite(rom, entryAt, game) }.getOrNull()
    }

    /**
     * Every species but Unown's own signature is verified in the exact
     * position `PokemonPicPointers` puts it, once a candidate start [t] has
     * already passed a cheap per-entry shape check on its way here.
     */
    private fun locatePicPointers(rom: ByteArray, baseStatsOffset: Int, bySpecies: List<Gen2Species>, game: Gen2Game): Int? {
        val expectedTilesByDex = HashMap<Int, Int>(bySpecies.size * 2)
        for (species in bySpecies) {
            if (species.dexNumber == UNOWN_DEX) continue
            val picSizeAt = baseStatsOffset + (species.dexNumber - 1) * BASE_DATA_SIZE + PIC_SIZE_OFFSET
            if (picSizeAt !in rom.indices) return null
            expectedTilesByDex[species.dexNumber] = rom[picSizeAt].toInt() and 0xF
        }

        val tableSize = bySpecies.size * PIC_POINTER_ENTRY_SIZE
        val last = rom.size - tableSize
        candidates@ for (t in 0..last) {
            for (species in bySpecies) {
                if (species.dexNumber == UNOWN_DEX) continue
                val entryAt = t + (species.dexNumber - 1) * PIC_POINTER_ENTRY_SIZE
                val expectedSideTiles = expectedTilesByDex.getValue(species.dexNumber)
                val sprite = runCatching { readSprite(rom, entryAt, game) }.getOrNull()
                if (sprite == null || sprite.widthPx != expectedSideTiles * 8) continue@candidates
            }
            return t
        }
        return null
    }

    /** Reads one `PokemonPicPointers` entry's front half and decompresses whatever it points to. */
    private fun readSprite(rom: ByteArray, entryAt: Int, game: Gen2Game): Gen1SpriteCodec.DecodedSprite? {
        if (entryAt + 2 !in rom.indices) return null
        val bankRaw = rom[entryAt].toInt() and 0xFF
        val addr = (rom[entryAt + 1].toInt() and 0xFF) or ((rom[entryAt + 2].toInt() and 0xFF) shl 8)
        if (addr !in 0x4000..0x7FFF) return null
        val bank = fixPicBank(bankRaw, game) ?: return null
        val fileOffset = bank * 0x4000 + (addr - 0x4000)
        if (fileOffset !in rom.indices) return null
        return Gen2SpriteCodec.decompress(rom, fileOffset)
    }

    /**
     * `FixPicBank`, ported per game rather than guessed at — Crystal's own
     * table is 24 sequential banks starting at `BANK("Pics 1")`, so the
     * subtract-then-index it performs collapses to one additive constant;
     * Gold and Silver's is the raw byte unchanged except the three values
     * their own `.FixPicBankTable` names. Null for a raw byte neither game
     * ever names — Unown's placeholder `$FF` included.
     */
    private fun fixPicBank(raw: Int, game: Gen2Game): Int? = when (game) {
        Gen2Game.CRYSTAL -> {
            val index = raw - CRYSTAL_PICS_INDEX_BASE
            if (index in 0 until CRYSTAL_PICS_BANK_COUNT) CRYSTAL_PICS_FIRST_BANK + index else null
        }
        Gen2Game.GOLD_SILVER -> when (raw) {
            0x13 -> 0x1F
            0x14 -> 0x20
            0x1F -> 0x2E
            else -> raw
        }
    }

    /** The eleven struct bytes this app can already name for a species without reading a ROM. */
    private fun signatureOf(species: Gen2Species): ByteArray {
        val type1 = TYPE_BYTES[species.primaryType] ?: error("unknown type ${species.primaryType}")
        val type2 = species.secondaryType?.let { TYPE_BYTES[it] ?: error("unknown type $it") } ?: type1
        return byteArrayOf(
            species.dexNumber.toByte(),
            species.baseHp.toByte(),
            species.baseAttack.toByte(),
            species.baseDefense.toByte(),
            species.baseSpeed.toByte(),
            species.baseSpecialAttack.toByte(),
            species.baseSpecialDefense.toByte(),
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

    // constants/type_constants.asm: PHYSICAL types 0-9 (Generation II added
    // STEEL here), then a gap, then SPECIAL types from $14 (unchanged from
    // Generation I) plus DARK at the end. BIRD ($06) is defined but no
    // species in the base-stats table actually carries it.
    private val TYPE_BYTES: Map<String, Int> = mapOf(
        "NORMAL" to 0x00, "FIGHTING" to 0x01, "FLYING" to 0x02, "POISON" to 0x03,
        "GROUND" to 0x04, "ROCK" to 0x05, "BUG" to 0x07, "GHOST" to 0x08, "STEEL" to 0x09,
        "FIRE" to 0x14, "WATER" to 0x15, "GRASS" to 0x16, "ELECTRIC" to 0x17,
        "PSYCHIC" to 0x18, "ICE" to 0x19, "DRAGON" to 0x1A, "DARK" to 0x1B,
    )

    /** `BASE_DATA_SIZE`: 24 bytes up to and including egg groups, then a TM/HM bitfield 8 bytes long in all three games. */
    private const val BASE_DATA_SIZE = 32

    /** `BASE_PIC_SIZE`'s byte offset within that record — the same in Gold, Silver, and Crystal. */
    private const val PIC_SIZE_OFFSET = 17

    /** `table_width 3 * 2`: a bank+address pair for the front pic, then another for the back. */
    private const val PIC_POINTER_ENTRY_SIZE = 6

    // Crystal's own layout.link: "Pics 1" through "Pics 24" are twenty-four
    // sequential banks starting at $48, and PICS_FIX is $36 — so raw byte
    // `BANK(label) - PICS_FIX` is `BANK(label) - $48 + $12`, i.e. an index
    // from $12 that FixPicBank turns back into $48 + index.
    private const val CRYSTAL_PICS_INDEX_BASE = 0x12
    private const val CRYSTAL_PICS_FIRST_BANK = 0x48
    private const val CRYSTAL_PICS_BANK_COUNT = 24
}
