package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.logie.gen1storage.pokemon.Gen1Pokemon
import kotlin.math.floor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.logie.gen1storage.sprites.TrainerStore
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.sprites.SpriteStore
import com.logie.gen1storage.sync.RemoteSave

/**
 * A playthrough as its trainer card.
 *
 * The cartridge's own TRAINER INFO screen, which is what Generation I has
 * instead of the later games' trainer card: NAME, MONEY, TIME, and the eight
 * badges in gym order. The name it is known by here — a cartridge can be given
 * one — leads, and the game it is from colours the whole card, so a shelf of
 * them is read by colour before it is read by name.
 *
 * This is what a save *is*, from the player's side. The app used to show a save
 * as a row of facts about a file; a trainer card is the same facts as the thing
 * the player actually remembers having.
 */
@Composable
fun Gen1TrainerCard(
    remote: RemoteSave,
    save: Gen1RecompSave?,
    /** What the player has called this one, if they have. */
    title: String,
    sprites: SpriteStore,
    /** The trainer art, for the portrait the player has chosen. */
    trainers: TrainerStore,
    /** Which trainer this card wears, or null while it wears none. */
    trainerSprite: String?,
    spriteRevision: Int,
    modifier: Modifier = Modifier,
    /** Where the cursor is, drawn as the arrow every other row uses. */
    cursor: Boolean = false,
    /** Whether this is the card in the machine. */
    inserted: Boolean = false,
    /** The long form: the badges get their own line and the lead is bigger. */
    full: Boolean = false,
) {
    val palette = paletteFor(remote.version.id)
    val trainer = (save?.trainerName ?: remote.summary.trainerName ?: "?").uppercase()
    val lead = save?.party?.firstOrNull()
    val read = save != null

    Gen1SpineCard(
        // Every card on the shelf the same height, whatever is on it. A
        // Generation II card carries sixteen badges in two rows where a
        // Generation I card carries eight in one, and a card whose save has
        // not been read yet carries none at all — so left to its contents a
        // list of cards came out as a list of different-sized cards, and one
        // of them changed size as its save arrived. The long form is exempt:
        // it is the only card on the screen and has more to say.
        if (full) modifier else modifier.height(gen1Dp(CARD_PIXELS)),
        fill = palette.lightest,
        ink = palette.darkest,
        // The game's own name down the edge, so a list holding every game's
        // cards at once is read by its spines the way a shelf of books is.
        spine = remote.version.label,
        // The second darkest of the four: dark enough to carry the lightest
        // as letters, light enough not to be taken for the border.
        spineFill = palette.dark,
        spineInk = palette.lightest,
    ) {
        // The badge case is eight tiles and the seven gaps between them, and
        // it is the one thing on the card with a size of its own: a badge is
        // sixteen pixels because that is how it was drawn. So it is measured
        // first and the portrait takes what is left over.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cased = gen1Dp(
                TrainerStore.BADGES * BADGE_PIXELS + (TrainerStore.BADGES - 1) * BADGE_GAP
            )
            val spare = maxWidth - cased
            // Beside the badges where there is room for a portrait bigger than
            // the one that used to sit above them, and above them where there
            // is not. A narrow phone would otherwise hand the portrait a strip
            // two Pokémon wide and call it a photograph.
            val beside = spare >= gen1Dp(SMALL_PORTRAIT_PIXELS)

            val header: @Composable ColumnScope.() -> Unit = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GbText(
                        if (cursor) "▶$title" else title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (inserted) GbText("IN USE", style = Gen1TextSmall, maxLines = 1)
                }
                Field("NAME", trainer)
                Field("MONEY", save?.money?.let { "¥$it" } ?: " ")
                Field("TIME", save?.playTimeText ?: " ")
            }
            val badges: @Composable () -> Unit = {
                // A Generation II card has sixteen to show, and they are two
                // different sets: Johto's eight off pokecrystal's own sheet,
                // Kanto's eight off the one this app already had — the same
                // eight badges Red wins, because they are the same gyms.
                if (save?.isGen2 == true) {
                    Column(verticalArrangement = Arrangement.spacedBy(gen1Dp(BADGE_GAP))) {
                        Badges(save.johtoBadges, trainers, spriteRevision, johto = true)
                        Badges(save.kantoBadges, trainers, spriteRevision)
                    }
                } else {
                    Badges(
                        save?.badges ?: List(Gen1RecompSave.BADGE_IDS.size) { false },
                        trainers,
                        spriteRevision,
                    )
                }
            }
            val portrait: @Composable (Modifier, Dp) -> Unit = { portraitModifier, width ->
                Portrait(
                    modifier = portraitModifier,
                    width = width,
                    // A card nobody has dressed wears the player, which is who
                    // is on a trainer card. It used to wear nothing at all,
                    // and a card with only the party's lead on it reads as a
                    // card that failed to load rather than as a default.
                    // Nobody chosen: the game's own player, which for a
                    // Generation II save is the boy or the girl its trainer
                    // card wears rather than Red.
                    trainerSprite = trainerSprite ?: when {
                        save?.isGen2 != true -> TrainerStore.PLAYER
                        save.isFemale -> TrainerStore.GEN2_PLAYER_FEMALE
                        else -> TrainerStore.GEN2_PLAYER_MALE
                    },
                    trainers = trainers,
                    lead = if (read) lead else null,
                    gameVersionId = remote.version.id,
                    sprites = sprites,
                    spriteRevision = spriteRevision,
                )
            }

            if (beside) {
                // The portrait runs the whole height of the card beside both
                // the name and the case, rather than sitting in the corner
                // over the case with the card's own height to itself. Drawn
                // with `matchParentSize` so it takes the height the rest of
                // the card settles on without being one of the things that
                // decides it.
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(end = spare)) {
                        header()
                        Spacer(Modifier.weight(1f))
                        badges()
                    }
                    // Standing on the same line the badge case ends on: the
                    // case is the last thing in the column beside it, so the
                    // bottom of this box is the bottom of the badges, and a
                    // trainer with their feet on it is a trainer standing on
                    // the card rather than floating over the corner of it.
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) {
                        portrait(Modifier.fillMaxHeight(), spare)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), content = header)
                        portrait(Modifier, gen1Dp(SMALL_PORTRAIT_PIXELS))
                    }
                    Spacer(Modifier.weight(1f))
                    badges()
                }
            }
        }

        if (full) {
            Spacer(Modifier.height(gen1Dp(3)))
            Field("GAME", GameVersion.fromId(remote.version.id)?.label ?: "?")
            Field(
                "POKéMON",
                save?.let { "${it.partyCount} OUT, ${it.storedCount} STORED" } ?: " ",
            )
            Field("SEEN", save?.let { "${it.dexOwnedCount}" } ?: " ")
            Field("BOXES", save?.let { "${it.boxCount}" } ?: " ")
        }
    }
}

