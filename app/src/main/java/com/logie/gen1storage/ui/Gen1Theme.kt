package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.gen1storage.R
import kotlin.math.ceil
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

/** Roughly how tall the body em should be, before it is snapped to the grid. */
private val BodyEm = 13.dp

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
 * The Generation I window: a hard outer rule, a light gutter, and an inner
 * rule, over a flat panel. Every list, dialogue box and status panel in the app
 * is one of these, which is what makes the whole thing read as one machine.
 */
@Composable
fun Gen1Window(
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // Drawn as alternating fills rather than a border: modifiers paint
    // outside-in, so a background after a border would cover it.
    Column(
        modifier
            .fillMaxWidth()
            .background(Gen1Palette.Ink)      // outer rule
            .padding(3.dp)
            .background(Gen1Palette.Panel)    // light gutter
            .padding(2.dp)
            .background(Gen1Palette.Ink)      // inner rule
            .padding(2.dp)
            .background(Gen1Palette.Panel)    // interior
    ) {
        if (title != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Gen1Palette.Ink)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                GbText(title.uppercase(), style = Gen1Text.copy(color = Gen1Palette.Panel))
            }
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
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

/**
 * A menu row. Touch-first: one tap moves the cursor onto a row, a second tap
 * confirms it — the original's cursor-then-A rhythm, without a virtual D-pad.
 */
@Composable
fun Gen1MenuItem(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onConfirm: () -> Unit,
    trailing: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val ink = if (enabled) Gen1Palette.Ink else Gen1Palette.Shadow
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) Gen1Palette.Muted else Gen1Palette.Panel)
            .clickable(enabled = enabled) { if (selected) onConfirm() else onSelect() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Gen1Cursor(selected && enabled)
        Column(Modifier.weight(1f)) {
            GbText(label.uppercase(), style = Gen1Text.copy(color = ink), maxLines = 2)
            if (subtitle != null) GbText(subtitle.uppercase(), style = Gen1TextSmall, maxLines = 2)
        }
        if (trailing != null) {
            GbText(trailing.uppercase(), style = Gen1Text.copy(color = ink))
            Spacer(Modifier.width(10.dp))
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
            .clickable(enabled = enabled, onClick = onClick)
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

/** The dialogue box the games use for every message and confirmation. */
@Composable
fun Gen1Dialogue(
    lines: List<String>,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Gen1Window(modifier) {
        lines.forEach { GbText(it.uppercase()) }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

/** A label/value pair as the status screens lay them out. */
@Composable
fun Gen1Field(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GbText(label.uppercase(), style = Gen1TextSmall, modifier = Modifier.width(112.dp))
        GbText(value.uppercase(), maxLines = 2)
    }
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
                        fraction > 0.5f -> Gen1Palette.Darkest
                        fraction > 0.2f -> Gen1Palette.Dark
                        else -> Gen1Palette.Light
                    }
                )
        )
    }
}
