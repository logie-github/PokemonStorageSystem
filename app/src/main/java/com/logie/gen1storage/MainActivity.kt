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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import com.logie.gen1storage.ui.Gen1Typing
import com.logie.gen1storage.ui.Gen1Haptics
import com.logie.gen1storage.ui.Gen1Motion
import com.logie.gen1storage.ui.Gen1NoOverscroll
import com.logie.gen1storage.ui.rememberConfirmTick
import com.logie.gen1storage.ui.rememberCursorTick
import com.logie.gen1storage.ui.Gen1TradeScene
import com.logie.gen1storage.ui.RestoreScreen
import com.logie.gen1storage.ui.DexEntryScreen
import com.logie.gen1storage.ui.DexStatsScreen
import com.logie.gen1storage.ui.DexScreen
import com.logie.gen1storage.ui.TradeScreen
import com.logie.gen1storage.ui.OptionsScreen
import com.logie.gen1storage.ui.PromptWindow
import com.logie.gen1storage.ui.ChooseCartScreen
import com.logie.gen1storage.ui.CreditsScreen
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
import com.logie.gen1storage.ui.Gen1TransferScene
import com.logie.gen1storage.ui.ItemPcScreen
import com.logie.gen1storage.ui.MainMenuScreen
import com.logie.gen1storage.ui.StorageHomeScreen
import com.logie.gen1storage.ui.StatusScreen
import com.logie.gen1storage.ui.TrainerCardScreen
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
        Gen1Typing.speed = state.textSpeed
        Gen1Haptics.enabled = state.haptics
        Gen1Motion.reduced = state.reduceMotion
        Gen1Motion.allowed = state.motionsOn
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

    // Nothing is live while a transfer is in the air or a save is being read:
    // not the cursor, not a button, not Back. A question is the exception —
    // being asked something is the app waiting on the player, not the other
    // way round — so the scene's own YES and NO stay live.
    val locked = state.busy ||
        (state.transferScene != null && state.transferScene?.question == null)

    // The B button: Android's Back closes a window, then walks the menu stack.
    BackHandler(
        enabled = locked ||
            state.transferScene?.question != null ||
            state.prompt != null ||
            state.stack.size > 1,
    ) {
        // Enabled but deliberately deaf while locked: swallowing Back is what
        // stops it walking out of a screen the transfer is still working on,
        // and it must not fall through to closing the app either.
        if (locked) return@BackHandler
        // Backing out of the question is saying no to it.
        if (state.transferScene?.question != null) model.cancelSend() else model.back()
    }

    val cursor = remember { Gen1Cursor() }
    val cursorTick = rememberCursorTick()
    val confirmTick = rememberConfirmTick()
    // Read by the gesture layer, which was built once and holds the cursor
    // object rather than this composition's state.
    SideEffect { cursor.locked = locked }
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
    Gen1NoOverscroll {
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
                // The cursor turns its own moves and confirms away, but B and
                // START are the gesture layer's alone — a hold or a double tap
                // mid-transfer would otherwise still navigate.
                if (cursor.locked) return@gen1Gestures
                when (button) {
                    // Back, from anywhere. At the top of the stack this does
                    // nothing rather than closing the app — a hold should never
                    // be the thing that puts someone out of the machine.
                    GbButton.B -> model.back()
                    GbButton.A -> {
                        audio.play(SoundEffect.CURSOR)
                        confirmTick()
                        cursor.confirm()
                    }
                    GbButton.START ->
                        if (state.screen != Screen.Options) {
                            audio.play(SoundEffect.OPTIONS)
                            model.open(Screen.Options)
                        }
                    GbButton.UP, GbButton.DOWN, GbButton.LEFT, GbButton.RIGHT -> {
                        cursorTick()
                        cursor.move(button)
                    }
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
        // The pane that makes the lock real for tapping: everything below it
        // stops seeing touches, everything after it — the scene and the result
        // window — still gets them. It draws nothing.
        if (locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }
        // Over the screen it came from and under the result, so the ball is
        // what is on screen for the whole of the wait and the message lands
        // on top of it the moment the save answers.
        state.transferScene?.let { scene ->
            Gen1TransferScene(
                scene,
                model.sprites,
                state.spriteRevision,
                onConfirm = model::confirmSend,
                onCancel = model::cancelSend,
            )
        }
        // A trade is the app talking to itself, so it owns the screen while
        // it runs, over everything and under the message it ends with.
        state.tradeScene?.let { scene ->
            Gen1TradeScene(scene, model.sprites, model.trainers, state.spriteRevision)
        }
        if (state.prompt != null) PromptWindow(state, model)
    }
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
        Screen.Home -> MainMenuScreen(state, model)
        Screen.Storage -> StorageHomeScreen(state, model)
        Screen.ItemPc -> ItemPcScreen(state, model)
        Screen.Link -> LinkScreen(state, model)
        is Screen.ChooseCart ->
            ChooseCartScreen(
                state,
                model,
                screen.game,
                screen.sendUids,
                screen.thenOpenStorage,
            )
        is Screen.Status ->
            StatusScreen(state, model, screen.key, screen.area, screen.slot, screen.transfer)
        is Screen.TrainerCard -> TrainerCardScreen(state, model, screen.key)
        Screen.Restore -> RestoreScreen(state, model)
        Screen.Trade -> TradeScreen(state, model)
        Screen.Dex -> DexScreen(state, model)
        is Screen.DexEntry -> DexEntryScreen(state, model, screen.speciesId)
        Screen.DexStats -> DexStatsScreen(state, model)
        Screen.Credits -> CreditsScreen()
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
            "POKéMON STORAGE SYSTEM - DEMO",
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
