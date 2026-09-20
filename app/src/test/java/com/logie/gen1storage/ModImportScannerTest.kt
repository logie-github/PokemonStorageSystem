package com.logie.gen1storage

import com.logie.gen1storage.mods.DHash
import com.logie.gen1storage.mods.ModImportScanner
import com.logie.gen1storage.mods.ModReferenceDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The whole scan, run against real zip files on disk — the same
 * `java.util.zip.ZipFile` a real import reads, so what these prove is not
 * just that the pieces are individually correct but that reading a mod's
 * own archive end to end lands on the right answer.
 */
class ModImportScannerTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("mod-scanner-test", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `normalizePath resolves an ordinary nested path`() {
        assertEquals("gfx/front.png", ModImportScanner.normalizePath("gfx/front.png"))
    }

    @Test
    fun `normalizePath refuses to climb above the archive root`() {
        assertNull(ModImportScanner.normalizePath("../../etc/passwd"))
        assertNull(ModImportScanner.normalizePath("gfx/../../escape.png"))
    }

    @Test
    fun `normalizePath refuses an absolute path`() {
        assertNull(ModImportScanner.normalizePath("/etc/passwd"))
        assertNull(ModImportScanner.normalizePath("\\windows\\system32"))
    }

    @Test
    fun `normalizePath collapses a harmless dot-segment`() {
        assertEquals("gfx/front.png", ModImportScanner.normalizePath("./gfx/./front.png"))
    }

    @Test
    fun `a clean archive of text and unmatched images scans with no violations`() {
        val zip = zipOf(
            "mod.json" to """{"name": "Clean Mod"}""".toByteArray(),
            "README.md" to "hello".toByteArray(),
        )
        val violations = ZipFile(zip).use { file ->
            ModImportScanner.validateMetadata(file)
            ModImportScanner.scanEntries(file, ModReferenceDatabase.EMPTY) { null }
        }
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `a blacklisted extension is caught inside a real archive`() {
        val zip = zipOf("dump.gbc" to ByteArray(32))
        val violations = ZipFile(zip).use { file ->
            ModImportScanner.scanEntries(file, ModReferenceDatabase.EMPTY) { null }
        }
        assertEquals(1, violations.size)
        assertEquals("dump.gbc", violations.single().filename)
    }

    @Test
    fun `an image matching the reference database within the reject band is caught`() {
        val flatBright = IntArray(17 * 16) { if (it % 17 < 8) 40 else 220 }
        val referenceHash = DHash.fromResizedLuminance(flatBright)
        val referenceDb = ModReferenceDatabase.parse(
            """{"hashSize": 16, "hashes": {"pokered/gfx/pokemon/bulbasaur/front.png": "${DHash.toHex(referenceHash)}"}}"""
        )
        val zip = zipOf("sprites/front.png" to byteArrayOf(1, 2, 3))
        val violations = ZipFile(zip).use { file ->
            // The fake decoder stands in for BitmapFactory: whatever bytes
            // it is handed, it hands back the exact reference grid, as if
            // this mod's own sprite decoded to pixel-identical art.
            ModImportScanner.scanEntries(file, referenceDb) { flatBright }
        }
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("bulbasaur"))
    }

    @Test
    fun `an image far from every reference hash passes`() {
        val flatBright = IntArray(17 * 16) { if (it % 17 < 8) 40 else 220 }
        val distinct = IntArray(17 * 16) { (it * 37) % 256 }
        val referenceHash = DHash.fromResizedLuminance(flatBright)
        val referenceDb = ModReferenceDatabase.parse(
            """{"hashSize": 16, "hashes": {"pokered/gfx/pokemon/bulbasaur/front.png": "${DHash.toHex(referenceHash)}"}}"""
        )
        val hammingDistance = DHash.hammingDistance(referenceHash, DHash.fromResizedLuminance(distinct))
        assertTrue("fixture must actually be a CLEAN distance apart", hammingDistance > DHash.FLAG_THRESHOLD)

        val zip = zipOf("sprites/original.png" to byteArrayOf(9, 9, 9))
        val violations = ZipFile(zip).use { file ->
            ModImportScanner.scanEntries(file, referenceDb) { distinct }
        }
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `a header violation on one entry does not stop the rest of the archive being scanned`() {
        val zip = zipOf(
            "dump.gba" to ByteArray(16),
            "README.md" to "hi".toByteArray(),
        )
        val violations = ZipFile(zip).use { file ->
            ModImportScanner.scanEntries(file, ModReferenceDatabase.EMPTY) { null }
        }
        assertEquals(1, violations.size)
        assertEquals("dump.gba", violations.single().filename)
    }

    @Test
    fun `validateMetadata rejects an archive over the file count limit`() {
        val entries = (1..5).map { "file$it.txt" to "x".toByteArray() }.toTypedArray()
        val zip = zipOf(*entries)
        val threw = ZipFile(zip).use { file ->
            runCatching {
                ModImportScanner.validateMetadata(file, ModImportScanner.Limits(maxFileCount = 3))
            }.exceptionOrNull()
        }
        assertTrue(threw is ModImportScanner.ArchiveRejected)
    }

    @Test
    fun `validateMetadata rejects a path trying to escape the archive`() {
        val zip = zipOf("../escape.txt" to "x".toByteArray())
        val threw = ZipFile(zip).use { file ->
            runCatching { ModImportScanner.validateMetadata(file) }.exceptionOrNull()
        }
        assertTrue(threw is ModImportScanner.ArchiveRejected)
    }

    @Test
    fun `validateMetadata passes an ordinary small archive`() {
        val zip = zipOf("mod.json" to "{}".toByteArray(), "gfx/a.png" to ByteArray(10))
        ZipFile(zip).use { file ->
            // Throwing here would fail the test on its own; nothing further to assert.
            ModImportScanner.validateMetadata(file)
        }
    }
}
