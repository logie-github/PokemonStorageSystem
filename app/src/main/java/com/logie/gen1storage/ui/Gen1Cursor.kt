package com.logie.gen1storage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    class Layer(
        var count: Int,
        var columns: Int,
        /**
         * Whether running off one end comes back on the other.
         *
         * A menu wraps, as the games' menus do. A box does not: a grid is a
         * place rather than a list, and walking off its right-hand edge into
         * the row below — or out of the box entirely — is not what anyone
         * reaching for the next column means.
         */
        var wraps: Boolean,
        var onConfirm: (Int) -> Unit,
    ) {
        var index by mutableIntStateOf(0)

        /**
         * A row that does something of its own with left and right — a count
         * to wind up and down, say. Given the row and the direction, and
         * returning whether it took it; anything it leaves alone moves the
         * cursor as usual.
         */
        var onSide: ((Int, GbButton) -> Boolean)? = null
    }

    private val layers = mutableStateListOf<Layer>()

    private val top: Layer? get() = layers.lastOrNull()

    /**
     * Set while something is running that must not be interrupted — a transfer
     * in flight, a save being read.
     *
     * A Pokémon halfway between a cartridge and this PC is the one moment in
     * the app where a second button press could ask for something the first
     * one has not finished answering, so for as long as that lasts the cursor
     * neither moves nor takes anything. It lives here rather than in a screen
     * because the gesture layer reads it from the one object every screen
     * already shares.
     */
    var locked by mutableStateOf(false)

    fun push(layer: Layer) {
        layers.add(layer)
    }

    fun remove(layer: Layer) {
        layers.remove(layer)
    }

    /** Up and down move by a row; left and right only where there are columns. */
    fun move(direction: GbButton) {
        if (locked) return
        val layer = top ?: return
        if (layer.count <= 0) return
        if (direction == GbButton.LEFT || direction == GbButton.RIGHT) {
            if (layer.onSide?.invoke(layer.index, direction) == true) return
        }
        val step = when (direction) {
            GbButton.UP -> -layer.columns
            GbButton.DOWN -> layer.columns
            GbButton.LEFT -> if (layer.columns > 1) -1 else 0
            GbButton.RIGHT -> if (layer.columns > 1) 1 else 0
            else -> 0
        }
        if (step == 0) return
        if (layer.wraps) {
            layer.index = ((layer.index + step) % layer.count + layer.count) % layer.count
            return
        }
        val target = layer.index + step
        if (target !in 0 until layer.count) return
        // Sideways has to stay on its own row as well as inside the grid:
        // index + 1 off the right-hand edge is a real index, just the wrong
        // one — the first spot of the next row down.
        val sideways = layer.columns > 1 && (direction == GbButton.LEFT || direction == GbButton.RIGHT)
        if (sideways && target / layer.columns != layer.index / layer.columns) return
        layer.index = target
    }

    fun confirm() {
        if (locked) return
        val layer = top ?: return
        if (layer.index in 0 until layer.count) layer.onConfirm(layer.index)
    }

    val hasLayer: Boolean get() = top != null
}

val LocalGen1Cursor = staticCompositionLocalOf { Gen1Cursor() }

/**
 * What a tap should actually do.
 *
 * With SWIPE CONTROLS on, a tap is the A button and A takes whatever the
 * cursor is on: the finger is saying "now", not "this one". So a tap that
 * lands on a row is still the cursor's row, and a player who walked the
 * cursor somewhere and then pressed cannot be given a different answer by
 * where their thumb happened to be.
 *
 * With swipes off there is no second way to point at things and a tap means
 * the thing under it, which is how the app has always worked.
 *
 * Anything with nothing holding the cursor — a screen that registered no
 * layer — keeps its own tap either way, since there is no cursor to defer to.
 */
