package com.logie.gen1storage.pokemon

/**
 * One species as pokered stores it. [id] is the `constants/pokemon_constants.asm`
 * name, which is the key Gen1Recomp writes into `save.lua` (`mon.species`).
 */
data class Gen1Species(
    override val id: String,
    val internalIndex: Int,
    override val dexNumber: Int,
    override val displayName: String,
    val baseHp: Int,
    val baseAttack: Int,
    val baseDefense: Int,
    val baseSpeed: Int,
    val baseSpecial: Int,
    override val catchRate: Int,
    override val baseExp: Int,
    override val primaryType: String,
    override val secondaryType: String?,
    override val growthRate: String,
) : SpeciesInfo {

    override val generation: Int get() = 1

    fun baseStat(stat: Gen1Stat): Int = when (stat) {
        Gen1Stat.HP -> baseHp
        Gen1Stat.ATTACK -> baseAttack
        Gen1Stat.DEFENSE -> baseDefense
        Gen1Stat.SPEED -> baseSpeed
        Gen1Stat.SPECIAL -> baseSpecial
    }
}

/** One move as pokered stores it; [id] is the `move_constants.asm` name. */
data class Gen1Move(
    override val id: String,
    override val internalIndex: Int,
    override val displayName: String,
    override val type: String,
    override val power: Int,
    override val accuracy: Int,
    override val basePp: Int,
) : MoveInfo {
    override val generation: Int get() = 1
}

/**
 * One species' Pokédex entry, as the cartridge prints it.
 *
 * Two entries rather than one, because Yellow rewrote almost all of them and a
 * Pokémon should be read in the words of the game it came out of. [red] is what
 * Red and Blue print — pokered builds both from the same text — and [yellow] is
 * Yellow's, null only if that cartridge has none.
 *
 * Height and weight are stored the way the cartridge stores them: feet and
 * inches, and pounds to one decimal place. Converting them here would be this
 * app quietly disagreeing with the screen it is copying.
 */
data class Gen1DexEntry(
    val speciesId: String,
    /** The classification the Pokédex prints over the entry: "MOUSE", "SEED". */
    val category: String,
    val heightFeet: Int,
    val heightInches: Int,
    val weightTenthsOfAPound: Int,
    val red: String,
    val yellow: String?,
) {
    val heightText: String get() = "$heightFeet'${"%02d".format(heightInches)}\""

    val weightText: String get() =
        "%d.%dlb".format(weightTenthsOfAPound / 10, weightTenthsOfAPound % 10)

    /**
     * The entry the given game prints. Anything that is not Yellow — including
     * a save whose version cannot be read — gets Red and Blue's, which is the
     * one two of the three cartridges use.
     */
    fun forGame(gameVersionId: String?): String =
        if (gameVersionId?.lowercase() == "yellow") yellow ?: red else red

    /** The entry as separate lines, with the page break kept as a blank one. */
    fun lines(gameVersionId: String?): List<String> = forGame(gameVersionId).split("\n")

    /**
     * The entry as one piece of prose, to be wrapped by whatever shows it.
     *
     * The cartridge's breaks are where its own window ran out of room — a
     * fourteen-character line and a page that held three of them — not where
     * the sentence wanted one. Kept as they are on a phone they read as a
     * poem: six short lines stacked down the middle of a window wide enough
     * for three of them. The words are the same words; only the machine that
     * decides where they end is different.
     */
    fun flowing(gameVersionId: String?): String =
        forGame(gameVersionId).split("\n").joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
}

/**
 * The five Generation I stats, in `src/pokemon/Stats.lua`'s ORDER. Special is a
 * single stat in Gen I; the split arrives in Gen II and lives in [Gen2Stat],
 * which is a separate enum rather than two more entries here — a Generation I
 * Pokémon has five stats, and giving it a sixth that is always absent would be
 * a worse answer than not asking.
 */
enum class Gen1Stat(val key: String, val label: String) {
    HP("hp", "HP"),
    ATTACK("attack", "ATTACK"),
    DEFENSE("defense", "DEFENSE"),
    SPEED("speed", "SPEED"),
    SPECIAL("special", "SPECIAL");

    companion object {
        val ORDER: List<Gen1Stat> = listOf(HP, ATTACK, DEFENSE, SPEED, SPECIAL)
        fun byKey(key: String): Gen1Stat? = ORDER.firstOrNull { it.key == key }
    }
}

/**
 * The Generation I species and move tables, generated from pret/pokered by
 * `tools/generate_gen1_data.py`.
 *
 * A species or move the tables do not know is never rejected: Gen1Recomp
 * supports mods that add content, and this app must not decide a modded save's
 * Pokémon is invalid. Unknown ids simply display as their raw id and skip the
 * stat derivation that needs base stats.
 */
object Gen1Data {

    val species: List<Gen1Species> = GEN1_SPECIES_TABLE
    val moves: List<Gen1Move> = GEN1_MOVE_TABLE

    val dex: List<Gen1DexEntry> = GEN1_DEX_TABLE

    private val speciesById: Map<String, Gen1Species> = species.associateBy { it.id }
    private val dexById: Map<String, Gen1DexEntry> = dex.associateBy { it.speciesId }
    private val movesById: Map<String, Gen1Move> = moves.associateBy { it.id }

    fun species(id: String?): Gen1Species? = id?.let { speciesById[it] }
    fun move(id: String?): Gen1Move? = id?.let { movesById[it] }

    /** The Pokédex entry for a species, or null for one the tables never had. */
    fun dexEntry(id: String?): Gen1DexEntry? = id?.let { dexById[it] }

    fun speciesName(id: String?): String = species(id)?.displayName ?: id.orEmpty()
    fun moveName(id: String?): String = move(id)?.displayName ?: id.orEmpty()

    /** Status ids as `src/pokemon/Pokemon.lua` spells them. */
    val statusIds = listOf("SLP", "PSN", "BRN", "FRZ", "PAR")
}
