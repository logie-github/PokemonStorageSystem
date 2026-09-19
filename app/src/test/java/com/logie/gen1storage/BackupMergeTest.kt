package com.logie.gen1storage

import com.logie.gen1storage.backup.BackupExport
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageArchive
import com.logie.gen1storage.storage.StorageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * What `StorageViewModel.mergeExistingBackup` actually composes, proven at
 * the level that does not need an Android device to run: reading a backup a
 * folder already had, and folding it into a second device's own PC before
 * that device's first push there can overwrite it.
 *
 * A second phone linking the same folder a first phone already backs up to
 * must come away holding both phones' Pokémon, not whichever phone linked
 * last. This is the one place that promise is checked without a device.
 */
class BackupMergeTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun provenance(trainer: String) = Provenance(
        gameVersion = "red",
        saveId = "test::save.lua",
        savePath = "save.lua",
        slotId = "slot1",
        trainerName = trainer,
        trainerId = 12345,
        playthroughId = "quiet-forest-dawn",
        sourceKind = Provenance.KIND_PARTY,
        sourceIndex = 1,
        depositedAtEpochMillis = 1_700_000_000_000,
    )

    /** The same three steps `mergeExistingBackup` runs, laid out for a test. */
    private fun mergeInto(target: StorageRepository, backupBytes: ByteArray): com.logie.gen1storage.storage.ImportReport {
        val tempPc = temporaryFolder.newFolder("incoming-${System.nanoTime()}")
        val tempPrefs = temporaryFolder.newFolder("incoming-prefs-${System.nanoTime()}")
        BackupExport.read(tempPc, tempPrefs, ByteArrayInputStream(backupBytes))
        val incoming = StorageRepository(tempPc).state()
        val entries = incoming.boxes.flatMap { box ->
            box.slots.mapIndexedNotNull { slot, stored ->
                stored?.let { StorageArchive.Entry(box.index, slot + 1, it) }
            }
        }
        val boxNames = incoming.boxes.mapNotNull { box -> box.name?.let { name -> box.index to name } }.toMap()
        return target.importArchive(
            StorageArchive.Archive(
                version = StorageArchive.VERSION,
                exportedAtEpochMillis = null,
                entries = entries,
                boxNames = boxNames,
                unreadable = 0,
            )
        )
    }

    @Test
    fun `linking a folder a second phone already backs up to keeps both phones' Pokemon`() {
        val phoneA = StorageRepository(temporaryFolder.newFolder("phone-a"))
        val pikachu = phoneA.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance("RED"))!!
        val bulbasaur = phoneA.deposit(SaveFixtures.pokemon(nickname = "BULBY"), provenance("RED"))!!

        val backupOfA = ByteArrayOutputStream().also {
            BackupExport.write(File(temporaryFolder.root, "phone-a"), temporaryFolder.newFolder("phone-a-prefs"), it)
        }.toByteArray()

        // A second phone, with one Pokemon of its own the first phone has
        // never seen, about to link the same folder.
        val phoneB = StorageRepository(temporaryFolder.newFolder("phone-b"))
        val squirtle = phoneB.deposit(SaveFixtures.pokemon(nickname = "SQUIRTY"), provenance("BLUE"))!!

        val report = mergeInto(phoneB, backupOfA)

        assertEquals(2, report.added)
        assertEquals(0, report.skipped)
        assertEquals(0, report.unplaced)

        val merged = phoneB.state()
        assertEquals("all three, not whichever phone linked last", 3, merged.total)
        assertEquals("SPARKY", merged.find(pikachu.uid)!!.second.pokemon.displayName)
        assertEquals("BULBY", merged.find(bulbasaur.uid)!!.second.pokemon.displayName)
        assertEquals("SQUIRTY", merged.find(squirtle.uid)!!.second.pokemon.displayName)

        // And the first phone is untouched by any of this: a merge on the
        // second phone is not a write anywhere near the first one's files.
        assertEquals(2, phoneA.state().total)
    }

    @Test
    fun `relinking the same folder a second time does not double anything`() {
        val phoneA = StorageRepository(temporaryFolder.newFolder("phone-a"))
        val pikachu = phoneA.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance("RED"))!!
        val backupOfA = ByteArrayOutputStream().also {
            BackupExport.write(File(temporaryFolder.root, "phone-a"), temporaryFolder.newFolder("phone-a-prefs"), it)
        }.toByteArray()

        val phoneB = StorageRepository(temporaryFolder.newFolder("phone-b"))
        mergeInto(phoneB, backupOfA)
        val secondReport = mergeInto(phoneB, backupOfA)

        assertEquals("already has it, by uid", 0, secondReport.added)
        assertEquals(1, secondReport.skipped)
        assertEquals(1, phoneB.state().total)
        assertEquals("SPARKY", phoneB.state().find(pikachu.uid)!!.second.pokemon.displayName)
    }

    @Test
    fun `a folder with nothing pushed to it yet leaves the device untouched`() {
        // BackupFolderWriter.find returning null is the real gate in the app
        // (checked before any of this runs at all); this is the boundary
        // case one level in, an empty PC read back rather than no file
        // found, so it is worth pinning on its own.
        val emptyBackup = ByteArrayOutputStream().also {
            BackupExport.write(temporaryFolder.newFolder("nothing-pc"), temporaryFolder.newFolder("nothing-prefs"), it)
        }.toByteArray()

        val phoneB = StorageRepository(temporaryFolder.newFolder("phone-b"))
        val squirtle = phoneB.deposit(SaveFixtures.pokemon(nickname = "SQUIRTY"), provenance("BLUE"))!!

        val report = mergeInto(phoneB, emptyBackup)

        assertEquals(0, report.added)
        assertEquals(1, phoneB.state().total)
        assertEquals("SQUIRTY", phoneB.state().find(squirtle.uid)!!.second.pokemon.displayName)
        assertNull("no box name invented from an empty backup", phoneB.state().boxes[0].name)
    }

    @Test
    fun `each Pokemon keeps its own data across the merge, not a rebuilt copy`() {
        val phoneA = StorageRepository(temporaryFolder.newFolder("phone-a"))
        val gengar = phoneA.deposit(SaveFixtures.pokemon(nickname = "SHADOW"), provenance("RED"))!!
        val backupOfA = ByteArrayOutputStream().also {
            BackupExport.write(File(temporaryFolder.root, "phone-a"), temporaryFolder.newFolder("phone-a-prefs"), it)
        }.toByteArray()

        val phoneB = StorageRepository(temporaryFolder.newFolder("phone-b"))
        mergeInto(phoneB, backupOfA)

        val landed = phoneB.state().find(gengar.uid)!!.second
        assertEquals(gengar.pokemon.fingerprint, landed.pokemon.fingerprint)
        assertTrue(phoneB.contains(gengar.uid))
    }
}
