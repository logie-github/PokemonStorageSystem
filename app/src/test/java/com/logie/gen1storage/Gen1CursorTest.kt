package com.logie.gen1storage

import com.logie.gen1storage.ui.GbButton
import com.logie.gen1storage.ui.Gen1Cursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cursor a swipe drives.
 *
 * Worth testing on its own because the thing it gets wrong is silent: an
 * off-by-one confirms the row above the one with the arrow on it, and on a
 * screen where one of those rows is RELEASE that matters.
 */
class Gen1CursorTest {

    private fun layer(count: Int, columns: Int = 1, onConfirm: (Int) -> Unit = {}) =
        Gen1Cursor.Layer(count, columns, onConfirm)

    @Test
    fun `the topmost menu owns the cursor`() {
        val cursor = Gen1Cursor()
        val menu = layer(4)
        val list = layer(9)
        cursor.push(menu)
        cursor.push(list)

        cursor.move(GbButton.DOWN)

        assertEquals("the list moved", 1, list.index)
        assertEquals("the menu behind it did not", 0, menu.index)
    }

    @Test
    fun `closing a menu hands the cursor back`() {
        val cursor = Gen1Cursor()
        val menu = layer(4)
        val list = layer(9)
        cursor.push(menu)
        cursor.push(list)
        cursor.remove(list)

        cursor.move(GbButton.DOWN)

        assertEquals(1, menu.index)
    }

    @Test
    fun `the cursor wraps, as the games' menus do`() {
        val cursor = Gen1Cursor()
        val menu = layer(3)
        cursor.push(menu)

        cursor.move(GbButton.UP)
        assertEquals("up from the first row is the last", 2, menu.index)

        cursor.move(GbButton.DOWN)
        assertEquals(0, menu.index)
    }

    @Test
    fun `left and right do nothing in a single column`() {
        val cursor = Gen1Cursor()
        val menu = layer(5)
        cursor.push(menu)

        cursor.move(GbButton.RIGHT)
        cursor.move(GbButton.LEFT)

        assertEquals(0, menu.index)
    }

    @Test
    fun `a grid moves a row at a time up and down`() {
        val cursor = Gen1Cursor()
        val grid = layer(9, columns = 3)
        cursor.push(grid)

        cursor.move(GbButton.DOWN)
        assertEquals(3, grid.index)

        cursor.move(GbButton.RIGHT)
        assertEquals(4, grid.index)

        cursor.move(GbButton.UP)
        assertEquals(1, grid.index)
    }

    @Test
    fun `confirming takes the row the cursor is actually on`() {
        val cursor = Gen1Cursor()
        var taken = -1
        val menu = layer(4) { taken = it }
        cursor.push(menu)

        cursor.move(GbButton.DOWN)
        cursor.move(GbButton.DOWN)
        cursor.confirm()

        assertEquals(2, taken)
    }

    @Test
    fun `an empty menu neither moves nor confirms`() {
        val cursor = Gen1Cursor()
        var taken = false
        val empty = layer(0) { taken = true }
        cursor.push(empty)

        cursor.move(GbButton.DOWN)
        cursor.confirm()

        assertEquals(0, empty.index)
        assertFalse(taken)
    }

    @Test
    fun `with nothing open a swipe is harmless`() {
        val cursor = Gen1Cursor()
        assertFalse(cursor.hasLayer)
        cursor.move(GbButton.DOWN)
        cursor.confirm()
        assertTrue(true)
    }
}
