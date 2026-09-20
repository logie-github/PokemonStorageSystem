package com.logie.gen1storage.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Everything a mod is allowed to change about how this app looks, in one
 * value.
 *
 * Every field is either null or a default meaning "this app's own", so the
 * whole of a mod's reach is visible here in one list — and so taking a mod
 * off is one assignment of [Gen1Mod.NONE] rather than a dozen fields
 * remembered one at a time.
 *
 * None of it can name a behaviour. A picture, a colour and two switches
 * about whether something turns: a manifest that can only say these things
 * cannot change what the app does with a save, only what the player sees
 * while it does it.
 */
data class Gen1ModTheme(
    /** How solid a window's fill is, 1 for this app's own opaque windows. */
    val opacity: Float = 1f,
    /** A 3x3 border tileset drawn in place of the cartridge's own border. */
    val border: ImageBitmap? = null,
    /** A picture drawn in place of the dithered ground. */
    val background: ImageBitmap? = null,
    val backgroundFit: Fit = Fit.COVER,
    /** A picture drawn in place of the Poké Ball turning in the corner. */
    val ball: ImageBitmap? = null,
    /** Whether that picture turns. A mod's own emblem may be meant to sit still. */
    val ballSpins: Boolean = true,
    /**
     * Whether a mod's own pictures are resampled smoothly.
     *
     * Off is this app's own rule — one source pixel to a whole number of
     * device pixels, nothing softened — and it is right for a mod that
     * drew pixel art. A mod whose art is glow and gradients wants the
     * opposite, since nearest-neighbour takes a smooth falloff apart into
     * bands, so it says so and gets it. It reaches a mod's own art only;
     * the sprites and the built-in border are pixels either way.
     */
    val smoothing: Boolean = false,

    // Chrome. Null anywhere a mod had nothing to say, and then the palette
    // decides it exactly as it did before mods existed.
    val ink: Color? = null,
    val panel: Color? = null,
    val shadow: Color? = null,
    val muted: Color? = null,
    val surround: Color? = null,
    val barFill: Color? = null,
    val barText: Color? = null,
    val hpGreen: Color? = null,
    val hpYellow: Color? = null,
    val hpRed: Color? = null,
) {
    /** How a background picture is fitted to the screen. */
    enum class Fit { COVER, TILE, STRETCH }

    /** How this mod's own art is resampled — see [smoothing]. */
    val filter: FilterQuality
        get() = if (smoothing) FilterQuality.Medium else FilterQuality.None
}

/**
 * The mod in force, global to the drawing code the way [Gen1Palette] is.
 *
 * Read directly by `drawBehind` lambdas, which have no view model in reach
 * to ask; written once per change from [com.logie.gen1storage.MainActivity].
 */
object Gen1Mod {

    /** Nothing modded: this app exactly as it ships. */
    val NONE = Gen1ModTheme()

    var theme by mutableStateOf(NONE)
}
