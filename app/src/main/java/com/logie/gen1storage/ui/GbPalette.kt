package com.logie.gen1storage.ui

import androidx.compose.ui.graphics.Color

/**
 * A four-shade Game Boy palette.
 *
 * Generation I on a Game Boy Color drew everything through a four-entry
 * palette, and this is that idea kept: one ramp, lightest to darkest, applied
 * to the screen behind the windows and to the sprites themselves.
 *
 * The game ramps here are built to each game's identity — white, a light tint,
 * a dark tint, black — rather than copied out of a particular ROM's palette
 * table. They are a starting point to tune by eye, not a transcription.
 */
data class GbPalette(
    val id: String,
    val label: String,
    val lightest: Color,
    val light: Color,
    val dark: Color,
    val darkest: Color,
    /** The screen behind the windows. */
    val surround: Color,
    /** False for the untinted default, where sprites keep their own colours. */
    val tintsSprites: Boolean,
) {
    /** Lightest to darkest, for the sprite recolour. */
    val ramp: List<Color> get() = listOf(darkest, dark, light, lightest)

    companion object {
        /** The app's own look: no tint, and sprites keep the art's own colour. */
        val ORIGINAL = GbPalette(
            id = "original",
            label = "ORIGINAL",
            lightest = Color(0xFFF8F8F8),
            light = Color(0xFFC8C8C8),
            dark = Color(0xFF686868),
            darkest = Color(0xFF101010),
            surround = Color(0xFF303030),
            tintsSprites = false,
        )

        val RED = GbPalette(
            id = "red", label = "RED",
            lightest = Color(0xFFFFF0F0),
            light = Color(0xFFFF9A9A),
            dark = Color(0xFF9C2828),
            darkest = Color(0xFF200808),
            surround = Color(0xFF3C1010),
            tintsSprites = true,
        )

        val BLUE = GbPalette(
            id = "blue", label = "BLUE",
            lightest = Color(0xFFF0F4FF),
            light = Color(0xFF94ACFF),
            dark = Color(0xFF32448C),
            darkest = Color(0xFF080C20),
            surround = Color(0xFF141C3C),
            tintsSprites = true,
        )

        val GREEN = GbPalette(
            id = "green", label = "GREEN",
            lightest = Color(0xFFF0FFF0),
            light = Color(0xFF90DC90),
            dark = Color(0xFF2C7434),
            darkest = Color(0xFF08180C),
            surround = Color(0xFF10301A),
            tintsSprites = true,
        )

        val YELLOW = GbPalette(
            id = "yellow", label = "YELLOW",
            lightest = Color(0xFFFFFCEC),
            light = Color(0xFFFFE070),
            dark = Color(0xFF9C7818),
            darkest = Color(0xFF201804),
            surround = Color(0xFF3C300C),
            tintsSprites = true,
        )

        /**
         * The soft pink-to-blue mix the Game Boy Color could apply to an
         * original Game Boy cartridge, rather than any one game's own palette.
         */
        val GBC_PASTEL = GbPalette(
            id = "pastel", label = "GBC PASTEL",
            lightest = Color(0xFFFFFFFF),
            light = Color(0xFFFFC4C4),
            dark = Color(0xFF7C7CD4),
            darkest = Color(0xFF1C1C4C),
            surround = Color(0xFF2C2C5C),
            tintsSprites = true,
        )

        /**
         * `PAL_ROUTE` from pret/pokered's `data/sgb/sgb_palettes.asm`, the
         * Super Game Boy colours the overworld routes were shown in.
         *
         * The two middle shades are swapped from the order the table lists
         * them in. A Super Game Boy palette is indexed by a tile's shade, not
         * by how bright the colour is, and here entry 2 (the blue) is very
         * slightly lighter than entry 1 (the green) — 203 against 198. Left as
         * written, the background ramp would step backwards and the sprite
         * recolour would put them in the wrong buckets, so they are ordered by
         * brightness like every other palette here. At five parts in 256 apart
         * it is not a difference anyone can see.
         */
        val ROUTE = GbPalette(
            id = "route", label = "ROUTE",
            lightest = Color(0xFFFFEFFF),
            light = Color(0xFFA5D6FF),
            dark = Color(0xFFADE75A),
            darkest = Color(0xFF181010),
            surround = Color(0xFF181010),
            tintsSprites = true,
        )

        /**
         * Four shades given by name rather than read off a cartridge, ordered
         * the way every palette here is: darkest at the foot of the screen,
         * lightest at the top.
         *
         * The magenta is the second-darkest of the four by brightness — 91
         * against the deep purple's 43 and the lilac's 126 — so it sits where
         * a Game Boy's second shade sits, carrying the dither between the
         * purple below it and the lilac above.
         */
        val PURPLE_RAIN = GbPalette(
            id = "purple_rain", label = "PURPLE RAIN",
            lightest = Color(0xFFADFFFC),
            light = Color(0xFF8570B2),
            dark = Color(0xFFFF0084),
            darkest = Color(0xFF68006A),
            // Between the two darkest, as the others have it: the ground the
            // windows sit on is darker than any of them and not black.
            surround = Color(0xFF8E0070),
            tintsSprites = true,
        )

        val ALL =
            listOf(ORIGINAL, RED, BLUE, GREEN, YELLOW, GBC_PASTEL, ROUTE, PURPLE_RAIN)

        fun fromId(id: String?): GbPalette = ALL.firstOrNull { it.id == id } ?: ORIGINAL
    }
}
