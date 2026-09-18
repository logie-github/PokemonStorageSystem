package com.logie.gen1storage.sprites

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.logie.gen1storage.download.DownloadProgress
import com.logie.gen1storage.gen1recomp.GameVersion
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
    /** Whether Generation II art takes the palette. See [SpriteStore]. */
    var gbcFollowsPalette: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            memory.clear()
        }

    fun setTint(id: String, ramp: IntArray?) {
        if (id == tintId) return
        tintId = id
        tintRamp = ramp
        memory.clear()
    }

    fun file(id: String): File = File(directory, "$id.png")

    fun has(id: String): Boolean = file(id).let { it.isFile && it.length() > 0 }

    /** The trainers, plus the sheets the badges and the trade are cut from. */
    fun count(): Int = ALL.count { has(it.id) } + EXTRA_ART.keys.count { has(it) } +
        GEN2_ART.keys.count { has(it) } + GEN2_TRAINER_IDS.count { has(it) }

    /**
     * One of the extra sheets whole, recoloured onto the palette in force.
     *
     * The trade's Game Boy is already a picture and is used as one; the cable
     * and the ball are tile strips and are cut up by [tile].
     */
    fun art(id: String): ImageBitmap? {
        if (id !in EXTRA_ART && id !in GEN2_ART) return null
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
        if (id !in EXTRA_ART && id !in GEN2_ART) return null
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
        val key = "$tintId/badge/cut/$gym"
        memory[key]?.let { return it }

        val sheet = decode(file(BADGE_SHEET)) ?: return null
        if (sheet.width != BADGE_SIZE || sheet.height != BADGE_SIZE * BADGE_TILES) {
            sheet.recycle()
            return null
        }

        val cut = Bitmap.createBitmap(sheet, 0, (gym * 2 + 1) * BADGE_SIZE, BADGE_SIZE, BADGE_SIZE)
        sheet.recycle()
        // Cut out of the white field the sheet draws them on, the same as the
        // trainers are. A badge is a shape on the card, and the field around
        // it read as a white plate laid over the card's own colour with eight
        // badges sitting on it.
        return tinted(cut, cutout = true).asImageBitmap().also { memory[key] = it }
    }

    /**
     * One 8x8 tile of the Pokédex sheet, as plain pixels for the printer.
     *
     * Handed back undecorated — no tint, no cut-out — because the printer
     * draws it into a print of its own and decides the colours there. Null
     * while the sheet has not been downloaded, which is the ordinary state
     * before DOWNLOADS has run and is why every caller has a fallback.
     *
     * The sheet is sixteen tiles across; [index] counts along it in reading
     * order. See [DEX_SHEET] for what is on it and why it is the printer's.
     */
    fun dexTile(index: Int): Bitmap? {
        val sheet = decode(file(DEX_SHEET)) ?: return null
        val across = sheet.width / DEX_TILE
        if (across <= 0 || index < 0 || index >= across * (sheet.height / DEX_TILE)) {
            sheet.recycle()
            return null
        }
        val cut = Bitmap.createBitmap(
            sheet,
            (index % across) * DEX_TILE,
            (index / across) * DEX_TILE,
            DEX_TILE,
            DEX_TILE,
        )
        sheet.recycle()
        return cut
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
        // The player is not one of the trainer classes and is still a picture
        // a card can wear, so the extras are askable for by name too.
        if (ALL.none { it.id == id } && id !in EXTRA_ART && id !in GEN2_ART &&
            !isGen2Trainer(id)
        ) return null
        val key = "$tintId/${if (gbcFollowsPalette) "pal/" else ""}${if (cutout) "cut/" else ""}$id"
        memory[key]?.let { return it }

        val source = file(id).takeIf { it.isFile } ?: return null
        val bytes = runCatching { source.readBytes() }.getOrNull()
        if (bytes == null || !isCompletePng(bytes)) {
            // fetch() treats "the file exists" as "already have it" and never
            // re-checks completeness, so a trainer cached from before a
            // download was made to reject a short read stayed exactly this
            // broken forever. Deleted here so the next fetch actually
            // replaces it instead of skipping it as done.
            source.delete()
            return null
        }
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: run {
            source.delete()
            return null
        }

        // Generation II's pictures arrive in the colours the Game Boy Color
        // gave them, the same as its Pokémon do, so they are left alone
        // unless GBC SPRITES has been set to follow the palette.
        val ramp = tintRamp.takeIf { !isGen2Trainer(id) || gbcFollowsPalette }
        val finished = runCatching {
            when {
                ramp != null -> recolourToRamp(decoded, ramp, cutout)
                cutout -> cutOutLightest(decoded)
                else -> decoded
            }
        }.getOrNull() ?: decoded
        return finished.asImageBitmap().also { memory[key] = it }
    }

    /**
     * One of Johto's eight, off pokecrystal's sheet.
     *
     * Which is badges alone rather than leaders and badges alternating, so
     * the tile wanted is the gym itself and not twice it plus one.
     */
    fun johtoBadge(gym: Int): ImageBitmap? {
        if (gym !in 0 until BADGES) return null
        val key = "$tintId/johto/cut/$gym"
        memory[key]?.let { return it }

        val sheet = decode(file(GEN2_BADGE_SHEET)) ?: return null
        if (sheet.width != BADGE_SIZE || sheet.height != BADGE_SIZE * GEN2_BADGE_TILES) {
            sheet.recycle()
            return null
        }
        val cut = Bitmap.createBitmap(sheet, 0, gym * BADGE_SIZE, BADGE_SIZE, BADGE_SIZE)
        sheet.recycle()
        return tinted(cut, cutout = true).asImageBitmap().also { memory[key] = it }
    }

    /** Fetches one if it is not here yet. */
    fun fetch(id: String): File? {
        val extra = EXTRA_ART[id] ?: GEN2_ART[id]
        if (extra == null && ALL.none { it.id == id } && !isGen2Trainer(id)) return null
        val target = file(id)
        if (target.isFile && target.length() > 0) return target

        val gen2 = gen2TrainerUrl(id)
        val root = GEN2_ROOT_URL.takeIf { id in GEN2_ART } ?: ROOT_URL
        val url = URL(
            when {
                gen2 != null -> gen2
                extra != null -> "$root/$extra"
                else -> "$root/gfx/trainers/$id.png"
            }
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }
        // Left connected on purpose: a disconnect here costs the next file a
        // whole handshake, and there are forty-five of them from one host.
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val bytes = runCatching { connection.inputStream.use { it.readBytes() } }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        // A dropped connection does not always throw — some paths just hand
        // back what arrived before the socket closed, which used to land on
        // disk looking like a trainer: a real file, a real name, and a
        // truncated picture, because IHDR (and so the bounds check below)
        // sits near the front of a PNG and decodes fine long before the
        // pixel data run out. The server's declared length is the one thing
        // that says how much there was supposed to be.
        val expectedLength = connection.contentLengthLong
        if (expectedLength > 0 && bytes.size.toLong() != expectedLength) return null
        // Content-Length is not always sent (chunked responses carry none at
        // all), so the file's own structure is checked too: an IEND-less tail
        // means the transfer was cut short regardless of what the header said.
        if (!isCompletePng(bytes)) return null

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
            val wanted = ALL.map { it.id } + EXTRA_ART.keys + GEN2_ART.keys + GEN2_TRAINER_IDS
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

        /**
         * Generation II's, from the tip rather than a pinned commit: this art
         * has been still for years and pokecrystal is the reference for it.
         */
        private const val GEN2_ROOT_URL = "https://raw.githubusercontent.com/pret/pokecrystal/master"

        /**
         * The player's own pic, which is who a trainer card has on it.
         *
         * `gfx/player/red.png` — the picture the cartridge draws in the Hall
         * of Fame and on the card itself, at the same fifty-six pixels every
         * other trainer is drawn at. It is what a card wears until its owner
         * picks somebody else.
         */
        const val PLAYER = "player"

        /** The badge sheet, kept under this name beside the trainers. */
        const val BADGE_SHEET = "badges"

        /**
         * Johto's eight, from pokecrystal's own trainer card.
         *
         * Kanto's eight are the sheet above: a Generation II playthrough wins
         * the same eight badges Red does over there, so the art this app
         * already has is the art for them.
         *
         * The sheet is badges alone, eleven tiles of them — the eight and
         * three pieces of the card's own furniture — where Generation I's
         * alternates a leader's face with their badge.
         */
        const val GEN2_BADGE_SHEET = "badges_gen2"

        /** The two the Generation II card wears, by the player's gender. */
        const val GEN2_PLAYER_MALE = "chris"
        const val GEN2_PLAYER_FEMALE = "kris"

        /** The Game Boy the trade animation draws, already a whole picture. */
        const val TRADE_GAME_BOY = "trade-game-boy"

        /** The cable's tiles, as the trade tilemap arranges them. */
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

        /**
         * The Pokédex screen's own sheet, which is also the printer's.
         *
         * `gfx/pokedex/pokedex.png` — sixteen tiles by four. The printed
         * Pokédex page is drawn out of it: the beaded rule that separates the
         * head of the page from its text is these tiles, and so are the `No.`
         * and the prime marks that set a height in feet and inches. Printing
         * a page with a rule this app drew itself would be a picture of a
         * printout rather than the printout.
         */
        const val DEX_SHEET = "pokedex"

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
            PLAYER to "gfx/player/red.png",
            BADGE_SHEET to "gfx/trainer_card/badges.png",
            TRADE_GAME_BOY to "gfx/trade/game_boy.png",
            TRADE_CABLE to "gfx/trade/link_cable.png",
            TRADE_BALL to "gfx/trade/cable_ball.png",
            DEX_BALLS to "gfx/battle/balls.png",
            DEX_SHEET to "gfx/pokedex/pokedex.png",
        )

        /**
         * Generation II's art, from pokecrystal rather than pokered.
         *
         * Its own map so [fetch] knows which decompilation to ask; everything
         * else about it — where it lands, how it is cut out, how it is tinted
         * — is the same as the rest.
         */
        val GEN2_ART: Map<String, String> = linkedMapOf(
            GEN2_BADGE_SHEET to "gfx/trainer_card/badges.png",
            GEN2_PLAYER_MALE to "gfx/trainer_card/chris_card.png",
            GEN2_PLAYER_FEMALE to "gfx/trainer_card/kris_card.png",
        )

        /** Tiles down the Generation II sheet: eight badges, then furniture. */
        const val GEN2_BADGE_TILES = 11

        /** What each must measure, so a proxy's error page cannot land as art. */
        val EXTRA_SIZE: Map<String, Pair<Int, Int>> = mapOf(
            BADGE_SHEET to (BADGE_SIZE to BADGE_SIZE * BADGE_TILES),
            GEN2_BADGE_SHEET to (BADGE_SIZE to BADGE_SIZE * GEN2_BADGE_TILES),
            GEN2_PLAYER_MALE to (40 to 56),
            GEN2_PLAYER_FEMALE to (40 to 56),
            TRADE_GAME_BOY to (48 to 64),
            TRADE_CABLE to (24 to 40),
            TRADE_BALL to (16 to 16),
            DEX_BALLS to (32 to 8),
            DEX_SHEET to (128 to 32),
        )

        /** One tile of the Pokédex sheet, in real pixels. */
        const val DEX_TILE = 8

        /**
         * `No.`, which the printed Pokédex page sets under the picture. Its
         * own tile on the sheet rather than three characters of the text
         * face, because that is what the cartridge draws there.
         */
        const val DEX_TILE_NUMBER = 43

        /** The ball strung along the rule that divides the page. */
        const val DEX_TILE_BALL = 30

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
         * Generation II's trainer classes, by the name their picture is filed
         * under, alphabetically as the folder holds them.
         *
         * Two games' worth of art from one list: Gold and Silver share
         * pokegold's drawing of a class and Crystal has its own in
         * pokecrystal, so an id here is a prefix and a name — see
         * [gen2Trainers]. Eusine is the one Crystal added and pokegold does
         * not have (he is filed as `mysticalman`), so a Gold or Silver card
         * is not offered him.
         */
        val GEN2_TRAINER_NAMES: List<TrainerSprite> = listOf(
            TrainerSprite("beauty", "BEAUTY"),
            TrainerSprite("biker", "BIKER"),
            TrainerSprite("bird_keeper", "BIRD KEEPER"),
            TrainerSprite("blackbelt_t", "BLACKBELT"),
            TrainerSprite("blaine", "BLAINE"),
            TrainerSprite("blue", "BLUE"),
            TrainerSprite("boarder", "BOARDER"),
            TrainerSprite("brock", "BROCK"),
            TrainerSprite("bruno", "BRUNO"),
            TrainerSprite("bug_catcher", "BUG CATCHER"),
            TrainerSprite("bugsy", "BUGSY"),
            TrainerSprite("burglar", "BURGLAR"),
            TrainerSprite("cal", "CAL"),
            TrainerSprite("camper", "CAMPER"),
            TrainerSprite("champion", "CHAMPION"),
            TrainerSprite("chuck", "CHUCK"),
            TrainerSprite("clair", "CLAIR"),
            TrainerSprite("cooltrainer_f", "COOLTRAINER♀"),
            TrainerSprite("cooltrainer_m", "COOLTRAINER♂"),
            TrainerSprite("erika", "ERIKA"),
            TrainerSprite("executive_f", "EXECUTIVE♀"),
            TrainerSprite("executive_m", "EXECUTIVE♂"),
            TrainerSprite("falkner", "FALKNER"),
            TrainerSprite("firebreather", "FIREBREATHER"),
            TrainerSprite("fisher", "FISHER"),
            TrainerSprite("gentleman", "GENTLEMAN"),
            TrainerSprite("grunt_f", "GRUNT♀"),
            TrainerSprite("grunt_m", "GRUNT♂"),
            TrainerSprite("guitarist", "GUITARIST"),
            TrainerSprite("hiker", "HIKER"),
            TrainerSprite("janine", "JANINE"),
            TrainerSprite("jasmine", "JASMINE"),
            TrainerSprite("juggler", "JUGGLER"),
            TrainerSprite("karen", "KAREN"),
            TrainerSprite("kimono_girl", "KIMONO GIRL"),
            TrainerSprite("koga", "KOGA"),
            TrainerSprite("lass", "LASS"),
            TrainerSprite("lt_surge", "LT.SURGE"),
            TrainerSprite("medium", "MEDIUM"),
            TrainerSprite("misty", "MISTY"),
            TrainerSprite("morty", "MORTY"),
            TrainerSprite("mysticalman", "EUSINE"),
            TrainerSprite("oak", "OAK"),
            TrainerSprite("officer", "OFFICER"),
            TrainerSprite("picnicker", "PICNICKER"),
            TrainerSprite("pokefan_f", "POKéFAN♀"),
            TrainerSprite("pokefan_m", "POKéFAN♂"),
            TrainerSprite("pokemaniac", "POKéMANIAC"),
            TrainerSprite("pryce", "PRYCE"),
            TrainerSprite("psychic_t", "PSYCHIC"),
            TrainerSprite("red", "RED"),
            TrainerSprite("rival1", "RIVAL"),
            TrainerSprite("rival2", "RIVAL 2"),
            TrainerSprite("sabrina", "SABRINA"),
            TrainerSprite("sage", "SAGE"),
            TrainerSprite("sailor", "SAILOR"),
            TrainerSprite("schoolboy", "SCHOOLBOY"),
            TrainerSprite("scientist", "SCIENTIST"),
            TrainerSprite("skier", "SKIER"),
            TrainerSprite("super_nerd", "SUPER NERD"),
            TrainerSprite("swimmer_f", "SWIMMER♀"),
            TrainerSprite("swimmer_m", "SWIMMER♂"),
            TrainerSprite("teacher", "TEACHER"),
            TrainerSprite("twins", "TWINS"),
            TrainerSprite("whitney", "WHITNEY"),
            TrainerSprite("will", "WILL"),
            TrainerSprite("youngster", "YOUNGSTER"),
        )

        /** Gold and Silver's art, from pokegold. */
        const val GS_PREFIX = "gs_"

        /** Crystal's own, from pokecrystal. */
        const val CRYSTAL_PREFIX = "c_"

        /** The one class pokegold has no picture of. */
        private const val CRYSTAL_ONLY = "mysticalman"

        /** Which trainers a card of this game may wear, ids and all. */
        fun gen2Trainers(version: GameVersion): List<TrainerSprite> {
            val crystal = version == GameVersion.CRYSTAL
            val prefix = if (crystal) CRYSTAL_PREFIX else GS_PREFIX
            return GEN2_TRAINER_NAMES
                .filter { crystal || it.id != CRYSTAL_ONLY }
                .map { TrainerSprite(prefix + it.id, it.label) }
        }

        /** Every Generation II trainer id, for the download to walk. */
        val GEN2_TRAINER_IDS: List<String> =
            gen2Trainers(GameVersion.GOLD).map { it.id } +
                gen2Trainers(GameVersion.CRYSTAL).map { it.id }

        /** Whether an id names one of them, and where its picture comes from. */
        fun gen2TrainerUrl(id: String): String? = when {
            id.startsWith(GS_PREFIX) ->
                "https://raw.githubusercontent.com/pret/pokegold/master/gfx/trainers/" +
                    "${id.removePrefix(GS_PREFIX)}.png"

            id.startsWith(CRYSTAL_PREFIX) ->
                "https://raw.githubusercontent.com/pret/pokecrystal/master/gfx/trainers/" +
                    "${id.removePrefix(CRYSTAL_PREFIX)}.png"

            else -> null
        }

        fun isGen2Trainer(id: String): Boolean = gen2TrainerUrl(id) != null

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
