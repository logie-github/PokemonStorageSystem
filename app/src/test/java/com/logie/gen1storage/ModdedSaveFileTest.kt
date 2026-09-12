package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.SaveClassification
import com.logie.gen1storage.gen1recomp.SaveClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A save produced by running the mod itself, not by describing what it does.
 *
 * `modded-save.lua` is the output of TM32 Double Team's own `Boxes.ensure`
 * wrapper, run in Lua over a stock twelve-box save and written back out in
 * Gen1Recomp's grammar. Reading it here is the only check that closes the
 * loop: everything else in this suite tests the app against a save shaped the
 * way this app believes the mod shapes one.
 */
class ModdedSaveFileTest {

    private fun source(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("modded-save.lua")) {
            "modded-save.lua is missing from the test resources"
        }.use { it.readBytes().toString(Charsets.ISO_8859_1) }

    @Test
    fun `the app reads what the mod actually wrote`() {
        val classified = SaveClassifier.classify(source())

        assertTrue(classified.summary, classified is SaveClassification.Valid)
        val save = classified.save!!

        // Twenty-four boxes, counted off the file.
        assertEquals(24, save.boxCount)
        assertEquals(24, save.boxes.size)
        // The open box the player was on, not clamped back to twelve.
        assertEquals(20, save.currentBox)
        // Both the one that was already there and the one in a box the mod added.
        assertEquals("TWELVE", save.boxes[11].single().nickname)
        assertEquals("LAST", save.boxes[23].single().nickname)
        assertEquals(2, save.storedCount)
    }

    @Test
    fun `the mod's card comes through`() {
        val save = SaveClassifier.classify(source()).save!!

        val mod = save.mods.single()
        assertEquals("tm32_double_team", mod.id)
        assertEquals("TM32 Double Team", mod.name)
        assertEquals("1.3", mod.version)
        assertEquals(24, mod.boxCount)
        assertEquals(20, mod.boxCapacity)
        // The card agrees with the file, which is the point of writing it
        // alongside the boxes rather than once at install.
        assertEquals(save.boxCount, mod.boxCount)
        assertEquals(save.boxCapacity, mod.boxCapacity)
    }

    @Test
    fun `every box the mod made survives being written back`() {
        val save = SaveClassifier.classify(source()).save!!
        save.ensureBoxes()

        val rewritten = SaveClassifier.classify(
            com.logie.gen1storage.lua.LuaWriter.encode(save.root)
        ).save!!

        assertEquals(24, rewritten.boxCount)
        assertEquals(20, rewritten.currentBox)
        assertEquals(2, rewritten.storedCount)
        assertEquals("TM32 Double Team", rewritten.mods.single().name)
        assertNotNull(rewritten.boxes[23].single())
    }
}
