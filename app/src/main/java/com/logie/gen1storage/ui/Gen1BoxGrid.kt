package com.logie.gen1storage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import com.logie.gen1storage.sprites.FollowerStore
import com.logie.gen1storage.sprites.SpriteStore
import com.logie.gen1storage.storage.StorageBox
import com.logie.gen1storage.storage.StorageLayout
import com.logie.gen1storage.storage.StoredPokemon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One Pokémon as its overworld follower.
 *
 * Standing still by default and walking on the spot while the cursor is on it,
 * which is the later games' box exactly: thirty of them moving at once is
 * noise, one of them moving is what tells you which one you are looking at.
 * Both frames face the player — the first standing, the other mid-step.
 */
@Composable
fun FollowerSprite(
    dexNumber: Int?,
    store: FollowerStore,
    revision: Int,
    modifier: Modifier = Modifier,
    sizeInPixels: Int = FollowerStore.SIZE,
    /** Whether this is the one the cursor is on. */
    animating: Boolean = false,
) {
    var idle by remember(dexNumber, revision) { mutableStateOf<ImageBitmap?>(null) }
    var stepping by remember(dexNumber, revision) { mutableStateOf<ImageBitmap?>(null) }
    var stepped by remember(dexNumber, revision) { mutableStateOf(false) }

    LaunchedEffect(dexNumber, revision) {
        if (dexNumber == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            idle = store.frame(dexNumber, FollowerStore.FRAME_IDLE)
            stepping = store.frame(dexNumber, FollowerStore.FRAME_STEP)
        }
    }

    LaunchedEffect(dexNumber, revision, animating) {
        // Standing is the resting state, so a Pokémon the cursor leaves is
        // back on its feet the same frame rather than frozen mid-step.
        stepped = false
        if (dexNumber == null || !animating) return@LaunchedEffect
        while (true) {
            delay(FRAME_MILLIS)
            stepped = !stepped
        }
    }

    val shown = if (stepped) stepping ?: idle else idle
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
 * The four corner brackets that mark the spot the cursor is on.
 *
 * Brackets rather than a box: a Pokémon is drawn right out to the edge of its
 * spot, and an outline around one reads as a cage rather than a selection.
 * Corners leave the sprite's own silhouette alone.
 */
@Composable
private fun CursorBrackets(modifier: Modifier = Modifier) {
    val pixel = gen1PixelPx().toFloat()
    Box(
        modifier.fillMaxSize().drawBehind {
            val arm = pixel * BRACKET_ARM_PIXELS
            val thick = pixel
            val right = size.width - thick
            val bottom = size.height - thick
            // Each corner is two strokes: one across, one down.
            drawRect(Gen1Palette.Ink, Offset(0f, 0f), Size(arm, thick))
            drawRect(Gen1Palette.Ink, Offset(0f, 0f), Size(thick, arm))
            drawRect(Gen1Palette.Ink, Offset(size.width - arm, 0f), Size(arm, thick))
            drawRect(Gen1Palette.Ink, Offset(right, 0f), Size(thick, arm))
            drawRect(Gen1Palette.Ink, Offset(0f, bottom), Size(arm, thick))
            drawRect(Gen1Palette.Ink, Offset(0f, size.height - arm), Size(thick, arm))
            drawRect(Gen1Palette.Ink, Offset(size.width - arm, bottom), Size(arm, thick))
            drawRect(Gen1Palette.Ink, Offset(right, size.height - arm), Size(thick, arm))
        }
    )
}

/**
 * The box as a grid: six across, a hundred down, scrolling.
 *
 * The gestures live on each row rather than on the grid as a whole, because a
 * hundred rows have to be a lazy list and a lazy list has no single surface to
 * put one detector on. A row knows which row it is, so a tap needs only the
 * column; a drag converts its own position into the grid's so it can still be
 * dragged over a neighbour the way it always could.
 */
@Composable
fun Gen1BoxGrid(
    box: StorageBox,
    followers: FollowerStore,
    revision: Int,
    /** Where the cursor is sitting, bracketed and walking on the spot. */
    cursorSlot: Int?,
    onTap: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cell = gen1Dp(CELL_PIXELS)
    val cellPx = with(LocalDensity.current) { cell.toPx() }
    val columns = StorageLayout.BOX_COLUMNS
    val rows = StorageLayout.BOX_ROWS
    val scroll = rememberLazyListState()

    // Every row is exactly one cell tall, so how far the list has travelled is
    // arithmetic rather than a measurement.
    fun scrolledPx(): Float =
        scroll.firstVisibleItemIndex * cellPx + scroll.firstVisibleItemScrollOffset

    /** A point on the grid's own face, scrolling included, as a spot. */
    fun slotAt(onGrid: Offset): Int? {
        val column = (onGrid.x / cellPx).toInt()
        val row = ((onGrid.y + scrolledPx()) / cellPx).toInt()
        if (column !in 0 until columns || row !in 0 until rows) return null
        return row * columns + column
    }

    var gridTop by remember { mutableFloatStateOf(0f) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    // Where the finger is, on the grid's face.
    var dragAt by remember { mutableStateOf<Offset?>(null) }

    // The box arrives a column at a time rather than all at once, which is how
    // the games change anything that fills the screen. Only on the way in: a
    // box that did this every time it was scrolled would be unusable.
    var revealed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (revealed < columns) {
            delay(COLUMN_MILLIS)
            revealed++
        }
    }

    // The cursor can walk past the bottom of what is on screen, so the list
    // follows it. Only when it has actually gone out of sight — scrolling on
    // every step would drag the whole box about under a cursor that was
    // perfectly visible where it was.
    LaunchedEffect(cursorSlot) {
        val row = (cursorSlot ?: return@LaunchedEffect) / columns
        val first = scroll.firstVisibleItemIndex
        val visible = scroll.layoutInfo.visibleItemsInfo
        val last = visible.lastOrNull()?.index ?: first
        if (row <= first) scroll.animateScrollToItem(row)
        // Stops one short of the end so the row lands inside the window rather
        // than half under its bottom edge.
        else if (row >= last) scroll.animateScrollToItem((row - (last - first) + 1).coerceAtLeast(0))
    }

    Box(
        modifier
            .width(cell * columns)
            .onGloballyPositioned { gridTop = it.positionInRoot().y }
    ) {
        LazyColumn(state = scroll) {
            items(rows, key = { it }) { row ->
                var rowTop by remember { mutableFloatStateOf(0f) }

                fun onGrid(local: Offset) = Offset(local.x, rowTop - gridTop + local.y)

                Row(
                    Modifier
                        .onGloballyPositioned { rowTop = it.positionInRoot().y }
                        // Claims the hold, so picking a Pokémon up is not also
                        // a request to go back a screen.
                        .gen1HoldRegion()
                        .pointerInput(row, columns, cellPx) {
                            detectTapGestures { at -> slotAt(onGrid(at))?.let(onTap) }
                        }
                        .pointerInput(row, columns, cellPx) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { at ->
                                    val slot = slotAt(onGrid(at))
                                    dragFrom =
                                        if (slot != null && box.slots.getOrNull(slot) != null) slot
                                        else -1
                                    dragAt = onGrid(at)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    dragAt = onGrid(change.position)
                                },
                                onDragEnd = {
                                    val to = dragAt?.let(::slotAt)
                                    if (dragFrom >= 0 && to != null && to != dragFrom) {
                                        onMove(dragFrom, to)
                                    }
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
                    repeat(columns) { column ->
                        val slot = row * columns + column
                        val stored = box.slots.getOrNull(slot)
                        // Nothing drawn around a Pokémon: six hundred ruled-off
                        // cells stop reading as a boxful and start reading as a
                        // table. Only the one under the cursor is marked.
                        Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                            if (slot == cursorSlot) CursorBrackets()
                            // The one being carried is not drawn in its old
                            // spot: it is under the finger.
                            if (stored != null && slot != dragFrom && column < revealed) {
                                FollowerSprite(
                                    stored.pokemon.species?.dexNumber,
                                    followers,
                                    revision,
                                    sizeInPixels = SPRITE_PIXELS,
                                    animating = slot == cursorSlot,
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
                    animating = true,
                )
            }
        }
    }
}

/**
 * What the cursor is standing on, read off the way the status screen reads a
 * Pokémon: the front sprite, the name, the level, the types, the number.
 *
 * No HP, no status. Those are facts about a Pokémon in play; a box is where
 * they are not in play, and putting a health bar over thirty resting Pokémon
 * only invited the question of why it never moves.
 *
 * Every line is drawn whether or not there is anything to put in it, so the
 * block is the same height on an empty spot as on a full one and the grid
 * underneath never shifts as the cursor walks.
 */
@Composable
private fun BoxHead(
    stored: StoredPokemon?,
    sprites: SpriteStore,
    spriteRevision: Int,
    modifier: Modifier = Modifier,
) {
    val pokemon = stored?.pokemon
    val types = pokemon?.species?.types.orEmpty()
    val end = Gen1Text.copy(textAlign = TextAlign.End)
    Row(modifier) {
        Column(Modifier.width(gen1Dp(HEAD_SPRITE_PIXELS))) {
            // An empty spot leaves the block blank rather than showing the
            // bracketed mark: the mark means "there is no art for this one",
            // and on an empty spot there is no "this one" to have art.
            if (pokemon == null) {
                Spacer(Modifier.size(gen1Dp(HEAD_SPRITE_PIXELS)))
            } else {
                Gen1Sprite(
                    pokemon.speciesId,
                    stored.provenance.gameVersion,
                    sprites,
                    revision = spriteRevision,
                    sizeInPixels = HEAD_SPRITE_PIXELS,
                )
            }
            Spacer(Modifier.height(gen1Dp(2)))
            GbText(
                pokemon?.species?.let { "No.%03d".format(it.dexNumber) } ?: " ",
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(gen1Dp(4)))
        Gen1CornerRule(Modifier.weight(1f)) {
            GbText(
                pokemon?.displayName?.uppercase() ?: " ",
                modifier = Modifier.fillMaxWidth(),
                style = end,
                maxLines = 1,
            )
            GbText(
                pokemon?.let { ":L${it.level}" } ?: " ",
                modifier = Modifier.fillMaxWidth(),
                style = end,
                maxLines = 1,
            )
            Spacer(Modifier.height(gen1Dp(2)))
            GbText(if (pokemon == null) " " else "TYPE1/", maxLines = 1)
            GbText(if (pokemon == null) " " else " ${types.getOrNull(0) ?: "---"}", maxLines = 1)
            GbText(if (pokemon == null) " " else "TYPE2/", maxLines = 1)
            GbText(if (pokemon == null) " " else " ${types.getOrNull(1) ?: "---"}", maxLines = 1)
        }
    }
}

/**
 * The window VIEW BOXES opens: what the cursor is on, the box, and the way
 * between boxes.
 *
 * Laid out the way the later games lay a PC out — the one being looked at
 * across the top, the grid under it — but drawn as one Generation I window, so
 * it sits on the same screen as the menu it came from rather than replacing it.
 *
 * There is one box and it scrolls, so there is nowhere else to go: the cursor
 * walks down it and the grid follows. It stops at the left and right edges
 * rather than wrapping, because a row is a row.
 */
@Composable
fun BoxGridOverlay(
    box: StorageBox,
    followers: FollowerStore,
    sprites: SpriteStore,
    revision: Int,
    /** What the caption says while a Pokémon is waiting to be put down. */
    heldName: String?,
    /**
     * Where the cursor starts. Coming back from a Pokémon's stats, that is the
     * one just looked at — otherwise the cursor would be back at the first
     * spot and the second press would ask about the wrong Pokémon.
     */
    startSlot: Int?,
    onTap: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onCancel: () -> Unit,
) {
    // The grid owns the cursor while this window is open, and a tap puts the
    // cursor where the finger went so both ways of driving it agree about
    // what is picked.
    val slots = StorageLayout.BOX_CAPACITY
    val layer = rememberCursorLayerHandle(
        count = slots,
        columns = StorageLayout.BOX_COLUMNS,
        wraps = false,
    ) { slot -> onTap(slot) }
    // Once, on the way in: after that the cursor is the player's.
    LaunchedEffect(Unit) { startSlot?.let { layer.index = it.coerceIn(0, slots - 1) } }
    val cursorSlot = layer.index

    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Gen1Layout.corner(top = true, menuSide = true),
    ) {
        Gen1Frame(Modifier.wrapContentWidth().padding(top = gen1Dp(5))) {
            BoxHead(
                stored = box.slots.getOrNull(cursorSlot),
                sprites = sprites,
                spriteRevision = revision,
                modifier = Modifier.width(gen1Dp(HEAD_PIXELS)),
            )
            Spacer(Modifier.height(gen1Dp(3)))
            GbText(box.label, modifier = Modifier.width(gen1Dp(HEAD_PIXELS)), maxLines = 1)
            GbText(
                when {
                    heldName != null -> "PUT $heldName WHERE?"
                    else -> "${box.contents.size}/${StorageLayout.BOX_CAPACITY}"
                },
                style = Gen1TextSmall,
                maxLines = 1,
            )
            Spacer(Modifier.height(gen1Dp(2)))
            Gen1BoxGrid(
                box = box,
                followers = followers,
                revision = revision,
                cursorSlot = cursorSlot,
                onTap = { slot ->
                    layer.index = slot
                    onTap(slot)
                },
                onMove = onMove,
                // Whole rows, and never taller than the screen can hold: the
                // box is a hundred rows deep and the window has to end
                // somewhere above CANCEL.
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .height(gen1Dp(CELL_PIXELS * visibleRows())),
            )
            Spacer(Modifier.height(gen1Dp(2)))
            Gen1MenuRow("CANCEL", selected = false, onSelect = {}, onConfirm = onCancel)
        }
    }
}

/**
 * How many rows of the box the window shows at once.
 *
 * Worked out from the screen rather than fixed, so the box fills a tall phone
 * and still leaves room for the block above it and CANCEL below on a short
 * one. In whole rows: half a row of Pokémon along the bottom edge reads as the
 * window having been cut off.
 */
@Composable
private fun visibleRows(): Int {
    val screen = LocalConfiguration.current.screenHeightDp
    val cell = gen1Dp(CELL_PIXELS).value
    val forTheRest = gen1Dp(HEAD_SPRITE_PIXELS + ROOM_AROUND_THE_GRID).value
    return (((screen - forTheRest) / cell).toInt()).coerceIn(MIN_ROWS_SHOWN, StorageLayout.BOX_ROWS)
}

/** The block above the grid and CANCEL below it, in game pixels. */
private const val ROOM_AROUND_THE_GRID = 60
private const val MIN_ROWS_SHOWN = 3

/** How long each column of the box waits for the one before it. */
private const val COLUMN_MILLIS = 26L

/** A spot's side, in game pixels: the sprite with a little air around it. */
private const val CELL_PIXELS = 24
private const val SPRITE_PIXELS = 16

/** How far each arm of a cursor bracket reaches along its edges. */
private const val BRACKET_ARM_PIXELS = 4

/** The front sprite over the box, at the size the games draw one. */
private const val HEAD_SPRITE_PIXELS = 56

/**
 * The window's width, which is the grid's: six spots across. Everything above
 * the grid is measured to the same line so the window is one column rather
 * than a stack of differently sized blocks.
 */
private const val HEAD_PIXELS = CELL_PIXELS * StorageLayout.BOX_COLUMNS

/** How long a follower holds each frame while the cursor is on it. */
private const val FRAME_MILLIS = 500L
