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
}

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

    private val speciesById: Map<String, Gen2Species> = species.associateBy { it.id }

    fun species(id: String?): Gen2Species? = id?.let { speciesById[it] }

    fun speciesName(id: String?): String = species(id)?.displayName ?: id.orEmpty()

    /** Every species id Generation II knows, in Pokédex order. */
    val speciesIds: List<String> = species.map { it.id }

    /** How many there are, which is the number everyone knows this table by. */
    const val SPECIES_COUNT = 251
}
