package com.logie.gen1storage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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

    fun step(by: Int) {
        at = (at + by).coerceIn(0, kinds.lastIndex)
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
                                settle.animateTo(target, tween(SETTLE)) { dragPx = value }
                                if (goNext) step(1) else if (goPrev) step(-1)
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                val settle = Animatable(dragPx)
                                settle.animateTo(0f, tween(SETTLE)) { dragPx = value }
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        // Nothing past either end: the roll has a first page
                        // and a last one, and rubber-banding off them is how
                        // that is said without a message.
                        val room = when {
                            at == 0 && dragPx + amount.x > 0 -> amount.x / 3f
                            at == kinds.lastIndex && dragPx + amount.x < 0 -> amount.x / 3f
                            else -> amount.x
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
        val cursor = rememberCursorLayer(rows.size) { rows[it].second() }
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

/** How far a print has to be dragged before the next one is the one in hand. */
private const val FLING = 0.3f

/** How faint the print waiting at the edge is. */
private const val NEIGHBOUR_ALPHA = 0.45f

private const val SETTLE = 150
