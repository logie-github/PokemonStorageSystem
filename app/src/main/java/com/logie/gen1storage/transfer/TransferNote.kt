package com.logie.gen1storage.transfer

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.pokemon.Gen1Pokemon
import org.json.JSONObject

/**
 * A request left for the game to change one of its own saves.
 *
 * This is the whole of what this app says to a cartridge. It does not write
 * the save; it leaves a note, the game applies it the next time it syncs, and
 * the note comes back saying what happened. One program writes a save and it
 * is the one the save belongs to, which is the only arrangement where two
 * programs editing the same file cannot fork it.
 *
 * A note is addressed to a playthrough, carries an id, and is applied at most
 * once: the game deletes it as part of the same save it writes, so a sync that
 * dies halfway either did the write and cleared the note or did neither.
 *
 * ## The wire form
 *
 * ```json
 * {
 *   "id": "5f2c…",                    // uuid, the idempotency key
 *   "version": "red",
 *   "playthroughId": "quiet-forest-dawn",
 *   "kind": "give" | "take",
 *   "createdAt": 1757808000,
 *
 *   // give: put this Pokémon into the save
 *   "mon": "{dvs={15,12,10,9},…}",    // one record, as the Lua a save holds
 *   "to": { "party": true } | { "box": 3 },
 *
 *   // take: remove the Pokémon at this spot
 *   "from": { "party": 2 } | { "box": 3, "slot": 4 },
 *   "expect": {                       // and only if it is still this one
 *     "species": "CLEFABLE", "level": 36,
 *     "otName": "A", "otId": 49363, "exp": 41874
 *   },
 *
 *   "status": "pending" | "applied" | "refused",
 *   "reason": "THAT BOX IS FULL"      // refused only
 * }
 * ```
 *
 * `expect` is what stops a note doing the wrong thing to a save that has moved
 * on. A box slot is a position, and positions shift: the player may have
 * rearranged that box, released what was in it, or evolved it, between this
 * app reading the save and the game reading the note. The game compares these
 * five fields to what is actually in that spot and refuses the note if they
 * disagree — a refusal costs a message, and taking the wrong Pokémon costs the
 * Pokémon.
 *
 * The Pokémon in a `give` travels as the Lua record a save file holds, not as
 * a translation of it. The game already parses exactly this to load a save, so
 * what arrives is what this app was handed, field for field, including any
 * field a mod wrote that neither side has heard of.
 */
