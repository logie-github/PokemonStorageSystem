package com.logie.gen1storage.sprites

import android.graphics.Bitmap

/**
 * Maps art onto a four-shade palette ramp by brightness.
 *
 * A Generation I sprite was four shades to begin with, so bucketing by
 * luminance lands each original shade on exactly one palette entry rather than
 * blending towards it — the result is recoloured art, not a tinted photograph.
 * Alpha is carried through untouched, so a transparent surround stays
 * transparent.
 *
 * [ramp] runs darkest first. Shared by the downloaded sprites and by the
 * app's own artwork so both land on the same shades.
 */
fun recolourToRamp(
    source: Bitmap,
    ramp: IntArray,
    /**
     * Whether the lightest shade is cut out rather than painted.
     *
     * These sprites come from the decomps as pictures with a white field
     * around them, because that is what a Game Boy drew — the lightest of the
     * four shades *is* the background. Painted onto a window of a different
     * tint that field shows as a white card behind the Pokémon; dropped, the
     * sprite sits on whatever it is placed on, which is what a cartridge's
     * own screen looks like.
     *
     * Off by default: a sprite on the ground still wants its field, and
     * cutting it out there would leave the dither showing through the gaps in
     * a Pokémon's outline.
     */
    cutOutLightest: Boolean = false,
): Bitmap {
    require(ramp.size >= 4) { "a ramp needs four shades, got ${ramp.size}" }
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val alpha = pixel ushr 24
        if (alpha == 0) continue
        // Integer luma weights, the usual 77/151/28 over 256.
        val luma = (
            ((pixel shr 16) and 0xFF) * 77 +
                ((pixel shr 8) and 0xFF) * 151 +
                (pixel and 0xFF) * 28
            ) shr 8
        val bucket = when {
            luma < 64 -> 0
            luma < 128 -> 1
            luma < 192 -> 2
            else -> 3
        }
        if (cutOutLightest && bucket == 3) {
            pixels[i] = 0
            continue
        }
        pixels[i] = (alpha shl 24) or (ramp[bucket] and 0x00FFFFFF)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * Drops a sprite's white field without touching anything else.
 *
 * The companion to [recolourToRamp]'s `cutOutLightest` for the palettes that
 * leave sprites alone: the picture keeps its own colours, and only the field
 * around it — the lightest of the four shades the Game Boy had — is cut away.
 */
fun cutOutLightest(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        if (pixel ushr 24 == 0) continue
        val luma = (
            ((pixel shr 16) and 0xFF) * 77 +
                ((pixel shr 8) and 0xFF) * 151 +
                (pixel and 0xFF) * 28
            ) shr 8
        if (luma >= 192) pixels[i] = 0
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
