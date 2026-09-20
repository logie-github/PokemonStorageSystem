package com.logie.gen1storage.mods

import org.json.JSONObject

/**
 * A mod's own palette: the same five colours [com.logie.gen1storage.ui.GbPalette]
 * already is, named out of a manifest instead of written into this app's
 * own source, plus how solid the windows drawn in it are. Nothing here is
 * about what anything does, only what it looks like, so a manifest that can
 * only ever describe this is a manifest that cannot change how this app
 * behaves no matter what it says.
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
    /**
     * How solid a window's own fill is, lowest first. Floored well above
     * zero: a window a player cannot read the text in is not a look, it is
     * the interface breaking, and nothing about "aesthetics only" asks for
     * that.
     */
    val opacity: Float,
)

/**
 * A picture drawn in place of the dithered screen behind the windows, and
 * how it is fitted to that screen.
 */
data class ModBackgroundDefinition(val asset: String, val fit: String) {
    companion object {
        const val COVER = "cover"
        const val TILE = "tile"
        const val STRETCH = "stretch"
        val FITS = setOf(COVER, TILE, STRETCH)
    }
}

/** A picture drawn in place of the Poké Ball turning in the corner. */
data class ModBallDefinition(val asset: String, val spins: Boolean)

/**
 * Colours a mod names outright rather than leaving to the palette.
 *
 * Every one is optional and every one is only a colour. The palette alone
 * cannot describe a dark window with bright text — a window is always
 * filled with the ramp's lightest shade and written in its darkest — so
 * this is what a mod that is not simply a tint has to say instead.
 */
data class ModChromeDefinition(
    val ink: Int?,
    val panel: Int?,
    val shadow: Int?,
    val muted: Int?,
    val surround: Int?,
    val barFill: Int?,
    val barText: Int?,
    val hpGreen: Int?,
    val hpYellow: Int?,
    val hpRed: Int?,
)

/** What `mod.json`, at the top of a mod's own zip, is allowed to say. */
data class ModManifest(
    val name: String,
    val author: String?,
    val palette: ModPaletteDefinition?,
    /** A `.ttf`/`.otf` bundled in the zip, replacing the built-in face. */
    val fontAsset: String?,
    /** A border tileset image bundled in the zip — see `drawGen1BorderBitmap`. */
    val borderAsset: String?,
    val background: ModBackgroundDefinition?,
    val ball: ModBallDefinition?,
    val chrome: ModChromeDefinition?,
    /**
     * Whether this mod's own pictures are resampled smoothly rather than as
     * pixels. False unless a mod says otherwise, which keeps a pixel-art
     * mod looking the way this app's own art does.
     */
    val smoothing: Boolean,
) {
    companion object {
        /** The manifest's own filename, expected at the root of a mod zip. */
        const val FILENAME = "mod.json"

        /** A window's fill never goes below this fraction of solid. */
        private const val MIN_OPACITY = 0.35f

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
            val fontAsset = sanitizeAssetPath(root.optString("fontAsset"))
            val borderAsset = sanitizeAssetPath(root.optString("borderAsset"))
            val background = root.optJSONObject("background")?.let(::parseBackground)
            val ball = root.optJSONObject("ball")?.let(::parseBall)
            val chrome = root.optJSONObject("chrome")?.let(::parseChrome)
            val smoothing = root.optBoolean("smoothing", false)
            return ModManifest(
                name, author, palette, fontAsset, borderAsset, background, ball, chrome, smoothing,
            )
        }

        private fun parseBackground(json: JSONObject): ModBackgroundDefinition? {
            val asset = sanitizeAssetPath(json.optString("asset")) ?: return null
            val named = json.optString("fit").lowercase()
            val fit = if (named in ModBackgroundDefinition.FITS) named else ModBackgroundDefinition.COVER
            return ModBackgroundDefinition(asset, fit)
        }

        private fun parseBall(json: JSONObject): ModBallDefinition? {
            val asset = sanitizeAssetPath(json.optString("asset")) ?: return null
            return ModBallDefinition(asset, json.optBoolean("spins", true))
        }

        private fun parseChrome(json: JSONObject) = ModChromeDefinition(
            ink = parseColor(json.optString("ink")),
            panel = parseColor(json.optString("panel")),
            shadow = parseColor(json.optString("shadow")),
            muted = parseColor(json.optString("muted")),
            surround = parseColor(json.optString("surround")),
            barFill = parseColor(json.optString("barFill")),
            barText = parseColor(json.optString("barText")),
            hpGreen = parseColor(json.optString("hpGreen")),
            hpYellow = parseColor(json.optString("hpYellow")),
            hpRed = parseColor(json.optString("hpRed")),
        )

        private fun parsePalette(json: JSONObject, fallbackId: String, modName: String): ModPaletteDefinition? {
            val lightest = parseColor(json.optString("lightest")) ?: return null
            val light = parseColor(json.optString("light")) ?: return null
            val dark = parseColor(json.optString("dark")) ?: return null
            val darkest = parseColor(json.optString("darkest")) ?: return null
            val surround = parseColor(json.optString("surround")) ?: return null
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: "mod_$fallbackId"
            val label = json.optString("label").takeIf { it.isNotBlank() } ?: modName.uppercase()
            val opacity = json.optDouble("opacity", 1.0).toFloat().coerceIn(MIN_OPACITY, 1f)
            return ModPaletteDefinition(
                id = id,
                label = label,
                lightest = lightest,
                light = light,
                dark = dark,
                darkest = darkest,
                surround = surround,
                tintsSprites = json.optBoolean("tintsSprites", true),
                opacity = opacity,
            )
        }

        /**
         * "#RRGGBB" to an opaque ARGB int, or "#AARRGGBB" to one carrying its
         * own alpha — a chrome colour meant to let the background through
         * has no other way to say so. Null for anything else.
         */
        private fun parseColor(hex: String): Int? {
            val cleaned = hex.removePrefix("#")
            if (cleaned.any { it.digitToIntOrNull(16) == null }) return null
            return when (cleaned.length) {
                6 -> (0xFF shl 24) or cleaned.toInt(16)
                8 -> cleaned.toLong(16).toInt()
                else -> null
            }
        }

        /**
         * A path a mod's own zip actually has an entry for, never one that
         * reaches outside the mod's own extracted folder — the manifest is
         * read before extraction even runs, so nothing here has been
         * through [ModImportScanner.normalizePath] yet.
         */
        private fun sanitizeAssetPath(path: String): String? {
            val trimmed = path.trim()
            if (trimmed.isBlank() || trimmed.startsWith("/") || trimmed.contains("..")) return null
            return trimmed
        }
    }
}
