package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.delay

/**
 * Text that arrives a letter at a time, the way the games print it.
 *
 * The line's full width is held from the first frame — the invisible rest of
 * it is drawn in the background colour rather than left out — so nothing
 * reflows or jumps as the letters land. That is the whole trick: a window
 * that grows while it types reads as a bug, not as typing.
 */
@Composable
fun Gen1TypedLines(
    lines: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = Gen1Text,
    /** Called once the last letter is down, for whatever waits on it. */
    onFinished: () -> Unit = {},
) {
    val whole = remember(lines) { lines.sumOf { it.length } }
    var typed by remember(lines) { mutableIntStateOf(0) }

    LaunchedEffect(lines) {
        // Held still, the line is simply already printed: the words are the
        // graphic here, and reduce motion takes the movement, not the text.
        if (!Gen1Motion.moves(Motion.TEXT)) {
            typed = whole
            onFinished()
            return@LaunchedEffect
        }
        typed = 0
        while (typed < whole) {
            delay(Gen1Typing.speed.letterMillis)
            typed++
        }
        onFinished()
    }

    Column(modifier) {
        var consumed = 0
        lines.forEach { line ->
            val shown = (typed - consumed).coerceIn(0, line.length)
            consumed += line.length
            TypedLine(line, shown, style)
        }
    }
}

/** One line, with the part not yet typed drawn in no colour at all. */
@Composable
private fun TypedLine(line: String, shown: Int, style: TextStyle) {
    // The whole line, always, with the part that has not arrived yet made
    // invisible where it stands. It used to be two Texts side by side — what
    // was typed, then the rest of it in the window's own colour — and a line
    // long enough to wrap then had each half wrapping on its own: three lines
    // of remainder became two of typed and two of remainder as the split
    // moved, and the window grew and shrank a row at a time while it spoke.
    // One piece of text is laid out once, at its finished size, whatever is
    // showing of it.
    //
    // Invisible rather than absent, and rather than the window's colour: the
    // space the letters will land in is held either way, and transparent
    // holds it over any fill.
    //
    // No line cap: a question that runs past the window should wrap, not be
    // cut off mid-word with an ellipsis.
    val text = remember(line, shown) {
        buildAnnotatedString {
            append(line)
            if (shown < line.length) {
                addStyle(SpanStyle(color = Color.Transparent), shown, line.length)
            }
        }
    }
    GbText(text, style = style)
}

/**
 * A dialogue box whose lines type themselves, with whatever follows them held
 * back until they are down — the games do not offer a choice before they have
 * finished saying what it is about.
 */
@Composable
fun Gen1TypedBox(
    lines: List<String>,
    modifier: Modifier = Modifier,
    after: @Composable ColumnScope.() -> Unit = {},
) {
    var finished by remember(lines) { mutableStateOf(false) }
    Gen1Frame(modifier, opening = true) {
        Gen1TypedLines(lines, onFinished = { finished = true })
        if (finished) after()
    }
}

/**
 * How fast the text prints, for the drawing code to read.
 *
 * Snapshot state on an object rather than a parameter threaded through every
 * window, for the same reason the palette is: it is one decision the whole
 * app has to agree on, and passing it around would let any window quietly
 * disagree.
 */
object Gen1Typing {
    var speed by mutableStateOf(TextSpeed.DEFAULT)
}

/**
 * The ▼ the games blink at the bottom of a box that has more to say.
 *
 * On for two thirds of its cycle and off for a third, which is roughly the
 * duty the cartridge blinks it at.
 */
@Composable
fun Gen1BlinkingArrow(style: TextStyle = Gen1Text) {
    var lit by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(if (lit) BLINK_ON_MILLIS else BLINK_OFF_MILLIS)
            lit = !lit
        }
    }
    GbText("▼", style = if (lit) style else style.copy(color = Gen1Palette.Panel))
}

private const val BLINK_ON_MILLIS = 420L
private const val BLINK_OFF_MILLIS = 220L

/**
 * The choice a window offers, as the games offer one: rows with a cursor
 * running down them, not words laid side by side.
 *
 * They stack rather than sitting in a row because a row of them has no room
 * for a cursor, and without a cursor there is nothing saying which one the
 * A button would take.
 */
@Composable
fun Gen1ChoiceRows(choices: List<Pair<String, () -> Unit>>) {
    val cursor = rememberCursorLayer(choices.size) { choices[it].second() }
    choices.forEachIndexed { index, (label, action) ->
        Gen1MenuRow(label, cursor == index, {}, action)
    }
}
