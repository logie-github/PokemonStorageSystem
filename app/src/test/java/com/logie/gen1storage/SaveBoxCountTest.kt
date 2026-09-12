package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many boxes a save has is the save's business, not this app's.
 *
 * The game ships with twelve and mods add more. Taking the twelve on faith hid
 * everything above box twelve from the lists — and, worse, clamped the open box
 * to twelve on the way back out, which would have moved a modded player's
 * current box for them the first time this app wrote their save.
 */
class SaveBoxCountTest {

    private fun mon(name: String) = SaveFixtures.pokemon(nickname = name)

    /** Twelve as the game ships, plus eight a mod added. */
    private fun moddedSave(currentBox: Int = 1): Gen1RecompSave {
        val boxes = (1..20).map { index ->
            if (index % 5 == 0) listOf(mon("BOX$index")) else emptyList()
        }
        return Gen1RecompSave(
            SaveFixtures.save(boxes = boxes, currentBox = currentBox)
        )
    }

    @Test
    fun `a save with more boxes than the game ships with shows all of them`() {
        val save = moddedSave()

        assertEquals(20, save.boxCount)
        assertEquals(20, save.boxes.size)
        // One in each of boxes 5, 10, 15 and 20 — the last two of which were
        // invisible when twelve was assumed.
        assertEquals(4, save.storedCount)
        assertEquals("BOX20", save.boxes[19].single().displayName)
    }

    @Test
    fun `an ordinary save still has the twelve`() {
        val save = Gen1RecompSave(SaveFixtures.save(boxes = List(12) { emptyList() }))

        assertEquals(12, save.boxCount)
        assertEquals(12, save.boxes.size)
    }

    @Test
    fun `a save with no boxes at all is given the twelve the game would make`() {
        val save = Gen1RecompSave(SaveFixtures.save())

        assertEquals(Gen1RecompSave.BOX_COUNT, save.boxCount)
        assertEquals(Gen1RecompSave.BOX_COUNT, save.boxes.size)
    }

    @Test
    fun `the open box is kept rather than clamped to twelve`() {
        assertEquals(18, moddedSave(currentBox = 18).currentBox)
    }

    @Test
    fun `writing a box does not move a modded player's open box`() {
        val save = moddedSave(currentBox = 18)

        // Anything that writes a box runs this first, and it writes the open
        // box back. Clamping to twelve here is what would have moved it.
        save.ensureBoxes()

        assertEquals(18, save.root["currentBox"].asInt())
        assertEquals(18, save.currentBox)
        assertEquals(20, save.boxCount)
    }

    @Test
    fun `filling in missing boxes neither invents nor drops any`() {
        val save = moddedSave()
        // A file where a middle box was never materialised.
        save.root["boxes"].asTable()!!.remove(com.logie.gen1storage.lua.LuaKey.Index(3.0))

        val boxes = save.ensureBoxes()

        // Every box up to the twentieth is there, and there is no twenty-first.
        (1..20).forEach { index -> assertNotNull("box $index", boxes[index].asTable()) }
        assertEquals(null, boxes[21].asTable())
        assertEquals(20, save.boxCount)
    }

    @Test
    fun `a modded save survives the round trip through the writer`() {
        val save = moddedSave(currentBox = 17)
        save.addToBox(20, mon("LATE"))

        val text = LuaWriter.encode(save.root)
        val reopened = Gen1RecompSave(LuaParser.parse(text))

        assertEquals(20, reopened.boxCount)
        assertEquals(17, reopened.currentBox)
        assertEquals(5, reopened.storedCount)
        assertTrue(reopened.boxes[19].any { it.displayName == "LATE" })
    }

    @Test
    fun `a box past the twelfth can be taken from and put into`() {
        val save = moddedSave()

        assertTrue(save.addToBox(20, mon("ADDED")))
        assertEquals(2, save.boxes[19].size)

        val taken = save.removeFromBox(20, 1)

        assertNotNull(taken)
        assertEquals(1, save.boxes[19].size)
        // Nothing else moved.
        assertEquals(4, save.storedCount)
    }

    @Test
    fun `a stray high number past a gap is not mistaken for a box`() {
        val root = SaveFixtures.save(boxes = List(12) { emptyList() })
        // Box 99 with nothing between it and the twelfth: a Lua array stops at
        // the gap, and so does this.
        root["boxes"].asTable()!![99] = LuaValue.Table()
        val save = Gen1RecompSave(root)

        assertEquals(12, save.boxCount)
        // And it is left in the file rather than tidied away.
        save.ensureBoxes()
        assertNotNull(save.root["boxes"].asTable()!![99].asTable())
    }
}
