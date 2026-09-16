package com.logie.gen1storage.pokemon

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable

/**
 * MAIL: the ten letters a Generation II Pokémon can carry.
 *
 * The letter is not a field on the Pokémon. `sPartyMail` is six `mailmsg`
 * structs indexed **by party slot**, and Gen1Recomp keeps that shape as
 * `save.mail.party` (see its `src/core/gen2/Mail.lua`, which ports
 * `engine/pokemon/mail.asm` and the struct out of `macros/ram.asm`). The
 * consequence is the whole reason this object exists: taking a Pokémon out of
 * slot 2 has to move slots 3 through 6 up behind it, or the next Pokémon
 * along inherits somebody else's letter. That is `RemoveMonFromPartyOrBox`'s
 * "Mail time!" tail in `engine/pokemon/move_mon.asm`, and it is not optional.
 *
 * The Pokémon holding one is a separate question, answered by [isMail] over
 * the item it carries.
 */
object Gen2Mail {

    /**
     * `MailItems` from `data/items/mail_items.asm`, in its order.
     *
     * `ItemIsMail` is a linear search of exactly this list, which is why it is
     * a list here rather than a name test: LITEBLUEMAIL and PORTRAITMAIL do
     * not end in `_MAIL`, and a test on the spelling would miss both.
     */
    val ITEMS: List<String> = listOf(
        "FLOWER_MAIL", "SURF_MAIL", "LITEBLUEMAIL", "PORTRAITMAIL", "LOVELY_MAIL",
        "EON_MAIL", "MORPH_MAIL", "BLUESKY_MAIL", "MUSIC_MAIL", "MIRAGE_MAIL",
    )

    private val IDS: Set<String> = ITEMS.toSet()

    /** `PARTY_LENGTH`: `sPartyMail` is this many structs and no more. */
    const val PARTY_LENGTH = 6

    fun isMail(itemId: String?): Boolean =
        itemId != null && itemId.uppercase() in IDS

    /** One `mailmsg`, as much of it as anything here needs to show. */
    data class Letter(
        val type: String,
        val message: String,
        val author: String,
        val authorId: Int?,
        val species: String?,
    )

    /** The letter pinned to a party slot, 1-based, or null for an empty one. */
    fun letter(root: LuaValue.Table, slot: Int): Letter? {
        val entry = partyTable(root)?.get(slot).asTable() ?: return null
        val type = entry["type"].asString() ?: return null
        return Letter(
            type = type,
            message = entry["message"].asString().orEmpty(),
            author = entry["author"].asString().orEmpty(),
            authorId = entry["authorId"].asInt(),
            species = entry["species"].asString(),
        )
    }

    /**
     * The "Mail time!" tail: every struct after the departing slot moves up
     * one, and the slot that was last is emptied.
     *
     * A no-op on a save that has never seen a letter, which is most of them,
     * and deliberately so: this must never create the table it did not find.
     * A save that serializes six empty structs where it used to have none is
     * a save this app has changed for no reason.
     */
    fun removePartySlot(root: LuaValue.Table, slot: Int) {
        if (slot < 1) return
        val party = partyTable(root) ?: return
        // Setting a key to null is how this table deletes one, so a slot with
        // nothing above it empties rather than keeping what it had.
        for (at in slot until PARTY_LENGTH) party[at] = party[at + 1]
        party[PARTY_LENGTH] = null
    }

    private fun partyTable(root: LuaValue.Table): LuaValue.Table? =
        root["mail"].asTable()?.get("party").asTable()
}
