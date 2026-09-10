package com.logie.gen1storage.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.logie.gen1storage.sprites.recolourToRamp

/**
 * The app's own artwork — the title cards and the cartridge — drawn through a
 * palette.
 *
 * Every piece of it ships as four flat greys, which is not how it is ever
 * shown: it is recoloured onto a [GbPalette]'s ramp at load time, so one file
 * serves whichever game or palette needs it. That is why the title cards can
 * be one image each and still read as Red, Blue and Yellow.
 *
 * Drawing is nearest-neighbour with no smoothing, and every size these are
 * asked for is a whole number of Game Boy pixels, so the art scales without
 * ever landing on a fraction.
 */
@Composable
fun Gen1Art(
    resourceId: Int,
    palette: GbPalette,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val image = remember(resourceId, palette.id) {
        loadRecoloured(context.resources, resourceId, palette)
    } ?: return

    Image(
        bitmap = image,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        // Pixel art: smoothing it would undo the whole presentation.
        filterQuality = FilterQuality.None,
    )
}

private fun loadRecoloured(
    resources: android.content.res.Resources,
    resourceId: Int,
    palette: GbPalette,
): ImageBitmap? {
    val options = BitmapFactory.Options().apply {
        inScaled = false          // the file is already at its native size
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val source = runCatching {
        BitmapFactory.decodeResource(resources, resourceId, options)
    }.getOrNull() ?: return null

    val ramp = palette.ramp.map { it.toArgb() }.toIntArray()
    val recoloured = runCatching { recolourToRamp(source, ramp) }.getOrNull() ?: source
    if (recoloured !== source) source.recycle()
    return recoloured.asImageBitmap()
}
