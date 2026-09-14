package com.logie.gen1storage.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
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
    // The six cards on the shelf. The Generation I three open that game's
    // saves; the Generation II three say so and do nothing else, which is all
    // an app that cannot read a Gold save yet can honestly offer.
    val shelf = TITLE_CARDS

    val saves = if (game == null) emptyList() else state.saves.filter { it.version.id == game }
    // Two columns once there is room for two, one otherwise. A save is a
    // window like every other window here, so it is capped the same way and
    // never runs the width of an opened screen.
    val columns = if (isUnfolded()) 2 else 1
    val chooseSave: (RemoteSave) -> Unit = { remote ->
        if (sending) model.chooseWithdrawSave(sendUids, remote.key)
        else model.chooseCart(remote.key, thenOpenStorage)
    }
    val choose: (TitleCardArt) -> Unit = { card ->
        model.replace(Screen.ChooseCart(card.version.id, sendUids, thenOpenStorage))
    }
    // Whichever of the two things on this screen is the one to take: the games
    // until one is picked and shown to have saves, the saves after that. The
    // cursor used to be registered only in the second case, so a swipe on the
    // screen that asks which game did nothing at all and the three cards could
    // only be tapped.
    val pickingGame = saves.isEmpty()
    val cursor = rememberCursorLayer(
        count = if (pickingGame) shelf.size else saves.size,
        columns = if (pickingGame) shelf.size else columns,
    ) { index ->
        if (pickingGame) shelf.getOrNull(index)?.let(choose)
        else saves.getOrNull(index)?.let(chooseSave)
    }

    Column(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .padding(gen1Dp(4)),
    ) {
        // All six in one row, the three that can be picked and the three that
        // are only there to be looked at. Six across whatever the screen is:
        // the shelf is the shelf, and a card that moved to a second row on a
        // folded phone would be a card a player has to go looking for.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gen1Dp(2)),
        ) {
            shelf.forEachIndexed { index, _ ->
                Box(Modifier.weight(1f)) { CardCursor(pickingGame && cursor == index) }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gen1Dp(2)),
        ) {
            shelf.forEach { card ->
                TitleCard(
                    label = card.label,
                    art = card.art,
                    palette = card.palette,
                    chosen = game == card.version.id,
                    modifier = Modifier.weight(1f),
                    onClick = { choose(card) },
                )
            }
        }

        Spacer(Modifier.height(gen1Dp(3)))

        if (game == null) {
            Notice(if (sending) "Send to whose card?" else "INSERT YOUR TRAINER CARD")
            return@Column
        }

        if (saves.isEmpty()) {
            Notice("NO TRAINER CARDS FOUND.")
            return@Column
        }

        // Said once, above the cards themselves: a Generation II card can be
        // read — its trainer, its badges, its boxes — and nothing in this app
        // will change one.
        if (GameVersion.fromId(game)?.isWritable == false) {
            Notice("READ ONLY. NOTHING IS WRITTEN TO THESE.")
            Spacer(Modifier.height(gen1Dp(3)))
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
                            onChoose = { chooseSave(remote) },
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
    GameVersion.GOLD.id -> GOLD_CARD
    GameVersion.SILVER.id -> SILVER_CARD
    GameVersion.CRYSTAL.id -> CRYSTAL_CARD
    else -> GbPalette.ORIGINAL
}

/**
 * One card on the shelf.
 *
 * The art is the same four flat greys every card in the app ships as, and the
 * palette is what makes it red, gold or crystal — see [Gen1Art]. [version] is
 * null for the three this app cannot open yet.
 */
private data class TitleCardArt(
    val label: String,
    val art: Int,
    val palette: GbPalette,
    val version: GameVersion,
)

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

private val TITLE_CARDS = listOf(
    TitleCardArt("RED", R.drawable.title_red, GbPalette.RED, GameVersion.RED),
    TitleCardArt("BLUE", R.drawable.title_blue, GbPalette.BLUE, GameVersion.BLUE),
    TitleCardArt("YELLOW", R.drawable.title_yellow, GbPalette.YELLOW, GameVersion.YELLOW),
    TitleCardArt("GOLD", R.drawable.title_gold, GOLD_CARD, GameVersion.GOLD),
    TitleCardArt("SILVER", R.drawable.title_silver, SILVER_CARD, GameVersion.SILVER),
    TitleCardArt("CRYSTAL", R.drawable.title_crystal, CRYSTAL_CARD, GameVersion.CRYSTAL),
)

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
