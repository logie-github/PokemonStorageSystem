package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalDensity
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.gen1storage.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import androidx.compose.material3.Text as MaterialText

/**
 * The Generation I presentation layer.
 *
 * Four flat shades, hard black outlines, square corners, no gradients and no
 * elevation. Everything is drawn with borders and solid fills so it stays crisp
 * at any density — there is no bitmap to resample and therefore nothing to
 * blur. Type is a monospace face at whole-pixel sizes with wide letter spacing,
 * which reads as the Game Boy font without shipping one.
 */
object Gen1Palette {

    /**
     * The palette everything is drawn through. Snapshot state rather than a
     * constant, so a change in OPTIONS repaints every screen at once —
     * including the `drawBehind` lambdas, which read these getters directly.
     */
    var palette by mutableStateOf(GbPalette.ORIGINAL)

    /**
     * Whether the windows follow the palette as well as the screen behind them.
     *
     * Off by default, because the cartridge draws its text boxes black on white
     * whatever the screen is tinted, and that is the more readable of the two.
     * Turning it on lets the boxes take the palette too.
     */
    var windowsFollowPalette by mutableStateOf(false)

    /** The four-shade ramp, lightest to darkest. Always the chosen palette. */
    val Lightest: Color get() = palette.lightest
    val Light: Color get() = palette.light
    val Dark: Color get() = palette.dark
    val Darkest: Color get() = palette.darkest

    /** The screen behind every window, as the console letterboxes it. */
    val Surround: Color get() = palette.surround

    // Window chrome. Black on white unless the player says otherwise.
    val Ink: Color get() = if (windowsFollowPalette) palette.darkest else MonoInk
    val Panel: Color get() = if (windowsFollowPalette) palette.lightest else MonoPanel
    val Shadow: Color get() = if (windowsFollowPalette) palette.dark else MonoShadow
    val Muted: Color get() = if (windowsFollowPalette) palette.light else MonoMuted

    private val MonoInk = Color(0xFF101010)
    private val MonoPanel = Color(0xFFF8F8F8)
    private val MonoShadow = Color(0xFF686868)
    private val MonoMuted = Color(0xFFC8C8C8)
}

/**
 * The Generation I face itself, bundled with the app.
 *
 * It is a vector font drawing square pixels rather than a bitmap one, so it
 * carries the same rule any pixel font does: it is only crisp when one design
 * pixel lands on a whole number of device pixels.
 *
 * Measured from the source rather than taken on faith — the project's own
 * README says to use multiples of ten, which does not hold. The em is 320
 * units and all but a handful of the Latin outline coordinates are multiples
 * of 40, so one design pixel is an **eighth** of the em: in that grid a capital
 * is 7 pixels tall, the advance is 8, and the ascender is 10. A size that is a whole
 * multiple of eight device pixels therefore puts every glyph edge on a pixel
 * boundary, and any other size hands the anti-aliaser a fractional edge to
 * soften. [pixelSize] is what enforces it.
 */
val Gen1FontFamily = FontFamily(Font(R.font.pokemon_font))

/** Device pixels per design pixel step. The em is eight of these. */
private const val FONT_GRID_PX = 8

/**
 * How tall the body em wants to be before it is snapped to the grid.
 *
 * One number for the whole interface: the type, the window borders, the
 * sprites and the status pages are all measured in Game Boy pixels off the
 * size this settles on, so there is exactly one grid and nothing can drift off
 * it. Three device pixels per Game Boy pixel at a typical phone density.
 */
private val BodyEm = 6.5.dp * UI_SCALE

/** The scale everything is drawn at. */
const val UI_SCALE = 3

/** Extra line height, as a fraction of the em, before snapping. */
private const val LEADING = 0.4f

/**
 * A grid-aligned type size.
 *
 * [steps] moves in whole design-pixel steps away from the body size, so every
 * size in the app stays a multiple of eight device pixels however dense the
 * screen is. The result is converted back through the density, which also
 * absorbs the user's font scale — so what comes out renders at exactly the
 * pixel count asked for.
 */
@Composable
private fun pixelSize(steps: Int): Pair<TextUnit, TextUnit> {
    val density = LocalDensity.current
    return with(density) {
        val (size, leading) = snapFontPixels(BodyEm.toPx(), steps)
        size.toFloat().toSp() to leading.toFloat().toSp()
    }
}

/**
 * The grid arithmetic, as size and leading in device pixels.
 *
 * Kept separate from the composable so the one claim the whole presentation
 * rests on — that every size is a whole multiple of the grid — is checkable
 * without a screen.
 *
 * The leading is about four tenths again, snapped to the same grid so
 * baselines land on pixels too. The face's own line box is 1.375 em, so this
 * always leaves it room rather than compressing it.
 */
internal fun snapFontPixels(bodyPx: Float, steps: Int): Pair<Int, Int> {
    val body = (Math.round(bodyPx / FONT_GRID_PX) * FONT_GRID_PX)
        .coerceAtLeast(FONT_GRID_PX * 3)
    val size = (body + steps * FONT_GRID_PX).coerceAtLeast(FONT_GRID_PX * 2)
    // Rounded up, never to nearest: rounding down here is what would push the
    // line box under the face's own and have the renderer compress it.
    val leading = size + (ceil(size * LEADING / FONT_GRID_PX).toInt() * FONT_GRID_PX)
        .coerceAtLeast(FONT_GRID_PX)
    return size to leading
}