/**
 * The card's own corner: the trainer, with the party's lead over their
 * shoulder.
 *
 * The trainer fills the corner and the first Pokémon in the party stands in
 * the bottom right of it — overlapping, the way a photograph of the two of
 * them would be, rather than set out side by side as a pair of equal exhibits.
 *
 * Both are cut out of the white field the decomps draw them on: on a Game Boy
 * the lightest of the four shades *is* the background, and painted onto a
 * tinted card it reads as a white plate with somebody standing on it.
 *
 * Sized in whole multiples of the art's own resolution rather than to the
 * space available. A trainer is fifty-six pixels square and nothing else: at
 * three times that every pixel of it is a clean three-by-three block, and at
 * three and a half some pixels are three across and some are four, which on a
 * picture with a one-pixel outline is a picture with a wobbly outline. So the
 * portrait takes the largest whole multiple that fits what it has been given
 * and leaves the remainder as air around itself.
 */
@Composable
private fun Portrait(
    modifier: Modifier,
    /** How wide a column the card has handed over. */
    width: Dp,
    trainerSprite: String?,
    trainers: TrainerStore,
    lead: Gen1Pokemon?,
    gameVersionId: String?,
    sprites: SpriteStore,
    spriteRevision: Int,
) {
    BoxWithConstraints(modifier.width(width)) {
        val density = LocalDensity.current
        val room = with(density) { minOf(maxWidth, maxHeight).toPx() }
        val scale = floor(room / ART_PIXELS).toInt().coerceAtLeast(1)
        val side = with(density) { (scale * ART_PIXELS).toDp() }
        // Three quarters of the trainer. Half read as a pet at their heel;
        // this reads as the two of them standing together, which is what a
        // trainer card is a picture of.
        //
        // The room it is given rather than the size it is drawn at: the
        // sprite snaps itself to a whole multiple of its own art inside
        // whatever box it is handed (see [Gen1WholePixels]), so the box can
        // be three quarters of anything without the picture in it ever being
        // scaled by a fraction. Which also means this no longer has to know
        // that Generation II draws its Pokémon at three different sizes.
        val leadSide = side * LEAD_FRACTION
        // And nothing at all where there is no room to draw it smaller than
        // the trainer. The smallest whole multiple any art has is its own
        // size, so on a card drawn at one times — the shelf's, beside another
        // card — the lead came out exactly as big as the trainer and was laid
        // over him in the corner, the two of them reading as one torn picture
        // rather than as somebody standing with their Pokémon. A trainer
        // alone is what the card has always been able to be.
        val showLead = lead != null && scale >= 2

        Box(Modifier.size(side).align(Alignment.BottomCenter)) {
            if (trainerSprite != null) {
                Gen1TrainerSprite(
                    id = trainerSprite,
                    store = trainers,
                    revision = spriteRevision,
                    modifier = Modifier.fillMaxSize(),
                    sizeInPixels = null,
                    cutout = true,
                )
            }
            if (showLead && lead != null) {
                Gen1Sprite(
                    speciesId = lead.spriteSpeciesId(),
                    gameVersionId = gameVersionId,
                    store = sprites,
                    revision = spriteRevision,
                    modifier = Modifier.align(Alignment.BottomEnd).size(leadSide),
                    sizeInPixels = null,
                    cutout = true,
                )
            }
        }
    }
}

