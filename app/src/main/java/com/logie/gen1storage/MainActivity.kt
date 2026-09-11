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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
import com.logie.gen1storage.ui.ChooseCartScreen
import com.logie.gen1storage.ui.CreditsScreen
import com.logie.gen1storage.ui.SoundEffectsScreen
import com.logie.gen1storage.ui.GbButton
import com.logie.gen1storage.sound.Gen1Audio
import com.logie.gen1storage.sound.LocalGen1Audio
import com.logie.gen1storage.sound.SoundEffect
import com.logie.gen1storage.sound.rememberGen1Audio
import com.logie.gen1storage.ui.Gen1Cursor
import com.logie.gen1storage.ui.Gen1Layout
import com.logie.gen1storage.ui.Gen1WindowBounds
import com.logie.gen1storage.ui.LocalGen1Cursor
import com.logie.gen1storage.ui.UiState
import com.logie.gen1storage.ui.isUnfolded
import com.logie.gen1storage.ui.LocalGen1WindowBounds
import com.logie.gen1storage.ui.LinkScreen
import com.logie.gen1storage.ui.CriesScreen
import com.logie.gen1storage.ui.FollowersScreen
import com.logie.gen1storage.ui.DownloadsScreen
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
        Gen1Layout.windowsOnRight = state.windowsOnRight
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

    val cursor = remember { Gen1Cursor() }
    val windows = remember { Gen1WindowBounds() }
    val audio = rememberGen1Audio()

    // The player's own switches decide what is heard; the audio layer just
    // asks. Kept in a side effect so a setting changed in OPTIONS takes hold
    // without the player object having to be rebuilt.
    SideEffect { audio.allowed = { effect -> effect.id in state.soundsOn } }

    // Turning the machine on, once.
    LaunchedEffect(Unit) {
        audio.play(SoundEffect.OPEN_PC)
        model.checkForUpdate(BuildConfig.VERSION_NAME)
    }

    // A transfer that went through, heard once.
    LaunchedEffect(state.transfers) {
        if (state.transfers > 0) audio.play(SoundEffect.TRANSFER)
    }

    // Backing all the way out to the main menu is logging off. Only on the
    // way down — the app starts at the main menu, and that is turning on.
    var lastDepth by remember { mutableIntStateOf(1) }
    LaunchedEffect(state.stack.size) {
        if (state.stack.size == 1 && lastDepth > 1) audio.play(SoundEffect.LOG_OFF)
        lastDepth = state.stack.size
    }

    CompositionLocalProvider(
        LocalGen1Cursor provides cursor,
        LocalGen1WindowBounds provides windows,
        LocalGen1Audio provides audio,
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .gen1Ground()
            // The system bars are hidden, so only the camera cutout is still
            // something the interface has to stay out of.
            .displayCutoutPadding()
            // Always on. Everything but the hold is read only in empty space,
            // so this never takes a gesture a window wanted.
            .gen1Gestures(windows::isFreeSpace, windows::isHoldClaimed) { button ->
                when (button) {
                    // Back, from anywhere. At the top of the stack this does
                    // nothing rather than closing the app — a hold should never
                    // be the thing that puts someone out of the machine.
                    GbButton.B -> model.back()
                    GbButton.A -> {
                        audio.play(SoundEffect.CURSOR)
                        cursor.confirm()
                    }
                    GbButton.START ->
                        if (state.screen != Screen.Options) {
                            audio.play(SoundEffect.OPTIONS)
                            model.open(Screen.Options)
                        }
                    GbButton.UP, GbButton.DOWN, GbButton.LEFT, GbButton.RIGHT ->
                        cursor.move(button)
                    else -> Unit
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar()
            Box(Modifier.weight(1f)) {
                // Opened up, the status pages take the left half — so the
                // screen they were opened from stays live on the right rather
                // than disappearing behind them. Drawn first, in the half the
                // pages do not cover, so the app is still usable while reading
                // a Pokémon.
                val beneath = state.stack.getOrNull(state.stack.size - 2)
                if (isUnfolded() && state.screen is Screen.Status && beneath != null) {
                    Row(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.weight(1f)) { ScreenContent(beneath, state, model, context) }
                    }
                }
                ScreenContent(state.screen, state, model, context)
            }
        }
        if (state.prompt != null) PromptWindow(state, model)
    }
    }
}

/**
 * The console's own header: the machine's name, centred, and nothing else.
 *
 * There are deliberately no buttons in it. Back is Android's own — its gesture,
 * its key, and the B button when swipe controls are on — so the bar has no
 * state of its own to get wrong and reads the same on every screen.
 */
/** One screen, so the unfolded layout can draw two of them side by side. */
@Composable
private fun ScreenContent(
    screen: Screen,
    state: UiState,
    model: StorageViewModel,
    context: android.content.Context,
) {
    when (screen) {
        Screen.Home -> HomeScreen(state, model)
        Screen.Link -> LinkScreen(state, model)
        is Screen.ChooseCart -> ChooseCartScreen(state, model, screen.game, screen.sendUids)
        is Screen.Status ->
            StatusScreen(state, model, screen.key, screen.area, screen.slot, screen.transfer)
        Screen.Downloads -> DownloadsScreen(state, model)
        Screen.Sprites -> SpritesScreen(state, model)
        Screen.Cries -> CriesScreen(state, model)
        Screen.Followers -> FollowersScreen(state, model)
        Screen.Credits -> CreditsScreen()
        Screen.SoundEffects -> SoundEffectsScreen(state, model)
        Screen.Options -> OptionsScreen(
            state = state,
            model = model,
            onShareReport = { shareReport(context, model.debugReport()) },
        )
    }
}

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
