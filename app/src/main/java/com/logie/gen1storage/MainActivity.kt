package com.logie.gen1storage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.logie.gen1storage.ui.GbPalette
import com.logie.gen1storage.ui.GbText
import com.logie.gen1storage.ui.Gen1Palette
import com.logie.gen1storage.ui.Gen1Text
import com.logie.gen1storage.ui.Gen1Theme
import com.logie.gen1storage.ui.HomeScreen
import com.logie.gen1storage.ui.OptionsScreen
import com.logie.gen1storage.ui.PromptWindow
import com.logie.gen1storage.ui.SaveFilesScreen
import com.logie.gen1storage.ui.CreditsScreen
import com.logie.gen1storage.ui.GbButton
import com.logie.gen1storage.ui.LinkScreen
import com.logie.gen1storage.ui.SpritesScreen
import com.logie.gen1storage.ui.StatusScreen
import com.logie.gen1storage.ui.gen1Gestures
import com.logie.gen1storage.ui.gen1Ground
import com.logie.gen1storage.ui.Screen
import com.logie.gen1storage.ui.StorageViewModel
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val model by viewModels<StorageViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullScreen()
        setContent { Gen1Theme { StorageApp(model) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The bars come back on their own after a swipe or a dialog; this puts
        // them away again once the app has the window to itself.
        if (hasFocus) goFullScreen()
    }

    /**
     * Full screen in every orientation and every posture.
     *
     * The console had no status bar, so neither does this. The bars stay
     * reachable by a swipe from the edge rather than being locked away, which
     * is what `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` means, and the layout
     * runs edge to edge underneath them.
     */
    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
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

    // The game can save at any moment, and every revision this app is holding
    // is stale the instant it does. So the account is re-read as soon as the
    // app is on screen and every thirty seconds it stays there — silently,
    // because a poll that opens a window over what someone is doing is worse
    // than one that quietly gets on with it. It stops with the lifecycle, so
    // nothing is fetched while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                model.sync(silent = true)
                delay(SYNC_INTERVAL_MILLIS)
            }
        }
    }

    // The B button: Android's Back closes a window, then walks the menu stack.
    BackHandler(enabled = state.prompt != null || state.stack.size > 1) { model.back() }

    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            // The system bars are hidden, so only the camera cutout is still
            // something the interface has to stay out of.
            .displayCutoutPadding()
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
            TopBar()
            Box(Modifier.weight(1f)) {
                when (val screen = state.screen) {
                    Screen.Home -> HomeScreen(state, model)
                    Screen.Link -> LinkScreen(state, model)
                    is Screen.Status -> StatusScreen(state, model, screen.key, screen.area, screen.slot)
                    Screen.Sprites -> SpritesScreen(state, model)
                    Screen.SaveFiles -> SaveFilesScreen(state, model)
                    Screen.Credits -> CreditsScreen()
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

/**
 * The console's own header: the machine's name, centred, and nothing else.
 *
 * There are deliberately no buttons in it. Back is Android's own — its gesture,
 * its key, and the B button when swipe controls are on — so the bar has no
 * state of its own to get wrong and reads the same on every screen.
 */
@Composable
private fun TopBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Gen1Palette.Darkest)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GbText(
            "POKéMON STORAGE SYSTEM",
            style = Gen1Text.copy(color = Gen1Palette.Lightest),
            maxLines = 1,
        )
    }
}

/** How often the account is re-read while the app is on screen. */
private const val SYNC_INTERVAL_MILLIS = 30_000L

private fun shareReport(context: android.content.Context, report: String) {
    val title = URLEncoder.encode("Gen1 Storage debug report", "UTF-8")
    val body = URLEncoder.encode(report, "UTF-8")
    val url = "https://github.com/logie-github/PokemonStorageSystem/issues/new?title=$title&body=$body"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
