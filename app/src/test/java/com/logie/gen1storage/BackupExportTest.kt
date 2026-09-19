package com.logie.gen1storage

import com.logie.gen1storage.backup.BackupExport
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** The file the linked backup folder gets, and what a restore does with it. */
class BackupExportTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a backup carries the boxes, the histories and the settings back`() {
        val pcDir = temporaryFolder.newFolder("pc")
        File(pcDir, "storage.lua").writeText("return { revision = 1 }")
        File(pcDir, "lineages.lua").writeText("return {}")
        val prefsDir = temporaryFolder.newFolder("shared_prefs")
        File(prefsDir, "gen1storage-settings.xml").writeText("<map><boolean name=\"swipeControls\" value=\"true\" /></map>")
        File(prefsDir, "gen1storage-sync.xml").writeText("<map><string name=\"code\">ABC123</string></map>")

        val zipped = ByteArrayOutputStream().also { BackupExport.write(pcDir, prefsDir, it) }.toByteArray()

        val restoredPc = temporaryFolder.newFolder("restored-pc")
        val restoredPrefs = temporaryFolder.newFolder("restored-prefs")
        BackupExport.read(restoredPc, restoredPrefs, ByteArrayInputStream(zipped))

        assertEquals("return { revision = 1 }", File(restoredPc, "storage.lua").readText())
        assertEquals("return {}", File(restoredPc, "lineages.lua").readText())
        assertTrue(File(restoredPrefs, "gen1storage-settings.xml").readText().contains("swipeControls"))
        assertTrue(File(restoredPrefs, "gen1storage-sync.xml").readText().contains("ABC123"))
    }

    @Test
    fun `the per-cartridge backup copies do not travel`() {
        val pcDir = temporaryFolder.newFolder("pc")
        File(pcDir, "storage.lua").writeText("return {}")
        val cartridgeBackups = File(pcDir, "backups").apply { mkdirs() }
        File(cartridgeBackups, "red-1.lua").writeText("return { should = 'not travel' }")
        val prefsDir = temporaryFolder.newFolder("shared_prefs")

        val zipped = ByteArrayOutputStream().also { BackupExport.write(pcDir, prefsDir, it) }.toByteArray()

        val restoredPc = temporaryFolder.newFolder("restored-pc")
        val restoredPrefs = temporaryFolder.newFolder("restored-prefs")
        BackupExport.read(restoredPc, restoredPrefs, ByteArrayInputStream(zipped))

        assertTrue(File(restoredPc, "storage.lua").isFile)
        assertFalse(File(File(restoredPc, "backups"), "red-1.lua").exists())
    }

    @Test
    fun `restoring overwrites what was already on this device`() {
        val pcDir = temporaryFolder.newFolder("pc")
        File(pcDir, "storage.lua").writeText("return { revision = 2 }")
        val prefsDir = temporaryFolder.newFolder("shared_prefs")
        val zipped = ByteArrayOutputStream().also { BackupExport.write(pcDir, prefsDir, it) }.toByteArray()

        val restoredPc = temporaryFolder.newFolder("restored-pc")
        val restoredPrefs = temporaryFolder.newFolder("restored-prefs")
        File(restoredPc, "storage.lua").writeText("return { revision = 999, stale = true }")

        BackupExport.read(restoredPc, restoredPrefs, ByteArrayInputStream(zipped))

        assertEquals("return { revision = 2 }", File(restoredPc, "storage.lua").readText())
    }

    @Test(expected = java.io.IOException::class)
    fun `a zip entry trying to escape its directory is refused`() {
        val restoredPc = temporaryFolder.newFolder("restored-pc")
        val restoredPrefs = temporaryFolder.newFolder("restored-prefs")
        // Valid envelope (magic + version), so this exercises the path guard
        // itself rather than being turned away for not looking like a
        // backup at all.
        val malicious = ByteArrayOutputStream().also { out ->
            out.write("PSSB".toByteArray())
            out.write(1)
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("pc/../../../etc/evil.lua"))
                zip.write("return { pwned = true }".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        BackupExport.read(restoredPc, restoredPrefs, ByteArrayInputStream(malicious))
    }

    @Test(expected = java.io.IOException::class)
    fun `a file without the app's own header is refused`() {
        val restoredPc = temporaryFolder.newFolder("restored-pc")
        val restoredPrefs = temporaryFolder.newFolder("restored-prefs")
        val notABackup = "just some other file, or a corrupted one".toByteArray()

        BackupExport.read(restoredPc, restoredPrefs, ByteArrayInputStream(notABackup))
    }

    private fun provenance() = Provenance(
        gameVersion = "red",
        saveId = "test::save.lua",
        savePath = "save.lua",
        slotId = "slot1",
        trainerName = "ASH",
        trainerId = 12345,
        playthroughId = "quiet-forest-dawn",
        sourceKind = Provenance.KIND_PARTY,
        sourceIndex = 1,
        depositedAtEpochMillis = 1_700_000_000_000,
    )

    /**
     * The complaint this whole file exists to rule out: a restore that
     * claims to have worked and leaves the PC exactly as it was. Real
     * Pokémon in, through the same repository this app actually runs, so a
     * bug in the Lua round trip underneath `storage.lua` (not just in the
     * zip wrapped around it) would show up here as a box that came back
     * short rather than as a placeholder file matching itself.
     */
    @Test
    fun `a restore gives back the exact same Pokemon, not just a file that decodes`() {
        val source = StorageRepository(temporaryFolder.newFolder("device-a"))
        val sparky = source.deposit(SaveFixtures.pokemon(nickname = "SPARKY"), provenance())!!
        val bulby = source.deposit(SaveFixtures.pokemon(nickname = "BULBY"), provenance())!!
        source.renameBox(1, "GARDEN")

        val pcDir = File(temporaryFolder.root, "device-a")
        val prefsDir = temporaryFolder.newFolder("device-a-prefs")
        val zipped = ByteArrayOutputStream().also { BackupExport.write(pcDir, prefsDir, it) }.toByteArray()

        // A fresh device, not merely a fresh directory: nothing here has ever
        // touched this repository before the restore does.
        val restoredPcDir = temporaryFolder.newFolder("device-b")
        val restoredPrefsDir = temporaryFolder.newFolder("device-b-prefs")
        BackupExport.read(restoredPcDir, restoredPrefsDir, ByteArrayInputStream(zipped))

        val restored = StorageRepository(restoredPcDir)
        val state = restored.state()
        assertEquals(2, state.total)
        assertEquals("SPARKY", state.find(sparky.uid)!!.second.pokemon.displayName)
        assertEquals("BULBY", state.find(bulby.uid)!!.second.pokemon.displayName)
        // The Generation I data itself, not a rebuilt equivalent of it.
        assertEquals(
            sparky.pokemon.fingerprint,
            state.find(sparky.uid)!!.second.pokemon.fingerprint,
        )
        assertEquals("GARDEN", state.boxes[0].name)
    }

    @Test(expected = java.io.IOException::class)
    fun `a backup from a future, unreadable format is refused rather than half-applied`() {
        val restoredPc = temporaryFolder.newFolder("restored-pc")
        val restoredPrefs = temporaryFolder.newFolder("restored-prefs")
        val fromTheFuture = ByteArrayOutputStream().also { out ->
            out.write("PSSB".toByteArray())
            out.write(99)
        }.toByteArray()

        BackupExport.read(restoredPc, restoredPrefs, ByteArrayInputStream(fromTheFuture))
    }
}
