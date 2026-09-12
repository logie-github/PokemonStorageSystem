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

    /** The trainers, plus the sheets the badges and the trade are cut from. */
    fun count(): Int = ALL.count { has(it.id) } + EXTRA_ART.keys.count { has(it) }

    /**
     * One of the extra sheets whole, recoloured onto the palette in force.
     *
     * The trade's Game Boy is already a picture and is used as one; the cable
     * and the ball are tile strips and are cut up by [tile].
     */
    fun art(id: String): ImageBitmap? {
        if (id !in EXTRA_ART) return null
        val key = "$tintId/art/$id"
        memory[key]?.let { return it }
        val decoded = decode(file(id)) ?: return null
        return tinted(decoded).asImageBitmap().also { memory[key] = it }
    }

    /**
     * One 8x8 tile of a sheet, counted left to right and then down, which is
     * the order the cartridge's own tilemaps index them in.
     */
    fun tile(id: String, index: Int, cutout: Boolean = false): ImageBitmap? {
        if (id !in EXTRA_ART) return null
        val key = "$tintId/tile/${if (cutout) "cut/" else ""}$id/$index"
        memory[key]?.let { return it }
        val sheet = decode(file(id)) ?: return null
        val across = sheet.width / TILE
        val down = sheet.height / TILE
        if (across <= 0 || index < 0 || index >= across * down) {
            sheet.recycle()
            return null
        }
        val cut = Bitmap.createBitmap(
            sheet,
            index % across * TILE,
            index / across * TILE,
            TILE,
            TILE,
        )
        sheet.recycle()
        return tinted(cut, cutout).asImageBitmap().also { memory[key] = it }
    }

    private fun decode(source: File): Bitmap? {
        if (!source.isFile) return null
        return runCatching {
            BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull()
    }

    private fun tinted(bitmap: Bitmap, cutout: Boolean = false): Bitmap {
        val ramp = tintRamp
        return runCatching {
            when {
                ramp != null -> recolourToRamp(bitmap, ramp, cutout)
                cutout -> cutOutLightest(bitmap)
                else -> bitmap
            }
        }.getOrNull() ?: bitmap
    }

    /**
     * One gym's badge, or null while the sheet is not on the device.
     *
     * The sheet alternates down its length — a gym leader's face, then that
     * gym's badge, eight times over — so the badges are its odd tiles. [gym]
     * is zero-based in the order Generation I awards them, which is the order
     * the card draws them in.
     */
    fun badge(gym: Int): ImageBitmap? {
        if (gym !in 0 until BADGES) return null
        val key = "$tintId/badge/$gym"
        memory[key]?.let { return it }

        val sheet = decode(file(BADGE_SHEET)) ?: return null
        if (sheet.width != BADGE_SIZE || sheet.height != BADGE_SIZE * BADGE_TILES) {
            sheet.recycle()
            return null
        }

        val cut = Bitmap.createBitmap(sheet, 0, (gym * 2 + 1) * BADGE_SIZE, BADGE_SIZE, BADGE_SIZE)
        sheet.recycle()
        return tinted(cut).asImageBitmap().also { memory[key] = it }
    }

    fun bytesOnDisk(): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        directory.deleteRecursively()
        memory.clear()
    }

    /**
     * One trainer, or null when that one is not on the device.
     *
     * [cutout] drops the white field the decomp's picture carries, so the
     * trainer stands on the card rather than on a white plate.
     */
    fun load(id: String, cutout: Boolean = false): ImageBitmap? {
        if (ALL.none { it.id == id }) return null
        val key = "$tintId/${if (cutout) "cut/" else ""}$id"
        memory[key]?.let { return it }

        val source = file(id).takeIf { it.isFile } ?: return null
        val decoded = runCatching {
            BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null

        val ramp = tintRamp
        val finished = runCatching {
            when {
                ramp != null -> recolourToRamp(decoded, ramp, cutout)
                cutout -> cutOutLightest(decoded)
                else -> decoded
            }
        }.getOrNull() ?: decoded
        return finished.asImageBitmap().also { memory[key] = it }
    }

    /** Fetches one if it is not here yet. */
    fun fetch(id: String): File? {
        val extra = EXTRA_ART[id]
        if (extra == null && ALL.none { it.id == id }) return null
        val target = file(id)
        if (target.isFile && target.length() > 0) return target

        val url = URL(if (extra != null) "$ROOT_URL/$extra" else "$ROOT_URL/gfx/trainers/$id.png")
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

        // It must be the shape this expects before it lands, so a proxy's
        // error page cannot sit on disk looking like a trainer.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val (width, height) = EXTRA_SIZE[id] ?: (SIZE to SIZE)
        if (bounds.outWidth != width || bounds.outHeight != height) return null

        directory.mkdirs()
        val staged = File(directory, "$id.png.part")
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
            // The sheets go with them: the badges are the other half of what a
            // trainer card is drawn from, and the trade art is the rest of what
            // pokered has that is not a Pokémon.
            val wanted = ALL.map { it.id } + EXTRA_ART.keys
            val total = wanted.size
            onProgress(DownloadProgress(0, total))
            val failed = fetchInParallel(wanted, total, onProgress) { fetch(it) }
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

        private const val ROOT_URL = "https://raw.githubusercontent.com/pret/pokered/$COMMIT"

        /** The badge sheet, kept under this name beside the trainers. */
        const val BADGE_SHEET = "badges"

        /** The Game Boy the trade animation draws, already a whole picture. */
        const val TRADE_GAME_BOY = "trade-game-boy"

        /** The cable's tiles, assembled by the tilemap in [Gen1TradeScene]. */
        const val TRADE_CABLE = "trade-cable"

        /** The ball that travels down the cable, as four flips of one tile. */
        const val TRADE_BALL = "trade-ball"

        /**
         * The battle HUD's four balls, the first of which is the mark the
         * Pokédex puts beside a species the player has caught.
         *
         * `LoadPokedexTilePatterns` copies exactly one tile of this over the
         * dex screen's own graphics for that job, and `bills_pc.asm` does the
         * same — so a storage system marking its list with it is using the
         * tile the cartridge uses, in the place the cartridge uses it.
         */
        const val DEX_BALLS = "balls"

        /** Which of the four: caught, ailing, fainted, and an empty slot. */
        const val BALL_CAUGHT = 0
        const val BALL_AILING = 1
        const val BALL_FAINTED = 2
        const val BALL_EMPTY = 3

        /** One tile's side, which is what the cartridge counts in. */
        const val TILE = 8

        /**
         * Everything from pokered that is not a trainer, by where it lives.
         *
         * Kept with the trainers rather than in a store of its own because it
         * is the same download from the same pinned commit, and a player who
         * has fetched the trainers should not then discover a second, smaller
         * download standing between them and a trade.
         */
        val EXTRA_ART: Map<String, String> = linkedMapOf(
            BADGE_SHEET to "gfx/trainer_card/badges.png",
            TRADE_GAME_BOY to "gfx/trade/game_boy.png",
            TRADE_CABLE to "gfx/trade/link_cable.png",
            TRADE_BALL to "gfx/trade/cable_ball.png",
            DEX_BALLS to "gfx/battle/balls.png",
        )

        /** What each must measure, so a proxy's error page cannot land as art. */
        val EXTRA_SIZE: Map<String, Pair<Int, Int>> = mapOf(
            BADGE_SHEET to (BADGE_SIZE to BADGE_SIZE * BADGE_TILES),
            TRADE_GAME_BOY to (48 to 64),
            TRADE_CABLE to (24 to 40),
            TRADE_BALL to (16 to 16),
            DEX_BALLS to (32 to 8),
        )

        /** The eight gyms. */
        const val BADGES = 8

        /** One tile of the badge sheet, in real pixels. */
        const val BADGE_SIZE = 16

        /**
         * Tiles down the sheet: a gym leader's face and that gym's badge,
         * eight times over. Only the badges are wanted here, which is every
         * odd one.
         */
        const val BADGE_TILES = BADGES * 2

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
