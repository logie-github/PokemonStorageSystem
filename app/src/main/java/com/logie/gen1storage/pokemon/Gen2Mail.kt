package com.logie.gen1storage.pokemon

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr

/**
 * MAIL: the ten letters a Generation II Pokémon can carry, and the PC
 * MAILBOX that holds the ones detached from a Pokémon.
 *
 * Two structures, both ported from `engine/pokemon/mail.asm` by way of
 * Gen1Recomp's `src/core/gen2/Mail.lua`:
 *
 *  - `save.mail.party` — `sPartyMail`, six `mailmsg` structs indexed **by
 *    party slot**, sparse. A letter is not a field on the Pokémon: taking one
 *    out of slot 2 has to move slots 3 through 6 up behind it, or the next
 *    Pokémon along inherits somebody else's letter. That is
 *    `RemoveMonFromPartyOrBox`'s "Mail time!" tail and [removePartySlot] is
 *    it. Sending a letter to the PC ([clearPartySlot]) does not shift
 *    anything — the mon stays exactly where it was, only lighter.
 *
 *  - `save.mail.box` — `sMailboxes`, up to [MAILBOX_CAPACITY] structs, dense
 *    and ordered, exactly the array every other list in this save is (see
 *    [LuaValue.Table.array]). `MailboxPC` lists them by author; nothing here
 *    reorders them.
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

    /** `MAILBOX_CAPACITY`: what `sMailboxCount` is allowed to reach. */
    const val MAILBOX_CAPACITY = 10

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

    // ------- the letter on a Pokémon

    /** The letter pinned to a party slot, 1-based, or null for an empty one. */
    fun letter(root: LuaValue.Table, slot: Int): Letter? = fromLua(partyTable(root)?.get(slot))

    /**
     * `Mail.clear`'s own shape: empties one slot and nothing else.
     *
     * For a letter that is leaving the Pokémon rather than the Pokémon
     * leaving the party — [appendToMailbox] uses this, never
     * [removePartySlot], because the mon stays exactly where it was.
     */
    fun clearPartySlot(root: LuaValue.Table, slot: Int) {
        partyTable(root)?.let { it[slot] = null }
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

    /**
     * `Mail.set`: pins a letter to a party slot, making the party mail table
     * the first time this save has ever needed one.
     */
    private fun setPartySlot(root: LuaValue.Table, slot: Int, letter: Letter) {
        if (slot < 1) return
        ensurePartyTable(root)[slot] = toLua(letter)
    }

    /**
     * `Mail.sendToPc` (the deposit-blocking `SEND MAIL TO PC` question, once
     * answered yes): detaches [partySlot]'s letter from its Pokémon and files
     * it in the MAILBOX, clearing the held item in the same motion so a
     * Pokémon can never end up holding an item with no letter behind it or a
     * letter with no item. False when that Pokémon holds no mail or the
     * MAILBOX has no room; the party is untouched either way.
     */
    fun sendToPc(root: LuaValue.Table, partySlot: Int): Boolean {
        val party = root["party"].asTable()?.array() ?: return false
        val mon = party.getOrNull(partySlot - 1).asTable() ?: return false
        val heldItem = mon["item"].asString() ?: return false
        if (!isMail(heldItem) || mailboxFull(root)) return false
        // A mail ITEM with no struct behind it — an older save, or a letter
        // the extractor could not resolve — is sent on as a blank letter
        // rather than dropping the stationery on the floor.
        val entry = letter(root, partySlot) ?: Letter(heldItem, "", "", null, mon["species"].asString())
        appendToMailbox(root, entry)
        clearPartySlot(root, partySlot)
        mon["item"] = null
        return true
    }

    /**
     * `Mail.moveFromPcToParty` (ATTACH MAIL): moves the letter at
     * [mailboxIndex] onto [partySlot] and gives that Pokémon its stationery
     * as a held item. False when there is no such letter or no such
     * Pokémon; whether that Pokémon is *allowed* to take it — an egg, one
     * already holding something — is for the caller to have asked first,
     * the same way the cartridge's own menu asks before this ever runs.
     */
    fun attachFromMailbox(root: LuaValue.Table, mailboxIndex: Int, partySlot: Int): Boolean {
        val party = root["party"].asTable()?.array() ?: return false
        val mon = party.getOrNull(partySlot - 1).asTable() ?: return false
        val entry = mailbox(root).getOrNull(mailboxIndex - 1) ?: return false
        removeFromMailbox(root, mailboxIndex)
        setPartySlot(root, partySlot, entry)
        mon["item"] = luaStr(entry.type)
        return true
    }

    private fun partyTable(root: LuaValue.Table): LuaValue.Table? =
        root["mail"].asTable()?.get("party").asTable()

    private fun ensurePartyTable(root: LuaValue.Table): LuaValue.Table {
        val mail = root["mail"].asTable() ?: LuaValue.Table().also { root["mail"] = it }
        return mail["party"].asTable() ?: LuaValue.Table().also { mail["party"] = it }
    }

    // ------- the MAILBOX

    /** Every letter the MAILBOX holds, author-list order — `Mail.mailbox`. */
    fun mailbox(root: LuaValue.Table): List<Letter> =
        boxTable(root)?.array().orEmpty().mapNotNull(::fromLua)

    fun mailboxCount(root: LuaValue.Table): Int = boxTable(root)?.array()?.size ?: 0

    fun mailboxFull(root: LuaValue.Table): Boolean = mailboxCount(root) >= MAILBOX_CAPACITY

    /**
     * `SendMailToPC`'s struct move, without the party or item side of it —
     * see the transfer engine for the whole operation. False only when the
     * MAILBOX has no room, which the caller checks first so nothing here
     * needs to undo a partial write.
     */
    fun appendToMailbox(root: LuaValue.Table, letter: Letter): Boolean {
        if (mailboxFull(root)) return false
        val box = ensureBoxTable(root)
        box.setArray(box.array() + toLua(letter))
        return true
    }

    /**
     * `DeleteMailFromPC`: the shift-up that keeps `sMailboxes` dense, taken
     * by 1-based position exactly as the list on screen numbers it.
     */
    fun removeFromMailbox(root: LuaValue.Table, index: Int): Letter? {
        val box = boxTable(root) ?: return null
        val list = box.array().toMutableList()
        if (index !in 1..list.size) return null
        val removed = list.removeAt(index - 1)
        box.setArray(list)
        return fromLua(removed)
    }

    private fun boxTable(root: LuaValue.Table): LuaValue.Table? =
        root["mail"].asTable()?.get("box").asTable()

    private fun ensureBoxTable(root: LuaValue.Table): LuaValue.Table {
        val mail = root["mail"].asTable() ?: LuaValue.Table().also { root["mail"] = it }
        return mail["box"].asTable() ?: LuaValue.Table().also { mail["box"] = it }
    }

    // ------- the struct itself

    private fun fromLua(value: LuaValue?): Letter? {
        val entry = value.asTable() ?: return null
        val type = entry["type"].asString() ?: return null
        return Letter(
            type = type,
            message = entry["message"].asString().orEmpty(),
            author = entry["author"].asString().orEmpty(),
            authorId = entry["authorId"].asInt(),
            species = entry["species"].asString(),
        )
    }

    private fun toLua(letter: Letter): LuaValue.Table = LuaValue.Table().apply {
        this["type"] = luaStr(letter.type)
        this["message"] = luaStr(letter.message)
        this["author"] = luaStr(letter.author)
        letter.authorId?.let { this["authorId"] = luaNum(it) }
        letter.species?.let { this["species"] = luaStr(it) }
    }
}