@Composable
fun gen1Tap(onTap: () -> Unit): () -> Unit {
    val cursor = LocalGen1Cursor.current
    val defer = LocalGen1Swipe.current && cursor.hasLayer
    return if (defer) ({ cursor.confirm() }) else onTap
}

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
    wraps: Boolean = true,
    onSide: ((Int, GbButton) -> Boolean)? = null,
    onConfirm: (Int) -> Unit,
): Int = rememberCursorLayerHandle(count, columns, wraps, onSide, onConfirm).index

/**
 * The same layer, handed back whole.
 *
 * A screen that can also be tapped needs to put the cursor where the finger
 * went, so the two ways of driving it agree about what is picked. Only the box
 * grid needs that much; everywhere else the index is the whole story.
 */
@Composable
fun rememberCursorLayerHandle(
    count: Int,
    columns: Int = 1,
    wraps: Boolean = true,
    onSide: ((Int, GbButton) -> Boolean)? = null,
    onConfirm: (Int) -> Unit,
): Gen1Cursor.Layer {
    val cursor = LocalGen1Cursor.current
    val layer = remember { Gen1Cursor.Layer(count, columns, wraps) { } }
    layer.count = count
    layer.columns = columns
    layer.wraps = wraps
    layer.onSide = onSide
    layer.onConfirm = onConfirm
    DisposableEffect(cursor, layer) {
        cursor.push(layer)
        onDispose { cursor.remove(layer) }
    }
    // A list that shrinks under the cursor should not leave it pointing past
    // the end; clamping here keeps the confirm honest.
    if (layer.index >= count) layer.index = (count - 1).coerceAtLeast(0)
    return layer
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

    /** Lists that scroll by finger even while swipes are driving the cursor. */
    private val scrollers = mutableStateMapOf<Any, Rect>()

    fun setScroller(owner: Any, rect: Rect) {
        scrollers[owner] = rect
    }

    fun forgetScroller(owner: Any) {
        scrollers.remove(owner)
    }

    /**
     * Whether a drag starting here belongs to a list rather than the cursor.
     *
     * One exception to swipe controls, and a narrow one: a list long enough
     * to need dragging is a list the cursor can only walk one row at a time,
     * and thirty taps to reach the bottom of it is worse than losing the
     * swipe over that one rectangle. Everything else on the screen still
     * reads a swipe as the D-pad.
     */
    fun isScroller(point: Offset): Boolean = scrollers.values.any { it.contains(point) }
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
 * Marks this list as one that keeps its own drags: the gesture layer leaves
 * vertical swipes that start inside it alone, so it scrolls by finger even
 * with SWIPE CONTROLS on.
 */
@Composable
fun Modifier.gen1ScrollRegion(): Modifier {
    val registry = LocalGen1WindowBounds.current
    val owner = remember { Any() }
    DisposableEffect(registry, owner) { onDispose { registry.forgetScroller(owner) } }
    return onGloballyPositioned { registry.setScroller(owner, it.boundsInRoot()) }
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
 * Set where a screen is drawn into part of the window rather than all of it.
 *
 * The unfolded layout draws two screens side by side, and the one in the
 * second half is a whole screen that has been handed half a window. It has no
 * way of knowing that: every layout in the app asks [isUnfolded], which reads
 * the window, so each of them split its own half in two again. The status
 * pages ended up a quarter of the screen wide, with the words breaking one
 * letter to a line, and the pane beside them drew a second copy of what was
 * already on the left.
 */
val LocalGen1Narrow = staticCompositionLocalOf { false }

/**
 * Whether there is a lot of width to lay out in.
 *
 * Read off the width rather than from a hinge API, so it is honest about what
 * it actually knows: this is "there is a lot of width here", which is the thing
 * every layout decision here cares about. A tablet and an unfolded phone are
 * treated the same, and a folded one is not — and neither is half of either,
 * which is what [LocalGen1Narrow] says.
 */
@Composable
fun isUnfolded(): Boolean =
    !LocalGen1Narrow.current &&
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
