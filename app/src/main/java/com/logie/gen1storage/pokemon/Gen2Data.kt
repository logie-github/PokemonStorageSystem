package com.logie.gen1storage.pokemon

/**
 * What every generation's species table can answer.
 *
 * Generation II rebalanced a good many of the original 151 and split Special
 * in two while it was at it, so PIDGEY is not one row that both games read: it
 * is two rows, and which one is right depends on the Pokémon in hand rather
 * than on the app's preference. This is the part both tables agree on, and the
 * part the app displays; anything a generation alone has — Generation I's
 * internal index, Generation II's two Special stats — stays on its own type.
 */
interface SpeciesInfo {
    val id: String
    val dexNumber: Int
    val displayName: String
    val primaryType: String
    val secondaryType: String?
    val catchRate: Int
    val baseExp: Int
    val growthRate: String

    /** Which generation's table this row came out of. */
    val generation: Int

    val types: List<String> get() = listOfNotNull(primaryType, secondaryType)
}

/**
 * What every generation's move table can answer.
 *
 * Generation II did not only add moves: it rewrote seventeen of Generation
 * I's. KARATE CHOP became FIGHTING, GUST became FLYING, BITE became DARK,
 * EXPLOSION went from 170 to 250, and BLIZZARD lost twenty points of
 * accuracy. A move is therefore read against the generation of the Pokémon
 * that knows it — the same rule the species tables follow, and for the same
 * reason: the id is identical and the numbers are not.
 */
interface MoveInfo {
    val id: String
    val internalIndex: Int
    val displayName: String
    val type: String
    val power: Int
    val accuracy: Int
    val basePp: Int
    val generation: Int
}

/** One move as pokecrystal stores it; [id] is the `move_constants.asm` name. */
data class Gen2Move(
    override val id: String,
    override val internalIndex: Int,
    override val displayName: String,
    override val type: String,
    override val power: Int,
    override val accuracy: Int,
    override val basePp: Int,
) : MoveInfo {
    override val generation: Int get() = 2
}

/**
 * A Pokédex page as the screens show one, whichever generation wrote it.
 *
 * The two tables are shaped differently — Generation I holds one entry with
 * Red's and Yellow's texts inside it, Generation II holds three whole pages —
 * so this is what a screen asks a Pokémon for, and [Gen1Pokemon.dexPage] is
 * what decides which table answers.
 */
data class DexPage(
    val category: String,
    val heightText: String,
    val weightText: String,
    /** The entry's own lines, with the page break kept as a blank one. */
    val lines: List<String>,
    /** The entry as prose, to be wrapped by whatever shows it. */
    val flowing: String,
)

/**
 * One species as pokecrystal stores it. [id] is the
 * `constants/pokemon_constants.asm` name, which is what Gen1Recomp writes into
 * a Generation II `save.lua` (`mon.species`) — the same spelling Generation I
 * uses for the Pokémon the two share, which is exactly why a Pokémon has to
 * carry its generation rather than have it guessed from its name.
 *
 * [dexNumber] is also the species' index: Generation II numbers them in
 * Pokédex order, where Generation I's internal order was its own.
 */
data class Gen2Species(
    override val id: String,
    override val dexNumber: Int,
    override val displayName: String,
    val baseHp: Int,
    val baseAttack: Int,
    val baseDefense: Int,
    val baseSpeed: Int,
    val baseSpecialAttack: Int,
    val baseSpecialDefense: Int,
    override val catchRate: Int,
    override val baseExp: Int,
    override val primaryType: String,
    override val secondaryType: String?,
    override val growthRate: String,
    /**
     * How likely one of this species is to be female, out of 255 -
     * pokecrystal's own `BASE_GENDER` byte, from `constants/pokemon_data_
     * constants.asm`'s `percent EQUS "* $ff / 100"` macro: 0 is always male,
     * 254 is always female, 255 (`GENDER_UNKNOWN`) is no gender at all.
     * [genderOf] is what turns this and one Pokémon's own DVs into a gender.
     */
    val genderRatio: Int,
) : SpeciesInfo {

    override val generation: Int get() = 2

    fun baseStat(stat: Gen2Stat): Int = when (stat) {
        Gen2Stat.HP -> baseHp
        Gen2Stat.ATTACK -> baseAttack
        Gen2Stat.DEFENSE -> baseDefense
        Gen2Stat.SPEED -> baseSpeed
        Gen2Stat.SPECIAL_ATTACK -> baseSpecialAttack
        Gen2Stat.SPECIAL_DEFENSE -> baseSpecialDefense
    }

    /**
     * Which gender one specific Pokémon of this species is, ported from
     * `GetGender` (`engine/pokemon/mon_stats.asm`): the Attack DV's own four
     * bits at the top of a byte, the Speed DV's at the bottom, weighed
     * against [genderRatio] the same way every later generation still does.
     * Null for a species with no gender at all.
     */
    fun genderOf(attackDv: Int, speedDv: Int): Gender? = when (genderRatio) {
        GENDER_UNKNOWN -> null
        0 -> Gender.MALE
        GENDER_F100 -> Gender.FEMALE
        else -> {
            val combined = ((attackDv and 0xF) shl 4) or (speedDv and 0xF)
            if (combined <= genderRatio) Gender.FEMALE else Gender.MALE
        }
    }

    companion object {
        private const val GENDER_F100 = 254
        const val GENDER_UNKNOWN = 255
    }
}

