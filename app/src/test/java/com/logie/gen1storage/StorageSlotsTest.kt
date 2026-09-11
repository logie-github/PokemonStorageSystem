package com.logie.gen1storage

import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageArchive
import com.logie.gen1storage.storage.StorageLayout
import com.logie.gen1storage.storage.StorageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A box is a grid the player arranges, so where a Pokémon sits is part of what
 * the PC holds — an empty spot in the middle has to survive a withdrawal, a
 * restart and a round trip through an export.
 */
class StorageSlotsTest {

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
    fun `a box is six across and five down`() {
        assertEquals(6, StorageLayout.BOX_COLUMNS)
        assertEquals(5, StorageLayout.BOX_ROWS)
        assertEquals(30, StorageLayout.BOX_CAPACITY)
    }

    @Test
    fun `taking one out leaves its spot empty rather than closing the gap`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val first = repository.deposit(SaveFixtures.pokemon(nickname = "ONE"), provenance())!!
        val second = repository.deposit(SaveFixtures.pokemon(nickname = "TWO"), provenance())!!
        val third = repository.deposit(SaveFixtures.pokemon(nickname = "THREE"), provenance())!!

        repository.withdraw(second.uid)

        val slots = repository.state().boxes[0].slots
        assertEquals(first.uid, slots[0]?.uid)
        assertNull(slots[1])
        assertEquals(third.uid, slots[2]?.uid)
    }

    @Test
    fun `a dropped Pokemon lands on the spot it was dropped on`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val stored = repository.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())!!

        assertTrue(repository.moveToSlot(stored.uid, 3, 17))

        val state = repository.state()
        assertNull(state.boxes[0].slots[0])
        assertEquals(stored.uid, state.boxes[2].slots[17]?.uid)
        assertEquals(3, state.find(stored.uid)!!.first)
    }

    @Test
    fun `dropping onto an occupied spot swaps the two`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val first = repository.deposit(SaveFixtures.pokemon(nickname = "ONE"), provenance())!!
        val second = repository.deposit(SaveFixtures.pokemon(nickname = "TWO"), provenance())!!

        assertTrue(repository.moveToSlot(first.uid, 1, 1))

        val slots = repository.state().boxes[0].slots
        assertEquals(second.uid, slots[0]?.uid)
        assertEquals(first.uid, slots[1]?.uid)
        // A swap is two placements. Neither one left the PC.
        assertEquals(2, repository.state().total)
    }

    @Test
    fun `a swap across boxes keeps both`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val first = repository.deposit(SaveFixtures.pokemon(nickname = "ONE"), provenance())!!
        val second = repository.deposit(SaveFixtures.pokemon(nickname = "TWO"), provenance(), preferredBox = 5)!!

        assertTrue(repository.moveToSlot(first.uid, 5, 0))

        val state = repository.state()
        assertEquals(first.uid, state.boxes[4].slots[0]?.uid)
        assertEquals(second.uid, state.boxes[0].slots[0]?.uid)
        assertEquals(2, state.total)
    }

    @Test
    fun `the arrangement survives a restart`() {
        val directory = temporaryFolder.newFolder()
        val stored = StorageRepository(directory).let { repository ->
            val stored = repository.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())!!
            repository.moveToSlot(stored.uid, 2, 23)
            stored
        }

        val reopened = StorageRepository(directory).state()

        assertEquals(stored.uid, reopened.boxes[1].slots[23]?.uid)
        assertEquals(1, reopened.total)
    }

    @Test
    fun `an export puts every Pokemon back on its own spot`() {
        val directory = temporaryFolder.newFolder()
        val source = StorageRepository(directory)
        val first = source.deposit(SaveFixtures.pokemon(nickname = "ONE"), provenance())!!
        val second = source.deposit(SaveFixtures.pokemon(nickname = "TWO"), provenance())!!
        source.moveToSlot(first.uid, 1, 29)
        source.moveToSlot(second.uid, 4, 11)

        val archive = StorageArchive
            .decode(StorageArchive.encode(source.state(), 1L))
            .getOrThrow()
        val target = StorageRepository(temporaryFolder.newFolder())
        assertEquals(2, target.importArchive(archive).added)

        val state = target.state()
        assertEquals(first.uid, state.boxes[0].slots[29]?.uid)
        assertEquals(second.uid, state.boxes[3].slots[11]?.uid)
    }

    @Test
    fun `a file written before boxes were a grid packs from the top`() {
        val directory = temporaryFolder.newFolder()
        val source = StorageRepository(directory)
        val first = source.deposit(SaveFixtures.pokemon(nickname = "ONE"), provenance())!!
        val second = source.deposit(SaveFixtures.pokemon(nickname = "TWO"), provenance())!!
        source.moveToSlot(second.uid, 1, 20)

        // The old shape is this file with the slots taken back out of it.
        val file = java.io.File(directory, StorageRepository.FILE_NAME)
        file.writeText(
            file.readText(Charsets.ISO_8859_1).replace(Regex("""\s*slot = [0-9.]+,"""), ""),
            Charsets.ISO_8859_1,
        )

        val slots = StorageRepository(directory).state().boxes[0].slots

        assertEquals(first.uid, slots[0]?.uid)
        assertEquals(second.uid, slots[1]?.uid)
        assertEquals(2, slots.count { it != null })
    }

    @Test
    fun `a full box refuses a thirty-first`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        repeat(StorageLayout.BOX_CAPACITY) {
            repository.deposit(SaveFixtures.pokemon(nickname = "MON$it"), provenance())
        }

        assertTrue(repository.state().boxes[0].isFull)
        assertFalse(repository.state().boxes[1].isFull)
        // The wrap-around puts the next one in the following box, not nowhere.
        val overflow = repository.deposit(SaveFixtures.pokemon(nickname = "EXTRA"), provenance())
        assertEquals(2, repository.state().find(overflow!!.uid)!!.first)
    }
}
