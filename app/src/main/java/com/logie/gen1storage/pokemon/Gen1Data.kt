package com.logie.gen1storage.pokemon

/**
 * One species as pokered stores it. [id] is the `constants/pokemon_constants.asm`
 * name, which is the key Gen1Recomp writes into `save.lua` (`mon.species`).
 */
data class Gen1Species(
    val id: String,
    val internalIndex: Int,
    val dexNumber: Int,
    val displayName: String,
    val baseHp: Int,
    val baseAttack: Int,
    val baseDefense: Int,
    val baseSpeed: Int,
    val baseSpecial: Int,
    val catchRate: Int,
    val baseExp: Int,
    val primaryType: String,
    val secondaryType: String?,
    val growthRate: String,
) {
    fun baseStat(stat: Gen1Stat): Int = when (stat) {
        Gen1Stat.HP -> baseHp
        Gen1Stat.ATTACK -> baseAttack
        Gen1Stat.DEFENSE -> baseDefense
        Gen1Stat.SPEED -> baseSpeed
        Gen1Stat.SPECIAL -> baseSpecial
    }

    val types: List<String> get() = listOfNotNull(primaryType, secondaryType)
}

/** One move as pokered stores it; [id] is the `move_constants.asm` name. */
data class Gen1Move(
    val id: String,
    val internalIndex: Int,
    val displayName: String,
    val type: String,
    val power: Int,
    val accuracy: Int,
    val basePp: Int,
)

/**
 * The five Generation I stats, in `src/pokemon/Stats.lua`'s ORDER. Special is a
 * single stat in Gen I — the split arrives in Gen II, which is out of scope.
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

    private val speciesById: Map<String, Gen1Species> = species.associateBy { it.id }
    private val movesById: Map<String, Gen1Move> = moves.associateBy { it.id }

    fun species(id: String?): Gen1Species? = id?.let { speciesById[it] }
    fun move(id: String?): Gen1Move? = id?.let { movesById[it] }

    fun speciesName(id: String?): String = species(id)?.displayName ?: id.orEmpty()
    fun moveName(id: String?): String = move(id)?.displayName ?: id.orEmpty()

    /** Status ids as `src/pokemon/Pokemon.lua` spells them. */
    val statusIds = listOf("SLP", "PSN", "BRN", "FRZ", "PAR")
}