/**
 * One trainer's battle sprite, off the device.
 *
 * Blank where that one has not been downloaded: the bracketed mark a Pokémon
 * gets means "there is no art for this species", and a trainer the player
 * deliberately chose is better shown as a gap than as a shrug.
 */
@Composable
fun Gen1TrainerSprite(
    id: String,
    store: TrainerStore,
    revision: Int,
    modifier: Modifier = Modifier,
    /** A side in game pixels, or null to take whatever the modifier gives it. */
    sizeInPixels: Int? = TrainerStore.SIZE,
    /** Drops the white field the decomp's picture carries. */
    cutout: Boolean = false,
) {
    var image by remember(id, revision, cutout) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id, revision, store, cutout) {
        image = withContext(Dispatchers.IO) { store.load(id, cutout) }
    }
    Box(
        modifier.then(if (sizeInPixels == null) Modifier else Modifier.size(gen1Dp(sizeInPixels))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = id,
                modifier = Modifier.fillMaxSize(),
                // A whole multiple or nothing — Generation II draws its
                // trainers at their own sizes too. See [Gen1WholePixels].
                contentScale = Gen1WholePixels,
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/**
 * "NAME/RED" — the games' own label-then-value, on one line.
 *
 * The value sits against its label rather than against the far edge. TRAINER
 * INFO draws the slash and then the value immediately after it, and pushing
 * the value across the card instead put it under the portrait with a gulf in
 * between, which reads as two columns that have nothing to do with each other.
 */
@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GbText("$label/", style = Gen1TextSmall, maxLines = 1)
        GbText(value, maxLines = 1)
    }
}

/**
 * The eight gyms, in order, as the badges themselves.
 *
 * The cartridge's own badge art, cut from pokered's trainer-card sheet, with
 * nothing drawn where a badge has not been won — which is exactly what the
 * card does: the case is there, and it fills up as the player earns them.
 *
 * Unlabelled on purpose: the badges are recognisable on sight, and a word over
 * them would only cost the row its resemblance to the thing it is copying. One
 * pixel between them, which is enough to keep two badges that meet at the edge
 * from reading as one shape now that they are cut out of their own field.
 */
@Composable
private fun Badges(
    won: List<Boolean>,
    trainers: TrainerStore,
    revision: Int,
    /** Johto's row, which is drawn from the Generation II sheet. */
    johto: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(gen1Dp(BADGE_GAP))) {
        for (gym in 0 until TrainerStore.BADGES) {
            Badge(gym, won.getOrElse(gym) { false }, trainers, revision, johto)
        }
    }
}

/**
 * One gym's badge, or the space it will occupy.
 *
 * Blank while the sheet has not been downloaded, for the same reason a trainer
 * the player picked shows as a gap: the row keeps its shape either way, and
 * DOWNLOADS fills it in.
 */
