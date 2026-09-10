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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The three type sizes, read fresh on every composition.
 *
 * They are composable getters rather than constants because the ink colour is
 * now a setting: a `val` would bake whichever palette happened to be active
 * when the class initialised and never change again.
 */
val Gen1Text: TextStyle
    @Composable get() = Gen1BaseText.copy(color = Gen1Palette.Ink)

val Gen1TextSmall: TextStyle
    @Composable get() = Gen1BaseText.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = Gen1Palette.Shadow,
    )

val Gen1TextLarge: TextStyle
    @Composable get() = Gen1BaseText.copy(
        fontSize = 19.sp,
        lineHeight = 26.sp,
        color = Gen1Palette.Ink,
    )

/** Everything but the colour, which is the only part a palette changes. */
private val Gen1BaseText = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 15.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.6.sp,
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
            GbText("▶", style = Gen1Text.copy(fontSize = 13.sp))
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
