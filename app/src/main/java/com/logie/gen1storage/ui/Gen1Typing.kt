package com.logie.gen1storage.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.delay
import kotlin.math.floor

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
     * Null and the box takes its own: a tap on it finishes the line and then
     * turns the page. Given one, whoever owns the tap can ask for the rest of
     * a page, then the next page, and be told when there is nothing left —
     * which is how a tour knows a tap should move it on rather than turn a
     * page.
     */
    dialogue: Gen1Dialogue? = null,
    /**
     * How many lines of room to hold open, whatever is currently in the box.
     *
     * Null and the box is as tall as the page inside it, which is what a
     * caption in the corner wants. Given a number it is always exactly that
     * tall: a window that grew a row when he said a longer sentence and shrank
     * again on the next one is the one thing a Game Boy's text box never did.
     * Count the arrow's row in the number — it is pinned to the bottom of the
     * room held here rather than added under it.
     */
    holdLines: Int? = null,
    /** Called once the last letter of the last page is down. */
    onFinished: () -> Unit = {},
) {
    val state = dialogue ?: remember { Gen1Dialogue() }
    // What a Game Boy does and this did not: a box holds a few lines and then
    // waits. Eight lines of somebody talking, all arriving at once, is a wall
    // of text in a window built for three.
    var width by remember { mutableIntStateOf(0) }

    // How many characters fit across, counted rather than measured.
    //
    // This face advances every glyph by a whole em — see the note on
    // [Gen1FontFamily] — so the columns are simply the width over the type
    // size, and the wrap can be worked out from the string alone.
    //
    // It used to ask a TextMeasurer instead, and that is what was dropping
    // half of what Bill said. A measurer resolves a font synchronously and
    // falls back to the platform's own the moment the resource font is not
    // loaded yet, while the Text beside it waits for the real one and draws
    // with it. The fallback is proportional, so it fitted about twice as many
    // characters on a line as the box really holds: pages came out around six
    // lines long, the three that did not fit were cut off by the line cap
    // below, and the tour jumped from "IT WORKS ON THE SAME" to "REGION."
    // Worst of all it only happened before the font was cached, which is to
    // say on the opening beats and nowhere else.
    val density = LocalDensity.current
    val columns = remember(width, style.fontSize, density) {
        val em = with(density) { style.fontSize.toPx() }
        if (em <= 0f || width <= 0) 0 else floor(width / em).toInt()
    }

    // Paginated in an effect rather than during composition. Everything here
    // writes to state something else reads in the same frame, and a write
    // during composition invalidates the frame that is writing it — which is
    // a box that retypes its first line for ever.
    LaunchedEffect(lines, columns) {
        if (columns <= 0) return@LaunchedEffect
        state.reset(paginate(lines, columns))
    }

    LaunchedEffect(state, state.pages, state.page) {
        val text = state.text
        // Held still, the line is simply already printed: the words are the
        // graphic here, and reduce motion takes the movement, not the text.
        if (!Gen1Motion.moves(Motion.TEXT)) {
            state.typed = text.length
            if (state.onLastPage) onFinished()
            return@LaunchedEffect
        }
        state.typed = 0
        while (state.typed < text.length) {
            delay(Gen1Typing.speed.letterMillis)
            // A tap may have finished the page underneath this loop, which is
            // what `next` does, and then there is nothing left to type.
            if (state.typed >= text.length) break
            state.typed++
        }
        if (state.onLastPage) onFinished()
    }

    // The arrow a page with more behind it ends on, which is how the cartridge
    // says a box is not finished with you.
    val arrow = state.pageIsDown && !state.onLastPage
    Box(
        modifier
            // The width to wrap inside is the one this window is *offering*,
            // not the one the text currently fills. Reading the filled size
            // is a deadlock and was one: the box holds nothing until it has
            // been paginated, a box holding nothing measures nought across,
            // and nought is refused as a width to paginate against — so it
            // stayed empty, and every typed box in the app printed nothing
            // at all. What is offered is known before there is any text.
            .layout { measurable, constraints ->
                if (constraints.hasBoundedWidth) width = constraints.maxWidth
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            // Held open at a set height where one was asked for, so the window
            // is the same window from the first sentence to the last. The
            // width goes with it: the arrow belongs in the corner of the box
            // rather than at the end of whatever was said.
            .then(
                if (holdLines == null) Modifier
                else Modifier
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { style.lineHeight.toDp() } * holdLines)
            )
            // A box nobody else is driving turns its own pages.
            .then(
                if (dialogue == null) Modifier.pointerInput(state) {
                    detectTapGestures { state.next() }
                } else Modifier
            )
    ) {
        Column {
            TypedLine(state.text, state.typed, style)
            // With no room held there is nothing to sit the arrow against, so
            // it goes on a row of its own under the text, as it always did.
            if (holdLines == null && arrow) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Gen1BlinkingArrow(style)
                }
            }
        }
        // And where room *is* held, it sits in the floor of it — the corner of
        // the window, where the cartridge always put it, rather than wandering
        // up the box behind a shorter page.
        if (holdLines != null && arrow) {
            Box(Modifier.align(Alignment.BottomEnd)) { Gen1BlinkingArrow(style) }
        }
    }
}

