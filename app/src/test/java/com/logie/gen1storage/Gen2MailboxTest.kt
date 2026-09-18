package com.logie.gen1storage

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.pokemon.Gen2Mail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC MAILBOX itself: `save.mail.box`, and the two whole operations —
 * `SEND MAIL TO PC` and `ATTACH MAIL` — that move a letter between it and a
 * party Pokémon. [Gen2EggMailTest] covers the party side alone
 * (`sPartyMail`'s own shifting); this is `Mail.sendToPc` and
 * `Mail.moveFromPcToParty` in full, both trees moving in the one call.
 */
class Gen2MailboxTest {

    private fun root(vararg party: LuaValue.Table): LuaValue.Table = LuaValue.Table().apply {
        this["party"] = LuaValue.Table().apply { setArray(party.toList()) }
    }

    private fun mon(species: String, item: String? = null, isEgg: Boolean = false): LuaValue.Table =
        LuaValue.Table().apply {
            this["species"] = luaStr(species)
            this["level"] = luaNum(10)
            item?.let { this["item"] = luaStr(it) }
            if (isEgg) this["isEgg"] = LuaValue.Bool(true)
        }

    @Test
    fun `sending mail to the PC clears both the letter and the item`() {
        val holder = mon("PIDGEY", item = "FLOWER_MAIL")
        val save = root(holder)
        save["mail"] = LuaValue.Table().apply {
            this["party"] = LuaValue.Table().apply {
                this[1] = LuaValue.Table().apply {
                    this["type"] = luaStr("FLOWER_MAIL")
                    this["message"] = luaStr("Hi!")
                    this["author"] = luaStr("RED")
                }
            }
        }

        assertTrue(Gen2Mail.sendToPc(save, 1))

        assertNull(holder["item"])
        assertNull(Gen2Mail.letter(save, 1))
        assertEquals(1, Gen2Mail.mailboxCount(save))
        val filed = Gen2Mail.mailbox(save).single()
        assertEquals("FLOWER_MAIL", filed.type)
        assertEquals("Hi!", filed.message)
        assertEquals("RED", filed.author)
    }

    @Test
    fun `a mail item with no struct behind it still files as a blank letter`() {
        // An older save, or one the extractor could not resolve: the item is
        // there but save.mail.party has nothing for this slot.
        val holder = mon("PIDGEY", item = "SURF_MAIL")
        val save = root(holder)

        assertTrue(Gen2Mail.sendToPc(save, 1))

        assertNull(holder["item"])
        val filed = Gen2Mail.mailbox(save).single()
        assertEquals("SURF_MAIL", filed.type)
        assertEquals("", filed.message)
    }

    @Test
    fun `sending mail does not touch a Pokemon holding an ordinary item`() {
        val holder = mon("PIDGEY", item = "LEFTOVERS")
        val save = root(holder)

        assertFalse(Gen2Mail.sendToPc(save, 1))
        assertEquals("LEFTOVERS", holder["item"].asString())
    }

    @Test
    fun `the MAILBOX holds ten and no more`() {
        val save = root(mon("PIDGEY"))
        repeat(Gen2Mail.MAILBOX_CAPACITY) { index ->
            assertTrue(
                Gen2Mail.appendToMailbox(
                    save,
                    Gen2Mail.Letter("FLOWER_MAIL", "letter $index", "RED", null, "PIDGEY"),
                )
            )
        }
        assertEquals(Gen2Mail.MAILBOX_CAPACITY, Gen2Mail.mailboxCount(save))
        assertTrue(Gen2Mail.mailboxFull(save))
        assertFalse(
            Gen2Mail.appendToMailbox(
                save,
                Gen2Mail.Letter("FLOWER_MAIL", "one too many", "RED", null, "PIDGEY"),
            )
        )
        assertEquals(Gen2Mail.MAILBOX_CAPACITY, Gen2Mail.mailboxCount(save))

        // And sendToPc refuses the same way once it is full.
        val holder = mon("SPEAROW", item = "SURF_MAIL")
        val full = root(holder)
        full["mail"] = save["mail"]
        assertFalse(Gen2Mail.sendToPc(full, 1))
        assertEquals("SURF_MAIL", holder["item"].asString())
    }

    @Test
    fun `deleting from the MAILBOX keeps it dense`() {
        val save = root(mon("PIDGEY"))
        listOf("a", "b", "c").forEach {
            Gen2Mail.appendToMailbox(save, Gen2Mail.Letter("FLOWER_MAIL", it, "RED", null, null))
        }
        val removed = Gen2Mail.removeFromMailbox(save, 2)
        assertEquals("b", removed?.message)
        assertEquals(listOf("a", "c"), Gen2Mail.mailbox(save).map { it.message })
        assertNull(Gen2Mail.removeFromMailbox(save, 99))
    }

    @Test
    fun `attaching mail gives the Pokemon both the letter and its stationery`() {
        val target = mon("SPEAROW")
        val save = root(target)
        Gen2Mail.appendToMailbox(save, Gen2Mail.Letter("MUSIC_MAIL", "La la la", "KRIS", 4242, "SPEAROW"))

        assertTrue(Gen2Mail.attachFromMailbox(save, 1, 1))

        assertEquals("MUSIC_MAIL", target["item"].asString())
        assertEquals("La la la", Gen2Mail.letter(save, 1)?.message)
        assertEquals(0, Gen2Mail.mailboxCount(save))
    }

    @Test
    fun `attaching a letter that is no longer there refuses cleanly`() {
        val save = root(mon("SPEAROW"))
        assertFalse(Gen2Mail.attachFromMailbox(save, 1, 1))
    }

    @Test
    fun `mail items are the ten the cartridge lists and nothing else`() {
        assertTrue(Gen2Mail.isMail("PORTRAITMAIL"))
        assertFalse(Gen2Mail.isMail("MAIL"))
        assertFalse(Gen2Mail.isMail(null))
        assertEquals(10, Gen2Mail.ITEMS.toSet().size)
    }
}
