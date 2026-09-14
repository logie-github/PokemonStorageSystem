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
    /**
     * Which generation's art this is.
     *
     * The two never mix. A Pokémon out of a Red save is drawn in Red's art and
     * one out of a Gold save in Gold's, because they are different drawings of
     * the same creature and a box that mixed them would look like two games
     * side by side. Only the player choosing a set for a species crosses it.
     */
    val generation: Int = 1,
    /**
     * For Generation II, the decompilation the art is taken from and the file
     * inside a species' folder. Generation I's sets come from one archive of
     * upscaled art instead, which is what [remotePath] describes.
     */
    val repo: String? = null,
    val frontFile: String? = null,
) {
    RED_BLUE("rb", "RED/BLUE", "rb/gen1rb/Enlarged", true),
    YELLOW("yellow", "YELLOW", "yellow/gen1/Enlarged", true),

    /**
     * Green's art is in the same archive and slots in here whenever it is
     * wanted; nothing downloads it yet, so it never appears in the picker.
     */
    GREEN("rg", "GREEN", "rg/gen1rg/Enlarged", false),

    /**
     * Generation II, from pret's own decompilations: the sprite as the
     * cartridge drew it, at its own size, with the colours the Game Boy Color
     * gave it. `front_gold` and `front_silver` sit side by side in pokegold
     * because the two games were drawn separately; Crystal redrew them again
     * and keeps its in pokecrystal as `front`, with its animation frames
     * stacked underneath the first one.
     */
    GOLD("gold", "GOLD", "", true, generation = 2, repo = "pret/pokegold", frontFile = "front_gold.png"),
    SILVER("silver", "SILVER", "", true, generation = 2, repo = "pret/pokegold", frontFile = "front_silver.png"),
    CRYSTAL("crystal", "CRYSTAL", "", true, generation = 2, repo = "pret/pokecrystal", frontFile = "front.png");

    companion object {
        fun fromId(id: String?): SpriteSet? = entries.firstOrNull { it.id == id }

        val downloadable: List<SpriteSet> = entries.filter { it.downloadedByDefault }

        /** Which game's art a save's Pokémon is shown in by default. */
        fun forGame(version: GameVersion?): SpriteSet = when (version) {
            GameVersion.YELLOW -> YELLOW
            else -> RED_BLUE
        }

        /**
         * Which set a Pokémon out of this game is drawn in.
         *
         * The Generation II ids are read here rather than through
         * [GameVersion], which is the three Generation I games and nothing
         * else on purpose — a Gold save is out of this app's scope, and a
         * Pokémon that came from one still has to be drawn.
         */
        fun forGameId(id: String?): SpriteSet = when (id?.lowercase()) {
            "gold" -> GOLD
            "silver" -> SILVER
            "crystal" -> CRYSTAL
            else -> forGame(GameVersion.fromId(id))
        }

        /** Which generation a save's Pokémon belong to, by its version id. */
        fun generationOf(gameVersionId: String?): Int = forGameId(gameVersionId).generation

        /** The Generation II sets, which share one download and one palette file. */
        val gen2: List<SpriteSet> = entries.filter { it.generation == 2 }
    }
}

/**
 * What pret calls a species' folder: the name in lower case with every piece
 * of punctuation turned into an underscore rather than dropped.
 *
 * Which is why it is not [spriteFileName]. MR. MIME is `mr__mime` — the dot
 * and the space each leave one — and FARFETCH'D is `farfetch_d`. Checked
 * against all 151 folders in pokecrystal.
 */
fun gen2SpriteFolder(speciesId: String): String = when (speciesId.uppercase()) {
    "MR_MIME", "MR.MIME", "MRMIME" -> "mr__mime"
    "FARFETCH'D", "FARFETCHD", "FARFETCH_D" -> "farfetch_d"
    "HO_OH", "HO-OH" -> "ho_oh"
    else -> speciesId.lowercase().filter { it.isLetterOrDigit() || it == '_' }
}

/**
 * The archive names its files after the species in lower case with the
 * punctuation dropped — `mrmime.png`, `nidoranf.png`, `farfetchd.png` — so a
 * pokered constant maps across by stripping everything that is not a letter or
 * a digit. Verified against all 151 filenames in each set.
 */
fun spriteFileName(speciesId: String): String =
    speciesId.lowercase().filter { it.isLetterOrDigit() }
