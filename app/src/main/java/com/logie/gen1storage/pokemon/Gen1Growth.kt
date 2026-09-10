package com.logie.gen1storage.pokemon

import kotlin.math.floor
import kotlin.math.max

/**
 * The six Generation I experience curves, ported from upstream
 * `src/pokemon/Growth.lua`, which in turn carries pokered's
 * `engine/pokemon/experience.asm` GrowthRateTable coefficients.
 *
 * The status screen's second page needs this and nothing else does: "LEVEL UP
 * 61 to :L5" is the experience still owed to the next level.
 */
object Gen1Growth {

    fun expForLevel(growthRate: String?, level: Int): Int {
        val n = level.toLong()
        val value = when (growthRate) {
            "MEDIUM_FAST" -> n * n * n
            "SLIGHTLY_FAST" -> floor((3.0 * n * n * n) / 4.0).toLong() + 10 * n * n - 30
            "SLIGHTLY_SLOW" -> floor((3.0 * n * n * n) / 4.0).toLong() + 20 * n * n - 70
            "MEDIUM_SLOW" -> floor((6.0 * n * n * n) / 5.0).toLong() - 15 * n * n + 100 * n - 140
            "FAST" -> floor((4.0 * n * n * n) / 5.0).toLong()
            "SLOW" -> floor((5.0 * n * n * n) / 4.0).toLong()
            // Upstream falls back to MEDIUM_FAST for an unknown curve rather
            // than mis-levelling silently; a modded curve lands here.
            else -> n * n * n
        }
        return max(0L, value).toInt()
    }

    /**
     * Experience still owed to reach the next level, as the status screen
     * shows it. Null at level 100 and for a species whose curve is unknown,
     * where the screen has nothing truthful to print.
     */
    fun expToNextLevel(pokemon: Gen1Pokemon): Int? {
        val species = pokemon.species ?: return null
        val level = pokemon.level
        if (level >= MAX_LEVEL) return null
        return max(0, expForLevel(species.growthRate, level + 1) - pokemon.exp)
    }

    const val MAX_LEVEL = 100
}
