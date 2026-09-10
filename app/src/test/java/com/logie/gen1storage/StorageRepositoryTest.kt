package com.logie.gen1storage

import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageLayout
import com.logie.gen1storage.storage.StorageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageRepositoryTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun provenance() = Provenance(
        gameVersion = "red",
        saveId = "test::save.lua",
        savePath = "save.lua",
        slotId = "slot1",
        trainerName = "ASH",
        trainerId = 12345,
        playthroughId = "quiet-forest-dawn",
        sourceKind = Provenance.KIND_PARTY,
        sourceIndex = 2,
        depositedAtEpochMillis = 1_700_000_000_000,
    )

    @Test
    fun `a deposited Pokemon survives a restart with its data byte-identical`() {
        val directory = temporaryFolder.newFolder()
        val mon = SaveFixtures.pokemon(nickname = "SPARKY")
        val encoded = com.logie.gen1storage.lua.LuaWriter.encodeValue(mon)

        val stored = StorageRepository(directory).deposit(mon, provenance())!!
        val reopened = StorageRepository(directory).get(stored.uid)!!

        assertEquals(encoded, com.logie.gen1storage.lua.LuaWriter.encodeValue(reopened.data))
        assertEquals("ASH", reopened.provenance.trainerName)
        assertEquals(2, reopened.provenance.sourceIndex)
    }

    @Test
    fun `deposit overflows into the next box with room, like Bill's PC`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        repeat(StorageLayout.BOX_CAPACITY) {
            assertNotNull(repository.deposit(SaveFixtures.pokemon(level = it + 1), provenance(), 1))
        }
        val overflow = repository.deposit(SaveFixtures.pokemon(species = "ODDISH"), provenance(), 1)!!
        assertEquals(2, repository.state().find(overflow.uid)!!.first)
    }

    @Test
    fun `a full PC refuses a deposit rather than dropping anything`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        repeat(StorageLayout.TOTAL_CAPACITY) {
            assertNotNull(repository.deposit(SaveFixtures.pokemon(level = 1), provenance(), 1))
        }
        assertNull(repository.deposit(SaveFixtures.pokemon(), provenance(), 1))
        assertEquals(StorageLayout.TOTAL_CAPACITY, repository.state().total)
    }

    @Test
    fun `withdraw removes exactly one entry`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val first = repository.deposit(SaveFixtures.pokemon(nickname = "A"), provenance())!!
        val second = repository.deposit(SaveFixtures.pokemon(nickname = "B"), provenance())!!

        assertEquals(first.uid, repository.withdraw(first.uid)!!.uid)
        assertNull(repository.withdraw(first.uid))
        assertTrue(repository.contains(second.uid))
        assertEquals(1, repository.state().total)
    }

    @Test
    fun `moving between boxes keeps a single instance`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val stored = repository.deposit(SaveFixtures.pokemon(), provenance())!!
        assertTrue(repository.moveTo(stored.uid, 5))
        assertEquals(5, repository.state().find(stored.uid)!!.first)
        assertEquals(1, repository.state().total)
    }

    @Test
    fun `a corrupt storage file falls back to the backup`() {
        val directory = temporaryFolder.newFolder()
        val repository = StorageRepository(directory)
        val first = repository.deposit(SaveFixtures.pokemon(nickname = "FIRST"), provenance())!!
        // A second write leaves the first as the .bak.
        repository.deposit(SaveFixtures.pokemon(nickname = "SECOND"), provenance())

        File(directory, StorageRepository.FILE_NAME).writeText("this is not a storage file")
        val recovered = StorageRepository(directory)
        assertTrue(recovered.contains(first.uid))
        assertTrue(recovered.loadNotes.any { it.contains("Could not read") })
    }

    @Test
    fun `an empty or missing storage file starts an empty PC without complaint`() {
        val directory = temporaryFolder.newFolder()
        val repository = StorageRepository(directory)
        assertEquals(0, repository.state().total)
        assertEquals(StorageLayout.BOX_COUNT, repository.state().boxes.size)
        assertTrue(repository.loadNotes.isEmpty())
    }

    @Test
    fun `duplicate uids in a hand-edited file are reduced to one`() {
        val directory = temporaryFolder.newFolder()
        val repository = StorageRepository(directory)
        val stored = repository.deposit(SaveFixtures.pokemon(), provenance())!!

        val file = File(directory, StorageRepository.FILE_NAME)
        val root = com.logie.gen1storage.lua.LuaParser.parse(file.readText(Charsets.ISO_8859_1))
        val boxes = root["boxes"] as com.logie.gen1storage.lua.LuaValue.Table
        val boxOne = boxes[1] as com.logie.gen1storage.lua.LuaValue.Table
        boxOne.setArray(boxOne.array() + boxOne.array())
        file.writeText(com.logie.gen1storage.lua.LuaWriter.encode(root), Charsets.ISO_8859_1)

        val reopened = StorageRepository(directory)
        assertEquals(1, reopened.state().total)
        assertTrue(reopened.contains(stored.uid))
        assertTrue(reopened.loadNotes.any { it.contains("duplicate") })
    }

    @Test
    fun `box names persist and are bounded`() {
        val directory = temporaryFolder.newFolder()
        StorageRepository(directory).renameBox(3, "TRADES FOR LATER")
        assertEquals(
            "TRADES FOR L",
            StorageRepository(directory).state().boxes[2].name,
        )
    }

    @Test
    fun `the storage file is valid save-grammar source`() {
        val directory = temporaryFolder.newFolder()
        StorageRepository(directory).deposit(SaveFixtures.pokemon(), provenance())
        val text = File(directory, StorageRepository.FILE_NAME).readText(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("return {"))
        assertNotNull(com.logie.gen1storage.lua.LuaParser.parse(text))
        assertFalse(text.contains("function"))
    }
}
