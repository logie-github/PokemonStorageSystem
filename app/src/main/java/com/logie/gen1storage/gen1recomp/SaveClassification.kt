package com.logie.gen1storage.gen1recomp

import com.logie.gen1storage.lua.LuaParseException
import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.pokemon.Gen1Pokemon

/**
 * What a candidate file turned out to be.
 *
 * A save that fails to parse is never overwritten on that basis. Classifying
 * the failure is the whole point: an unsupported file, a Generation II save and
 * a half-written staged file each call for a different answer, and none of them
 * calls for this app to rewrite the player's progress.
 */
sealed interface SaveClassification {

    /** Parsed, Generation I, and shaped like a save this app can work with. */
    data class Valid(override val save: Gen1RecompSave, val warnings: List<String> = emptyList()) : SaveClassification

    /** Parsed and Generation I, but at least one Pokémon is not usable. */
    data class ValidWithInvalidPokemon(
        override val save: Gen1RecompSave,
        val problems: List<String>,
    ) : SaveClassification

    /** Parsed Lua, but not a Gen1Recomp progress file at all. */
    data class Unsupported(val reason: String) : SaveClassification

    /** The bytes are not the serializer's grammar. */
    data class Malformed(val reason: String, val offset: Int?) : SaveClassification

    /** Parsed, but a table this app must be able to read is missing. */
    data class Incomplete(val missing: List<String>) : SaveClassification

    /** The file could not be read at all. */
    data class Inaccessible(val reason: String) : SaveClassification

    /** The parsed save, or null when this file is not one that can be used. */
    val save: Gen1RecompSave? get() = null

    /** A short line for the save list and the diagnostics report. */
    val summary: String
        get() = when (this) {
            is Valid -> if (warnings.isEmpty()) "OK" else warnings.first()
            is ValidWithInvalidPokemon -> problems.first()
            is Unsupported -> reason
            is Malformed -> if (offset != null) "$reason (byte $offset)" else reason
            is Incomplete -> "MISSING: ${missing.joinToString(", ")}"
            is Inaccessible -> reason
        }
}

/**
 * Turns save bytes into a classification.
 *
 * The parse itself is upstream's grammar (see [LuaParser]); everything after it
 * checks the tables `SaveData` guarantees a Generation I progress file has.
 */
object SaveClassifier {

    fun classify(bytes: ByteArray): SaveClassification {
        val text = try {
            LuaText.decode(bytes)
        } catch (e: Exception) {
            return SaveClassification.Inaccessible(e.message ?: "Unreadable file")
        }
        return classify(text)
    }

    fun classify(text: String): SaveClassification {
        val root = try {
            LuaParser.parse(text)
        } catch (e: LuaParseException) {
            return SaveClassification.Malformed(
                e.message?.substringAfter(": ") ?: "Not save-file source", e.offset,
            )
        } catch (e: Exception) {
            return SaveClassification.Malformed(e.message ?: "Not save-file source", null)
        }

        val save = Gen1RecompSave(root)

        // `options.lua`, a mod's own store and a slot registry all parse as
        // valid Lua source; only a progress file carries a player and a party.
        val missing = buildList {
            if (root["player"].asTable() == null) add("player")
            if (root["party"].asTable() == null) add("party")
        }
        if (missing.size == 2) {
            return SaveClassification.Unsupported("NOT A GEN1RECOMP SAVE")
        }
        if (missing.isNotEmpty()) {
            return SaveClassification.Incomplete(missing)
        }
        save.version ?: return SaveClassification.Unsupported("UNKNOWN GAME VERSION")

        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val partyEntries = root["party"].asTable()?.array().orEmpty()
        partyEntries.forEachIndexed { index, entry ->
            if (!Gen1Pokemon.looksLikePokemon(entry)) problems += "PARTY SLOT ${index + 1} IS NOT A POKéMON"
        }
        if (partyEntries.size > Gen1RecompSave.PARTY_MAX) {
            problems += "PARTY HOLDS ${partyEntries.size} (MAX ${Gen1RecompSave.PARTY_MAX})"
        }

        // Against what this save's boxes actually hold, which a mod may have
        // said is more than twenty. Judging a modded save by the stock number
        // would report every one of its boxes as broken.
        val capacity = save.boxCapacity
        save.boxes.forEachIndexed { index, box ->
            if (box.size > capacity) {
                problems += "BOX ${index + 1} HOLDS ${box.size} (MAX $capacity)"
            }
        }

        // A Pokémon whose species this app's tables do not know is a mod's
        // Pokémon, not a broken one. It is carried through untouched and only
        // noted, because refusing it would make the app useless on a modded
        // playthrough and "fixing" it would corrupt one.
        // Against the save's own generation's table: CHIKORITA is not a
        // non-vanilla species in a Gold save, and MR_MIME is spelled two ways.
        val unknownSpecies = (save.party + save.boxes.flatten())
            .mapNotNull { it.speciesId }
            .filter { it.speciesIsUnknownIn(save.isGen2) }
            .distinct()
        if (unknownSpecies.isNotEmpty()) {
            warnings += "NON-VANILLA SPECIES: ${unknownSpecies.take(3).joinToString(", ")}"
        }

        return if (problems.isEmpty()) SaveClassification.Valid(save, warnings)
        else SaveClassification.ValidWithInvalidPokemon(save, problems)
    }

    private fun String.speciesIsUnknownIn(gen2: Boolean): Boolean =
        if (gen2) com.logie.gen1storage.pokemon.Gen2Data.species(this) == null
        else com.logie.gen1storage.pokemon.Gen1Data.species(this) == null
}
