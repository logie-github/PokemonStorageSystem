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

    private fun layer(
        count: Int,
        columns: Int = 1,
        wraps: Boolean = true,
        onConfirm: (Int) -> Unit = {},
    ) = Gen1Cursor.Layer(count, columns, wraps, onConfirm)

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
    fun `a box does not wrap off its edges`() {
        val cursor = Gen1Cursor()
        // The shape of a storage box: six across, five down.
        val box = layer(30, columns = 6, wraps = false)
        cursor.push(box)

        cursor.move(GbButton.LEFT)
        assertEquals("left off the first spot stays put", 0, box.index)

        cursor.move(GbButton.UP)
        assertEquals("and so does up out of the top row", 0, box.index)

        repeat(5) { cursor.move(GbButton.RIGHT) }
        assertEquals("across to the end of the row", 5, box.index)

        cursor.move(GbButton.RIGHT)
        assertEquals("right off the end does not drop a row", 5, box.index)

        repeat(4) { cursor.move(GbButton.DOWN) }
        assertEquals(29, box.index)

        cursor.move(GbButton.DOWN)
        assertEquals("nor does down leave the box", 29, box.index)
    }

    @Test
    fun `a locked cursor neither moves nor confirms`() {
        val cursor = Gen1Cursor()
        var taken = false
        val menu = layer(4) { taken = true }
        cursor.push(menu)
        cursor.locked = true

        cursor.move(GbButton.DOWN)
        cursor.confirm()

        assertEquals(0, menu.index)
        assertFalse("nothing was taken mid-transfer", taken)

        cursor.locked = false
        cursor.move(GbButton.DOWN)
        assertEquals("and it comes back when the transfer is done", 1, menu.index)
    }

    @Test
    fun `a row that wants left and right gets them first`() {
        val cursor = Gen1Cursor()
        val menu = layer(4, columns = 2)
        var wound = 0
        // Only the first row takes them; the rest move as usual.
        menu.onSide = { row, button ->
            if (row != 0) false
            else {
                wound += if (button == GbButton.RIGHT) 1 else -1
                true
            }
        }
        cursor.push(menu)

        cursor.move(GbButton.RIGHT)
        assertEquals("the row took it", 1, wound)
        assertEquals("so the cursor stayed where it was", 0, menu.index)

        cursor.move(GbButton.DOWN)
        cursor.move(GbButton.RIGHT)
        assertEquals("another row leaves it alone", 1, wound)
        assertEquals(3, menu.index)
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
