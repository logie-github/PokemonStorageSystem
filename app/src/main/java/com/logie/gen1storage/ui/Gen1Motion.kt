package com.logie.gen1storage.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The kinds of movement the interface makes, each switchable on its own.
 *
 * One entry per thing that moves rather than one switch over the lot, because
 * they are not the same ask: a player who cannot watch a ball turn in the
 * corner of every screen may still want to see a transfer happen, and someone
 * who finds the text printing slow is not asking about either.
 */
enum class Motion(val id: String, val label: String) {
    /** The Poké Ball turning behind every screen. */
    BALL("ball", "TURNING BALL"),

    /** Windows growing open the way the cartridge's boxes do. */
    WINDOWS("windows", "WINDOWS"),

    /** The ball scene a deposit or a withdrawal plays. */
    TRANSFERS("transfers", "TRANSFERS"),

    /** The link cable, when a trade is set to show itself. */
    TRADE("trade", "TRADE"),

    /** Text arriving a letter at a time. */
    TEXT("text", "TEXT"),

    /** Lists gliding to the row the cursor moved to. */
    SCROLL("scroll", "SCROLL"),
}

/**
 * What may move, read by the things that move.
 *
 * Snapshot state on an object rather than something passed down, for the same
 * reason [Gen1Palette] is: the places that need it are `drawBehind` lambdas and
 * modifier factories with no view model in reach, and a setting that only some
 * of them honoured would be worse than none.
 *
 * REDUCE MOTION is the one switch over all of them. It stops movement and
 * nothing else: every graphic still draws, at the position it would have
 * settled at, so a screen with it on is the same screen holding still.
 */
object Gen1Motion {

    var reduced by mutableStateOf(false)

    /** Which kinds are on under that, by [Motion.id]. */
    var allowed by mutableStateOf(Motion.entries.map { it.id }.toSet())

    fun moves(motion: Motion): Boolean = !reduced && motion.id in allowed
}

/**
 * Moves a list to a row, gliding or not as the player has asked.
 *
 * Every list in the app scrolls to follow its cursor, and all of them go
 * through here so REDUCE MOTION cannot be honoured by some and not others.
 * With movement off the list still goes to the row — the row is where the
 * cursor is, and leaving it off screen would be an accessibility setting
 * making the app harder to use.
 */
suspend fun LazyListState.scrollToRow(index: Int) {
    // A screen whose cursor has been taken by something on top of it reports
    // no row at all, and a list still following it should sit where it is
    // rather than be asked to scroll to nowhere.
    val row = index.coerceAtLeast(0)
    if (Gen1Motion.moves(Motion.SCROLL)) animateScrollToItem(row) else scrollToItem(row)
}

/**
 * Turns off the platform's overscroll.
 *
 * Android stretches or bounces a list that is dragged past its end. It is a
 * good effect and it belongs to a different machine entirely: nothing on a
 * Game Boy had elastic edges, and a Pokédex that springs back when it runs out
 * of entries reads as a modern app wearing the interface rather than as the
 * interface. Every list in the app is inside this, so none of them do it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Gen1NoOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides null, content = content)
}

/**
 * Whether art is still arriving, for the places that draw a gap where a
 * sprite should be.
 *
 * Snapshot state on an object rather than a parameter, for the same reason
 * [Gen1Motion] is: the things that need it are a box cell and a sprite
 * placeholder buried under half a dozen composables, and threading a download
 * flag through every one of them to reach them would be worse than the flag.
 *
 * It is the difference between "there is no art for this" and "the art has
 * not landed yet", which look identical on screen and mean opposite things:
 * one is a gap to report and the other is a wait.
 */
object Gen1Loading {
    var fetching by mutableStateOf(false)
}
