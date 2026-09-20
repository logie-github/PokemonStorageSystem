package com.logie.gen1storage.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * The Generation I window border, tile for tile.
 *
 * These are not an approximation. They are tiles `$19`–`$1E` of
 * `gfx/font/font_extra.png` in pret/pokered — the set `TextBoxGraphics` points
 * at — transcribed a pixel at a time, and they match a screenshot of the PC
 * exactly. Every oddity in them is the cartridge's own: the horizontal rule is
 * one pixel, a gap, then two, while the vertical rule is one, a gap, then one;
 * the left edge sits two and four pixels in while the right sits four and six;
 * and each corner jogs its rules a pixel sideways to meet the ring. Reproducing
 * those is the difference between the real frame and something that merely
 * resembles it.
 *
 * A window is one tile of border on every side, exactly as the games lay it
 * out, so nothing here is scaled or rounded — it is drawn at a whole number of
 * device pixels per Game Boy pixel and stays square at any size.
 */
private val CORNER_TOP_LEFT = listOf(
    "........",
    "...##...",
    "..#.##.#",
    ".######.",
    ".#....#.",
    "..#..#.#",
    "...##.#.",
    "...#.#..",
)

private val CORNER_TOP_RIGHT = listOf(
    "........",
    "...##...",
    "#.#.##..",
    ".######.",
    ".#....#.",
    "#.#..#..",
    ".#.##...",
    "..#.#...",
)

private val CORNER_BOTTOM_LEFT = listOf(
    "...#.#..",
    "...##.#.",
    "..#.##.#",
    ".######.",
    ".#....#.",
    "..#..#.#",
    "...##...",
    "........",
)

private val CORNER_BOTTOM_RIGHT = listOf(
    "..#.#...",
    ".#.##...",
    "#.#.##..",
    ".######.",
    ".#....#.",
    "#.#..#..",
    "...##...",
    "........",
)

/** Rows of the horizontal edge tile that are filled: one, a gap, then two. */
private val EDGE_ROWS = listOf(2, 4, 5)

/** Columns of the vertical edge tile that are filled. */
private val EDGE_COLUMNS = listOf(2, 4)

/** One Game Boy pixel is an eighth of a tile. */
const val GEN1_TILE = 8

/** A horizontal run of set pixels in a tile, as (x, y, length). */
private data class Run(val x: Int, val y: Int, val length: Int)

/** Merges each row into runs, so a corner is a dozen rectangles, not sixty. */
private fun runsOf(tile: List<String>): List<Run> = buildList {
    tile.forEachIndexed { y, row ->
        var x = 0
        while (x < row.length) {
            if (row[x] == '#') {
                var end = x
                while (end + 1 < row.length && row[end + 1] == '#') end++
                add(Run(x, y, end - x + 1))
                x = end + 1
            } else {
                x++
            }
        }
    }
}

private val TOP_LEFT_RUNS = runsOf(CORNER_TOP_LEFT)
private val TOP_RIGHT_RUNS = runsOf(CORNER_TOP_RIGHT)
private val BOTTOM_LEFT_RUNS = runsOf(CORNER_BOTTOM_LEFT)
private val BOTTOM_RIGHT_RUNS = runsOf(CORNER_BOTTOM_RIGHT)

/**
 * Draws the border around the whole of the current draw area.
 *
 * [pixel] is the size of one Game Boy pixel in device pixels; it is a whole
 * number, so every rule and every corner lands on a pixel boundary.
 */
