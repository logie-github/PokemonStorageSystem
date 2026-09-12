package com.logie.gen1storage.sprites

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.download.fetchInParallel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/** One trainer class's battle sprite, as the game names it. */
data class TrainerSprite(val id: String, val label: String)

/**
 * The trainer battle sprites, for the portrait on a trainer card.
 *
 * Straight from pret/pokered's own `gfx/trainers`, which is where these live
 * and the only place they are authoritative. The list and its order are read
 * off `gfx/pics.asm` rather than invented, so it is the game's own roll of
 * trainers in the game's own order.
 *
 * Every one is 56 pixels square in four flat greys, which is exactly what a
 * Pokémon's front sprite is, so they go through the same recolour onto
 * whatever palette is in force and need nothing of their own.
 *
 * There is deliberately no Red here. Generation I has no front-facing sprite
 * of the player — only the back he is drawn from behind in battle — so the
 * roll is the trainers he meets.
 */
class TrainerStore(private val directory: File) {

    constructor(context: Context) : this(File(context.filesDir, "trainers"))

    /** Forty-five small images; every one asked for is simply kept. */
    private val memory: MutableMap<String, ImageBitmap> =
        Collections.synchronizedMap(HashMap<String, ImageBitmap>())

    private var tintId: String = "original"
    private var tintRamp: IntArray? = null

    /** Points the trainers at a palette, as [SpriteStore.setTint] does. */
    fun setTint(id: String, ramp: IntArray?) {
        if (id == tintId) return
        tintId = id
        tintRamp = ramp
        memory.clear()
    }

    fun file(id: String): File = File(directory, "$id.png")

    fun has(id: String): Boolean = file(id).let { it.isFile && it.length() > 0 }

    fun count(): Int = ALL.count { has(it.id) }

    fun bytesOnDisk(): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        directory.deleteRecursively()
        memory.clear()
    }

    /** One trainer, or null when that one is not on the device. */
    fun load(id: String): ImageBitmap? {
        if (ALL.none { it.id == id }) return null
        val key = "$tintId/$id"
        memory[key]?.let { return it }

        val source = file(id).takeIf { it.isFile } ?: return null
        val decoded = runCatching {
            BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null

        val ramp = tintRamp
        val finished = if (ramp == null) decoded
        else runCatching { recolourToRamp(decoded, ramp) }.getOrNull() ?: decoded
        return finished.asImageBitmap().also { memory[key] = it }
    }

    /** Fetches one if it is not here yet. */
    fun fetch(id: String): File? {
        val known = ALL.firstOrNull { it.id == id } ?: return null
        val target = file(known.id)
        if (target.isFile && target.length() > 0) return target

        val url = URL("$BASE_URL/${known.id}.png")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }
        // Left connected on purpose: a disconnect here costs the next file a
        // whole handshake, and there are forty-five of them from one host.
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return null

        // It must be the square this expects before it lands, so a proxy's
        // error page cannot sit on disk looking like a trainer.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth != SIZE || bounds.outHeight != SIZE) return null

        directory.mkdirs()
        val staged = File(directory, "${known.id}.png.part")
        staged.writeBytes(bytes)
        if (!staged.renameTo(target)) {
            staged.delete()
            return null
        }
        return target
    }

    /** Fetches every trainer that is not already here, reporting after each. */
    suspend fun downloadAll(onProgress: (DownloadProgress) -> Unit): DownloadProgress =
        withContext(Dispatchers.IO) {
            val total = ALL.size
            onProgress(DownloadProgress(0, total))
            val failed = fetchInParallel(ALL, total, onProgress) { fetch(it.id) }
            memory.clear()
            DownloadProgress(total, total, failed, finished = true).also(onProgress)
        }

    companion object {
        /** A trainer sprite's side, in real pixels — the same as a Pokémon's. */
        const val SIZE = 56

        /**
         * Pinned to the commit these were read at rather than to master.
         *
         * A decomp's master moves, and art moving under a player who has half
         * of it downloaded is the one thing a pinned reference prevents. The
         * same reasoning as the follower pack, which pins to a tag; pokered
         * has no tag to pin to, so the commit is named instead.
         */
        private const val COMMIT = "a1a22aaf84d1675bcdbaeb194592379d586d838e"

        private const val BASE_URL =
            "https://raw.githubusercontent.com/pret/pokered/$COMMIT/gfx/trainers"

        /**
         * Every trainer pic the game has, in the order `gfx/pics.asm` lists
         * them — which is the order of the trainer class table, so it reads
         * as the game's own roll rather than an alphabetical one.
         *
         * The ids are the filenames, dots and all: `lt.surge`, `prof.oak`,
         * `jr.trainerm`. The labels are the names the game gives these classes
         * in its own text.
         */
        val ALL: List<TrainerSprite> = listOf(
            TrainerSprite("youngster", "YOUNGSTER"),
            TrainerSprite("bugcatcher", "BUG CATCHER"),
            TrainerSprite("lass", "LASS"),
            TrainerSprite("sailor", "SAILOR"),
            TrainerSprite("jr.trainerm", "JR.TRAINER♂"),
            TrainerSprite("jr.trainerf", "JR.TRAINER♀"),
            TrainerSprite("pokemaniac", "POKéMANIAC"),
            TrainerSprite("supernerd", "SUPER NERD"),
            TrainerSprite("hiker", "HIKER"),
            TrainerSprite("biker", "BIKER"),
            TrainerSprite("burglar", "BURGLAR"),
            TrainerSprite("engineer", "ENGINEER"),
            TrainerSprite("fisher", "FISHERMAN"),
            TrainerSprite("swimmer", "SWIMMER"),
            TrainerSprite("cueball", "CUE BALL"),
            TrainerSprite("gambler", "GAMBLER"),
            TrainerSprite("beauty", "BEAUTY"),
            TrainerSprite("psychic", "PSYCHIC"),
            TrainerSprite("rocker", "ROCKER"),
            TrainerSprite("juggler", "JUGGLER"),
            TrainerSprite("tamer", "TAMER"),
            TrainerSprite("birdkeeper", "BIRD KEEPER"),
            TrainerSprite("blackbelt", "BLACKBELT"),
            TrainerSprite("rival1", "RIVAL"),
            TrainerSprite("prof.oak", "PROF.OAK"),
            TrainerSprite("scientist", "SCIENTIST"),
            TrainerSprite("giovanni", "GIOVANNI"),
            TrainerSprite("rocket", "ROCKET"),
            TrainerSprite("cooltrainerm", "COOLTRAINER♂"),
            TrainerSprite("cooltrainerf", "COOLTRAINER♀"),
            TrainerSprite("bruno", "BRUNO"),
            TrainerSprite("brock", "BROCK"),
            TrainerSprite("misty", "MISTY"),
            TrainerSprite("lt.surge", "LT.SURGE"),
            TrainerSprite("erika", "ERIKA"),
            TrainerSprite("koga", "KOGA"),
            TrainerSprite("blaine", "BLAINE"),
            TrainerSprite("sabrina", "SABRINA"),
            TrainerSprite("gentleman", "GENTLEMAN"),
            TrainerSprite("rival2", "RIVAL"),
            TrainerSprite("rival3", "RIVAL"),
            TrainerSprite("lorelei", "LORELEI"),
            TrainerSprite("channeler", "CHANNELER"),
            TrainerSprite("agatha", "AGATHA"),
            TrainerSprite("lance", "LANCE"),
        )
    }
}
