package com.logie.gen1storage.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opening Gen1Recomp from here.
 *
 * A transfer finishes on the server, and the cartridge only learns about it
 * the next time the game syncs. The game syncs on the way into a save —
 * `Prelaunch` runs `SyncEngine:syncNow`, which ignores the five-minute timer
 * and clears any pending conflict before it checks — so the fastest a change
 * made here can reach a save is a tap that starts the game. That is what this
 * is: the button the player would otherwise leave the app to press.
 *
 * Upstream publishes a launch link (`README.md`, "To test a link on Android"),
 * and `main.lua` puts a link with a `game` on the same `Prelaunch` path as any
 * other launch, so the sync still happens and the save is current when the
 * title screen appears. If the link is not handled — an older build, or one
 * with the scheme stripped — the app's own launcher intent is used instead and
 * the player arrives in the launcher, which syncs as well, one screen earlier.
 */
object TheGame {

    /**
     * Package names to try, in order.
     *
     * The first is what upstream's build script brands a release with
     * (`mobile/ANDROID.md`). The second is the id the fixes build carries;
     * both are listed in the manifest's `<queries>` because Android 11 hides
     * packages a manifest does not name.
     */
    private val PACKAGES = listOf(
        "com.theboisclub.pokemonred",
        "com.theboisclub.pokemonred.androidfixes",
    )

    private const val SCHEME = "gen1recomp++"

    /** The installed one, if any is. */
    fun installedPackage(context: Context): String? {
        val packages = context.packageManager
        return PACKAGES.firstOrNull { name ->
            runCatching { packages.getPackageInfo(name, 0) }.isSuccess
        }
    }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /**
     * Starts the game, at [gameVersionId] and [slot] where they are known.
     *
     * [slot] is the save itself rather than the game: an account can hold
     * several playthroughs of one version, and the one worth opening is the
     * one that was just written to. Upstream's `LaunchOptions.selectSlot`
     * takes either a slot id or an index and makes it active before the game
     * boots, and the id the server lists is the id that device uploaded it
     * under. A save first made on another phone has no matching slot here, in
     * which case the game boots whichever save is already active — the sync
     * still happens, which is the part that matters.
     *
     * Returns false if nothing could be started, so a caller can say so rather
     * than look like it did something.
     */
    fun open(
        context: Context,
        gameVersionId: String? = null,
        slot: String? = null,
        chooseSave: Boolean = false,
    ): Boolean {
        val name = installedPackage(context) ?: return false
        val deepLink = Intent(
            Intent.ACTION_VIEW,
            launchUri(gameVersionId, slot, chooseSave),
        ).apply {
            setPackage(name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (start(context, deepLink)) return true
        val launcher = context.packageManager.getLaunchIntentForPackage(name) ?: return false
        launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, launcher)
    }

    private fun launchUri(gameVersionId: String?, slot: String?, chooseSave: Boolean): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("launch")
            .apply {
                gameVersionId?.lowercase()?.takeIf { it.isNotBlank() }
                    ?.let { appendQueryParameter("game", it) }
                // Only alongside a game: a slot id means nothing without the
                // version it belongs to, and upstream reads the two together.
                val named = gameVersionId != null && !slot.isNullOrBlank()
                if (named) appendQueryParameter("slot", slot)
                // Nothing to name, and more than one save it could be: land on
                // the game's save list rather than on whichever save happens to
                // be open. The launcher syncs as it opens — `RomImporter`'s
                // first `_pumpSync` calls `syncNow` — so the Pokémon is there
                // by the time the player picks the card it went to.
                if (!named && chooseSave) appendQueryParameter("launcher", "1")
            }
            .build()

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess
}
