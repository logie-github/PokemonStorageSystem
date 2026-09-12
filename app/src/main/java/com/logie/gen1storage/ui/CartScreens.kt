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

    Column(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            .padding(gen1Dp(4)),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gen1Dp(4)),
        ) {
            games.forEachIndexed { index, (version, _) ->
                Box(Modifier.weight(1f)) {
                    CardCursor(pickingGame && cursor == index)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gen1Dp(4)),
        ) {
            games.forEach { (version, art) ->
                TitleCard(
                    version = version,
                    art = art,
                    chosen = game == version.id,
                    modifier = Modifier.weight(1f),
                    onClick = { openGame(version) },
                )
            }
        }

        Spacer(Modifier.height(gen1Dp(5)))

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

@Composable
private fun TitleCard(
    version: GameVersion,
    art: Int,
    chosen: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = paletteFor(version.id)
    Gen1Frame(
        modifier.aspectRatio(1f).gen1Clickable(onClick = onClick),
        fill = palette.lightest,
        ink = if (chosen) palette.darkest else Gen1Palette.Ink,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Gen1Art(art, palette, Modifier.fillMaxSize(), version.label)
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
 */
@Composable
fun Modifier.gen1MaxWidth(): Modifier {
    val screen = LocalConfiguration.current.screenWidthDp
    val cap = if (isUnfolded()) screen / 2 else (screen * 0.86f).toInt()
    return this.widthIn(max = cap.dp)
}
