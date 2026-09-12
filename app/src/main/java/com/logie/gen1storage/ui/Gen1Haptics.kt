package com.logie.gen1storage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The click under the finger.
 *
 * A Game Boy's buttons were the one part of it you could feel, and a glass
 * screen has nothing of that: a row taken and a row merely scrolled past feel
 * identical. A short tick on the cursor moving and on a row being taken puts
 * some of it back, and it is short on purpose — a long buzz reads as an error,
 * which is not what taking a menu row is.
 *
 * Two weights only. A move is the lightest thing the platform offers and a
 * confirm is one step up, so the two are distinguishable without either being
 * something you would call a vibration.
 */
object Gen1Haptics {

    /**
     * On by default, and switchable in ACCESSIBILITY. Silence is already a
     * setting there; this is the same idea for the other sense.
     */
    var enabled by mutableStateOf(true)
}

/** A tick for the cursor moving. */
@Composable
fun rememberCursorTick(): () -> Unit = rememberTick(HapticFeedbackType.TextHandleMove)

/** A slightly heavier one for a row actually being taken. */
@Composable
fun rememberConfirmTick(): () -> Unit = rememberTick(HapticFeedbackType.LongPress)

@Composable
private fun rememberTick(type: HapticFeedbackType): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics, type) {
        {
            // A device with no vibrator, or one that refuses, is not a reason
            // for a menu row to throw on its way to doing what it was for.
            if (Gen1Haptics.enabled) runCatching { haptics.performHapticFeedback(type) }
            Unit
        }
    }
}
