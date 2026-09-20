package com.logie.gen1storage.mods

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.logie.gen1storage.ui.GbPalette
import java.io.File

/** One mod already sitting on this device, read back off its own folder. */
data class InstalledMod(val id: String, val manifest: ModManifest, val directory: File) {
    /** The `.ttf`/`.otf` [manifest] names, if the zip actually had one there. */
    fun fontFile(): File? = manifest.fontAsset?.let { File(directory, it) }?.takeIf { it.isFile }

    /** The border tileset image [manifest] names, if the zip actually had one there. */
    fun borderFile(): File? = manifest.borderAsset?.let { File(directory, it) }?.takeIf { it.isFile }

    /** The background picture [manifest] names, if the zip actually had one there. */
    fun backgroundFile(): File? = manifest.background?.let { File(directory, it.asset) }?.takeIf { it.isFile }

    /** The ball picture [manifest] names, if the zip actually had one there. */
    fun ballFile(): File? = manifest.ball?.let { File(directory, it.asset) }?.takeIf { it.isFile }
}

/**
 * Every mod this device has imported, kept as a plain folder per mod under
 * this app's own files — the same shape [com.logie.gen1storage.rom.RomStore]
 * already keeps ROMs in.
 *
 * A mod is never code: what [ModImportPipeline] extracts here is exactly
 * what a scan already passed, a manifest and the pictures it names, so
 * reading one back is just reading a folder, never running anything out of
 * it.
 */
class ModStore(private val directory: File) {

    fun list(): List<InstalledMod> =
        directory.listFiles { file -> file.isDirectory }
            ?.mapNotNull(::loadManifestFrom)
            ?.sortedBy { it.manifest.name }
            ?: emptyList()

    fun directoryFor(modId: String): File = File(directory, modId)

    fun delete(modId: String) {
        directoryFor(modId).deleteRecursively()
        reregisterPalettes()
    }

    /**
     * Tells [GbPalette] about every installed mod's own palette. Cheap
     * enough to call after every install or delete rather than trying to
     * add or remove one entry at a time: reading a handful of small
     * `mod.json` files back off disk is nothing next to everything else a
     * launch already does.
     */
    fun reregisterPalettes() {
        GbPalette.clearMods()
        list().forEach { mod ->
            mod.manifest.palette?.let { definition ->
                GbPalette.registerMod(
                    GbPalette(
                        id = definition.id,
                        label = definition.label,
                        lightest = Color(definition.lightest),
                        light = Color(definition.light),
                        dark = Color(definition.dark),
                        darkest = Color(definition.darkest),
                        surround = Color(definition.surround),
                        tintsSprites = definition.tintsSprites,
                    )
                )
            }
        }
    }

    private fun loadManifestFrom(modDir: File): InstalledMod? {
        val manifestFile = File(modDir, ModManifest.FILENAME)
        if (!manifestFile.isFile) return null
        val manifest = runCatching {
            ModManifest.parse(manifestFile.readText(), fallbackId = modDir.name, fallbackName = modDir.name)
        }.getOrNull() ?: return null
        return InstalledMod(modDir.name, manifest, modDir)
    }

    companion object {
        fun directoryIn(context: Context): File = File(context.filesDir, "mods")
    }
}
