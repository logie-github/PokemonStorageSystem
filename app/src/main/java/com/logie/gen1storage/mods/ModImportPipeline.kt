package com.logie.gen1storage.mods

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.zip.ZipFile

/**
 * The one door a mod comes through: a zip a player picked, copied
 * somewhere this app can open at random (a `content://` stream cannot be
 * seeked, and [ModImportScanner] needs a real [ZipFile] to read its central
 * directory before trusting anything in it), scanned whole, and only then,
 * if nothing in it needed saying no to, unpacked.
 *
 * Nothing is written to [ModStore] on anything but a clean scan: a mod with
 * one flagged sprite among a hundred clean ones still does not get to keep
 * the ninety-nine, because a player who imports a mod is trusting all of
 * it, not whichever parts happened to pass.
 */
object ModImportPipeline {

    /** Either the mod that is now installed, or every reason it is not. */
    data class ImportResult(val installed: InstalledMod?, val violations: List<ModViolation>) {
        val isSuccess: Boolean get() = installed != null
    }

    fun import(
        context: Context,
        uri: Uri,
        modStore: ModStore,
        referenceDb: ModReferenceDatabase,
    ): ImportResult {
        val tempZip = File(context.cacheDir, "mod-import-${System.nanoTime()}.zip")
        try {
            val opened = context.contentResolver.openInputStream(uri)?.use { input ->
                tempZip.outputStream().use { output -> input.copyTo(output) }
                true
            }
            if (opened != true) {
                return rejected("THE CHOSEN FILE WOULD NOT OPEN.")
            }

            val zipFile = try {
                ZipFile(tempZip)
            } catch (e: Exception) {
                return rejected("THAT IS NOT A VALID ZIP FILE.")
            }

            zipFile.use { zip ->
                try {
                    ModImportScanner.validateMetadata(zip)
                } catch (e: ModImportScanner.ArchiveRejected) {
                    return rejected(e.message.orEmpty().uppercase())
                }

                val manifestEntry = zip.getEntry(ModManifest.FILENAME)
                    ?: return rejected("NO ${ModManifest.FILENAME.uppercase()} AT THE TOP OF THE ZIP.")
                val manifestText = zip.getInputStream(manifestEntry).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
                val modId = "mod-${System.currentTimeMillis()}"
                val manifest = try {
                    ModManifest.parse(manifestText, fallbackId = modId, fallbackName = modId)
                } catch (e: Exception) {
                    return rejected("${ModManifest.FILENAME.uppercase()} COULD NOT BE READ.")
                }

                val violations = ModImportScanner.scanEntries(zip, referenceDb, ::decodeToLuminance)
                if (violations.isNotEmpty()) {
                    return ImportResult(null, violations)
                }

                val destination = modStore.directoryFor(modId)
                destination.mkdirs()
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val safePath = ModImportScanner.normalizePath(entry.name) ?: continue
                    val target = File(destination, safePath)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }

                modStore.reregisterPalettes()
                return ImportResult(InstalledMod(modId, manifest, destination), emptyList())
            }
        } finally {
            tempZip.delete()
        }
    }

    private fun rejected(reason: String) =
        ImportResult(null, listOf(ModViolation("", "Archive rejected", reason)))

    /**
     * The one seam [ModImportScanner] leaves for Android to fill: a
     * decoded, resized, white-composited luminance grid ready for
     * [DHash.fromResizedLuminance]. Null for anything that will not decode
     * as an image at all, or that is too small to be more than an icon
     * slice — the same floor `tools/generate_mod_scanner_hashes.py` filters
     * the reference set by.
     */
    private fun decodeToLuminance(bytes: ByteArray): IntArray? {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return null
        if (bitmap.width < 16 || bitmap.height < 16) {
            bitmap.recycle()
            return null
        }
        val width = DHash.HASH_SIZE + 1
        val height = DHash.HASH_SIZE
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val alpha = ((pixel ushr 24) and 0xFF) / 255.0
            val red = (pixel ushr 16) and 0xFF
            val green = (pixel ushr 8) and 0xFF
            val blue = pixel and 0xFF
            // Composited onto white first, same as the Python generator, so
            // a transparent background never reads as black and swamps
            // the comparison it is supposed to stay out of.
            val r = red * alpha + 255 * (1 - alpha)
            val g = green * alpha + 255 * (1 - alpha)
            val b = blue * alpha + 255 * (1 - alpha)
            // ITU-R 601 luma, the same weights Pillow's own "L" convert uses.
            luminance[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
        }
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()
        return luminance
    }
}
