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
    fun `a release takes the Pokemon out of the PC and keeps a record of it`() {
        val directory = temporaryFolder.newFolder()
        val repository = StorageRepository(directory)
        val stored = repository.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())!!

        val released = repository.release(stored.uid)

        assertNotNull(released)
        assertEquals(stored.uid, released!!.uid)
        assertFalse(repository.contains(stored.uid))
        assertEquals(0, repository.state().total)
        // Gone from the PC, but recorded: the app never loses one outright.
        assertEquals(1, repository.releasedCount())
        val log = File(directory, StorageRepository.RELEASED_FILE_NAME)
        assertTrue(log.isFile)
        assertTrue(log.readText().contains("SPARKY"))
    }

    @Test
    fun `each release appends its own readable chunk`() {
        val directory = temporaryFolder.newFolder()
        val repository = StorageRepository(directory)
        val first = repository.deposit(SaveFixtures.pokemon(nickname = "ONE"), provenance())!!
        val second = repository.deposit(SaveFixtures.pokemon(nickname = "TWO"), provenance())!!

        repository.release(first.uid)
        repository.release(second.uid)

        assertEquals(2, repository.releasedCount())
        // Every chunk parses on its own with the same reader the saves use.
        File(directory, StorageRepository.RELEASED_FILE_NAME)
            .readText()
            .split("return ")
            .filter { it.isNotBlank() }
            .forEach { chunk ->
                val table = com.logie.gen1storage.lua.LuaParser.parse("return $chunk")
                assertNotNull(table["pokemon"])
                assertNotNull(table["releasedAtEpochMillis"])
            }
    }

    @Test
    fun `releasing something that is not there changes nothing`() {
        val directory = temporaryFolder.newFolder()
        val repository = StorageRepository(directory)
        repository.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())

        assertNull(repository.release("not-a-uid"))

        assertEquals(1, repository.state().total)
        assertEquals(0, repository.releasedCount())
    }

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
    fun `deposit fills the box from the top, one spot after another`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        repeat(12) {
            assertNotNull(repository.deposit(SaveFixtures.pokemon(level = it + 1), provenance(), 1))
        }
        val slots = repository.state().boxes[0].slots
        assertEquals(12, slots.count { it != null })
        // Straight down the box rather than into a second one: there is no
        // second one, and the thirty-first spot is an ordinary spot now.
        assertTrue(slots.take(12).all { it != null })
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
    fun `moving keeps a single instance`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val stored = repository.deposit(SaveFixtures.pokemon(), provenance())!!
        assertTrue(repository.moveTo(stored.uid, StorageLayout.THE_BOX))
        assertEquals(StorageLayout.THE_BOX, repository.state().find(stored.uid)!!.first)
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
    fun `the box name persists and is bounded`() {
        val directory = temporaryFolder.newFolder()
        StorageRepository(directory).renameBox(StorageLayout.THE_BOX, "TRADES FOR LATER")
        assertEquals(
            "TRADES FOR",
            StorageRepository(directory).state().boxes[0].name,
        )
    }

    @Test
    fun `an unnamed box is THE PC`() {
        val directory = temporaryFolder.newFolder()
        val boxes = StorageRepository(directory).state().boxes

        assertNull(boxes[0].name)
        assertEquals("THE PC", boxes[0].label)
    }

    @Test
    fun `a named box is called what it was named`() {
        val directory = temporaryFolder.newFolder()
        val repository = StorageRepository(directory)
        repository.renameBox(StorageLayout.THE_BOX, "SHINIES")

        assertEquals("SHINIES", repository.state().boxes[0].label)
    }

    @Test
    fun `clearing the name puts the box back to THE PC`() {
        val directory = temporaryFolder.newFolder()
        val repository = StorageRepository(directory)
        repository.renameBox(StorageLayout.THE_BOX, "TRADES")
        repository.renameBox(StorageLayout.THE_BOX, "  ")

        assertNull(repository.state().boxes[0].name)
        assertEquals("THE PC", repository.state().boxes[0].label)
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

    // ------------------------------------------------------------------
    // Nicknaming, and the trade that evolves

    @Test
    fun `a nickname is written and cleared the way the cartridge spells it`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val stored = repository.deposit(SaveFixtures.pokemon(species = "PIKACHU"), provenance())!!
        assertNull(stored.pokemon.nickname)

        assertTrue(repository.rename(stored.uid, "SPARKY"))
        assertEquals("SPARKY", repository.get(stored.uid)!!.pokemon.nickname)

        // Cleared means the field goes, which is how Generation I says "not
        // nicknamed" — not an empty string sitting where a name was.
        assertTrue(repository.rename(stored.uid, "  "))
        val after = repository.get(stored.uid)!!
        assertNull(after.pokemon.nickname)
        assertNull(after.data[com.logie.gen1storage.lua.LuaKey.Name("nickname")])
        assertEquals("PIKACHU", after.pokemon.displayName)
    }

    @Test
    fun `a nickname keeps only what the cartridge can draw, and only ten of it`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val stored = repository.deposit(SaveFixtures.pokemon(), provenance())!!

        repository.rename(stored.uid, "ABCDEFGHIJKLMNOP")
        assertEquals("ABCDEFGHIJ", repository.get(stored.uid)!!.pokemon.nickname)

        repository.rename(stored.uid, "A\u00a5B\u2603C")
        assertEquals("ABC", repository.get(stored.uid)!!.pokemon.nickname)
    }

    @Test
    fun `a trade evolves it in place and leaves the rest of the box alone`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val first = repository.deposit(SaveFixtures.pokemon(species = "PIKACHU"), provenance())!!
        val machoke = repository.deposit(
            SaveFixtures.pokemon(species = "MACHOKE", level = 40, nickname = "MUSCLES"),
            provenance(),
        )!!

        assertEquals("MACHAMP", repository.evolveByTrade(machoke.uid))
        val after = repository.get(machoke.uid)!!
        assertEquals("MACHAMP", after.pokemon.speciesId)
        assertEquals("MUSCLES", after.pokemon.nickname)
        // Same spot, same uid, and the Pokémon beside it untouched.
        val box = repository.state().boxes.first()
        assertEquals(first.uid, box.slots[0]?.uid)
        assertEquals(machoke.uid, box.slots[1]?.uid)
        assertEquals("PIKACHU", box.slots[0]?.pokemon?.speciesId)
    }

    @Test
    fun `a Pokemon that does not trade-evolve is left exactly as it was`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val stored = repository.deposit(SaveFixtures.pokemon(species = "PIKACHU"), provenance())!!
        val before = stored.pokemon.fingerprint

        assertNull(repository.evolveByTrade(stored.uid))
        assertEquals(before, repository.get(stored.uid)!!.pokemon.fingerprint)
    }

    @Test
    fun `renaming and evolving survive being read back off disk`() {
        val directory = temporaryFolder.newFolder()
        val uid = StorageRepository(directory).let { repository ->
            val stored = repository.deposit(
                SaveFixtures.pokemon(species = "GRAVELER", level = 37),
                provenance(),
            )!!
            repository.rename(stored.uid, "ROCKY")
            repository.evolveByTrade(stored.uid)
            stored.uid
        }

        val reopened = StorageRepository(directory).get(uid)!!
        assertEquals("GOLEM", reopened.pokemon.speciesId)
        assertEquals("ROCKY", reopened.pokemon.nickname)
    }
}
