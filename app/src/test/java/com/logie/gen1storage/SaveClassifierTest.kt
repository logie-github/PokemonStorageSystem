package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.gen1recomp.SaveClassification
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a blob turns out to be. A save that will not parse is classified and
 * left alone; it is never a licence to write something else over it.
 */
class SaveClassifierTest {

    /**
     * A Generation II save is judged by Generation II's own rules, not
     * Generation I's — a party of six Gold Pokémon is not a broken Red
     * party — and comes back [SaveClassification.Valid] the same as any
     * Generation I save does, which is what lets the transfer engine change
     * it at all.
     */
    @Test
    fun `a Gen II save is judged by Generation II's rules`() {
        val gold = LuaValue.Table().apply {
            this["version"] = luaStr("gold")
            this["generation"] = luaNum(2)
            this["player"] = LuaValue.Table().apply { this["name"] = luaStr("KRIS") }
            this["party"] = LuaValue.Table()
        }
        val classification = SaveClassifier.classify(LuaWriter.encode(gold))
        assertTrue(classification is SaveClassification.Valid)
        assertNotNull(classification.save)
        assertEquals("KRIS", classification.save?.trainerName)
        assertTrue(classification.save!!.isGen2)
        assertTrue(GameVersion.GOLD.isWritable)
        // Fourteen boxes, not twelve: what it is written back as is what the
        // game it belongs to ships with.
        assertEquals(14, classification.save!!.stockBoxes)
    }

    @Test
    fun `options and mod files are not mistaken for saves`() {
        val options = LuaValue.Table().apply {
            this["saveSlots"] = LuaValue.Table()
            this["safeMode"] = LuaValue.Bool(false)
        }
        assertTrue(SaveClassifier.classify(LuaWriter.encode(options)) is SaveClassification.Unsupported)
        assertTrue(SaveClassifier.classify("return { name = \"a mod\" }") is SaveClassification.Unsupported)
    }

    @Test
    fun `an untagged save is Red, as upstream migration 2 decides`() {
        val root = SaveFixtures.save()
        root.remove(LuaKey.Name("version"))
        assertEquals(GameVersion.RED, SaveClassifier.classify(LuaWriter.encode(root)).save?.version)
    }

    @Test
    fun `malformed source is classified, not thrown`() {
        val classification = SaveClassifier.classify("return { party = ")
        assertTrue(classification is SaveClassification.Malformed)
        assertNotNull((classification as SaveClassification.Malformed).offset)
    }

    @Test
    fun `a save missing its party is incomplete rather than unsupported`() {
        val root = SaveFixtures.save()
        root.remove(LuaKey.Name("party"))
        val classification = SaveClassifier.classify(LuaWriter.encode(root))
        assertTrue(classification is SaveClassification.Incomplete)
        assertEquals(listOf("party"), (classification as SaveClassification.Incomplete).missing)
    }

    @Test
    fun `an over-full party is flagged but still readable`() {
        val root = SaveFixtures.save(party = (1..8).map { SaveFixtures.pokemon(level = it) })
        val classification = SaveClassifier.classify(LuaWriter.encode(root))
        assertTrue(classification is SaveClassification.ValidWithInvalidPokemon)
        assertNotNull(classification.save)
    }

    @Test
    fun `a modded species is carried, not rejected`() {
        val root = SaveFixtures.save(
            party = listOf(SaveFixtures.pokemon(species = "TOTALLY_CUSTOM_MON", withStats = false))
        )
        val classification = SaveClassifier.classify(LuaWriter.encode(root))
        assertTrue(classification is SaveClassification.Valid)
        assertTrue((classification as SaveClassification.Valid).warnings.any { it.contains("NON-VANILLA") })
    }

    @Test
    fun `trainer, badges and play time read off a real save`() {
        val save = SaveClassifier.classify(SaveFixtures.encode(SaveFixtures.save())).save!!
        assertEquals("ASH", save.trainerName)
        assertEquals(12345, save.trainerId)
        assertEquals(2, save.badgeCount)
        assertEquals("2:01", save.playTimeText)
        assertEquals(2, save.dexOwnedCount)
    }
}
