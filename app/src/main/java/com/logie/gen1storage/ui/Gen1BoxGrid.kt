package com.logie.gen1storage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.logie.gen1storage.sprites.FollowerStore
import com.logie.gen1storage.storage.StorageBox
import com.logie.gen1storage.storage.StorageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * One Pokémon as its overworld follower, standing still and occasionally
 * looking around.
 *
 * The sheet's first frame is the one facing the player, and its third is the
 * same Pokémon turned sideways. Holding the first and flicking to the third
 * now and then is the whole animation: a boxful of them all moving at once
 * would be noise, so each waits its own random while first.
 */
@Composable
fun FollowerSprite(
    dexNumber: Int?,
    store: FollowerStore,
    revision: Int,
    modifier: Modifier = Modifier,
    sizeInPixels: Int = FollowerStore.SIZE,
) {
    var idle by remember(dexNumber, revision) { mutableStateOf<ImageBitmap?>(null) }
    var turned by remember(dexNumber, revision) { mutableStateOf<ImageBitmap?>(null) }
    var frame by remember(dexNumber, revision) { mutableIntStateOf(FollowerStore.FRAME_IDLE) }

    LaunchedEffect(dexNumber, revision) {
        if (dexNumber == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val one = store.frame(dexNumber, FollowerStore.FRAME_IDLE)
            val three = store.frame(dexNumber, FollowerStore.FRAME_TURNED)
            idle = one
            turned = three
        }
    }

    LaunchedEffect(dexNumber, revision) {
        if (dexNumber == null) return@LaunchedEffect
        while (true) {
            // Its own wait, drawn fresh each time, so two of the same species
            // side by side never fall into step.
            delay(Random.nextLong(QUIET_MIN_MILLIS, QUIET_MAX_MILLIS + 1))
            frame = FollowerStore.FRAME_IDLE
            delay(FRAME_MILLIS)
            frame = FollowerStore.FRAME_TURNED
            delay(FRAME_MILLIS)
            frame = FollowerStore.FRAME_IDLE
            delay(FRAME_MILLIS)
        }
    }

    val shown = if (frame == FollowerStore.FRAME_TURNED) turned ?: idle else idle
    Box(modifier.size(gen1Dp(sizeInPixels)), contentAlignment = Alignment.Center) {
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                // These are 16 pixels square. Anything but nearest neighbour
                // would turn them to soup at this magnification.
                filterQuality = FilterQuality.None,
            )
        } else {
            OccupiedMark()
        }
    }
}

/**
 * What a spot shows when the follower sheets are not on the device: a filled
 * marker, so the box still reads as arranged rather than empty.
 */
@Composable
private fun OccupiedMark() {
    val pixel = gen1PixelPx().toFloat()
    Box(
        Modifier.fillMaxSize().drawBehind {
            val inset = pixel * 4
            drawRect(
                Gen1Palette.Ink,
                Offset(inset, inset),
                Size(size.width - inset * 2, size.height - inset * 2),
            )
        }
    )
}

/**
 * A box as the games draw one: six across, five down, every Pokémon on its own
 * spot.
 *
 * The gestures live on the grid rather than on each cell, and the spot is
 * worked out from where the finger is. One detector over a fixed grid is both
 * simpler and the only way a drag can know what it is being dragged over —
 * per-cell handlers only ever hear about the cell the drag started in.
 */
