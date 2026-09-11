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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
                            slot = carts.indexOf(remote) + 1,
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
 * Identified by its slot rather than by its trainer — a cartridge is a
 * cartridge, and two playthroughs by the same trainer are otherwise the same
 * label twice. A hold renames it to whatever the player would rather call it.
 *
 * Nothing here is outlined. On a folded phone a cart is about a third of the
 * screen, the type on it is at its smallest, and an outline at that size turns
 * every glyph into a smudge. Flat dark text on the flat label reads.
 */
@Composable
private fun Cart(
    remote: RemoteSave,
    slot: Int,
    state: UiState,
    model: StorageViewModel,
    palette: GbPalette,
    selected: Boolean,
    modifier: Modifier,
) {
    val save = state.save(remote.key)?.save
    val trainer = (save?.trainerName ?: remote.summary.trainerName ?: "?").uppercase()
    val fallback = "SAVE $slot"
    // Read through the revision so a rename redraws the label.
    val title = remember(remote.key, state.cartRevision) {
        model.cartName(remote.key)
    }?.uppercase() ?: fallback
    val lead = save?.party?.firstOrNull()

    Box(
        modifier
            .aspectRatio(CART_WIDTH.toFloat() / CART_HEIGHT)
            .pointerInput(remote.key) {
                detectTapGestures(
                    onTap = { model.chooseCart(remote.key) },
                    onLongPress = { model.prompt(Prompt.RenameCart(remote.key, fallback)) },
                )
            },
    ) {
        Gen1Art(R.drawable.cart, palette, Modifier.fillMaxSize(), title)

        Column(
            Modifier
                .fillMaxSize()
                // The label recess the cartridge art leaves for exactly this.
                .padding(
                    start = gen1Dp(7),
                    end = gen1Dp(7),
                    top = gen1Dp(14),
                    bottom = gen1Dp(7),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label(if (selected) "▶$title" else title, palette)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Gen1Sprite(
                    speciesId = lead?.speciesId,
                    gameVersionId = remote.version.id,
                    store = model.sprites,
                    sizeInPixels = 20,
                )
            }
            Label(trainer, palette)
        }
    }
}

private const val CART_WIDTH = 48
private const val CART_HEIGHT = 54

/** A line on the label: the palette's darkest on the label's own shade. */
@Composable
private fun Label(text: String, palette: GbPalette) {
    GbText(
        text,
        style = Gen1TextTiny.copy(color = palette.darkest, textAlign = TextAlign.Center),
        maxLines = 1,
    )
}
