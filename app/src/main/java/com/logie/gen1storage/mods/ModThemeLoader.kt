package com.logie.gen1storage.mods

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.logie.gen1storage.ui.Gen1Mod
import com.logie.gen1storage.ui.Gen1ModTheme
import java.io.File

/**
 * A mod's manifest and its folder, read into the one value the drawing code
 * looks at.
 *
 * Every picture here is decoded off a file this app extracted itself, from
 * a zip [ModImportScanner] already passed — and decoded through
 * [runCatching], because "this is not an image after all" is a mod being
 * wrong, not this app being broken. Anything that will not decode simply is
 * not there, and the app draws its own.
 */
fun InstalledMod?.toTheme(): Gen1ModTheme {
    val mod = this ?: return Gen1Mod.NONE
    val chrome = mod.manifest.chrome
    return Gen1ModTheme(
        opacity = mod.manifest.palette?.opacity ?: 1f,
        border = mod.borderFile().decode(),
        background = mod.backgroundFile().decode(),
        backgroundFit = fitOf(mod.manifest.background?.fit),
        ball = mod.ballFile().decode(),
        ballSpins = mod.manifest.ball?.spins ?: true,
        smoothing = mod.manifest.smoothing,
        ink = chrome?.ink.asColor(),
        panel = chrome?.panel.asColor(),
        shadow = chrome?.shadow.asColor(),
        muted = chrome?.muted.asColor(),
        surround = chrome?.surround.asColor(),
        barFill = chrome?.barFill.asColor(),
        barText = chrome?.barText.asColor(),
        hpGreen = chrome?.hpGreen.asColor(),
        hpYellow = chrome?.hpYellow.asColor(),
        hpRed = chrome?.hpRed.asColor(),
    )
}

private fun File?.decode(): ImageBitmap? =
    this?.let { file -> runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() }
        ?.asImageBitmap()

private fun Int?.asColor(): Color? = this?.let(::Color)

private fun fitOf(name: String?): Gen1ModTheme.Fit = when (name) {
    ModBackgroundDefinition.TILE -> Gen1ModTheme.Fit.TILE
    ModBackgroundDefinition.STRETCH -> Gen1ModTheme.Fit.STRETCH
    else -> Gen1ModTheme.Fit.COVER
}
