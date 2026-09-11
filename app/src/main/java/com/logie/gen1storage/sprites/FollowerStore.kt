package com.logie.gen1storage.sprites

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.download.fetchInParallel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.coroutines.coroutineContext

/**
 * The overworld follower sheets: the little 16x16 Pokémon the box grid shows.
 *
 * One file per dex number, each a 16x96 sheet of six 16x16 frames stacked
 * downwards — facing down, up and sideways, twice over for the two walking
 * poses. The grid wants the two that keep it facing the player, so only
 * frames 1 and 4 are ever asked for.
 *
 * Downloaded rather than bundled, the same as the front sprites and the
 * cries. This art is the Followers EX / PokéPC lineage rather than this
 * project's own, so it is fetched to the device and nothing of it ships in
 * the APK.
 */
class FollowerStore(private val directory: File) {

    constructor(context: Context) : this(File(context.filesDir, "followers"))

    /** Frames are tiny, so every one asked for is simply kept. */
    private val memory: MutableMap<String, ImageBitmap> =
        Collections.synchronizedMap(HashMap<String, ImageBitmap>())

    private var tintId: String = "original"
    private var tintRamp: IntArray? = null

    /** Points the followers at a palette, as [SpriteStore.setTint] does. */
    fun setTint(id: String, ramp: IntArray?) {
        if (id == tintId) return
        tintId = id
        tintRamp = ramp
        memory.clear()
    }

    fun file(dexNumber: Int): File = File(directory, "follower_%03d.png".format(dexNumber))

    fun has(dexNumber: Int): Boolean = file(dexNumber).let { it.isFile && it.length() > 0 }

    fun count(): Int = (1..LAST_SHEET).count(::has)

    fun bytesOnDisk(): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        directory.deleteRecursively()
        memory.clear()
    }

    /**
     * One frame of one Pokémon, or null when the sheet is not on the device.
     *
     * [frame] is 1-based, matching how the sheet reads top to bottom. A sheet
     * that is not the expected size is refused rather than sliced blindly: a
     * half-downloaded or unexpected file would otherwise crop to nonsense.
     */
    fun frame(dexNumber: Int, frame: Int): ImageBitmap? {
        if (dexNumber !in 1..LAST_SHEET || frame !in 1..FRAMES) return null
        val key = "$tintId/$dexNumber/$frame"
        memory[key]?.let { return it }

        val source = file(dexNumber).takeIf { it.isFile } ?: return null
        val sheet = runCatching {
            BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null
        if (sheet.width != SIZE || sheet.height != SIZE * FRAMES) {
            sheet.recycle()
            return null
        }

        val cut = Bitmap.createBitmap(sheet, 0, (frame - 1) * SIZE, SIZE, SIZE)
        sheet.recycle()
        val ramp = tintRamp
        val finished = if (ramp == null) cut
        else runCatching { recolourToRamp(cut, ramp) }.getOrNull() ?: cut
        return finished.asImageBitmap().also { memory[key] = it }
    }

    /** Fetches one sheet if it is not here yet. */
    fun fetch(dexNumber: Int): File? {
        if (dexNumber !in 1..LAST_SHEET) return null
        val target = file(dexNumber)
        if (target.isFile && target.length() > 0) return target

        val url = URL("$BASE_URL/follower_%03d.png".format(dexNumber))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }
        // Left connected on purpose: see the note in CryStore.fetch — a
        // disconnect here costs the next file a whole handshake.
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return null

        // Confirm it decodes to the sheet this expects before it lands, so a
        // proxy's error page cannot sit on disk looking like a Pokémon.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth != SIZE || bounds.outHeight != SIZE * FRAMES) return null

        directory.mkdirs()
        val staged = File(directory, "follower_%03d.png.part".format(dexNumber))
        staged.writeBytes(bytes)
        if (!staged.renameTo(target)) {
            staged.delete()
            return null
        }
        return target
    }

    /** Fetches every sheet that is not already here, reporting after each. */
    suspend fun downloadAll(onProgress: (DownloadProgress) -> Unit): DownloadProgress =
        withContext(Dispatchers.IO) {
            onProgress(DownloadProgress(0, LAST_SHEET))
            val failed = fetchInParallel(1..LAST_SHEET, LAST_SHEET, onProgress) { fetch(it) }
            memory.clear()
            DownloadProgress(LAST_SHEET, LAST_SHEET, failed, finished = true).also(onProgress)
        }

    companion object {
        /** One frame's side, in real pixels. */
        const val SIZE = 16

        /** Frames down one sheet: down, up, side, then the same three walking. */
        const val FRAMES = 6

        /**
         * The two front-facing poses: standing, and mid-step.
         *
         * A sheet's six frames are three headings — down, up, side — each
         * twice over for the two walking poses, so the pair that keeps a
         * Pokémon facing the player is 1 and 4. Frames 2 and 3 turn it away
         * and side-on, which reads as a Pokémon walking off rather than one
         * shifting its weight where it stands.
         */
        const val FRAME_IDLE = 1
        const val FRAME_STEP = 4

        /**
         * Every sheet the pack has. Only the first 151 can be shown today —
         * nothing in this app reads a Generation II save's boxes — but the
         * download takes the lot, so the art is already here when it can.
         */
        const val LAST_SHEET = 251

        /**
         * burgerslayer7's fork at the release these frames were read from.
         * Pinned to the tag rather than a branch: a moving target would change
         * the art under a player who already has half of it.
         */
        private const val BASE_URL =
            "https://raw.githubusercontent.com/burgerslayer7/PokePCFollowers/v0.8.3/assets/sprites"
    }
}