/** MALE or FEMALE - [Gen2Species.genderOf] is null rather than either for a genderless species. */
enum class Gender(val symbol: String) { MALE("♂"), FEMALE("♀") }

/**
 * The six Generation II stats, keyed as upstream writes them.
 *
 * `src/battle/gen2/Mon.lua` calculates `hp`, `attack`, `defense`, `speed`,
 * `specialAttack` and `specialDefense`, so those are the keys a Generation II
 * `mon.stats` table carries. The DVs and stat exp behind them are still
 * Generation I's five — one Special DV feeds both halves — which is why this
 * enum describes the stats a Pokémon has and not the numbers it grew from.
 */
enum class Gen2Stat(val key: String, val label: String) {
    HP("hp", "HP"),
    ATTACK("attack", "ATTACK"),
    DEFENSE("defense", "DEFENSE"),
    SPEED("speed", "SPEED"),
    SPECIAL_ATTACK("specialAttack", "SP.ATK"),
    SPECIAL_DEFENSE("specialDefense", "SP.DEF");

    companion object {
        val ORDER: List<Gen2Stat> = listOf(HP, ATTACK, DEFENSE, SPEED, SPECIAL_ATTACK, SPECIAL_DEFENSE)

        /** The rows the status screen prints under HP. */
        val BATTLE: List<Gen2Stat> = listOf(ATTACK, DEFENSE, SPEED, SPECIAL_ATTACK, SPECIAL_DEFENSE)

        fun byKey(key: String): Gen2Stat? = ORDER.firstOrNull { it.key == key }
    }
}

/**
 * One species' Pokédex entry as one cartridge prints it.
 *
 * Height is stored as the cartridge stores it — feet and inches — and weight
 * to a tenth of a pound, the same as Generation I's table, so the app never
 * quietly disagrees with the screen it is copying.
 */
