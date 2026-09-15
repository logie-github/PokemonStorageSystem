package com.logie.gen1storage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen2Data
import com.logie.gen1storage.pokemon.SpeciesInfo
import com.logie.gen1storage.pokemon.dexPageOf
import com.logie.gen1storage.pokemon.gen1SpeciesId
import com.logie.gen1storage.sprites.TrainerStore
import com.logie.gen1storage.sound.LocalGen1Audio

/**
 * One Pokédex over every cartridge at once.
 *
 * A cartridge's Pokédex is a fact about that playthrough: Red's says what Red
 * has caught and knows nothing of Blue. This machine holds several playthroughs
 * and can move a Pokémon between them, which makes a different question worth
 * asking — *is this species anywhere at all, and if so where* — and that is the
 * question no cartridge can answer.
 *
 * Three states per species, in order of how much they are worth knowing:
 * a Pokémon of that species is in this PC right now, so it can be sent to any
 * cartridge that wants it; some cartridge has caught one; some cartridge has at
 * least seen one. The first is the useful one, and it is why this screen exists
 * rather than being a pretty total.
 *
 * Only cartridges whose save has actually been read can say anything, so the
 * screen says how many it is speaking for and offers to read the rest.
 *
 * One window with the list scrolling inside it, the way the box is drawn: a
 * couple of hundred separate boxes scrolling past each other is a list of
 * windows rather than a Pokédex.
 *
 * All 251 species are listed, not only Generation I's 151: a Gold or Silver
 * save has its own 100 the first three cartridges never had, and a machine
 * that reads every save has no reason to leave them off. Every id here is
 * kept under Generation I's spelling for the two species the games spell
 * differently — [gen1SpeciesId] is what a Generation II save's own sets are
 * read through to line up with it.
 */
@Composable
fun DexScreen(state: UiState, model: StorageViewModel) {
    var filter by remember { mutableStateOf(DexFilter.ALL) }

    val loaded = state.saves.mapNotNull { state.save(it.key) }
    val owned = remember(loaded.size, state.loaded) {
        loaded.flatMapTo(HashSet()) { it.save?.dexOwned.orEmpty().map(::gen1SpeciesId) }
    }
    val seen = remember(loaded.size, state.loaded) {
        loaded.flatMapTo(HashSet()) { it.save?.dexSeen.orEmpty().map(::gen1SpeciesId) }
    }
    val inThePc = remember(state.storage.revision) {
        state.storage.boxes.flatMap { it.contents }
            .mapNotNullTo(HashSet()) { it.pokemon.speciesId?.let(::gen1SpeciesId) }
    }


    val rows: List<SpeciesInfo> = remember(filter, owned, seen, inThePc) {
        val gen1: List<SpeciesInfo> = Gen1Data.species
        val gen2Only: List<SpeciesInfo> = Gen2Data.species.filter { it.dexNumber > Gen1Data.species.size }
        (gen1 + gen2Only)
            .sortedBy { it.dexNumber }
            .filter { species ->
                when (filter) {
                    DexFilter.ALL -> true
                    DexFilter.HERE -> species.id in inThePc
                    DexFilter.MISSING -> species.id !in owned && species.id !in inThePc
                }
            }
    }

    val unread = state.saves.count { state.save(it.key) == null }
    // The filter above the list, then every species, then reading the rest
    // where there is a rest, then the numbers, then out.
    val extras = if (unread > 0) 3 else 2
    val count = rows.size + 1 + extras

    fun take(index: Int) {
        when {
            index == 0 -> filter = filter.next
            index <= rows.size -> model.open(Screen.DexEntry(rows[index - 1].id))
            index == rows.size + 1 && unread > 0 -> model.loadAllSaves()
            index == count - 2 -> model.open(Screen.DexStats)
            else -> model.back()
        }
    }

    val cursor = rememberCursorLayer(count) { take(it) }
    val scroll = rememberLazyListState()
    // The list follows the cursor, and only when the cursor has actually left
    // what is on screen — the same rule the box grid uses.
    LaunchedEffect(cursor) {
        val row = cursor - 1
        if (row < 0) return@LaunchedEffect
        val first = scroll.firstVisibleItemIndex
        val last = scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
        if (row <= first) scroll.scrollToRow(row)
        else if (row >= last) scroll.scrollToRow((row - (last - first) + 1).coerceAtLeast(0))
    }

    Gen1Page {
        GbText("POKéDEX")
        Gen1MenuRow(
            "SHOWING",
            selected = cursor == 0,
            onSelect = {},
            onConfirm = { filter = filter.next },
            trailing = filter.label,
        )

        Spacer(Modifier.height(gen1Dp(2)))
        LazyColumn(
            Modifier.weight(1f),
            state = scroll,
            userScrollEnabled = !LocalGen1Swipe.current,
        ) {
            itemsIndexed(rows, key = { _, species -> species.id }) { index, species ->
                DexRow(
                    species = species,
                    caught = species.id in owned || species.id in inThePc,
                    here = species.id in inThePc,
                    met = species.id in owned || species.id in seen || species.id in inThePc,
                    trainers = model.trainers,
                    revision = state.spriteRevision,
                    selected = cursor == index + 1,
                    onConfirm = { model.open(Screen.DexEntry(species.id)) },
                )
            }
            if (rows.isEmpty()) {
                item { GbText("NOTHING TO SHOW.", maxLines = 1) }
            }
        }
        Spacer(Modifier.height(gen1Dp(2)))

        if (unread > 0) {
            Gen1MenuRow(
                if (state.loadingAll) "READING..." else "READ $unread MORE CARDS",
                selected = cursor == rows.size + 1,
                onSelect = {},
                onConfirm = { model.loadAllSaves() },
                enabled = !state.loadingAll,
            )
        }
        Gen1MenuRow(
            "STATS",
            selected = cursor == count - 2,
            onSelect = {},
            onConfirm = { model.open(Screen.DexStats) },
        )
        Gen1MenuRow(
            "BACK",
            selected = cursor == count - 1,
            onSelect = {},
            onConfirm = { model.back() },
        )
    }
}