@Composable
private fun Badge(
    gym: Int,
    earned: Boolean,
    trainers: TrainerStore,
    revision: Int,
    johto: Boolean = false,
) {
    var image by remember(gym, revision, johto) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(gym, revision, earned, trainers, johto) {
        image = if (!earned) null else withContext(Dispatchers.IO) {
            if (johto) trainers.johtoBadge(gym) else trainers.badge(gym)
        }
    }
    Box(Modifier.size(gen1Dp(BADGE_PIXELS))) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/**
 * The narrowest column worth giving the portrait, in game pixels.
 *
 * What the corner used to be. Below this the card falls back to the old
 * arrangement — portrait beside the name, badges on their own line — rather
 * than squeezing the pair into whatever a narrow screen has left over.
 */
private const val SMALL_PORTRAIT_PIXELS = 32

/** One badge's side, in game pixels — the sheet's own tile, scaled whole. */
private const val BADGE_PIXELS = 16

/** The air between two badges, in game pixels. */
private const val BADGE_GAP = 1

/**
 * How tall a card on the shelf stands, in game pixels.
 *
 * Room for the name, the three fields under it and two rows of badges — the
 * most any card has to hold — so that a Generation I card, a Generation II
 * card and one whose save has not arrived yet are all the same object.
 *
 * Four lines of text at this app's own leading come to about fifty pixels
 * and two rows of sixteen-pixel badges with a gap between them to thirty
 * three, plus the frame's inset either side: ninety six leaves the tallest
 * card its room rather than clipping it, and the shorter ones carry the
 * difference as air under the name.
 */
private const val CARD_PIXELS = 96

/**
 * How big the party's lead stands beside the trainer, as a fraction of them.
 *
 * Three quarters: tall enough to be standing with them rather than kept at
 * their heel, short enough that the trainer is still whose card this is.
 */
private const val LEAD_FRACTION = 0.75f

/** What a decomp's sprite is drawn at, and the unit the portrait scales by. */
private const val ART_PIXELS = 56f

/**
 * Which trainer a card wears.
 *
 * Every trainer the game has, in the game's own order, each shown as itself
 * rather than named in a list: a row of words would be asking the player to
 * remember what a CHANNELER looks like. NONE is first and puts the card back
 * to showing the party's lead.
 *
 * A trainer whose art is not on the device is still offered and still picked;
 * it simply shows as a gap until DOWNLOADS has fetched it. Hiding them would
 * mean the list changing shape depending on what had arrived.
 */
@Composable
fun TrainerSpritePicker(
    chosen: String?,
    store: TrainerStore,
    revision: Int,
    /** Which game's card this is, which decides whose trainers are offered. */
    version: GameVersion?,
    /** Whether the player is the girl, for the row that puts the card back. */
    female: Boolean = false,
    onChoose: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    // A Generation II card wears Generation II's classes. Its own game's, at
    // that: Gold and Silver share pokegold's drawing of a class and Crystal
    // redrew them, so a Crystal card is offered Crystal's.
    val rows = when {
        version != null && version.generation == 2 -> TrainerStore.gen2Trainers(version)
        else -> TrainerStore.ALL
    }
    val player = when {
        version?.generation != 2 -> TrainerStore.PLAYER
        female -> TrainerStore.GEN2_PLAYER_FEMALE
        else -> TrainerStore.GEN2_PLAYER_MALE
    }
    // The player, then every trainer, then the way out.
    val count = rows.size + 2
    val cursor = rememberCursorLayerHandle(count) { index ->
        when (index) {
            0 -> onChoose(null)
            count - 1 -> onCancel()
            else -> onChoose(rows[index - 1].id)
        }
    }
    val at = cursor.index
    val scroll = rememberLazyListState()
    LaunchedEffect(at) { scroll.scrollToRow(at) }

    Gen1Frame(Modifier.gen1MaxWidth().wrapContentWidth(), opening = true) {
        // What the card is wearing, said out loud. A tick against a row forty
        // rows down a list is not an answer to "which one is on it".
        GbText("TRAINER/" + (rows.firstOrNull { it.id == chosen }?.label ?: "PLAYER"))
        LazyColumn(
            Modifier.heightIn(max = 360.dp),
            state = scroll,
            userScrollEnabled = !LocalGen1Swipe.current,
        ) {
            item {
                // The card's own default, shown as itself. It read as "NONE"
                // with nothing beside it, which named the one row that is not
                // an absence of anything: taking it puts the player back.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Gen1TrainerSprite(
                        id = player,
                        store = store,
                        revision = revision,
                        sizeInPixels = PICKER_SPRITE_PIXELS,
                        cutout = true,
                    )
                    Gen1MenuRow(
                        "PLAYER",
                        selected = at == 0,
                        // The cursor goes where the finger went. Driving it by
                        // swipe and taking a row by tap are the same choice,
                        // and a cursor left pointing at a different row than
                        // the one that was taken is a list that looks as
                        // though it took the wrong one.
                        onSelect = { cursor.index = 0 },
                        onConfirm = { onChoose(null) },
                        mark = chosen == null,
                    )
                }
            }
            itemsIndexed(rows) { index, trainer ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Gen1TrainerSprite(
                        id = trainer.id,
                        store = store,
                        revision = revision,
                        sizeInPixels = PICKER_SPRITE_PIXELS,
                        cutout = true,
                    )
                    Gen1MenuRow(
                        trainer.label,
                        selected = at == index + 1,
                        onSelect = { cursor.index = index + 1 },
                        onConfirm = { onChoose(trainer.id) },
                        mark = chosen == trainer.id,
                    )
                }
            }
            item {
                Gen1MenuRow(
                    "CANCEL",
                    selected = at == count - 1,
                    onSelect = { cursor.index = count - 1 },
                    onConfirm = onCancel,
                )
            }
        }
    }
}

/** Small enough that a row is still a row, big enough to recognise. */
private const val PICKER_SPRITE_PIXELS = 24
