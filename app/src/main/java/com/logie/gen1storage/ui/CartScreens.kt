package com.logie.gen1storage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.logie.gen1storage.R
import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.sync.RemoteSave

/**
 * Choosing which cartridge the PC is working with.
 *
 * The screen opens on the three games and nothing else — no menu behind it, no
 * message window, no box window — because at that moment there is exactly one
 * decision to make. Picking a game keeps the three where they are and lists
 * that game's saves underneath as cartridges, so changing game is one tap
 * rather than a trip backwards.
 *
 * Whatever is chosen here stays chosen until it is changed or the app is
 * closed. It is deliberately not written to disk: which cartridge is in the
 * machine is a fact about this sitting, not a preference.
 */
@Composable
fun ChooseCartScreen(state: UiState, model: StorageViewModel, game: String?) {
    val games = listOf(
        GameVersion.RED to R.drawable.title_red,
        GameVersion.BLUE to R.drawable.title_blue,
        GameVersion.YELLOW to R.drawable.title_yellow,
    )

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
            games.forEach { (version, art) ->
                TitleCard(
                    version = version,
                    art = art,
                    chosen = game == version.id,
                    modifier = Modifier.weight(1f),
                    onClick = { model.replace(Screen.ChooseCart(version.id)) },
                )
            }
        }

        Spacer(Modifier.height(gen1Dp(5)))

        if (game == null) {
            Notice("WHICH GAME?")
            return@Column
        }

        val carts = state.saves.filter { it.version.id == game }
        if (carts.isEmpty()) {
            Notice("NO SAVES FOUND.")
            return@Column
        }

        val palette = paletteFor(game)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(gen1Dp(4))) {
            items(carts.chunked(CARTS_PER_ROW)) { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gen1Dp(3)),
                ) {
                    row.forEach { remote ->
                        Cart(
                            remote = remote,
                            state = state,
                            model = model,
                            palette = palette,
                            selected = state.activeSaveKey == remote.key,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps a short last row the same width as a full one.
                    repeat(CARTS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One line in a window sized to it, centred on the screen. */
@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Gen1Frame(Modifier.wrapContentWidth()) { GbText(text) }
    }
}

/** Three across, as asked; a fourth would make each one too narrow to read. */
private const val CARTS_PER_ROW = 3

/** Each game in its own colours, whatever the app's palette happens to be. */
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

/**
 * One save, drawn on the cartridge.
 *
 * The details sit on the label the way a player would have written them there:
 * the trainer's name, the Pokémon at the head of their party, and what they
 * have to show for it.
 */
@Composable
private fun Cart(
    remote: RemoteSave,
    state: UiState,
    model: StorageViewModel,
    palette: GbPalette,
    selected: Boolean,
    modifier: Modifier,
) {
    val save = state.save(remote.key)?.save
    val trainer = (save?.trainerName ?: remote.summary.trainerName ?: remote.label).uppercase()
    val lead = save?.party?.firstOrNull()
    val badges = save?.badgeCount ?: remote.summary.badges ?: 0
    val caught = save?.let { it.partyCount + it.storedCount } ?: remote.summary.dexCount ?: 0

    Box(
        modifier
            .aspectRatio(CART_WIDTH.toFloat() / CART_HEIGHT)
            .gen1Clickable { model.chooseCart(remote.key) },
    ) {
        Gen1Art(R.drawable.cart, palette, Modifier.fillMaxSize(), trainer)

        Column(
            Modifier
                .fillMaxSize()
                // The label recess the cartridge art leaves for exactly this.
                .padding(
                    start = gen1Dp(5),
                    end = gen1Dp(5),
                    top = gen1Dp(15),
                    bottom = gen1Dp(6),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedLabel(if (selected) "▶$trainer" else trainer, palette, Gen1TextSmall)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Gen1Sprite(
                    speciesId = lead?.speciesId,
                    gameVersionId = remote.version.id,
                    store = model.sprites,
                    sizeInPixels = 20,
                )
            }
            OutlinedLabel("$badges BADGES", palette, Gen1TextTiny)
            OutlinedLabel("$caught CAUGHT", palette, Gen1TextTiny)
        }
    }
}

private const val CART_WIDTH = 48
private const val CART_HEIGHT = 54

/**
 * Text that stays readable on any shade of the cartridge under it.
 *
 * The palette's lightest over its darkest, drawn as an outline rather than on a
 * plate, because a plate would hide the art it is sitting on. Eight offset
 * copies rather than four: a four-way outline leaves the diagonal corners of
 * each glyph bare against a matching background.
 */
@Composable
private fun OutlinedLabel(text: String, palette: GbPalette, style: TextStyle) {
    val step = gen1Dp(1)
    val outline = style.copy(color = palette.darkest, textAlign = TextAlign.Center)
    Box(contentAlignment = Alignment.Center) {
        listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, 1 to -1, -1 to 1, 1 to 1)
            .forEach { (dx, dy) ->
                GbText(
                    text,
                    modifier = Modifier.padding(
                        start = if (dx > 0) step * 2 else 0.dp,
                        end = if (dx < 0) step * 2 else 0.dp,
                        top = if (dy > 0) step * 2 else 0.dp,
                        bottom = if (dy < 0) step * 2 else 0.dp,
                    ),
                    style = outline,
                    maxLines = 1,
                )
            }
        GbText(
            text,
            modifier = Modifier.padding(step),
            style = style.copy(color = palette.lightest, textAlign = TextAlign.Center),
            maxLines = 1,
        )
    }
}