/**
 * One species in the list, marked the way the cartridge marks it.
 *
 * `HandlePokedexListMenu` puts a Poké Ball beside a species the player has
 * caught and nothing beside one merely seen, and a species never met at all is
 * not named — the row reads `-----`. That is the whole of the cartridge's
 * vocabulary here and it is what this uses.
 *
 * The one thing it adds is the second ball. A Pokémon sitting in this PC is
 * caught in a sense no cartridge has a word for — it is here, and it can be
 * sent to any cartridge that wants one — so it takes the darker of the four
 * balls the same sheet carries. Caught somewhere is the ordinary ball; in the
 * machine is the filled one.
 */
@Composable
private fun DexRow(
    species: SpeciesInfo,
    caught: Boolean,
    here: Boolean,
    met: Boolean,
    trainers: TrainerStore,
    revision: Int,
    selected: Boolean,
    onConfirm: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(gen1Dp(TrainerStore.TILE + 2))) {
            if (caught) {
                DexBall(
                    tile = if (here) TrainerStore.BALL_AILING else TrainerStore.BALL_CAUGHT,
                    trainers = trainers,
                    revision = revision,
                )
            }
        }
        Gen1MenuRow(
            if (met) "No.%03d %s".format(species.dexNumber, species.displayName)
            else "No.%03d -----".format(species.dexNumber),
            selected = selected,
            onSelect = {},
            onConfirm = onConfirm,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One of `gfx/battle/balls.png`'s four tiles, at the size the cartridge draws it. */
@Composable
private fun DexBall(tile: Int, trainers: TrainerStore, revision: Int) {
    // Cut out, so the ball sits on the window rather than on a plate of
    // whatever white the sheet happened to be drawn on.
    val image = remember(tile, revision) {
        trainers.tile(TrainerStore.DEX_BALLS, tile, cutout = true)
    }
    Box(Modifier.size(gen1Dp(TrainerStore.TILE))) {
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
 * What each cartridge knows, one line each.
 *
 * A combined total answers "how far along is this collection"; it cannot
 * answer "which cartridge should I be playing to finish it", which is the
 * question somebody with four playthroughs actually has. So the numbers are
 * given per card, with the PC's own count at the top — the PC is not a
 * playthrough and its number means something different: not what has been
 * caught, but what is on hand to send.
 */
@Composable
fun DexStatsScreen(state: UiState, model: StorageViewModel) {
    // The PC can hold either generation's Pokémon, so its own total is
    // against the full 251 — the same catalogue [DexScreen] lists.
    val whole = Gen2Data.SPECIES_COUNT
    val inThePc = remember(state.storage.revision) {
        state.storage.boxes.flatMap { it.contents }
            .mapNotNullTo(HashSet()) { it.pokemon.speciesId?.let(::gen1SpeciesId) }
    }
    val cards = state.saves.map { remote ->
        val save = state.save(remote.key)?.save
        // A Gold, Silver or Crystal card is judged against its own 251, not
        // Generation I's 151 — a Crystal player who has caught everything
        // Generation II offers should read 251/251, not more than whole.
        DexCard(
            name = model.cartName(remote.key)?.uppercase()
                ?: "${remote.version.label} ${remote.label}".uppercase(),
            owned = save?.dexOwned?.size,
            seen = save?.dexSeen?.size,
            whole = if (remote.version.generation >= 2) Gen2Data.SPECIES_COUNT else Gen1Data.species.size,
        )
    }
    val unread = cards.count { it.owned == null }
    val count = if (unread > 0) 2 else 1

    val cursor = rememberCursorLayer(count) { index ->
        if (unread > 0 && index == 0) model.loadAllSaves() else model.back()
    }

    Gen1Page {
        GbText("POKéDEX STATS")
        Spacer(Modifier.height(gen1Dp(2)))

        LazyColumn(Modifier.weight(1f), userScrollEnabled = !LocalGen1Swipe.current) {
            item {
                Column {
                    GbText("IN THE PC", maxLines = 1)
                    GbText(
                        "${inThePc.size}/$whole SPECIES ON HAND",
                        style = Gen1TextSmall.copy(color = Gen1Palette.Ink),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(gen1Dp(3)))
                }
            }
            items(cards) { card ->
                Column {
                    GbText(card.name, maxLines = 1)
                    GbText(
                        if (card.owned == null) "NOT READ YET"
                        else "OWN ${card.owned}/${card.whole}   SEEN ${card.seen}/${card.whole}",
                        style = Gen1TextSmall.copy(color = Gen1Palette.Ink),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(gen1Dp(3)))
                }
            }
        }

        if (unread > 0) {
            Gen1MenuRow(
                if (state.loadingAll) "READING..." else "READ $unread MORE CARDS",
                selected = cursor == 0,
                onSelect = {},
                onConfirm = { model.loadAllSaves() },
                enabled = !state.loadingAll,
            )
        }
        Gen1MenuRow(
            "BACK",
            selected = cursor == count - 1,
            onSelect = {},
            onConfirm = { model.back() },
        )
    }
}

/**
 * One species' Pokédex page, as the cartridge prints it.
 *
 * The sprite on the left with its number under it, the classification, height
 * and weight beside it, a rule, and then the entry — which is the arrangement
 * `engine/menus/pokedex.asm` draws. The entry's words are the cartridge's; the
 * places it broke them are not, those being where its fourteen-character
 * window ran out of room rather than where the sentence wanted a break.
 *
 * All six cartridges wrote their own entry, and a machine that holds several
 * playthroughs can have caught the same species on more than one of them. So
 * where more than one has, each gets a lettered square along the top — R B Y
 * G S C, in the games' own order, and only for the ones that actually caught
 * it — and taking a letter turns the page to that cartridge's words. Caught on
 * one, that one's words and no squares; caught on none, whatever has at least
 * seen one.
 */
@Composable
fun DexEntryScreen(state: UiState, model: StorageViewModel, speciesId: String) {
    val species = Gen1Data.species(speciesId) ?: Gen2Data.species(Gen2Data.idOf(speciesId))

    // Which cartridges have caught one. Read under each game's own spelling,
    // since a Generation II Pokédex is keyed MR__MIME where Generation I's is
    // keyed MR_MIME.
    val caught = remember(speciesId, state.loaded, state.saves) {
        GameVersion.entries.filter { version ->
            val id = if (version.generation >= 2) Gen2Data.idOf(speciesId) else speciesId
            state.saves.any { remote ->
                remote.version == version && state.save(remote.key)?.save?.dexOwned?.contains(id) == true
            }
        }
    }

    // A copy in the PC knows exactly which cartridge it came from; otherwise
    // the first cartridge on the account that has met one at all.
    val fallbackVersionId = remember(speciesId, state.storage.revision, state.loaded) {
        state.storage.boxes.asSequence().flatMap { it.contents.asSequence() }
            .firstOrNull { it.pokemon.speciesId == speciesId }
            ?.provenance?.gameVersion
            ?: state.saves.firstOrNull { remote ->
                state.save(remote.key)?.save?.let {
                    val id = if (remote.version.generation >= 2) Gen2Data.idOf(speciesId) else speciesId
                    id in it.dexSeen || id in it.dexOwned
                } == true
            }?.version?.id
    }

    // The first that caught it, until a letter is taken. Reset when the screen
    // is opened on a different species.
    var chosen by remember(speciesId) { mutableStateOf(caught.firstOrNull()) }
    val showing = chosen?.takeIf { it in caught } ?: caught.firstOrNull()
    val gameVersionId = showing?.id ?: fallbackVersionId
    val generation = showing?.generation
        ?: GameVersion.fromId(fallbackVersionId)?.generation
        ?: 1
    val entry = dexPageOf(speciesId, generation, gameVersionId)

    val cries = LocalGen1Audio.current
    LaunchedEffect(speciesId) { cries?.cry(species?.dexNumber) }

    // One row for the letters where there is a choice, then BACK. Left and
    // right walk the letters, so a swipe reaches them the way it reaches a
    // count that winds.
    val letters = if (caught.size > 1) caught else emptyList()
    val cursor = rememberCursorLayer(
        count = if (letters.isEmpty()) 1 else 2,
        onSide = { index, button ->
            if (letters.isEmpty() || index != 0) false
            else {
                val at = letters.indexOf(showing).coerceAtLeast(0)
                val step = if (button == GbButton.RIGHT) 1 else -1
                chosen = letters[(at + step + letters.size) % letters.size]
                true
            }
        },
    ) { index ->
        if (letters.isNotEmpty() && index == 0) {
            val at = letters.indexOf(showing).coerceAtLeast(0)
            chosen = letters[(at + 1) % letters.size]
        } else {
            model.back()
        }
    }
    val backRow = if (letters.isEmpty()) 0 else 1

    Gen1Page {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(gen1Dp(SPRITE_PIXELS))) {
                Gen1Sprite(
                    speciesId = speciesId,
                    gameVersionId = gameVersionId,
                    store = model.sprites,
                    revision = state.spriteRevision,
                    sizeInPixels = SPRITE_PIXELS,
                    onTap = { cries?.cry(species?.dexNumber) },
                )
                Spacer(Modifier.height(gen1Dp(2)))
                GbText(species?.let { "No.%03d".format(it.dexNumber) } ?: "No.???", maxLines = 1)
            }
            Spacer(Modifier.width(gen1Dp(4)))
            Gen1CornerRule(Modifier.weight(1f)) {
                GbText(
                    species?.displayName?.uppercase() ?: speciesId,
                    modifier = Modifier.fillMaxWidth(),
                    style = Gen1Text.copy(textAlign = TextAlign.End),
                    maxLines = 1,
                )
                GbText(
                    entry?.let { "${it.category} POKéMON" } ?: "POKéMON",
                    modifier = Modifier.fillMaxWidth(),
                    style = Gen1Text.copy(textAlign = TextAlign.End),
                    maxLines = 1,
                )
                GbText(
                    entry?.let { "HT ${it.heightText}" } ?: "HT ---",
                    modifier = Modifier.fillMaxWidth(),
                    style = Gen1Text.copy(textAlign = TextAlign.End),
                    maxLines = 1,
                )
                GbText(
                    entry?.let { "WT ${it.weightText}" } ?: "WT ---",
                    modifier = Modifier.fillMaxWidth(),
                    style = Gen1Text.copy(textAlign = TextAlign.End),
                    maxLines = 1,
                )
            }
        }

        if (letters.isNotEmpty()) {
            Spacer(Modifier.height(gen1Dp(3)))
            DexGamePicker(
                games = letters,
                showing = showing,
                selected = cursor == 0,
                onChoose = { chosen = it },
            )
        }

        Spacer(Modifier.height(gen1Dp(4)))
        val style = Gen1TextSmall.copy(color = Gen1Palette.Ink)
        // The cartridge's own line breaks are dropped and the words wrap to
        // this window instead: see [Gen1DexEntry.flowing].
        val text = entry?.flowing
        if (text == null) {
            GbText("NO DATA ON THIS POKéMON.", style = style, maxLines = 1)
        } else {
            GbText(text, modifier = Modifier.fillMaxWidth(), style = style)
        }

        Spacer(Modifier.weight(1f))
        Gen1MenuRow(
            "BACK",
            selected = cursor == backRow,
            onSelect = {},
            onConfirm = { model.back() },
        )
    }
}

/**
 * The cartridges that caught this species, one lettered square each.
 *
 * A square with the game's initial in it: R B Y G S C, in the order the games
 * came out, and only the ones that caught one. The square being read is filled
 * in, the way the games mark a chosen option by inverting it.
 */
@Composable
private fun DexGamePicker(
    games: List<GameVersion>,
    showing: GameVersion?,
    selected: Boolean,
    onChoose: (GameVersion) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gen1Dp(2)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The cursor's own mark, so the row reads as a row the cursor is on
        // rather than as six independent buttons.
        Box(Modifier.width(gen1Dp(CURSOR_PIXELS))) {
            if (selected) GbText("▶", maxLines = 1)
        }
        games.forEach { game ->
            DexGameSquare(
                letter = game.label.first().toString(),
                on = game == showing,
                onClick = { onChoose(game) },
            )
        }
    }
}