fun DrawScope.drawGen1Border(ink: Color, pixel: Float) {
    val tile = pixel * GEN1_TILE
    val width = size.width
    val height = size.height

    fun corner(runs: List<Run>, originX: Float, originY: Float) {
        runs.forEach { run ->
            drawRect(
                ink,
                Offset(originX + run.x * pixel, originY + run.y * pixel),
                Size(run.length * pixel, pixel),
            )
        }
    }

    // The straight edges are uniform along their length, so each rule is one
    // rectangle however long the window is rather than a tile repeated.
    val spanWidth = (width - tile * 2).coerceAtLeast(0f)
    if (spanWidth > 0f) {
        EDGE_ROWS.forEach { row ->
            drawRect(ink, Offset(tile, row * pixel), Size(spanWidth, pixel))
            drawRect(ink, Offset(tile, height - tile + row * pixel), Size(spanWidth, pixel))
        }
    }

    val spanHeight = (height - tile * 2).coerceAtLeast(0f)
    if (spanHeight > 0f) {
        EDGE_COLUMNS.forEach { column ->
            drawRect(ink, Offset(column * pixel, tile), Size(pixel, spanHeight))
            drawRect(ink, Offset(width - tile + column * pixel, tile), Size(pixel, spanHeight))
        }
    }

    corner(TOP_LEFT_RUNS, 0f, 0f)
    corner(TOP_RIGHT_RUNS, width - tile, 0f)
    corner(BOTTOM_LEFT_RUNS, 0f, height - tile)
    corner(BOTTOM_RIGHT_RUNS, width - tile, height - tile)
}

/**
 * The same border, drawn from a mod's own art instead of the cartridge's.
 *
 * [tileset] is a single square image cut into an even 3x3 grid of cells: the
 * four corners, blitted once each; the four edge cells, tiled along their
 * own span the way [drawGen1Border]'s rules run the length of the window;
 * and the centre cell, never drawn at all — the panel fill underneath
 * already covers it, so a mod's own tileset only has to draw a frame, not a
 * whole window.
 */
fun DrawScope.drawGen1BorderBitmap(tileset: ImageBitmap, pixel: Float) {
    val cell = tileset.width / 3
    if (cell <= 0 || tileset.height < cell * 3) return
    val tile = pixel * GEN1_TILE
    val width = size.width
    val height = size.height

    fun blit(cellX: Int, cellY: Int, at: Offset, extent: Size) {
        drawImage(
            image = tileset,
            srcOffset = IntOffset(cellX * cell, cellY * cell),
            srcSize = IntSize(cell, cell),
            dstOffset = IntOffset(at.x.roundToInt(), at.y.roundToInt()),
            dstSize = IntSize(
                extent.width.roundToInt().coerceAtLeast(1),
                extent.height.roundToInt().coerceAtLeast(1),
            ),
            // Whole cells scaled to whole tiles; smoothing would blur the
            // one thing a pixel tileset is trying to keep sharp.
            filterQuality = FilterQuality.None,
        )
    }

    // A trailing partial tile is squashed into what room is left rather than
    // clipped — visible only when a window's span is not a whole number of
    // tiles, which for windows sized off this app's own Game Boy grid it
    // always is.
    fun tileAcross(cellX: Int, cellY: Int, left: Float, top: Float, span: Float) {
        var travelled = 0f
        while (travelled < span) {
            val length = minOf(tile, span - travelled)
            blit(cellX, cellY, Offset(left + travelled, top), Size(length, tile))
            travelled += tile
        }
    }

    fun tileDown(cellX: Int, cellY: Int, left: Float, top: Float, span: Float) {
        var travelled = 0f
        while (travelled < span) {
            val length = minOf(tile, span - travelled)
            blit(cellX, cellY, Offset(left, top + travelled), Size(tile, length))
            travelled += tile
        }
    }

    val spanWidth = (width - tile * 2).coerceAtLeast(0f)
    val spanHeight = (height - tile * 2).coerceAtLeast(0f)

    if (spanWidth > 0f) {
        tileAcross(1, 0, tile, 0f, spanWidth)
        tileAcross(1, 2, tile, height - tile, spanWidth)
    }
    if (spanHeight > 0f) {
        tileDown(0, 1, 0f, tile, spanHeight)
        tileDown(2, 1, width - tile, tile, spanHeight)
    }

    blit(0, 0, Offset(0f, 0f), Size(tile, tile))
    blit(2, 0, Offset(width - tile, 0f), Size(tile, tile))
    blit(0, 2, Offset(0f, height - tile), Size(tile, tile))
    blit(2, 2, Offset(width - tile, height - tile), Size(tile, tile))
}
