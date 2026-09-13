package com.logie.gen1storage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.R
import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.sync.RemoteSave

/**
 * Choosing which save the PC is working with.
 *
 * The screen opens on the three games and nothing else, because at that moment
 * there is one decision to make. Picking a game keeps the three on screen and
 * lists that game's saves underneath.
 *
 * Whatever is chosen stays chosen until it is changed or the app is closed. It
 * is deliberately not written to disk: which save is loaded is a fact about
 * this sitting, not a preference.
 */
@Composable
fun ChooseCartScreen(
    state: UiState,
    model: StorageViewModel,
    game: String?,
    sendUids: List<String> = emptyList(),
    thenOpenStorage: Boolean = false,
) {
    val sending = sendUids.isNotEmpty()
    // The lead Pokémon is in the save itself; the account's summary carries
    // only the trainer, the badges and the count. So the saves on the shelf
    // are read as soon as the shelf is opened. Nothing is re-fetched that is
    // already held at the revision the account reports, so coming back here is
    // free.
    LaunchedEffect(state.saves) { model.loadAllSaves() }
    val games = listOf(
        GameVersion.RED to R.drawable.title_red,
        GameVersion.BLUE to R.drawable.title_blue,
        GameVersion.YELLOW to R.drawable.title_yellow,
    )

    val saves = if (game == null) emptyList() else state.saves.filter { it.version.id == game }
    // Two columns once there is room for two, one otherwise. A save is a
    // window like every other window here, so it is capped the same way and
    // never runs the width of an opened screen.
    val columns = if (isUnfolded()) 2 else 1
    val choose: (RemoteSave) -> Unit = { remote ->
        if (sending) model.chooseWithdrawSave(sendUids, remote.key)
        else model.chooseCart(remote.key, thenOpenStorage)
    }
    val openGame: (GameVersion) -> Unit = { version ->
        model.replace(Screen.ChooseCart(version.id, sendUids, thenOpenStorage))
    }
    // Whichever of the two things on this screen is the one to take: the games
    // until one is picked and shown to have saves, the saves after that. The
    // cursor used to be registered only in the second case, so a swipe on the
    // screen that asks which game did nothing at all and the three cards could
    // only be tapped.
    val pickingGame = saves.isEmpty()
    val cursor = rememberCursorLayer(
        count = if (pickingGame) games.size else saves.size,
        columns = if (pickingGame) games.size else columns,
    ) { index ->
        if (pickingGame) games.getOrNull(index)?.let { (version, _) -> openGame(version) }
        else saves.getOrNull(index)?.let(choose)
    }

    // Which shelf of cards is on top. Generation II is a second page rather
    // than three more cards in the row: they are here to be looked at and
    // there is nothing to pick, so they should not sit among the three that
    // can be.
    var showingGen2 by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .padding(gen1Dp(4))
            // A swipe across the cards turns the shelf. Its own handler
            // rather than the app's gesture layer, because that layer is off
            // unless a player has asked for it and this page has to be
            // reachable either way; horizontal only, so a list underneath
            // still scrolls.
            .pointerInput(Unit) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        if (travelled <= -PAGE_TURN_PX) showingGen2 = true
                        if (travelled >= PAGE_TURN_PX) showingGen2 = false
                    },
                ) { change, amount ->
                    travelled += amount
                    change.consume()
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gen1Dp(4)),
        ) {
            games.forEachIndexed { index, (version, _) ->
                Box(Modifier.weight(1f)) {
                    CardCursor(!showingGen2 && pickingGame && cursor == index)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gen1Dp(4)),
        ) {
            if (showingGen2) {
                GEN2_CARDS.forEach { card ->
                    TitleCard(
                        label = card.label,
                        art = card.art,
                        palette = card.palette,
                        chosen = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            model.prompt(
                                Prompt.Message(listOf("${card.label} IS NOT SUPPORTED YET."))
                            )
                        },
                    )
                }
            } else {
                games.forEach { (version, art) ->
                    TitleCard(
                        label = version.label,
                        art = art,
                        palette = paletteFor(version.id),
                        chosen = game == version.id,
                        modifier = Modifier.weight(1f),
                        onClick = { openGame(version) },
                    )
                }
            }
        }

        // Which shelf this is, and the way to the other one. A row rather
        // than only the swipe: the swipe is the nice way in and a tap is the
        // one that is always there.
        Box(
            Modifier.fillMaxWidth().gen1Clickable { showingGen2 = !showingGen2 },
            contentAlignment = if (showingGen2) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            GbText(
                if (showingGen2) "◀ GEN I" else "GEN II ▶",
                style = Gen1TextSmall,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(gen1Dp(3)))

        if (showingGen2) {
            Notice("GOLD, SILVER AND CRYSTAL ARE NOT READY YET.")
            return@Column
        }

        if (game == null) {
            Notice(if (sending) "Send to whose card?" else "INSERT YOUR TRAINER CARD")
            return@Column
        }

        if (saves.isEmpty()) {
            Notice("NO TRAINER CARDS FOUND.")
            return@Column
        }

        if (sending) {
            Notice("Send to whose card?")
            Spacer(Modifier.height(gen1Dp(4)))
        }

        val scroll = rememberLazyListState()
        LaunchedEffect(cursor, columns) { scroll.scrollToRow(cursor / columns) }
        LazyColumn(
            state = scroll,
            verticalArrangement = Arrangement.spacedBy(gen1Dp(4)),
            userScrollEnabled = !LocalGen1Swipe.current,
        ) {
            items(saves.chunked(columns)) { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gen1Dp(4)),
                ) {
                    row.forEach { remote ->
                        val index = saves.indexOf(remote)
                        TrainerCardRow(
                            remote = remote,
                            slot = index + 1,
                            state = state,
                            model = model,
                            cursor = cursor == index,
                            loaded = !sending && state.activeSaveKey == remote.key,
                            onChoose = { choose(remote) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Each game shown in its own colours, whatever the app's palette is set to. */
internal fun paletteFor(gameId: String?): GbPalette = when (gameId) {
    GameVersion.RED.id -> GbPalette.RED
    GameVersion.BLUE.id -> GbPalette.BLUE
    GameVersion.YELLOW.id -> GbPalette.YELLOW
    else -> GbPalette.ORIGINAL
}

/**
 * One Generation II game, as a card to look at.
 *
 * The art is the same four flat greys every card in the app ships as, and the
 * palette is what makes it gold, silver or crystal — see [Gen1Art].
 */
private data class Gen2Card(val label: String, val art: Int, val palette: GbPalette)

/**
 * The colours the three Generation II cards are drawn in.
 *
 * Each is built around one colour: the game's own, given as the shade the art
 * reads as rather than as its darkest. A mid-tone used as the darkest of four
 * leaves nothing under it and the card comes out a wash — Silver especially,
 * whose colour is nearly white — so the ramp runs down from it instead and
 * the card keeps the contrast the Red, Blue and Yellow cards have.
 */
private val GOLD_CARD = GbPalette(
    id = "card_gold", label = "GOLD",
    darkest = Color(0xFF242013),
    dark = Color(0xFF5A4F30),
    light = Color(0xFFA39058),
    lightest = Color(0xFFDCD5C0),
    surround = Color(0xFF242013),
    tintsSprites = true,
)

private val SILVER_CARD = GbPalette(
    id = "card_silver", label = "SILVER",
    darkest = Color(0xFF2A3033),
    dark = Color(0xFF687780),
    light = Color(0xFFBDD8E9),
    lightest = Color(0xFFE6F0F7),
    surround = Color(0xFF2A3033),
    tintsSprites = true,
)

private val CRYSTAL_CARD = GbPalette(
    id = "card_crystal", label = "CRYSTAL",
    darkest = Color(0xFF192732),
    dark = Color(0xFF3F627C),
    light = Color(0xFF72B3E2),
    lightest = Color(0xFFC9E2F4),
    surround = Color(0xFF192732),
    tintsSprites = true,
)

private val GEN2_CARDS = listOf(
    Gen2Card("GOLD", R.drawable.title_gold, GOLD_CARD),
    Gen2Card("SILVER", R.drawable.title_silver, SILVER_CARD),
    Gen2Card("CRYSTAL", R.drawable.title_crystal, CRYSTAL_CARD),
)

/** How far a finger has to travel across the cards to turn the shelf. */
private const val PAGE_TURN_PX = 90f

@Composable
private fun TitleCard(
    label: String,
    art: Int,
    palette: GbPalette,
    chosen: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Gen1Frame(
        modifier.aspectRatio(1f).gen1Clickable(onClick = onClick),
        fill = palette.lightest,
        ink = if (chosen) palette.darkest else Gen1Palette.Ink,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Gen1Art(art, palette, Modifier.fillMaxSize(), label)
        }
    }
}

/** The cursor's mark above a card, which has no room for one inside it. */
@Composable
private fun CardCursor(on: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GbText(if (on) "▼" else " ", maxLines = 1)
    }
}

/**
 * One playthrough on the shelf, as its trainer card.
 *
 * It used to be a window of facts about a save file. A trainer card is the
 * same facts as the thing the player actually remembers having — a name, some
 * money, a time and a row of badges — so it is what the shelf holds now. A
 * hold opens the card at full size, where it can also be renamed.
 */
@Composable
private fun TrainerCardRow(
    remote: RemoteSave,
    slot: Int,
    state: UiState,
    model: StorageViewModel,
    /** Where the cursor is. */
    cursor: Boolean,
    /** Whether this is the card already in the machine. */
    loaded: Boolean,
    onChoose: () -> Unit,
    modifier: Modifier,
) {
    val title = remember(remote.key, state.cartRevision) { model.cartName(remote.key) }
        ?.uppercase() ?: "CARD $slot"
    Gen1TrainerCard(
        remote = remote,
        save = state.save(remote.key)?.save,
        title = title,
        sprites = model.sprites,
        trainers = model.trainers,
        trainerSprite = remember(remote.key, state.cartRevision) {
            model.trainerSprite(remote.key)
        },
        spriteRevision = state.spriteRevision,
        modifier = modifier
            .gen1HoldRegion()
            .pointerInput(remote.key) {
                detectTapGestures(
                    onTap = { onChoose() },
                    onLongPress = { model.open(Screen.TrainerCard(remote.key)) },
                )
            },
        cursor = cursor,
        inserted = loaded,
    )
}

/**
 * One trainer card, read at full size.
 *
 * Everything the shelf shows and the rest of what the card knows underneath:
 * the game, what the playthrough is carrying and storing, how far its Pokédex
 * has got, and how many boxes its PC has — which is not always twelve.
 */
@Composable
fun TrainerCardScreen(state: UiState, model: StorageViewModel, key: String) {
    val remote = state.remote(key)
    if (remote == null) {
        ScreenColumn { item { Gen1Frame { GbText("THAT CARD IS GONE.") } } }
        return
    }
    val slot = state.saves.indexOfFirst { it.key == key } + 1
    val fallback = "CARD $slot"
    val title = remember(key, state.cartRevision) { model.cartName(key) }?.uppercase() ?: fallback
    val chosen = remember(key, state.cartRevision) { model.trainerSprite(key) }
    // The three things this card can be told to do, and then the way out.
    // Which of the game's saves it is does not appear: the shelf and the
    // game's save screen run in the same order, so the app counts it rather
    // than asking anyone to.
    val actions = listOf<Pair<String, () -> Unit>>(
        "INSERT" to { model.chooseCart(key, thenOpenStorage = true) },
        "TRAINER" to { model.prompt(Prompt.ChooseTrainerSprite(key)) },
        "RENAME" to { model.prompt(Prompt.RenameCart(key, fallback)) },
        "BACK" to { model.back() },
    )
    val at = rememberCursorLayer(actions.size) { actions[it].second() }

    ScreenColumn {
        item {
            Gen1TrainerCard(
                remote = remote,
                save = state.save(key)?.save,
                title = title,
                sprites = model.sprites,
                trainers = model.trainers,
                trainerSprite = chosen,
                spriteRevision = state.spriteRevision,
                modifier = Modifier.fillMaxWidth(),
                inserted = state.activeSaveKey == key,
                full = true,
            )
        }
        // One window with four rows in it, the way every other menu in the app
        // is drawn. Four windows in a stack is four things to look at where
        // there is one thing to choose from, and the cursor ends up outside
        // them all pointing at whichever is nearest.
        item {
            Gen1Frame(
                Modifier.wrapContentWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                actions.forEachIndexed { index, (label, action) ->
                    Gen1MenuRow(
                        label,
                        selected = at == index,
                        onSelect = {},
                        onConfirm = action,
                    )
                }
            }
        }
    }
}

/** One line in a window sized to it, centred on the screen. */
@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Gen1Frame(Modifier.gen1MaxWidth()) { GbText(text) }
    }
}

/**
 * How wide a window may get.
 *
 * Half the screen once there is a lot of it — a window that runs the width of
 * an opened foldable stops reading as a window and starts reading as a page.
 *
 * "The screen" is the room this screen has rather than the size of the device:
 * one drawn beside another has half a window to work in, and a cap measured
 * off the whole device would let its windows run past the edge of it.
 */
@Composable
fun Modifier.gen1MaxWidth(): Modifier {
    val screen = LocalConfiguration.current.screenWidthDp / if (LocalGen1Narrow.current) 2 else 1
    val cap = if (isUnfolded()) screen / 2 else (screen * 0.86f).toInt()
    return this.widthIn(max = cap.dp)
}
