package com.logie.gen1storage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logie.gen1storage.ui.GbPalette
import com.logie.gen1storage.ui.GbText
import com.logie.gen1storage.ui.Gen1Button
import com.logie.gen1storage.ui.Gen1Palette
import com.logie.gen1storage.ui.Gen1Text
import com.logie.gen1storage.ui.Gen1TextSmall
import com.logie.gen1storage.ui.Gen1Theme
import com.logie.gen1storage.ui.HomeScreen
import com.logie.gen1storage.ui.OptionsScreen
import com.logie.gen1storage.ui.PromptWindow
import com.logie.gen1storage.ui.SaveBoxScreen
import com.logie.gen1storage.ui.SaveFilesScreen
import com.logie.gen1storage.ui.AllPokemonScreen
import com.logie.gen1storage.ui.GbButton
import com.logie.gen1storage.ui.LinkScreen
import com.logie.gen1storage.ui.PcScreen
import com.logie.gen1storage.ui.SpritesScreen
import com.logie.gen1storage.ui.StatusScreen
import com.logie.gen1storage.ui.gen1Gestures
import com.logie.gen1storage.ui.SaveListScreen
import com.logie.gen1storage.ui.SaveMenuScreen
import com.logie.gen1storage.ui.SavePartyScreen
import com.logie.gen1storage.ui.Screen
import com.logie.gen1storage.ui.StorageSystemScreen
import com.logie.gen1storage.ui.StorageViewModel
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val model by viewModels<StorageViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Gen1Theme { StorageApp(model) } }
    }

    override fun onResume() {
        super.onResume()
        // The game may have synced while this app was in the background, so
        // every revision it is holding is stale until the account is re-read.
        model.sync()
    }
}

@Composable
private fun StorageApp(model: StorageViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The palette is global to the drawing code, including the draw lambdas
    // that cannot observe view-model state themselves. Applied as a side effect
    // rather than during composition, so nothing writes snapshot state while
    // the frame it belongs to is still being built.
    SideEffect {
        Gen1Palette.palette = GbPalette.fromId(state.paletteId)
        Gen1Palette.windowsFollowPalette = state.windowsFollowPalette
    }

    // The B button: Android's Back closes a window, then walks the menu stack.
    BackHandler(enabled = state.prompt != null || state.stack.size > 1) { model.back() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Gen1Palette.Surround)
            .statusBarsPadding()
            .navigationBarsPadding()
            // Off unless the player turns them on in OPTIONS; the app is
            // tappable either way, so this only adds a second way in.
            .gen1Gestures(state.swipeControls) { button ->
                when (button) {
                    GbButton.B -> model.back()
                    GbButton.START -> model.home()
                    GbButton.LEFT -> model.back()
                    else -> Unit
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = titleFor(state.screen),
                canGoBack = state.stack.size > 1,
                busy = state.busy || state.syncing || state.linking,
                onBack = { model.back() },
                onHome = { model.home() },
            )
            Box(Modifier.weight(1f)) {
                when (val screen = state.screen) {
                    Screen.Home -> HomeScreen(state, model)
                    Screen.Link -> LinkScreen(state, model)
                    Screen.SaveList -> SaveListScreen(state, model)
                    is Screen.SaveMenu -> SaveMenuScreen(state, model, screen.key)
                    is Screen.SaveParty -> SavePartyScreen(state, model, screen.key)
                    is Screen.SaveBox -> SaveBoxScreen(state, model, screen.key, screen.box)
                    is Screen.Pc -> PcScreen(state, model, screen.key)
                    is Screen.StorageSystem -> StorageSystemScreen(state, model, screen.key)
                    Screen.AllPokemon -> AllPokemonScreen(state, model)
                    is Screen.Status -> StatusScreen(state, model, screen.key, screen.area, screen.slot)
                    Screen.Sprites -> SpritesScreen(state, model)
                    Screen.SaveFiles -> SaveFilesScreen(state, model)
                    Screen.Options -> OptionsScreen(
                        state = state,
                        model = model,
                        onShareReport = { shareReport(context, model.debugReport()) },
                    )
                }
            }
        }
        if (state.prompt != null) PromptWindow(state, model)
    }
}

@Composable
private fun TopBar(
    title: String,
    canGoBack: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Gen1Palette.Darkest)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            Gen1Button("B", onBack)
            Box(Modifier.padding(horizontal = 8.dp))
        }
        Box(Modifier.weight(1f)) {
            GbText(title, style = Gen1Text.copy(color = Gen1Palette.Lightest))
        }
        if (busy) GbText("...", style = Gen1TextSmall.copy(color = Gen1Palette.Light))
        else if (canGoBack) Gen1Button("MENU", onHome)
    }
}

private fun titleFor(screen: Screen): String = when (screen) {
    Screen.Home -> "STORAGE SYSTEM"
    Screen.Link -> "SAVE SYNC"
    Screen.SaveList -> "ACCESS SAVE"
    is Screen.SaveMenu -> "PC"
    is Screen.SaveParty -> "PARTY POKéMON"
    is Screen.SaveBox -> "BOX ${screen.box}"
    is Screen.Pc -> "PC"
    is Screen.StorageSystem -> "STORAGE SYSTEM"
    Screen.AllPokemon -> "ALL POKéMON"
    is Screen.Status -> "STATUS"
    Screen.Sprites -> "SPRITES"
    Screen.SaveFiles -> "SAVE FILES"
    Screen.Options -> "OPTIONS"
}

private fun shareReport(context: android.content.Context, report: String) {
    val title = URLEncoder.encode("Gen1 Storage debug report", "UTF-8")
    val body = URLEncoder.encode(report, "UTF-8")
    val url = "https://github.com/logie-github/PokemonStorageSystem/issues/new?title=$title&body=$body"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
