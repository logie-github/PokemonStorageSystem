package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassification
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mod introducing itself to this app through the save.
 *
 * Gen1Recomp gives a mod no way to announce itself to anything outside the
 * running game, so the announcement goes where both sides can already reach:
 * a card under the save's own `meta.mods`, which travels with the save.
 *
 * What a card says about the boxes is a description of what the mod did, not
 * an instruction. How many boxes there are is still counted off the file; the
 * card only settles the one thing the file cannot show, which is how deep a
 * box is allowed to be when nothing has filled one yet.
 */
class SaveModHandshakeTest {

    /** The card TM32 Double Team writes. */
    private val doubleTeam = mapOf(
        "tm32_double_team" to mapOf<String, Any>(
            "name" to "TM32 Double Team",
            "version" to "1.3",
            "boxCount" to 24,
            "boxCapacity" to 20,
        )
    )

    private fun save(
        mods: Map<String, Map<String, Any>>? = null,
        boxes: List<List<LuaValue.Table>>? = null,
    ) = Gen1RecompSave(SaveFixtures.save(mods = mods, boxes = boxes))

    @Test
    fun `a mod that names itself is read back whole`() {
        val mod = save(mods = doubleTeam).mods.single()

        assertEquals("tm32_double_team", mod.id)
        assertEquals("TM32 Double Team", mod.name)
        assertEquals("1.3", mod.version)
        assertEquals(24, mod.boxCount)
        assertEquals(20, mod.boxCapacity)
    }

    @Test
    fun `a save with no mods names none`() {
        assertTrue(save().mods.isEmpty())
    }

    @Test
    fun `the card survives the round trip through the writer`() {
        val reopened = Gen1RecompSave(
            LuaParser.parse(LuaWriter.encode(SaveFixtures.save(mods = doubleTeam)))
        )

        assertEquals("TM32 Double Team", reopened.mods.single().name)
    }

    @Test
    fun `a plain list of mod ids is read for what it is`() {
        val root = SaveFixtures.save()
        root["meta"].asTable()!!["mods"] = LuaValue.Table.ofArray(
            listOf(luaStr("tm32_double_team"), luaStr("some_other_mod"))
        )

        val ids = Gen1RecompSave(root).mods.map { it.id }

        assertEquals(listOf("tm32_double_team", "some_other_mod"), ids)
        // A list carries no numbers, so nothing is claimed about the boxes.
        assertNull(Gen1RecompSave(root).mods.first().boxCount)
    }

    @Test
    fun `a set of id to true is read for what it is`() {
        val root = SaveFixtures.save()
        root["meta"].asTable()!!["mods"] = LuaValue.Table().apply {
            this["tm32_double_team"] = LuaValue.Bool(true)
            this["switched_off"] = LuaValue.Bool(false)
        }

        val ids = Gen1RecompSave(root).mods.map { it.id }

        assertEquals(listOf("tm32_double_team"), ids)
    }

    @Test
    fun `an undeclared save still holds twenty to a box`() {
        assertEquals(Gen1RecompSave.BOX_CAPACITY, save().boxCapacity)
    }

    @Test
    fun `Double Team's card leaves the box depth where it was`() {
        assertEquals(20, save(mods = doubleTeam).boxCapacity)
    }

    @Test
    fun `a mod that says it made the boxes deeper is taken at its word`() {
        val deep = mapOf(
            "roomier_boxes" to mapOf<String, Any>("name" to "Roomier Boxes", "boxCapacity" to 40)
        )

        assertEquals(40, save(mods = deep).boxCapacity)
    }

    @Test
    fun `a box deeper than stock is not called broken when the save says so`() {
        val deep = mapOf(
            "roomier_boxes" to mapOf<String, Any>("boxCapacity" to 40)
        )
        val thirty = (1..30).map { SaveFixtures.pokemon(nickname = "M$it") }
        val root = SaveFixtures.save(mods = deep, boxes = listOf(thirty))

        val classified = SaveClassifier.classify(LuaWriter.encode(root))

        assertTrue(classified.summary, classified is SaveClassification.Valid)
    }

    @Test
    fun `the same box is called broken when nothing has said otherwise`() {
        val thirty = (1..30).map { SaveFixtures.pokemon(nickname = "M$it") }
        val root = SaveFixtures.save(boxes = listOf(thirty))

        val classified = SaveClassifier.classify(LuaWriter.encode(root))

        assertTrue(classified.summary, classified is SaveClassification.ValidWithInvalidPokemon)
    }

    @Test
    fun `what a card claims about the count does not create boxes`() {
        // The card says twenty-four; the save has the stock twelve because the
        // PC has not been opened since the mod went on. The file wins: growing
        // it here would strand boxes the game has never made if the mod ever
        // came off again.
        val twelve = save(mods = doubleTeam, boxes = List(12) { emptyList() })

        assertEquals(12, twelve.boxCount)
        assertEquals(24, twelve.mods.single().boxCount)
        twelve.ensureBoxes()
        assertNull(twelve.root["boxes"].asTable()!![13].asTable())
    }

    @Test
    fun `a card with nothing in it is still a name`() {
        val bare = mapOf("mystery_mod" to emptyMap<String, Any>())

        val mod = save(mods = bare).mods.single()

        assertEquals("mystery_mod", mod.id)
        assertEquals("mystery_mod", mod.name)
        assertNull(mod.version)
        assertEquals(Gen1RecompSave.BOX_CAPACITY, save(mods = bare).boxCapacity)
    }
}
