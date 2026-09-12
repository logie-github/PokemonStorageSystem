package com.logie.gen1storage

import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageArchive
import com.logie.gen1storage.storage.StorageLayout
import com.logie.gen1storage.storage.StorageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An export is a restoration, not a description: what comes back has to be the
 * same Pokémon, in the same box, under the same uid — and a second import of
 * the same file has to change nothing.
 */
class StorageArchiveTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun provenance(name: String = "ASH") = Provenance(
        gameVersion = "red",
        saveId = "test::save.lua",
        savePath = "save.lua",
        slotId = "slot1",
        trainerName = name,
        trainerId = 12345,
        playthroughId = "quiet-forest-dawn",
        sourceKind = Provenance.KIND_PARTY,
        sourceIndex = 2,
        depositedAtEpochMillis = 1_700_000_000_000,
    )

    private fun repository() = StorageRepository(temporaryFolder.newFolder())

    @Test
    fun `every Pokemon comes back with its uid, its spot and its data`() {
        val source = repository()
        val first = source.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())!!
        val second = source.deposit(SaveFixtures.pokemon(nickname = "BULBY"), provenance())!!
        source.renameBox(StorageLayout.THE_BOX, "GARDEN")

        val bytes = StorageArchive.encode(source.state(), 1_700_000_000_000)
        val archive = StorageArchive.decode(bytes).getOrThrow()
        assertEquals(2, archive.entries.size)
        assertEquals(0, archive.unreadable)
        assertEquals("GARDEN", archive.boxNames[StorageLayout.THE_BOX])

        val target = repository()
        val report = target.importArchive(archive)

        assertEquals(2, report.added)
        assertEquals(0, report.skipped)
        assertEquals(0, report.unplaced)

        val state = target.state()
        assertEquals(2, state.total)
        assertEquals(StorageLayout.THE_BOX, state.find(first.uid)!!.first)
        assertEquals(StorageLayout.THE_BOX, state.find(second.uid)!!.first)
        // Back on the spots they were exported from, not merely present.
        assertEquals(first.uid, state.boxes[0].slots[0]?.uid)
        assertEquals(second.uid, state.boxes[0].slots[1]?.uid)
        assertEquals("SPARKY", state.find(first.uid)!!.second.pokemon.displayName)
        // The Generation I data is the same bytes, not a rebuilt equivalent.
        assertEquals(
            first.pokemon.fingerprint,
            state.find(first.uid)!!.second.pokemon.fingerprint,
        )
        assertEquals("GARDEN", state.boxes[0].name)
    }

    @Test
    fun `importing the same file twice leaves one of each`() {
        val source = repository()
        source.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())
        source.deposit(SaveFixtures.pokemon(nickname = "BULBY"), provenance())
        val archive = StorageArchive
            .decode(StorageArchive.encode(source.state(), 1L))
            .getOrThrow()

        val target = repository()
        assertEquals(2, target.importArchive(archive).added)
        val second = target.importArchive(archive)

        assertEquals(0, second.added)
        assertEquals(2, second.skipped)
        assertEquals(2, target.state().total)
    }

    @Test
    fun `a re-import into the PC it came from changes nothing`() {
        val repository = repository()
        repository.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())
        val before = repository.state()
        val archive = StorageArchive.decode(StorageArchive.encode(before, 1L)).getOrThrow()

        val report = repository.importArchive(archive)

        assertEquals(0, report.added)
        assertEquals(1, report.skipped)
        assertEquals(before.total, repository.state().total)
    }

    @Test
    fun `a box the player has named keeps their name`() {
        val source = repository()
        source.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())
        source.renameBox(StorageLayout.THE_BOX, "OLD")
        val archive = StorageArchive.decode(StorageArchive.encode(source.state(), 1L)).getOrThrow()

        val target = repository()
        target.renameBox(StorageLayout.THE_BOX, "MINE")
        target.importArchive(archive)

        assertEquals("MINE", target.state().boxes[0].name)
    }

    @Test
    fun `what will not fit is counted rather than dropped in silence`() {
        val source = repository()
        val coming = 5
        repeat(coming) {
            source.deposit(SaveFixtures.pokemon(nickname = "MON$it"), provenance())
        }
        val archive = StorageArchive.decode(StorageArchive.encode(source.state(), 1L)).getOrThrow()

        // A PC with exactly one spot left in it.
        val target = repository()
        repeat(StorageLayout.TOTAL_CAPACITY - 1) {
            target.deposit(SaveFixtures.pokemon(nickname = "FILL$it"), provenance())
        }
        val report = target.importArchive(archive)

        assertEquals(1, report.added)
        assertEquals(coming - 1, report.unplaced)
        assertEquals(StorageLayout.TOTAL_CAPACITY, target.state().total)
    }

    @Test
    fun `a file that is not an export is refused rather than guessed at`() {
        val other = "return {\n  foo = 1,\n}\n".toByteArray(Charsets.ISO_8859_1)

        val result = StorageArchive.decode(other)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `an export from a newer version is refused`() {
        val source = repository()
        source.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())
        val text = String(
            StorageArchive.encode(source.state(), 1L),
            Charsets.ISO_8859_1,
        ).replace("[\"version\"] = 1", "[\"version\"] = 99")
            .replace("version = 1", "version = 99")

        val result = StorageArchive.decode(text.toByteArray(Charsets.ISO_8859_1))

        assertTrue(result.isFailure)
    }
}