/**
 * One Game Boy pixel, in device pixels.
 *
 * The em is eight of these, so taking the snapped type size and dividing by
 * eight gives the grid everything else is measured against — a whole number by
 * construction, which is what keeps the borders and the sprites as square as
 * the type.
 */
@Composable
fun gen1PixelPx(): Int {
    val density = LocalDensity.current
    return with(density) { snapFontPixels(BodyEm.toPx(), 0).first / FONT_GRID_PX }
}

/** [gamePixels] of the shared grid, as a layout measurement. */
@Composable
fun gen1Dp(gamePixels: Int): Dp {
    val pixel = gen1PixelPx()
    return with(LocalDensity.current) { (gamePixels * pixel).toDp() }
}

/**
 * The three type sizes, read fresh on every composition.
 *
 * They are composable getters rather than constants because both halves of a
 * style are now context: the ink colour is a setting, and the size depends on
 * the screen's density.
 */
val Gen1Text: TextStyle
    @Composable get() {
        val (size, leading) = pixelSize(0)
        return Gen1BaseText.copy(fontSize = size, lineHeight = leading, color = Gen1Palette.Ink)
    }

val Gen1TextSmall: TextStyle
    @Composable get() {
        val (size, leading) = pixelSize(-1)
        return Gen1BaseText.copy(fontSize = size, lineHeight = leading, color = Gen1Palette.Shadow)
    }

/** Small enough to fit two words across a cartridge's label. */
val Gen1TextTiny: TextStyle
    @Composable get() {
        val (size, leading) = pixelSize(-2)
        return Gen1BaseText.copy(fontSize = size, lineHeight = leading, color = Gen1Palette.Ink)
    }

val Gen1TextLarge: TextStyle
    @Composable get() {
        val (size, leading) = pixelSize(2)
        return Gen1BaseText.copy(fontSize = size, lineHeight = leading, color = Gen1Palette.Ink)
    }

/**
 * Everything that does not depend on the palette or the density.
 *
 * No synthetic bold: the face has one weight, and asking for another would have
 * the renderer smear the glyphs sideways to fake it. No letter spacing either —
 * the advances are already whole design pixels, and adding a fraction of one
 * would push every glyph after the first off the grid.
 */
private val Gen1BaseText = TextStyle(
    fontFamily = Gen1FontFamily,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * The screen behind the windows: the palette's ramp, top to bottom, dithered.
 *
 * There is no gradient here and no blending of any kind. A Game Boy could not
 * mix two colours, so a designer wanting a tone between them alternated pixels
 * of each and let the eye do the mixing — and the density of that alternation
 * is what carried the shading. This does the same thing: every pixel on screen
 * is one of the palette's own colours, and only how many of each changes as
 * the eye travels down.
 *
 * The pattern is an ordered dither against an 8x8 Bayer matrix, which is the
 * arrangement that spreads the minority colour as evenly as possible instead of
 * clumping it. That is what produces the sparse dots at each end and the clean
 * checkerboard where two colours meet.
 *
 * The darkest shade is deliberately not in the ramp. It is the ink the windows
 * are drawn in, and a background that reaches it leaves their rules with
 * nothing to sit against.
 */
@Composable
fun Modifier.gen1Ground(): Modifier {
    val palette = Gen1Palette.palette
    val unit = with(LocalDensity.current) {
        (density.roundToInt() * DITHER_SCALE).coerceAtLeast(2)
    }
    // Remembered so the draw cache is not handed a fresh array — and a fresh
    // reason to rebuild the whole ramp — on every recomposition.
    val ramp = remember(palette) {
        intArrayOf(palette.lightest.toArgb(), palette.light.toArgb(), palette.dark.toArgb())
    }
    return this.drawWithCache {
        val heightPx = size.height.roundToInt().coerceAtLeast(1)
        val image = ditherRamp(unit, heightPx, ramp)
        // Repeated across, clamped down: the strip is already the full height,
        // so only the horizontal axis has anything to tile.
        val brush = ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Clamp))
        onDrawBehind { drawRect(brush) }
    }
}

/**
 * One Bayer tile wide and the full height tall, so the whole ramp is a single
 * strip the shader repeats sideways.
 *
 * Each row sits somewhere between two of the ramp's colours. A pixel takes the
 * later colour when that fraction clears the matrix's threshold for its
 * position, so at the start of a band almost none do, halfway through exactly
 * half do in a checkerboard, and by the end almost all do.
 */
