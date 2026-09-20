package com.logie.gen1storage.mods

import org.json.JSONObject

/**
 * A mod's own palette: the same five colours [com.logie.gen1storage.ui.GbPalette]
 * already is, named out of a manifest instead of written into this app's
 * own source. Nothing else about a mod is read yet — see [ModManifest] —
 * which is deliberate: a palette is a fact about colour, never about what
 * anything does, so a manifest that can only ever describe one is a
 * manifest that cannot change how this app behaves no matter what it says.
 */
data class ModPaletteDefinition(
    val id: String,
    val label: String,
    val lightest: Int,
    val light: Int,
    val dark: Int,
    val darkest: Int,
    val surround: Int,
    val tintsSprites: Boolean,
)

/** What `mod.json`, at the top of a mod's own zip, is allowed to say. */
data class ModManifest(
    val name: String,
    val author: String?,
    val palette: ModPaletteDefinition?,
) {
    companion object {
        /** The manifest's own filename, expected at the root of a mod zip. */
        const val FILENAME = "mod.json"

        /**
         * Parses `mod.json`. [fallbackId] and [fallbackName] stand in for a
         * name a mod never bothered to give itself — a palette still needs
         * an id to be picked by, and a mod is still worth listing even
         * without one.
         */
        fun parse(json: String, fallbackId: String, fallbackName: String): ModManifest {
            val root = JSONObject(json)
            val name = root.optString("name").takeIf { it.isNotBlank() } ?: fallbackName
            val author = root.optString("author").takeIf { it.isNotBlank() }
            val palette = root.optJSONObject("palette")?.let { parsePalette(it, fallbackId, name) }
            return ModManifest(name, author, palette)
        }

        private fun parsePalette(json: JSONObject, fallbackId: String, modName: String): ModPaletteDefinition? {
            val lightest = parseColor(json.optString("lightest")) ?: return null
            val light = parseColor(json.optString("light")) ?: return null
            val dark = parseColor(json.optString("dark")) ?: return null
            val darkest = parseColor(json.optString("darkest")) ?: return null
            val surround = parseColor(json.optString("surround")) ?: return null
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: "mod_$fallbackId"
            val label = json.optString("label").takeIf { it.isNotBlank() } ?: modName.uppercase()
            return ModPaletteDefinition(
                id = id,
                label = label,
                lightest = lightest,
                light = light,
                dark = dark,
                darkest = darkest,
                surround = surround,
                tintsSprites = json.optBoolean("tintsSprites", true),
            )
        }

        /** "#RRGGBB" or "RRGGBB" to an opaque ARGB int. Null for anything else. */
        private fun parseColor(hex: String): Int? {
            val cleaned = hex.removePrefix("#")
            if (cleaned.length != 6 || cleaned.any { it.digitToIntOrNull(16) == null }) return null
            return (0xFF shl 24) or cleaned.toInt(16)
        }
    }
}
