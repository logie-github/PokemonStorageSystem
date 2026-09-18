package com.logie.gen1storage

import com.logie.gen1storage.backup.BackupExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** The file BACK UP NOW writes, and what RESTORE FROM FILE does with it. */
class BackupExportTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a backup carries the boxes, the histories and the settings back`() {
        val pcDir = temporaryFolder.newFolder("pc")
        File(pcDir, "storage.lua").writeText("return { revision = 1 }")
        File(pcDir, "lineages.lua").writeText("return {}")
        val prefsDir = temporaryFolder.newFolder("shared_prefs")
        File(prefsDir, "gen1storage-settings.xml").writeText("<map><boolean name=\"cloudBackup\" value=\"true\" /></map>")
        File(prefsDir, "gen1storage-sync.xml").writeText("<map><string name=\"code\">ABC123</string></map>")

        val zipped = ByteArrayOutputStream().also { BackupExport.write(pcDir, prefsDir, it) }.toByteArray()

        val restoredPc = temporaryFolder.newFolder("restored-pc")
        val restoredPrefs = temporaryFolder.newFolder("restored-prefs")
        BackupExport.read(restoredPc, restoredPrefs, ByteArrayInputStream(zipped))

        assertEquals("return { revision = 1 }", File(restoredPc, "storage.lua").readText())
        assertEquals("return {}", File(restoredPc, "lineages.lua").readText())
        assertTrue(File(restoredPrefs, "gen1storage-settings.xml").readText().contains("cloudBackup"))
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
        val malicious = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("pc/../../../etc/evil.lua"))
                zip.write("return { pwned = true }".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        BackupExport.read(restoredPc, restoredPrefs, ByteArrayInputStream(malicious))
    }
}