@Composable
fun Gen1BoxGrid(
    box: StorageBox,
    followers: FollowerStore,
    revision: Int,
    selectedSlot: Int?,
    /** The one picked up and waiting to be put down, from MOVE or a hold. */
    heldSlot: Int?,
    onTap: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cell = gen1Dp(CELL_PIXELS)
    val cellPx = with(LocalDensity.current) { cell.toPx() }
    val columns = StorageLayout.BOX_COLUMNS
    val rows = StorageLayout.BOX_ROWS

    fun slotAt(position: Offset): Int? {
        val column = (position.x / cellPx).toInt()
        val row = (position.y / cellPx).toInt()
        if (column !in 0 until columns || row !in 0 until rows) return null
        return row * columns + column
    }

    var dragFrom by remember(box.index) { mutableIntStateOf(-1) }
    var dragAt by remember(box.index) { mutableStateOf<Offset?>(null) }

    Box(
        modifier
            .size(width = cell * columns, height = cell * rows)
            // Claims the hold, so picking a Pokémon up is not also a request
            // to go back a screen.
            .gen1HoldRegion()
            .pointerInput(box.index, columns, rows, cellPx) {
                detectTapGestures { position -> slotAt(position)?.let(onTap) }
            }
            .pointerInput(box.index, columns, rows, cellPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        val slot = slotAt(position)
                        dragFrom = if (slot != null && box.slots.getOrNull(slot) != null) slot else -1
                        dragAt = position
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragAt = change.position
                    },
                    onDragEnd = {
                        val to = dragAt?.let(::slotAt)
                        if (dragFrom >= 0 && to != null && to != dragFrom) onMove(dragFrom, to)
                        dragFrom = -1
                        dragAt = null
                    },
                    onDragCancel = {
                        dragFrom = -1
                        dragAt = null
                    },
                )
            }
    ) {
        Column {
            repeat(rows) { row ->
                Row {
                    repeat(columns) { column ->
                        val slot = row * columns + column
                        val stored = box.slots.getOrNull(slot)
                        Box(
                            Modifier
                                .size(cell)
                                .drawBehind {
                                    // An outline, never a fill: the cartridge
                                    // marks a choice with a cursor and leaves
                                    // what is under it readable.
                                    if (slot == selectedSlot || slot == heldSlot) {
                                        val rule = gen1Pixel(this).toFloat()
                                        val colour =
                                            if (slot == heldSlot) Gen1Palette.Shadow else Gen1Palette.Ink
                                        drawRect(colour, Offset.Zero, Size(size.width, rule))
                                        drawRect(
                                            colour,
                                            Offset(0f, size.height - rule),
                                            Size(size.width, rule),
                                        )
                                        drawRect(colour, Offset.Zero, Size(rule, size.height))
                                        drawRect(
                                            colour,
                                            Offset(size.width - rule, 0f),
                                            Size(rule, size.height),
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // The one being carried is not drawn in its old
                            // spot: it is under the finger.
                            if (stored != null && slot != dragFrom) {
                                FollowerSprite(
                                    stored.pokemon.species?.dexNumber,
                                    followers,
                                    revision,
                                    sizeInPixels = SPRITE_PIXELS,
                                )
                            }
                        }
                    }
                }
            }
        }

        val carried = dragAt
        val carriedMon = box.slots.getOrNull(dragFrom)
        if (carried != null && carriedMon != null) {
            val half = with(LocalDensity.current) { (cellPx / 2).toInt() }
            Box(
                Modifier.offset {
                    IntOffset(carried.x.toInt() - half, carried.y.toInt() - half)
                }
            ) {
                FollowerSprite(
                    carriedMon.pokemon.species?.dexNumber,
                    followers,
                    revision,
                    sizeInPixels = SPRITE_PIXELS,
                )
            }
        }
    }
}

/**
 * The window VIEW BOXES opens: the box, its grid, and the way between boxes.
 *
 * Laid out the way the later games lay a PC out — the box across the top, the
 * grid under it — but drawn as one Generation I window, so it sits on the
 * same screen as the menu it came from rather than replacing it.
 */
@Composable
fun BoxGridOverlay(
    box: StorageBox,
    followers: FollowerStore,
    revision: Int,
    selectedSlot: Int?,
    heldSlot: Int?,
    /** What the caption says while a Pokémon is waiting to be put down. */
    heldName: String?,
    onTap: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onPreviousBox: () -> Unit,
    onNextBox: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = true, menuSide = true),
    ) {
        Gen1Frame(Modifier.wrapContentWidth().padding(top = gen1Dp(5))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.gen1Clickable(onClick = onPreviousBox)) { GbText("◀") }
                Spacer(Modifier.width(gen1Dp(3)))
                GbText(box.label, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(gen1Dp(3)))
                Box(Modifier.gen1Clickable(onClick = onNextBox)) { GbText("▶") }
            }
            Spacer(Modifier.height(gen1Dp(2)))
            GbText(
                when {
                    heldName != null -> "PUT $heldName WHERE?"
                    else -> "${box.contents.size}/${StorageLayout.BOX_CAPACITY}"
                },
                style = Gen1TextSmall,
            )
            Spacer(Modifier.height(gen1Dp(2)))
            Gen1BoxGrid(
                box = box,
                followers = followers,
                revision = revision,
                selectedSlot = selectedSlot,
                heldSlot = heldSlot,
                onTap = onTap,
                onMove = onMove,
            )
            Spacer(Modifier.height(gen1Dp(2)))
            Gen1MenuRow("CANCEL", selected = false, onSelect = {}, onConfirm = onCancel)
        }
    }
}

/** The shared grid's pixel, as a [androidx.compose.ui.graphics.drawscope.DrawScope] sees it. */
private fun gen1Pixel(scope: androidx.compose.ui.graphics.drawscope.DrawScope): Int =
    (scope.size.height / CELL_PIXELS).toInt().coerceAtLeast(1)

/** A spot's side, in game pixels: the sprite with a little air around it. */
private const val CELL_PIXELS = 18
private const val SPRITE_PIXELS = 16

/** How long a follower stands still before glancing about. */
private const val QUIET_MIN_MILLIS = 5_000L
private const val QUIET_MAX_MILLIS = 30_000L
private const val FRAME_MILLIS = 500L