data class TransferNote(
    val id: String,
    val version: String,
    val playthroughId: String,
    val kind: Kind,
    val createdAtEpochSeconds: Long,
    /** [Kind.GIVE]: the Pokémon, as the Lua a save holds. */
    val mon: String? = null,
    /** [Kind.GIVE]: where in the save it should land. */
    val to: Target? = null,
    /** [Kind.TAKE]: where it is now. */
    val from: SaveLocation? = null,
    /** [Kind.TAKE]: what has to be in that spot for the note to be acted on. */
    val expect: Expectation? = null,
    val status: Status = Status.PENDING,
    val reason: String? = null,
) {
    /** The playthrough this is addressed to, keyed as the account lists it. */
    val key: String get() = "$version/$playthroughId"

    enum class Kind(val wire: String) {
        /** This app hands a Pokémon to the cartridge. */
        GIVE("give"),

        /** The cartridge hands one to this app. */
        TAKE("take");

        companion object {
            fun of(wire: String?): Kind? = entries.firstOrNull { it.wire == wire }
        }
    }

    enum class Status(val wire: String) {
        /** Left, and not yet picked up. */
        PENDING("pending"),

        /** The game changed its save and this is done. */
        APPLIED("applied"),

        /** The game read it and would not do it; [reason] says why. */
        REFUSED("refused");

        companion object {
            fun of(wire: String?): Status = entries.firstOrNull { it.wire == wire } ?: PENDING
        }
    }

    /** Where a given Pokémon should land: the party, or a particular box. */
    sealed interface Target {
        data object Party : Target
        data class Box(val box: Int) : Target
    }

    /**
     * The five fields the game checks before it takes a Pokémon out.
     *
     * Chosen because they are cheap on both sides and together they do not
     * repeat inside one save: two Pokémon can share a species and a level, and
     * a hundred and fifty of them can share a trainer, but the experience is a
     * running total of every battle a particular one has been in.
     */
    data class Expectation(
        val species: String,
        val level: Int,
        val otName: String?,
        val otId: Int?,
        val exp: Int?,
    ) {
        companion object {
            fun of(mon: Gen1Pokemon): Expectation = Expectation(
                species = mon.speciesId.orEmpty(),
                level = mon.level,
                otName = mon.otName,
                otId = mon.otId,
                exp = mon.exp,
            )

            fun of(table: LuaValue.Table): Expectation = of(Gen1Pokemon(table))
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("version", version)
        put("playthroughId", playthroughId)
        put("kind", kind.wire)
        put("createdAt", createdAtEpochSeconds)
        mon?.let { put("mon", it) }
        when (val where = to) {
            is Target.Party -> put("to", JSONObject().put("party", true))
            is Target.Box -> put("to", JSONObject().put("box", where.box))
            null -> Unit
        }
        when (val where = from) {
            is SaveLocation.Party -> put("from", JSONObject().put("party", where.slot))
            is SaveLocation.Box ->
                put("from", JSONObject().put("box", where.box).put("slot", where.slot))
            null -> Unit
        }
        expect?.let {
            put(
                "expect",
                JSONObject().apply {
                    put("species", it.species)
                    put("level", it.level)
                    it.otName?.let { name -> put("otName", name) }
                    it.otId?.let { id -> put("otId", id) }
                    it.exp?.let { exp -> put("exp", exp) }
                },
            )
        }
        put("status", status.wire)
        reason?.let { put("reason", it) }
    }

    companion object {

        /**
         * One Pokémon as the Lua a save holds, which is what a `give` carries.
         *
         * Written by the same encoder that writes a whole save, so what the
         * game reads out of a note is shaped exactly like what it reads out of
         * a file — the difference being only that this is one record rather
         * than the table around it.
         */
        fun encodeMon(data: LuaValue.Table): String = LuaWriter.encodeValue(data)

        fun fromJson(json: JSONObject): TransferNote? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val version = json.optString("version").takeIf { it.isNotBlank() } ?: return null
            val playthroughId =
                json.optString("playthroughId").takeIf { it.isNotBlank() } ?: return null
            val kind = Kind.of(json.optString("kind")) ?: return null
            return TransferNote(
                id = id,
                version = version,
                playthroughId = playthroughId,
                kind = kind,
                createdAtEpochSeconds = json.optLong("createdAt"),
                mon = json.optString("mon").takeIf { it.isNotBlank() },
                to = json.optJSONObject("to")?.let { where ->
                    when {
                        where.optBoolean("party") -> Target.Party
                        where.has("box") -> Target.Box(where.optInt("box"))
                        else -> null
                    }
                },
                from = json.optJSONObject("from")?.let { where ->
                    when {
                        where.has("box") ->
                            SaveLocation.Box(where.optInt("box"), where.optInt("slot"))
                        where.has("party") -> SaveLocation.Party(where.optInt("party"))
                        else -> null
                    }
                },
                expect = json.optJSONObject("expect")?.let { fields ->
                    Expectation(
                        species = fields.optString("species"),
                        level = fields.optInt("level"),
                        otName = fields.optString("otName").takeIf { it.isNotBlank() },
                        otId = fields.optInt("otId").takeIf { fields.has("otId") },
                        exp = fields.optInt("exp").takeIf { fields.has("exp") },
                    )
                },
                status = Status.of(json.optString("status")),
                reason = json.optString("reason").takeIf { it.isNotBlank() },
            )
        }

        /** What is actually in [location] of [save], for an [Expectation]. */
        fun at(save: Gen1RecompSave, location: SaveLocation): LuaValue.Table? = when (location) {
            is SaveLocation.Party -> save.party.getOrNull(location.slot - 1)?.raw
            is SaveLocation.Box ->
                save.boxes.getOrNull(location.box - 1)?.getOrNull(location.slot - 1)?.raw
        }
    }
}

/** Whether [table] is still the Pokémon a note was written about. */
fun TransferNote.Expectation.matches(table: LuaValue.Table?): Boolean {
    if (table == null) return false
    val mon = Gen1Pokemon(table)
    if (mon.speciesId != species) return false
    if (mon.level != level) return false
    if (otName != null && mon.otName != otName) return false
    if (otId != null && mon.otId != otId) return false
    if (exp != null && mon.exp != exp) return false
    return true
}
