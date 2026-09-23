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
 * The Pokedex mod shipped as `mods/pokedex/Pokedex.zip`, read the way an
 * import reads it — see [OpenHomeModTest], which this mirrors.
 */
class PokedexModTest {

    private fun repoFile(path: String): File =
        listOf(File(path), File("..", path)).firstOrNull { it.exists() }
            ?: error("$path not found from ${File("").absolutePath}")

    private val referenceDb by lazy {
        ModReferenceDatabase.parse(repoFile("app/src/main/assets/mod_reference_hashes.json").readText())
    }

    /** The JVM's stand-in for `ModImportPipeline.decodeToLuminance`. */
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
    fun `the Pokedex zip passes the import scan`() {
        assertTrue(referenceDb.size > 0)
        ZipFile(repoFile("mods/pokedex/Pokedex.zip")).use { zip ->
            ModImportScanner.validateMetadata(zip)
            val violations = ModImportScanner.scanEntries(zip, referenceDb, ::decodeToLuminance)
            assertEquals(violations.toString(), emptyList<Any>(), violations)
        }
    }

    @Test
    fun `the Pokedex manifest names a pickable palette and assets its zip carries`() {
        ZipFile(repoFile("mods/pokedex/Pokedex.zip")).use { zip ->
            val entry = zip.getEntry(ModManifest.FILENAME)
            assertNotNull("no ${ModManifest.FILENAME}", entry)
            val manifest = ModManifest.parse(
                zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8),
                fallbackId = "fallback",
                fallbackName = "fallback",
            )
            assertEquals("pokedex", manifest.palette?.id)
            assertFalse(manifest.palette!!.tintsSprites)
            assertTrue(manifest.smoothing)
            assertNotNull(manifest.chrome?.panel)
            assertNotNull(manifest.chrome?.ink)
            for (asset in listOfNotNull(
                manifest.borderAsset,
                manifest.background?.asset,
                manifest.ball?.asset,
            )) {
                assertNotNull("Pokedex.zip is missing $asset", zip.getEntry(asset))
            }
        }
    }
}
