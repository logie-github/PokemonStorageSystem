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
     * Whether the field around the picture is cut out rather than painted.
     *
     * These sprites come from the decomps as pictures with a white field
     * around them, because that is what a Game Boy drew — the lightest of the
     * four shades *is* the background. Painted onto a window of a different
     * tint that field shows as a white card behind the Pokémon; dropped, the
     * sprite sits on whatever it is placed on, which is what a cartridge's
     * own screen looks like.
     *
     * Only the field goes. See [field]: the lightest shade is also a
     * highlight — a Pokémon's eye, a shirt, the light on a Poké Ball — and
     * cutting every one of those left the sprite full of holes with whatever
     * was behind it showing through them, which is visible the moment one
     * sprite is drawn over another.
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
    val buckets = IntArray(pixels.size)
    for (i in pixels.indices) {
        // A pixel that is already transparent is field, whatever colour it
        // was left as, so the flood runs through it rather than stopping.
        buckets[i] = if (pixels[i] ushr 24 == 0) LIGHTEST else bucketOf(pixels[i])
    }

    val field = if (cutOutLightest) field(buckets, width, height) else null
    for (i in pixels.indices) {
        if (pixels[i] ushr 24 == 0) continue
        if (field != null && field[i]) {
            pixels[i] = 0
            continue
        }
        val alpha = pixels[i] ushr 24
        pixels[i] = (alpha shl 24) or (ramp[buckets[i]] and 0x00FFFFFF)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * Which pixels are the field the picture was drawn on, rather than part of it.
 *
 * The field is the lightest shade *reached from the edge of the picture*. A
 * Game Boy had no transparency, so a sprite sheet's background is simply the
 * lightest of the four shades — and so is a highlight inside the picture,
 * which is the same colour and nothing like the same thing. Taking every
 * lightest pixel left a Pikachu with holes where its cheeks are: invisible
 * against a window of that colour, and obvious the moment the sprite is laid
 * over another one, which is exactly what a trainer card does.
 *
 * So the field is found by flooding inwards from the border instead. Anything
 * the flood cannot reach without crossing the picture stays part of the
 * picture. Four-connected: a highlight that touches the field only at a corner
 * is enclosed by the outline, and a Game Boy's own tiles are drawn that way.
 *
 * Pixels that are already transparent count as field, so art that arrives with
 * an alpha channel seeds the flood rather than walling it off.
 */
private fun field(buckets: IntArray, width: Int, height: Int): BooleanArray {
    val field = BooleanArray(buckets.size)
    val stack = IntArray(buckets.size)
    var top = 0

    fun seed(index: Int) {
        if (index in buckets.indices && !field[index] && buckets[index] == LIGHTEST) {
            field[index] = true
            stack[top++] = index
        }
    }

    for (x in 0 until width) {
        seed(x)
        seed((height - 1) * width + x)
    }
    for (y in 0 until height) {
        seed(y * width)
        seed(y * width + width - 1)
    }
    while (top > 0) {
        val at = stack[--top]
        val x = at % width
        val y = at / width
        if (x > 0) seed(at - 1)
        if (x < width - 1) seed(at + 1)
        if (y > 0) seed(at - width)
        if (y < height - 1) seed(at + width)
    }
    return field
}

/** The bucket the Game Boy's background shade lands in. */
private const val LIGHTEST = 3

/** Which of the four shades a pixel is, ignoring what colour it is drawn in. */
/**
 * Swaps one exact set of four colours for another, pixel for pixel.
 *
 * For art that already carries its colours — Generation II's, where the file
 * holds what the Game Boy Color showed — rather than for the four greys
 * [recolourToRamp] tints. Nothing is measured or bucketed: a pixel that is
 * the second entry of [from] becomes the second entry of [to] and a pixel
 * that is none of them is left alone, so a sprite saved with a stray colour
 * keeps it instead of being rounded into the nearest shade.
 *
 * [cutOut] drops the field around the picture the way the tinting path does,
 * reading it from the border rather than by colour so an interior highlight
 * of the same shade survives.
 */
fun swapColours(source: Bitmap, from: IntArray, to: IntArray, cutOut: Boolean): Bitmap {
    require(from.size >= 4 && to.size >= 4) { "four colours, in and out" }
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val field = if (cutOut) {
        val buckets = IntArray(pixels.size) { i ->
            if (pixels[i] ushr 24 == 0) LIGHTEST else bucketOf(pixels[i])
        }
        field(buckets, width, height)
    } else null

    for (i in pixels.indices) {
        if (field != null && field[i]) {
            pixels[i] = 0
            continue
        }
        if (pixels[i] ushr 24 == 0) continue
        val opaque = pixels[i] or (0xFF shl 24)
        val at = from.indexOfFirst { it or (0xFF shl 24) == opaque }
        if (at in 0..3) pixels[i] = to[at] or (0xFF shl 24)
    }

    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

private fun bucketOf(pixel: Int): Int {
    // Integer luma weights, the usual 77/151/28 over 256.
    val luma = (
        ((pixel shr 16) and 0xFF) * 77 +
            ((pixel shr 8) and 0xFF) * 151 +
            (pixel and 0xFF) * 28
        ) shr 8
    return when {
        luma < 64 -> 0
        luma < 128 -> 1
        luma < 192 -> 2
        else -> LIGHTEST
    }
}

/**
 * Drops a sprite's white field without touching anything else.
 *
 * The companion to [recolourToRamp]'s `cutOutLightest` for the palettes that
 * leave sprites alone: the picture keeps its own colours, and only the field
 * around it — the lightest shade, reached from the border — is cut away. See
 * [field] for why it is reached from the border rather than simply matched.
 */
fun cutOutLightest(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val buckets = IntArray(pixels.size)
    for (i in pixels.indices) {
        buckets[i] = if (pixels[i] ushr 24 == 0) LIGHTEST else bucketOf(pixels[i])
    }
    val field = field(buckets, width, height)
    for (i in pixels.indices) {
        if (field[i]) pixels[i] = 0
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