private fun ditherRamp(unit: Int, heightPx: Int, ramp: IntArray): ImageBitmap {
    val width = BAYER_SIDE * unit
    val pixels = IntArray(width * heightPx)
    val bands = ramp.size - 1
    val thresholds = FloatArray(BAYER_SIDE)

    for (y in 0 until heightPx) {
        val position = if (heightPx <= 1) 0f else y.toFloat() / (heightPx - 1)
        val travelled = position * bands
        val band = floor(travelled).toInt().coerceIn(0, bands - 1)
        val into = travelled - band
        val low = ramp[band]
        val high = ramp[band + 1]

        val cellY = (y / unit) % BAYER_SIDE
        for (cellX in 0 until BAYER_SIDE) {
            thresholds[cellX] = (BAYER_8X8[cellY * BAYER_SIDE + cellX] + 0.5f) / BAYER_LEVELS
        }

        val row = y * width
        for (x in 0 until width) {
            pixels[row + x] = if (into > thresholds[(x / unit) % BAYER_SIDE]) high else low
        }
    }
    return Bitmap.createBitmap(pixels, width, heightPx, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Device pixels per dither pixel, as a multiple of the screen's density. */
private const val DITHER_SCALE = 2

private const val BAYER_SIDE = 8
private const val BAYER_LEVELS = (BAYER_SIDE * BAYER_SIDE).toFloat()

/** The standard 8x8 ordered-dither matrix, 0..63. */
private val BAYER_8X8 = intArrayOf(
    0, 32, 8, 40, 2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37,
    63, 31, 55, 23, 61, 29, 53, 21,
)

@Composable
fun Gen1Theme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGen1TextStyle provides Gen1Text, content = content)
}

private val LocalGen1TextStyle = compositionLocalOf { Gen1BaseText }

@Composable
fun GbText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Gen1Text,
    maxLines: Int = Int.MAX_VALUE,
) {
    MaterialText(
        text = text,
        modifier = modifier,
        style = style,
        color = style.color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A tap with nothing drawn under the finger.
 *
 * Material's default `clickable` paints a ripple, which on a flat four-shade
 * interface reads as a smear across the window. The cursor already says what
 * is selected, so nothing else needs to.
 */
fun Modifier.gen1Clickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/** The rectangular selection cursor: a filled arrow before the chosen row. */
@Composable
private fun Gen1Cursor(selected: Boolean) {
    Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            GbText("▶")
        }
    }
}

/** A full-width action, drawn as its own small window so it reads as a button. */
@Composable
fun Gen1Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(Gen1Palette.Ink)
            .padding(2.dp)
            .background(if (enabled) Gen1Palette.Panel else Gen1Palette.Muted)
            .gen1Clickable(enabled, onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        // A flat two-tone plate: outer rule, inner fill, no rounding.
        contentAlignment = Alignment.Center,
    ) {
        GbText(
            label.uppercase(),
            style = Gen1Text.copy(color = if (enabled) Gen1Palette.Ink else Gen1Palette.Shadow),
        )
    }
}

/**
 * A button that is a window rather than a plate.
 *
 * The Generation I screens have no button widget: what a player takes is
 * always a window with a word in it. So this is the window itself, named by
 * what it does and clickable across its whole face — no plate inside a box,
 * which is two outlines saying one thing.
 */
@Composable
fun Gen1BoxButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Gen1FrameBox(modifier.wrapContentWidth().gen1Clickable(enabled, onClick)) {
        GbText(
            label.uppercase(),
            style = Gen1Text.copy(color = if (enabled) Gen1Palette.Ink else Gen1Palette.Shadow),
        )
    }
}

/** A label/value pair as the status screens lay them out. */
@Composable
fun Gen1Field(label: String, value: String, modifier: Modifier = Modifier) {
    // Stacked rather than in two columns: a fixed label column had no way to
    // be right for both "STATUS" and "ON THIS DEVICE", and wrapped the short
    // ones onto two lines to make room for the long ones.
    Column(modifier) {
        GbText(label.uppercase(), style = Gen1TextSmall, maxLines = 1)
        GbText(value.uppercase(), maxLines = 1)
    }
}

/**
 * The three colours an HP bar is drawn in.
 *
 * `PAL_GREENBAR`, `PAL_YELLOWBAR` and `PAL_REDBAR` from pret/pokeyellow's
 * Super Game Boy table, converted from its five-bit channels the way the
 * hardware does it (`v shl 3 or v shr 2`). They are fixed rather than taken
 * from the chosen palette: the bar is the one place in the interface that has
 * to mean something at a glance, and a red bar that is not red does not.
 */
object Gen1HpBarColors {
    val Green = Color(0xFF00AD00)
    val Yellow = Color(0xFFE7BD4A)
    val Red = Color(0xFFD64A31)
}

/** The HP bar, drawn as the flat three-state bar the games use. */
@Composable
fun Gen1HpBar(current: Int, max: Int, modifier: Modifier = Modifier) {
    val fraction = if (max <= 0) 0f else (current.toFloat() / max).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .background(Gen1Palette.Ink)
            .padding(2.dp)
            .background(Gen1Palette.Panel)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(10.dp)
                .background(
                    when {
                        fraction > 0.5f -> Gen1HpBarColors.Green
                        fraction > 0.2f -> Gen1HpBarColors.Yellow
                        else -> Gen1HpBarColors.Red
                    }
                )
        )
    }
}
