package com.logie.gen1storage

import com.logie.gen1storage.mods.DHash
import com.logie.gen1storage.mods.ModImportScanner
import com.logie.gen1storage.mods.ModManifest
import com.logie.gen1storage.mods.ModReferenceDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * The OpenHome theme mods shipped under `mods/openhome/`, read the way an
 * import reads them: the zip judged whole, every picture scanned against
 * the real reference hashes, and the manifest parsed into a palette a
 * player can actually pick. A change to the scanner or the manifest that
 * would turn these away should fail here first, not on a player's phone.
 */
class OpenHomeModTest {

    private fun repoFile(path: String): File =
        listOf(File(path), File("..", path)).firstOrNull { it.exists() }
            ?: error("$path not found from ${File("").absolutePath}")

    private val referenceDb by lazy {
        ModReferenceDatabase.parse(repoFile("app/src/main/assets/mod_reference_hashes.json").readText())
    }

    private val zips = listOf("OpenHome.zip" to "openhome", "OpenHome-Dark.zip" to "openhome_dark")

    /** The JVM's stand-in for `ModImportPipeline.decodeToLuminance`: white-composited, resized, luma. */
    private fun decodeToLuminance(bytes: ByteArray): IntArray? {
        val image = ImageIO.read(bytes.inputStream()) ?: return null
        if (image.width < 16 || image.height < 16) return null
        val width = DHash.HASH_SIZE + 1
        val height = DHash.HASH_SIZE
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        return IntArray(width * height) { i ->
            val pixel = scaled.getRGB(i % width, i / width)
            val r = (pixel shr 16) and 0xFF
            val gr = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            (0.299 * r + 0.587 * gr + 0.114 * b).toInt().coerceIn(0, 255)
        }
    }

    @Test
    fun `each OpenHome zip passes the import scan`() {
        assertTrue(referenceDb.size > 0)
        for ((name, _) in zips) {
            ZipFile(repoFile("mods/openhome/$name")).use { zip ->
                ModImportScanner.validateMetadata(zip)
                val violations = ModImportScanner.scanEntries(zip, referenceDb, ::decodeToLuminance)
                assertEquals("$name: $violations", emptyList<Any>(), violations)
            }
        }
    }

    @Test
    fun `each OpenHome manifest names a pickable palette and assets its zip carries`() {
        for ((name, paletteId) in zips) {
            ZipFile(repoFile("mods/openhome/$name")).use { zip ->
                val entry = zip.getEntry(ModManifest.FILENAME)
                assertNotNull("$name has no ${ModManifest.FILENAME}", entry)
                val manifest = ModManifest.parse(
                    zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8),
                    fallbackId = "fallback",
                    fallbackName = "fallback",
                )
                assertEquals(paletteId, manifest.palette?.id)
                assertFalse(manifest.palette!!.tintsSprites)
                assertTrue(manifest.smoothing)
                assertNotNull(manifest.chrome?.panel)
                assertNotNull(manifest.chrome?.ink)
                for (asset in listOfNotNull(
                    manifest.borderAsset,
                    manifest.background?.asset,
                    manifest.ball?.asset,
                )) {
                    assertNotNull("$name is missing $asset", zip.getEntry(asset))
                }
            }
        }
    }
}
