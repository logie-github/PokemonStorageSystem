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
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.pokemon.Gen1Species
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
 * hundred and fifty one separate boxes scrolling past each other is a list of
 * windows rather than a Pokédex.
 */
@Composable
fun DexScreen(state: UiState, model: StorageViewModel) {
    var filter by remember { mutableStateOf(DexFilter.ALL) }

    val loaded = state.saves.mapNotNull { state.save(it.key) }
    val owned = remember(loaded.size, state.loaded) {
        loaded.flatMapTo(HashSet()) { it.save?.dexOwned.orEmpty() }
    }
    val seen = remember(loaded.size, state.loaded) {
        loaded.flatMapTo(HashSet()) { it.save?.dexSeen.orEmpty() }
    }
    val inThePc = remember(state.storage.revision) {
        state.storage.boxes.flatMap { it.contents }
            .mapNotNullTo(HashSet()) { it.pokemon.speciesId }
    }


    val rows = remember(filter, owned, seen, inThePc) {
        Gen1Data.species
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
        LazyColumn(Modifier.weight(1f), state = scroll) {
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
    species: Gen1Species,
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
    val whole = Gen1Data.species.size
    val inThePc = remember(state.storage.revision) {
        state.storage.boxes.flatMap { it.contents }
            .mapNotNullTo(HashSet()) { it.pokemon.speciesId }
    }
    val cards = state.saves.map { remote ->
        val save = state.save(remote.key)?.save
        Triple(
            model.cartName(remote.key)?.uppercase()
                ?: "${remote.version.label} ${remote.label}".uppercase(),
            save?.dexOwned?.size,
            save?.dexSeen?.size,
        )
    }
    val unread = cards.count { it.second == null }
    val count = if (unread > 0) 2 else 1

    val cursor = rememberCursorLayer(count) { index ->
        if (unread > 0 && index == 0) model.loadAllSaves() else model.back()
    }

    Gen1Page {
        GbText("POKéDEX STATS")
        Spacer(Modifier.height(gen1Dp(2)))

        LazyColumn(Modifier.weight(1f)) {
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
            items(cards) { (name, own, met) ->
                Column {
                    GbText(name, maxLines = 1)
                    GbText(
                        if (own == null) "NOT READ YET"
                        else "OWN $own/$whole   SEEN $met/$whole",
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
 * Which game's words are used follows the Pokémon rather than the species: one
 * in this PC is read in the words of the cartridge it came out of, and failing
 * that whichever loaded cartridge knows it. Red and Blue share their entries,
 * Yellow rewrote nearly all of them.
 */
@Composable
fun DexEntryScreen(state: UiState, model: StorageViewModel, speciesId: String) {
    val species = Gen1Data.species(speciesId)
    val entry = Gen1Data.dexEntry(speciesId)

    // A copy in the PC knows exactly which cartridge it came from; otherwise
    // the first cartridge on the account that has met one at all.
    val gameVersionId = remember(speciesId, state.storage.revision, state.loaded) {
        state.storage.boxes.asSequence().flatMap { it.contents.asSequence() }
            .firstOrNull { it.pokemon.speciesId == speciesId }
            ?.provenance?.gameVersion
            ?: state.saves.firstOrNull { remote ->
                state.save(remote.key)?.save?.let {
                    speciesId in it.dexSeen || speciesId in it.dexOwned
                } == true
            }?.version?.id
    }

    val cries = LocalGen1Audio.current
    LaunchedEffect(speciesId) { cries?.cry(species?.dexNumber) }

    val cursor = rememberCursorLayer(1) { model.back() }

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

        Spacer(Modifier.height(gen1Dp(4)))
        val style = Gen1TextSmall.copy(color = Gen1Palette.Ink)
        // The cartridge's own line breaks are dropped and the words wrap to
        // this window instead: see [Gen1DexEntry.flowing].
        val text = entry?.flowing(gameVersionId)
        if (text == null) {
            GbText("NO DATA ON THIS POKéMON.", style = style, maxLines = 1)
        } else {
            GbText(text, modifier = Modifier.fillMaxWidth(), style = style)
        }

        Spacer(Modifier.weight(1f))
        Gen1MenuRow("BACK", selected = cursor == 0, onSelect = {}, onConfirm = { model.back() })
    }
}

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

/** What the list is showing, taken round by pressing the row. */
enum class DexFilter(val label: String) {
    ALL("ALL"),
    HERE("IN THE PC"),
    MISSING("NOT CAUGHT");

    val next: DexFilter get() = entries[(ordinal + 1) % entries.size]
}

private const val SPRITE_PIXELS = 56
