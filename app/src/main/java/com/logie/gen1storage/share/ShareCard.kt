package com.logie.gen1storage.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing a rendered card to whatever the phone shares with.
 *
 * The file goes to the app's own cache under a name that says what it is, and
 * out through a [FileProvider] so the receiving app gets a readable URI and
 * nothing else: no storage permission is asked for, and nothing is written
 * anywhere a player would have to tidy up.
 *
 * Each share overwrites the last. One card at a time is what is being sent,
 * and a cache that grows a file per Pokémon ever shared would be this app
 * quietly taking up room for no reason.
 */
object ShareCard {

    private const val FOLDER = "shares"
    private const val AUTHORITY_SUFFIX = ".shares"

    /** Writes [card] out and opens the chooser. Returns false if it could not. */
    fun share(context: Context, card: Bitmap, name: String): Boolean {
        val file = runCatching {
            val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
            val target = File(folder, safeName(name))
            target.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            target
        }.getOrNull() ?: return false

        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    /** A nickname can be anything; a file name cannot. */
    private fun safeName(name: String): String {
        val cleaned = name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .take(24)
        return (cleaned.ifEmpty { "pokemon" }) + ".png"
    }
}
