package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The one question a restored install asks, before anything else happens.
 *
 * Android's backup brings the whole machine back — the boxes, every setting,
 * and the link to the account — so an app reinstalled on a phone that has one
 * is not a new app with some old files in it. It is the same app, and the only
 * thing it cannot know is whether that is what the person holding it wanted.
 *
 * So it asks, once, and it asks here: before the introduction, not after.
 * Being walked around a machine by Bill and *then* asked whether the machine
 * should exist is the wrong way round, and a player who says no would have sat
 * through a tour of somebody else's PC to get there. Saying no starts the
 * device clean and the introduction plays after it, which is what a first run
 * looks like — because that is what it now is.
 *
 * Nothing else is on screen and there is no way past it. A hold does not go
 * back, because there is nothing behind this.
 */
@Composable
fun RestoreScreen(state: UiState, model: StorageViewModel) {
    val stored = state.storage.total
    val rows = listOf<Pair<String, () -> Unit>>(
        "YES" to { model.useRestored() },
        "NO" to { model.discardRestored() },
    )
    val cursor = rememberCursorLayer(rows.size) { rows[it].second() }

    Box(
        Modifier.fillMaxSize().gen1Ground().padding(gen1Dp(4)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Gen1Frame(Modifier.gen1MaxWidth()) {
                Gen1TypedLines(
                    buildList {
                        add("THERE IS BACKED UP DATA")
                        add("FOR THIS MACHINE.")
                        // What is actually in it, so the answer is a decision
                        // rather than a guess. The link is named because it is
                        // the part somebody might not expect to come back.
                        add(
                            when (stored) {
                                0 -> "NO POKéMON ARE IN IT."
                                1 -> "1 POKéMON IS IN IT."
                                else -> "$stored POKéMON ARE IN IT."
                            }
                        )
                        if (state.linked) add("IT IS STILL LINKED TO YOUR GAME.")
                        add("USE IT?")
                    }
                )
            }
            Spacer(Modifier.height(gen1Dp(4)))
            Gen1Frame(Modifier.wrapContentWidth()) {
                rows.forEachIndexed { index, (label, act) ->
                    Gen1MenuRow(label, selected = cursor == index, onSelect = {}, onConfirm = act)
                }
            }
            Spacer(Modifier.height(gen1Dp(3)))
            // What NO costs, said before it is taken rather than after. This
            // is the one screen in the app behind which there is nothing to
            // go back to, so a wrong answer here is not recoverable.
            Gen1Frame(Modifier.gen1MaxWidth()) {
                GbText("NO CLEARS THIS DEVICE AND", style = Gen1TextSmall, maxLines = 1)
                GbText("STARTS IT FRESH.", style = Gen1TextSmall, maxLines = 1)
            }
        }
    }
}
