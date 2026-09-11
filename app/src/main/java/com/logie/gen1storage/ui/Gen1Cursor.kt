package com.logie.gen1storage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
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

    /** Windows that do something of their own with a hold. */
    private val holds = mutableStateMapOf<Any, Rect>()

    fun set(owner: Any, rect: Rect) {
        bounds[owner] = rect
    }

    fun forget(owner: Any) {
        bounds.remove(owner)
        holds.remove(owner)
    }

    fun setHold(owner: Any, rect: Rect) {
        holds[owner] = rect
    }

    fun isFreeSpace(point: Offset): Boolean = bounds.values.none { it.contains(point) }

    /**
     * Whether something under [point] wants the hold for itself.
     *
     * A hold anywhere goes back, but a cartridge and a box window are renamed
     * by holding them. Both fire on their own timer, so without this the hold
     * would rename *and* navigate away from what it renamed.
     */
    fun isHoldClaimed(point: Offset): Boolean = holds.values.any { it.contains(point) }
}

val LocalGen1WindowBounds = staticCompositionLocalOf { Gen1WindowBounds() }

/**
 * Marks this window as handling its own hold, so the global back gesture
 * leaves it alone.
 */
@Composable
fun Modifier.gen1HoldRegion(): Modifier {
    val registry = LocalGen1WindowBounds.current
    val owner = remember { Any() }
    DisposableEffect(registry, owner) { onDispose { registry.forget(owner) } }
    return onGloballyPositioned { registry.setHold(owner, it.boundsInRoot()) }
}

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

/**
 * Whether the screen is wide enough to be a foldable that has been opened.
 *
 * Read off the width rather than from a hinge API, so it is honest about what
 * it actually knows: this is "there is a lot of width here", which is the thing
 * every layout decision here cares about. A tablet and an unfolded phone are
 * treated the same, and a folded one is not.
 */
@Composable
fun isUnfolded(): Boolean =
    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= UNFOLDED_WIDTH_DP

/** Roughly where a folded phone stops and an opened one begins. */
const val UNFOLDED_WIDTH_DP = 600

/**
 * Which side of the screen the windows sit on.
 *
 * Snapshot state rather than a parameter threaded through every screen: this is
 * one decision that every layout in the app has to agree on, and passing it
 * around would mean any screen could quietly disagree.
 */
object Gen1Layout {
    var windowsOnRight by mutableStateOf(true)

    /** The horizontal edge menus and lists are pinned to. */
    val menuSide: Alignment.Horizontal
        get() = if (windowsOnRight) Alignment.End else Alignment.Start

    /** The opposite edge, for whatever pairs with them. */
    val otherSide: Alignment.Horizontal
        get() = if (windowsOnRight) Alignment.Start else Alignment.End

    fun corner(top: Boolean, menuSide: Boolean): Alignment {
        val right = windowsOnRight == menuSide
        return when {
            top && right -> Alignment.TopEnd
            top -> Alignment.TopStart
            right -> Alignment.BottomEnd
            else -> Alignment.BottomStart
        }
    }
}
