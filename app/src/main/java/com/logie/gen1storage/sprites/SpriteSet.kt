package com.logie.gen1storage.sprites

import com.logie.gen1storage.gen1recomp.GameVersion

/**
 * A set of Generation I front sprites, one per game whose art differs.
 *
 * A Pokémon is shown in the art of the game it came from: a Red or Blue save
 * gets the Red/Blue sprites, a Yellow save gets Yellow's. A player can override
 * that per species.
 */
enum class SpriteSet(
    val id: String,
    val label: String,
    /** Path inside the sprite repository, without a leading or trailing slash. */
    val remotePath: String,
    /** Downloaded as part of the normal sprite download. */
    val downloadedByDefault: Boolean,
) {
    RED_BLUE("rb", "RED/BLUE", "rb/gen1rb/Enlarged", true),
    YELLOW("yellow", "YELLOW", "yellow/gen1/Enlarged", true),

    /**
     * Green's art is in the same archive and slots in here whenever it is
     * wanted; nothing downloads it yet, so it never appears in the picker.
     */
    GREEN("rg", "GREEN", "rg/gen1rg/Enlarged", false);

    companion object {
        fun fromId(id: String?): SpriteSet? = entries.firstOrNull { it.id == id }

        val downloadable: List<SpriteSet> = entries.filter { it.downloadedByDefault }

        /** Which game's art a save's Pokémon is shown in by default. */
        fun forGame(version: GameVersion?): SpriteSet = when (version) {
            GameVersion.YELLOW -> YELLOW
            else -> RED_BLUE
        }

        fun forGameId(id: String?): SpriteSet = forGame(GameVersion.fromId(id))
    }
}

/**
 * The archive names its files after the species in lower case with the
 * punctuation dropped — `mrmime.png`, `nidoranf.png`, `farfetchd.png` — so a
 * pokered constant maps across by stripping everything that is not a letter or
 * a digit. Verified against all 151 filenames in each set.
 */
fun spriteFileName(speciesId: String): String =
    speciesId.lowercase().filter { it.isLetterOrDigit() }
