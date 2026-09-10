package com.logie.gen1storage.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * How large each part of the interface is drawn.
 *
 * Four separate scales rather than one, because they do not want to move
 * together: a bigger sprite on a small screen costs a stat row, and a heavier
 * border costs nothing but looks wrong beside small type. Each is a whole
 * multiple, so nothing lands on a fractional pixel.
 *
 * Snapshot state for the same reason [Gen1Palette] is: the border width is
 * read inside `drawBehind` lambdas, which are not composables and cannot
 * observe anything else.
 */
object Gen1Metrics {

    /** Type. */
    var text by mutableIntStateOf(DEFAULT)

    /** The rules and beads a window is drawn from. */
    var border by mutableIntStateOf(DEFAULT)

    /** The status and moves pages: their sprite column, gutters and padding. */
    var status by mutableIntStateOf(DEFAULT)

    /** The sprite itself, wherever one is drawn. */
    var sprite by mutableIntStateOf(DEFAULT)

    /**
     * The base every scale multiplies, chosen so [DEFAULT] is the size the app
     * was drawn at before any of this was adjustable. That leaves 1x genuinely
     * smaller and 4x genuinely larger, rather than only ever growing.
     */
    const val DEFAULT = 2
    val CHOICES = listOf(1, 2, 3, 4)

    fun clamp(scale: Int): Int = scale.coerceIn(CHOICES.first(), CHOICES.last())
}