/**
 * A box of dialogue, and where it has got to.
 *
 * Held out here rather than inside the composable so that one tap can mean
 * "go on" and then mean "I have read it" without whoever owns the tap knowing
 * where the pages fall. Every field is snapshot state written from effects and
 * from [next], never during composition: [more] is a read, so a screen can ask
 * it while it is drawing.
 */
@Stable
class Gen1Dialogue {

    internal var pages by mutableStateOf<List<String>>(emptyList())
        private set
    internal var page by mutableIntStateOf(0)
        private set
    internal var typed by mutableIntStateOf(0)

    internal val text: String get() = pages.getOrElse(page) { "" }

    /** Whether the page on screen has finished printing. */
    internal val pageIsDown: Boolean get() = typed >= text.length

    internal val onLastPage: Boolean get() = page >= pages.size - 1

    /** Whether a tap here would do anything. */
    val more: Boolean get() = !pageIsDown || !onLastPage

    internal fun reset(newPages: List<String>) {
        // A remeasure that comes out the same is not a new thing to say. The
        // pages are the key the typing runs off, so setting them to an equal
        // list would clear what has been printed without starting it again.
        if (newPages == pages) return
        pages = newPages
        page = 0
        typed = 0
    }

    /**
     * Takes a tap: finishes the line being printed, then turns the page.
     * False once there is nothing left, so the caller can use it for whatever
     * a tap means after that.
     */
    fun next(): Boolean {
        if (!more) return false
        if (!pageIsDown) typed = text.length else page++
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
 * the next. Words move to the next page whole; a single word too long for a
 * line is left where it is rather than dropped.
 *
 * Each entry in [lines] is its own thing being said and always opens its own
 * page, even when it would fit on the tail of the one before it. Joining them
 * first and rewrapping the result as one continuous ribbon of text used to
 * let a short line's last few words share a page with the next line's first
 * few — a sentence ending and a fresh one beginning back to back in the same
 * box, mid-page, which reads as one run-on thought instead of two.
 */
internal fun paginate(lines: List<String>, columns: Int): List<String> {
    val pages = ArrayList<String>()
    lines.forEach { line ->
        val whole = line.trim()
        if (whole.isEmpty()) return@forEach
        if (wrappedLineCount(whole, columns) <= GEN1_DIALOGUE_LINES) {
            pages.add(whole)
            return@forEach
        }
        val words = whole.split(" ").filter { it.isNotEmpty() }
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && wrappedLineCount(candidate, columns) > GEN1_DIALOGUE_LINES) {
                pages.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) pages.add(current.toString())
    }
    return pages
}

/**
 * How many lines [text] wraps to in a box [columns] characters across.
 *
 * Greedy, the way every renderer wraps: a word goes on the current line while
 * it and the space before it still fit, and starts a new one when it does
 * not. A word longer than the whole line is broken where it runs out of room
 * rather than dropped, which is what the renderer does with one too.
 */
internal fun wrappedLineCount(text: String, columns: Int): Int {
    if (columns <= 0) return 1
    var lines = 1
    var filled = 0
    text.split(" ").filter { it.isNotEmpty() }.forEach { word ->
        if (word.length > columns) {
            if (filled > 0) {
                lines++
                filled = 0
            }
            val rows = (word.length + columns - 1) / columns
            lines += rows - 1
            filled = word.length - (rows - 1) * columns
            return@forEach
        }
        val needed = if (filled == 0) word.length else filled + 1 + word.length
        if (needed <= columns) {
            filled = needed
        } else {
            lines++
            filled = word.length
        }
    }
    return lines
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
    // paginate() promises every page fits in GEN1_DIALOGUE_LINES, but nothing
    // before this held it to that: a page it got wrong by even one line had
    // nothing stopping it drawing straight past the box's own fixed height,
    // which is a held-open window with no floor. Capped here as well, so a
    // misjudged split reads as ellipsis rather than as running off the window.
    GbText(text, style = style, maxLines = GEN1_DIALOGUE_LINES)
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
