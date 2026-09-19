package com.logie.gen1storage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.share.PrintBorder
import com.logie.gen1storage.share.PrintKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The printer, with one Pokémon in it.
 *
 * The Game Boy Printer printed a page, not a Pokémon — you chose which page
 * and it came off the roll. So this shows the pages themselves rather than a
 * menu naming them: the print fills the middle of a black screen, the next
 * one waits at the edge, and a finger drags between them. Every page is drawn
 * before any of them is shown, so what is on screen is the print that will be
 * sent rather than a description of it.
 *
 * Black behind, because a print is a physical thing and the app's own dithered
 * ground would read as another window. The choices sit at the foot in the
 * ordinary window, which is where every other choice in this app sits.
 */
@Composable
fun PrinterScreen(state: UiState, model: StorageViewModel, uid: String) {
    val kinds = PrintKind.entries
    var at by remember { mutableIntStateOf(0) }
    val border = state.printBorder
    val scope = rememberCoroutineScope()
    var dragPx by remember(at) { mutableStateOf(0f) }

    // Drawn once per Pokémon, border and palette — all four pages up front, so
    // dragging between them is looking at prints rather than waiting for one.
    val prints = remember(uid, border, state.paletteId, state.spriteRevision) {
        mutableStateMapOf<PrintKind, ImageBitmap>()
    }
    LaunchedEffect(uid, border, state.paletteId, state.spriteRevision) {
        kinds.forEach { kind ->
            val drawn = withContext(Dispatchers.Default) { model.cardImage(uid, kind, border) }
            drawn?.let { prints[kind] = it.asImageBitmap() }
        }
    }

    val current = kinds[at.coerceIn(0, kinds.lastIndex)]
    // How wide the roll is, so a page can be sent the whole way off it by
    // something that is not a finger.
    var width by remember { mutableStateOf(0f) }

    fun step(by: Int) {
        at = (at + by).coerceIn(0, kinds.lastIndex)
    }

    /**
     * The same move a throw makes, made by the D-pad.
     *
     * Left and right are how the cursor turns pages everywhere else in this
     * app, and with SWIPE CONTROLS on a drag never reaches the carousel below
     * — the gesture layer takes every drag on the screen and hands back a
     * direction. So the page has to be turned from here as well, and it is
     * turned by running the same settle the throw ends with rather than by
     * swapping one print for another on the spot.
     */
    fun glide(by: Int) {
        if (at + by !in 0..kinds.lastIndex) return
        if (width <= 0f) {
            step(by)
            return
        }
        scope.launch {
            val settle = Animatable(dragPx)
            settle.animateTo(-by * width, gen1PrintSettle) { dragPx = value }
            step(by)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            // A print is a thing rather than a window onto one, so it is shown
            // against nothing at all.
            .background(Color.Black)
            .padding(gen1Dp(4)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GbText(
            "PRINTER",
            style = Gen1Text.copy(color = Gen1Palette.Panel),
            maxLines = 1,
        )
        Spacer(Modifier.height(gen1Dp(2)))

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { width = it.width.toFloat() }
                // Only reached with SWIPE CONTROLS off: on, the gesture layer
                // takes the drag first and [glide] gets it instead.
                .pointerInput(kinds.size) {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    detectDragGestures(
                        onDragEnd = {
                            val past = dragPx
                            val goNext = past <= -width * FLING && at < kinds.lastIndex
                            val goPrev = past >= width * FLING && at > 0
                            scope.launch {
                                val settle = Animatable(dragPx)
                                val target = when {
                                    goNext -> -width
                                    goPrev -> width
                                    else -> 0f
                                }
                                settle.animateTo(target, gen1PrintSettle) { dragPx = value }
                                if (goNext) step(1) else if (goPrev) step(-1)
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                val settle = Animatable(dragPx)
                                settle.animateTo(0f, gen1PrintSettle) { dragPx = value }
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        // The print follows the finger: dragged left, it
                        // slides left and the next one catches up from the
                        // right; dragged right, the previous one comes back
                        // from the left. Negating this once read as content
                        // moving against the finger instead of with it,
                        // which is the "weird" a swipe should never feel.
                        val delta = amount.x
                        // Nothing past either end: the roll has a first page
                        // and a last one, and rubber-banding off them is how
                        // that is said without a message.
                        val room = when {
                            at == 0 && dragPx + delta > 0 -> delta / 3f
                            at == kinds.lastIndex && dragPx + delta < 0 -> delta / 3f
                            else -> delta
                        }
                        dragPx += room
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // The one either side, waiting at the edge. Drawn first so the
            // one in hand is over them.
            listOf(-1, 1).forEach { side ->
                kinds.getOrNull(at + side)?.let { neighbour ->
                    Print(
                        prints[neighbour],
                        Modifier.graphicsLayer {
                            translationX = dragPx + side * (size.width + gen1PeekGap)
                            alpha = NEIGHBOUR_ALPHA
                        },
                    )
                }
            }
            Print(
                prints[current],
                Modifier.graphicsLayer { translationX = dragPx },
            )
        }

        Spacer(Modifier.height(gen1Dp(2)))
        // Which of them this is, and how far along the roll — the two things
        // a carousel has to say that a list says by standing still.
        GbText(
            "${current.label}   ${at + 1}/${kinds.size}",
            style = Gen1TextSmall.copy(color = Gen1Palette.Panel),
            maxLines = 1,
        )
        Spacer(Modifier.height(gen1Dp(2)))

        val rows = listOf<Pair<String, () -> Unit>>(
            "PRINT" to { model.shareCard(uid, current, border) },
            "BORDER/${border.label}" to {
                val all = PrintBorder.entries
                model.setPrintBorder(all[(all.indexOf(border) + 1) % all.size])
            },
            "BACK" to { model.back() },
        )
        // Up and down walk the three rows; left and right turn the roll,
        // which is the same division of the D-pad the status pages use.
        val cursor = rememberCursorLayer(
            rows.size,
            onSide = { _, button ->
                glide(if (button == GbButton.RIGHT) 1 else -1)
                true
            },
        ) { rows[it].second() }
        Gen1Frame(Modifier.wrapContentWidth()) {
            rows.forEachIndexed { index, (label, act) ->
                Gen1MenuRow(label, selected = cursor == index, onSelect = {}, onConfirm = act)
            }
        }
    }
}

/** One print, as big as it can be drawn without a fractional pixel. */
@Composable
private fun Print(image: ImageBitmap?, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (image == null) return@Box
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(horizontal = gen1Dp(6)),
            contentScale = Gen1WholePixels,
            filterQuality = FilterQuality.None,
        )
    }
}

/** The gap between one print and the next, so they read as separate sheets. */
private val gen1PeekGap = 48f

/**
 * How a print settles once a drag or a D-pad step decides where it is going.
 *
 * A spring rather than the fixed 150ms tween this used to run on: a tween
 * arrives at exactly the same pace no matter how the drag that led to it
 * felt, which read as the print snapping into place rather than easing
 * there. Low stiffness with no bounce keeps the same "coming to rest"
 * character everywhere this fires — a released drag, a cancelled one, a
 * D-pad step — without ever overshooting past the neighbour it is settling
 * on.
 */
private val gen1PrintSettle = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** How far a print has to be dragged before the next one is the one in hand. */
private const val FLING = 0.3f

/** How faint the print waiting at the edge is. */
private const val NEIGHBOUR_ALPHA = 0.45f