data class Gen2DexPage(
    /** The classification printed over the entry: "SEED", "ROCK SNAKE". */
    val category: String,
    val heightFeet: Int,
    val heightInches: Int,
    val weightTenthsOfAPound: Int,
    /** The two pages of three lines, with the page break kept as a blank one. */
    val text: String,
) {
    val heightText: String get() = "$heightFeet'${"%02d".format(heightInches)}\""

    val weightText: String get() =
        "%d.%dlb".format(weightTenthsOfAPound / 10, weightTenthsOfAPound % 10)

    /** The entry as separate lines, with the page break kept as a blank one. */
    val lines: List<String> get() = text.split("\n")

    /**
     * The entry as one piece of prose, to be wrapped by whatever shows it —
     * the same treatment [Gen1DexEntry.flowing] gives Generation I's, and for
     * the same reason: the cartridge's line breaks are where its own window
     * ran out of room, not where the sentence wanted one.
     */
    val flowing: String get() = text.split("\n").joinToString(" ") { it.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
}

/**
 * One species' Pokédex entry, in each of the three cartridges' own words.
 *
 * Gold, Silver and Crystal each print something different, and not only the
 * prose: GOLD and SILVER call NATU a LITTLEBIRD where CRYSTAL gives it the
 * space, GOLD has ENTEI four inches taller than CRYSTAL does, and SILVER does
 * the same to TYRANITAR. So each game gets a whole page rather than a shared
 * set of facts with three texts hung off it.
 */
data class Gen2DexEntry(
    val speciesId: String,
    val gold: Gen2DexPage,
    val silver: Gen2DexPage,
    val crystal: Gen2DexPage,
) {
    /**
     * The page the given game prints.
     *
     * Generation I could fall back on "whatever is not Yellow", since two of
     * its three cartridges share one set of entries. These three agree about
     * nothing, so a Pokémon whose version cannot be read gets GOLD's — the
     * first of the three, and the first card on the shelf.
     */
    fun forGame(gameVersionId: String?): Gen2DexPage = when (gameVersionId?.lowercase()) {
        "crystal" -> crystal
        "silver" -> silver
        else -> gold
    }
}

/**
 * The Generation II species table, generated from pret/pokecrystal by
 * `tools/generate_gen2_data.py`.
 *
 * Read only for Pokémon that came out of a Generation II save. A Pokémon from
 * Red, Blue or Yellow keeps Generation I's numbers however familiar its name
 * is here — see [Gen1Pokemon.generation]. Moving one forward is a thing the
 * games do deliberately, through the Time Capsule, and it is not something
 * this app should do to a Pokémon by accident of which table it read first.
 *
 * As with Generation I, a species the table does not know is never rejected:
 * it displays as its raw id and skips whatever needed base stats.
 */
object Gen2Data {

    val species: List<Gen2Species> = GEN2_SPECIES_TABLE

    /** Generated from pret/pokecrystal by `tools/generate_gen2_moves.py`. */
    val moves: List<Gen2Move> = GEN2_MOVE_TABLE

    /** Generated from pret/pokegold and pret/pokecrystal by `tools/generate_gen2_dex.py`. */
    val dex: List<Gen2DexEntry> = GEN2_DEX_TABLE

    private val speciesById: Map<String, Gen2Species> = species.associateBy { it.id }
    private val dexById: Map<String, Gen2DexEntry> = dex.associateBy { it.speciesId }
    private val movesById: Map<String, Gen2Move> = moves.associateBy { it.id }

    fun species(id: String?): Gen2Species? = id?.let { speciesById[it] }

    /** The Pokédex entry for a species, or null for one the tables never had. */
    fun dexEntry(id: String?): Gen2DexEntry? = id?.let { dexById[it] }

    fun speciesName(id: String?): String = species(id)?.displayName ?: id.orEmpty()

    fun move(id: String?): Gen2Move? = id?.let { movesById[it] }

    fun moveName(id: String?): String = move(id)?.displayName ?: id.orEmpty()

    /** Every species id Generation II knows, in Pokédex order. */
    val speciesIds: List<String> = species.map { it.id }

    /** How many there are, which is the number everyone knows this table by. */
    const val SPECIES_COUNT = 251

    /**
     * The same Pokémon under Generation II's spelling of its name.
     *
     * pokered writes MR_MIME and FARFETCHD where pokecrystal writes MR__MIME
     * and FARFETCH_D, so a Generation II save's Pokédex is keyed differently
     * from a Generation I one's for exactly two species. Anything else is
     * spelled the same in both.
     */
    fun idOf(speciesId: String): String = when (speciesId.uppercase()) {
        "MR_MIME" -> "MR__MIME"
        "FARFETCHD" -> "FARFETCH_D"
        else -> speciesId
    }
}

/** The same, back the other way: Generation I's spelling of a name. */
fun gen1SpeciesId(speciesId: String): String = when (speciesId.uppercase()) {
    "MR__MIME" -> "MR_MIME"
    "FARFETCH_D" -> "FARFETCHD"
    else -> speciesId
}

/**
 * A species' Pokédex page as one cartridge prints it, whichever generation
 * that cartridge belongs to.
 *
 * Takes the species under either generation's spelling and answers under the
 * one the asking game uses, so a screen holding a single id can ask all six
 * cartridges the same question.
 */
fun dexPageOf(speciesId: String?, generation: Int, gameVersionId: String?): DexPage? {
    if (speciesId == null) return null
    return if (generation >= 2) {
        Gen2Data.dexEntry(Gen2Data.idOf(speciesId))?.forGame(gameVersionId)?.let {
            DexPage(it.category, it.heightText, it.weightText, it.lines, it.flowing)
        }
    } else {
        Gen1Data.dexEntry(gen1SpeciesId(speciesId))?.let {
            DexPage(
                it.category,
                it.heightText,
                it.weightText,
                it.lines(gameVersionId),
                it.flowing(gameVersionId),
            )
        }
    }
}
