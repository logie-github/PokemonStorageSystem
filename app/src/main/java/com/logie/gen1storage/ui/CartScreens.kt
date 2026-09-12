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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
            Notice(if (sending) "Send where?" else "WHICH GAME?")
            return@Column
        }

        if (saves.isEmpty()) {
            Notice("NO SAVES FOUND.")
            return@Column
        }

        if (sending) {
            Notice("Send where?")
            Spacer(Modifier.height(gen1Dp(4)))
        }

        val scroll = rememberLazyListState()
        LaunchedEffect(cursor, columns) { scroll.animateScrollToItem(cursor / columns) }
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
                        SaveRow(
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
private fun paletteFor(gameId: String?): GbPalette = when (gameId) {
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
    val pixel = gen1PixelPx().toFloat()
    val ink = if (chosen) palette.darkest else Gen1Palette.Ink
    Box(
        modifier
            .aspectRatio(1f)
            .gen1WindowBounds()
            .background(palette.lightest)
            .gen1Clickable(onClick = onClick)
            // The picture runs to the edges of the card and the frame is a
            // frame: the tile it occupies is painted out first, so nothing of
            // the picture survives under it. Drawing the border straight over
            // the art does not do that — its tiles are mostly holes, and the
            // art showed through every one of them.
            .drawWithContent {
                drawContent()
                fillGen1BorderArea(palette.lightest, pixel)
                drawGen1Border(ink, pixel)
            },
        contentAlignment = Alignment.Center,
    ) {
        Gen1Art(art, palette, Modifier.fillMaxSize(), version.label)
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
 * One save, as an ordinary window.
 *
 * It used to be drawn on a picture of a cartridge, which meant a layout that
 * fought every other window in the app and type small enough to be a smudge on
 * a folded phone. It is a window like the rest now: four lines and the party's
 * lead on the right. A hold renames it.
 */
@Composable
private fun SaveRow(
    remote: RemoteSave,
    slot: Int,
    state: UiState,
    model: StorageViewModel,
    /** Where the cursor is. */
    cursor: Boolean,
    /** Whether this is the cartridge already in the machine. */
    loaded: Boolean,
    onChoose: () -> Unit,
    modifier: Modifier,
) {
    // The same ink as everything else in a window. The small size is what
    // separates these lines from the save's name; the grey on top of it only
    // made them harder to read.
    val small = Gen1TextSmall.copy(color = Gen1Palette.Ink)
    val save = state.save(remote.key)?.save
    // Nothing has been read yet, so nothing is known about the party. Saying
    // "NO POKéMON" here would be a claim rather than a reading.
    val read = save != null
    val fallback = "SAVE $slot"
    val title = remember(remote.key, state.cartRevision) { model.cartName(remote.key) }
        ?.uppercase() ?: fallback
    val trainer = (save?.trainerName ?: remote.summary.trainerName ?: "?").uppercase()
    val lead = save?.party?.firstOrNull()
    val badges = save?.badgeCount ?: remote.summary.badges ?: 0
    val caught = save?.let { it.partyCount + it.storedCount } ?: remote.summary.dexCount ?: 0
    val time = save?.playTimeLongText

    Gen1Frame(
        modifier
            .gen1HoldRegion()
            .pointerInput(remote.key) {
                detectTapGestures(
                    onTap = { onChoose() },
                    onLongPress = { model.prompt(Prompt.RenameCart(remote.key, fallback)) },
                )
            }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // The arrow is the cursor and nothing else. Which cart is
                // in the machine is a different fact, and it was confusing to
                // say both with one mark.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GbText(
                        if (cursor) "▶$title" else title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (loaded) GbText("LOADED", style = small, maxLines = 1)
                }
                GbText(trainer, style = small, maxLines = 1)
                GbText(
                    when {
                        lead != null -> "${lead.displayName.uppercase()}, L${lead.level}"
                        read -> "NO POKéMON"
                        else -> " "
                    },
                    style = small,
                    maxLines = 1,
                )
                time?.let { GbText("PLAY TIME: $it", style = small, maxLines = 1) }
                GbText("$badges BADGES - $caught CAUGHT", style = small, maxLines = 1)
            }
            // The bracketed mark means "there is no art for this one", which
            // is only worth saying once the save has actually been read.
            if (read) {
                Gen1Sprite(
                    speciesId = lead?.speciesId,
                    gameVersionId = remote.version.id,
                    store = model.sprites,
                    revision = state.spriteRevision,
                    sizeInPixels = 32,
                )
            } else {
                Spacer(Modifier.size(gen1Dp(32)))
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
