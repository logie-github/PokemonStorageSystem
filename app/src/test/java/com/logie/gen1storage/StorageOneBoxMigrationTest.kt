package com.logie.gen1storage

import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageLayout
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.storage.StoredPokemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The twelve boxes became one, and the only thing that matters about that is
 * that nothing was in the twelve that is not in the one.
 *
 * The app's rule is that it never loses a Pokémon. A reshaping of the file it
 * keeps them in is exactly where that rule is easiest to break quietly: the
 * loader used to read boxes one through [StorageLayout.BOX_COUNT], and with
 * that constant now 1 an unchanged loader would have read the first box and
 * dropped eleven without a word. So this builds a file in the old shape by
 * hand — not through the current writer, which can no longer produce one — and
 * checks every single entry comes back.
 */
class StorageOneBoxMigrationTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val oldBoxes = 12
    private val oldCapacity = 30

    private fun provenance(index: Int) = Provenance(
        gameVersion = "red",
        saveId = "test::save.lua",
        savePath = "save.lua",
        slotId = "slot1",
        trainerName = "ASH",
        trainerId = 12345,
        playthroughId = "quiet-forest-dawn",
        sourceKind = Provenance.KIND_BOX,
        sourceIndex = index,
        depositedAtEpochMillis = 1_700_000_000_000,
    )

    /**
     * A storage file as the twelve-box app wrote one: a `boxes` table of
     * twelve arrays, each entry carrying its slot within its own box.
     */
    private fun writeOldFile(directory: File, perBox: Int): List<String> {
        val order = mutableListOf<String>()
        val boxes = LuaValue.Table()
        for (box in 1..oldBoxes) {
            val entries = mutableListOf<LuaValue>()
            for (slot in 1..perBox) {
                val uid = "old-$box-$slot"
                order += uid
                val stored = StoredPokemon(
                    uid = uid,
                    data = SaveFixtures.pokemon(nickname = "B${box}S$slot"),
                    provenance = provenance(box),
                )
                // Its spot within its own box, which is what makes these
                // numbers ambiguous once there is only one box: slot 3 is
                // written twelve times over.
                entries += stored.toLua().apply { this["slot"] = luaNum(slot.toDouble()) }
            }
            boxes[box] = LuaValue.Table.ofArray(entries)
        }
        val names = LuaValue.Table()
        names[1] = luaStr("FIRST")
        names[4] = luaStr("GARDEN")

        val root = LuaValue.Table()
        root["revision"] = luaNum(7.0)
        root["boxes"] = boxes
        root["boxNames"] = names
        directory.mkdirs()
        File(directory, StorageRepository.FILE_NAME)
            .writeBytes(LuaText.encode(LuaWriter.encode(root)))
        return order
    }

    @Test
    fun `every Pokemon in a twelve-box file is in the one box afterwards`() {
        val directory = temporaryFolder.newFolder()
        val order = writeOldFile(directory, perBox = oldCapacity)

        val state = StorageRepository(directory).state()

        assertEquals(1, state.boxes.size)
        assertEquals(oldBoxes * oldCapacity, order.size)
        assertEquals("not one was lost", order.size, state.total)
        order.forEach { uid ->
            assertTrue("$uid is missing", state.find(uid) != null)
        }
    }

    @Test
    fun `they keep the order they were kept in, box after box`() {
        val directory = temporaryFolder.newFolder()
        val order = writeOldFile(directory, perBox = 3)

        val slots = StorageRepository(directory).state().boxes[0].slots

        // Poured in rather than placed by number: box 1's three, then box 2's
        // three, and so on, packed from the top with no gaps. Placing them by
        // their written slot would have all twelve boxes land on spots 1, 2
        // and 3 and eleven twelfths of them collide.
        order.forEachIndexed { position, uid ->
            assertEquals("position $position", uid, slots[position]?.uid)
        }
        assertEquals(order.size, slots.count { it != null })
    }

    @Test
    fun `the pour is written down where the player can see it`() {
        val directory = temporaryFolder.newFolder()
        writeOldFile(directory, perBox = 2)

        val notes = StorageRepository(directory).loadNotes

        assertTrue(notes.any { it.contains("poured", ignoreCase = true) })
    }

    @Test
    fun `the migrated box survives being written back out and read again`() {
        val directory = temporaryFolder.newFolder()
        val order = writeOldFile(directory, perBox = 4)

        // Any change at all rewrites the file in the new shape.
        val first = StorageRepository(directory)
        first.renameBox(StorageLayout.THE_BOX, "KEPT")

        val reopened = StorageRepository(directory).state()
        assertEquals(order.size, reopened.total)
        order.forEachIndexed { position, uid ->
            assertEquals(uid, reopened.boxes[0].slots[position]?.uid)
        }
        assertEquals("KEPT", reopened.boxes[0].name)
        // And the second reading is not another pour: the file has one box in
        // it now, so the slots it wrote are honoured.
        assertTrue(reopened.boxes[0].slots.take(order.size).all { it != null })
    }

    @Test
    fun `a file already in the one-box shape keeps its arrangement`() {
        val directory = temporaryFolder.newFolder()
        val repository = StorageRepository(directory)
        val stored = repository.deposit(
            SaveFixtures.pokemon(nickname = "SPARKY"),
            provenance(1),
        )!!
        repository.moveToSlot(stored.uid, StorageLayout.THE_BOX, 455)

        val reopened = StorageRepository(directory)

        assertEquals(stored.uid, reopened.state().boxes[0].slots[455]?.uid)
        assertTrue(reopened.loadNotes.isEmpty())
    }
}
