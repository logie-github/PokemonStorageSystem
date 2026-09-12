package com.logie.gen1storage.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

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

/**
 * How far in from each edge the border's rules actually reach, in Game Boy
 * pixels — which is not the tile, and is not the same on all four sides.
 *
 * The cartridge's own tiles are lopsided: the left rule sits two and four
 * pixels in while the right sits four and six, and the horizontal rule is one
 * pixel, a gap, then two. Anything that wants to meet the frame exactly has to
 * read these rather than assume a tile, or it leaves a band of panel showing
 * on some sides and none on others.
 */
val GEN1_RULE_LEFT = EDGE_COLUMNS.max() + 1
val GEN1_RULE_RIGHT = GEN1_TILE - EDGE_COLUMNS.min()
val GEN1_RULE_TOP = EDGE_ROWS.max() + 1
val GEN1_RULE_BOTTOM = GEN1_TILE - EDGE_ROWS.min()

/**
 * Fills the ground the border is drawn on, for a window whose contents run to
 * its edges.
 *
 * The tiles are mostly holes — the corners are ring-and-jog line work, the
 * edges are a rule and a gap — so a picture drawn underneath shows through
 * every one of them and the frame stops reading as a frame. This paints the
 * corners out whole and each edge out as far as its own rule reaches, so the
 * picture meets the frame exactly and stops.
 */
fun DrawScope.fillGen1BorderArea(fill: Color, pixel: Float) {
    val tile = pixel * GEN1_TILE
    val width = size.width
    val height = size.height

    // The corners go whole: their line work uses the full tile.
    drawRect(fill, Offset.Zero, Size(tile, tile))
    drawRect(fill, Offset(width - tile, 0f), Size(tile, tile))
    drawRect(fill, Offset(0f, height - tile), Size(tile, tile))
    drawRect(fill, Offset(width - tile, height - tile), Size(tile, tile))

    val span = (width - tile * 2).coerceAtLeast(0f)
    if (span > 0f) {
        drawRect(fill, Offset(tile, 0f), Size(span, pixel * GEN1_RULE_TOP))
        drawRect(
            fill,
            Offset(tile, height - pixel * GEN1_RULE_BOTTOM),
            Size(span, pixel * GEN1_RULE_BOTTOM),
        )
    }

    val drop = (height - tile * 2).coerceAtLeast(0f)
    if (drop > 0f) {
        drawRect(fill, Offset(0f, tile), Size(pixel * GEN1_RULE_LEFT, drop))
        drawRect(
            fill,
            Offset(width - pixel * GEN1_RULE_RIGHT, tile),
            Size(pixel * GEN1_RULE_RIGHT, drop),
        )
    }
}

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
