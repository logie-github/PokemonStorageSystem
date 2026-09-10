package com.logie.gen1storage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier

/**
 * The one cursor the whole app shares.
 *
 * A swipe in empty space has to move *something*, and which something depends
 * on what is open: the PC menu, or a list over it, or the small window over
 * that. So each menu pushes a layer while it is on screen and pops it when it
 * leaves, and the cursor always belongs to the topmost — which is the innermost
 * thing the player opened, and the only one they could mean.
 */
class Gen1Cursor {

    class Layer(var count: Int, var columns: Int, var onConfirm: (Int) -> Unit) {
        var index by mutableIntStateOf(0)
    }

    private val layers = mutableStateListOf<Layer>()

    private val top: Layer? get() = layers.lastOrNull()

    fun push(layer: Layer) {
        layers.add(layer)
    }

    fun remove(layer: Layer) {
        layers.remove(layer)
    }

    /** Up and down move by a row; left and right only where there are columns. */
    fun move(direction: GbButton) {
        val layer = top ?: return
        if (layer.count <= 0) return
        val step = when (direction) {
            GbButton.UP -> -layer.columns
            GbButton.DOWN -> layer.columns
            GbButton.LEFT -> if (layer.columns > 1) -1 else 0
            GbButton.RIGHT -> if (layer.columns > 1) 1 else 0
            else -> 0
        }
        if (step == 0) return
        // Wraps, as the games' menus do.
        layer.index = ((layer.index + step) % layer.count + layer.count) % layer.count
    }

    fun confirm() {
        val layer = top ?: return
        if (layer.index in 0 until layer.count) layer.onConfirm(layer.index)
    }

    val hasLayer: Boolean get() = top != null
}

val LocalGen1Cursor = staticCompositionLocalOf { Gen1Cursor() }

/**
 * Claims the cursor for as long as this menu is on screen, and reports where
 * it currently is.
 *
 * The layer is pushed on entering composition and popped on leaving, so the
 * stack matches what is actually open without anything having to coordinate.
 */
@Composable
fun rememberCursorLayer(
    count: Int,
    columns: Int = 1,
    onConfirm: (Int) -> Unit,
): Int {
    val cursor = LocalGen1Cursor.current
    val layer = remember { Gen1Cursor.Layer(count, columns) { } }
    layer.count = count
    layer.columns = columns
    layer.onConfirm = onConfirm
    DisposableEffect(cursor, layer) {
        cursor.push(layer)
        onDispose { cursor.remove(layer) }
    }
    // A list that shrinks under the cursor should not leave it pointing past
    // the end; clamping here keeps the confirm honest.
    if (layer.index >= count) layer.index = (count - 1).coerceAtLeast(0)
    return layer.index
}

/**
 * Where the windows are, so a gesture can tell the screen from the furniture.
 *
 * Swipes and taps in empty space drive the cursor; the same gestures on a
 * window belong to the window. This is how the gesture layer knows which it is
 * looking at, and it is registered by [Gen1Frame] itself so no screen has to
 * remember to do it.
 */
class Gen1WindowBounds {
    private val bounds = mutableStateMapOf<Any, Rect>()

    fun set(owner: Any, rect: Rect) {
        bounds[owner] = rect
    }

    fun forget(owner: Any) {
        bounds.remove(owner)
    }

    fun isFreeSpace(point: Offset): Boolean = bounds.values.none { it.contains(point) }
}

val LocalGen1WindowBounds = staticCompositionLocalOf { Gen1WindowBounds() }

/**
 * Reports this window's position to the gesture layer, and forgets it again
 * when the window leaves — otherwise a closed list would go on swallowing
 * gestures over the space it used to occupy.
 */
@Composable
fun Modifier.gen1WindowBounds(): Modifier {
    val registry = LocalGen1WindowBounds.current
    val owner = remember { Any() }
    DisposableEffect(registry, owner) {
        onDispose { registry.forget(owner) }
    }
    return onGloballyPositioned { registry.set(owner, it.boundsInRoot()) }
}
