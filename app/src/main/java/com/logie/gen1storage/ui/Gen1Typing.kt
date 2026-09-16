package com.logie.gen1storage.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
    /**
     * A handle on this box, for a screen that drives it with its own taps.
     *
     * Null and the box says everything it has as fast as it can type it.
     * Given one, whoever owns the tap can ask for the rest of a page, then
     * the next page, and be told when there is nothing left — which is how a
     * tour knows a tap should move it on rather than turn a page.
     */
    dialogue: Gen1Dialogue? = null,
    /** Called once the last letter of the last page is down. */
    onFinished: () -> Unit = {},
) {
    // What a Game Boy does and this did not: a box holds a few lines and then
    // waits. Eight lines of somebody talking, all arriving at once, is a wall
    // of text in a window built for three.
    var width by remember { mutableIntStateOf(0) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pages = remember(lines, width, style, density) {
        if (width <= 0) listOf(lines.joinToString(" ").trim()).filter { it.isNotEmpty() }
        else paginate(lines, measurer, style, width)
    }

    var page by remember(pages) { mutableIntStateOf(0) }
    val text = pages.getOrElse(page) { "" }
    var typed by remember(text) { mutableIntStateOf(0) }
    val done = typed >= text.length
    val last = page >= pages.size - 1

    LaunchedEffect(text) {
        // Held still, the line is simply already printed: the words are the
        // graphic here, and reduce motion takes the movement, not the text.
        if (!Gen1Motion.moves(Motion.TEXT)) {
            typed = text.length
            if (last) onFinished()
            return@LaunchedEffect
        }
        typed = 0
        while (typed < text.length) {
            delay(Gen1Typing.speed.letterMillis)
            typed++
        }
        if (last) onFinished()
    }

    // Taking a tap: finish the page it is on, then turn it, and only say no
    // once there is nothing left to say.
    if (dialogue != null) {
        dialogue.more = !done || !last
        dialogue.advance = {
            when {
                !done -> typed = text.length
                !last -> page++
                else -> Unit
            }
        }
    }

    Column(modifier.onSizeChanged { width = it.width }) {
        TypedLine(text, typed, style)
        // The arrow a page that has more behind it ends on, which is the
        // cartridge's own way of saying a box is not finished with you.
        if (done && !last) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Gen1BlinkingArrow(style)
            }
        }
    }
}

/**
 * A box being talked to from outside, so one tap can mean "go on" and then
 * mean "I have read it" without the caller knowing where the pages fall.
 */
@Stable
class Gen1Dialogue {
    /** Whether a tap here would do something. */
    var more by mutableStateOf(false)
        internal set

    internal var advance: () -> Unit = {}

    /** Takes a tap. True if the box used it; false if it had nothing left. */
    fun next(): Boolean {
        if (!more) return false
        advance()
        return true
    }
}

@Composable
fun rememberGen1Dialogue(vararg keys: Any?): Gen1Dialogue =
    remember(*keys) { Gen1Dialogue() }

/** How many lines of a window a page of dialogue may fill. */
const val GEN1_DIALOGUE_LINES = 3

/**
 * The text cut into pages of no more than [GEN1_DIALOGUE_LINES] lines.
 *
 * Measured rather than guessed at: how many lines a sentence takes depends on
 * the width it is given, the size the player has the text at, and the font,
 * and a character count that was right on one phone would break mid-word on
 * the next. Words are moved to the next page whole; a single word too long
 * for a line is left where it is rather than dropped.
 */
private fun paginate(
    lines: List<String>,
    measurer: TextMeasurer,
    style: TextStyle,
    width: Int,
): List<String> {
    val whole = lines.joinToString(" ").trim()
    if (whole.isEmpty()) return emptyList()
    fun linesOf(text: String): Int =
        measurer.measure(text, style, constraints = Constraints(maxWidth = width)).lineCount

    if (linesOf(whole) <= GEN1_DIALOGUE_LINES) return listOf(whole)

    val pages = ArrayList<String>()
    val words = whole.split(" ").filter { it.isNotEmpty() }
    var current = StringBuilder()
    words.forEach { word ->
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (current.isNotEmpty() && linesOf(candidate) > GEN1_DIALOGUE_LINES) {
            pages.add(current.toString())
            current = StringBuilder(word)
        } else {
            current = StringBuilder(candidate)
        }
    }
    if (current.isNotEmpty()) pages.add(current.toString())
    return pages
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
    val dialogue = rememberGen1Dialogue(lines)
    Gen1Frame(
        // A box with more to say is turned by tapping it, and one that has
        // finished ignores the tap rather than swallowing it.
        modifier.then(
            if (dialogue.more) Modifier.pointerInput(dialogue) {
                detectTapGestures { dialogue.next() }
            } else Modifier
        ),
        opening = true,
    ) {
        Gen1TypedLines(lines, dialogue = dialogue, onFinished = { finished = true })
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