@Composable
private fun DexGameSquare(letter: String, on: Boolean, onClick: () -> Unit) {
    val side = gen1Dp(SQUARE_PIXELS)
    Box(
        Modifier
            .size(side)
            .background(if (on) Gen1Palette.Ink else Gen1Palette.Panel)
            .border(gen1Dp(1), Gen1Palette.Ink)
            .gen1Clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GbText(
            letter,
            style = Gen1Text.copy(color = if (on) Gen1Palette.Panel else Gen1Palette.Ink),
            maxLines = 1,
        )
    }
}

/** The lettered square's side, and the width the cursor's mark is given. */
private const val SQUARE_PIXELS = 14
private const val CURSOR_PIXELS = 10

/**
 * A screen that is one window rather than a column of them.
 *
 * Folded it takes the whole screen with a margin, because there is nothing to
 * share the screen with and an alignment setting is about which edge a window
 * sits against when there is. Opened up it goes back to its edge and leaves the
 * other half alone. The same rule the box grid follows.
 */
@Composable
private fun Gen1Page(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val unfolded = isUnfolded()
    Box(
        Modifier.fillMaxSize().gen1Ground(),
        contentAlignment =
            if (unfolded) Gen1Layout.corner(top = true, menuSide = true) else Alignment.TopCenter,
    ) {
        Gen1Frame(
            modifier =
                if (unfolded) Modifier.fillMaxHeight().padding(gen1Dp(4))
                else Modifier.fillMaxSize().padding(gen1Dp(2)),
            fillsScreen = !unfolded,
            content = content,
        )
    }
}

/** One cartridge's line on the stats screen, judged against its own generation's catalogue. */
private data class DexCard(val name: String, val owned: Int?, val seen: Int?, val whole: Int)

/** What the list is showing, taken round by pressing the row. */
enum class DexFilter(val label: String) {
    ALL("ALL"),
    HERE("IN THE PC"),
    MISSING("NOT CAUGHT");

    val next: DexFilter get() = entries[(ordinal + 1) % entries.size]
}

private const val SPRITE_PIXELS = 56
