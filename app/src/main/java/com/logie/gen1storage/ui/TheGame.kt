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
     * [slot] and [playthroughId] are the save itself rather than the game: an
     * account can hold several playthroughs of one version, and the one worth
     * opening is the one that was just written to.
     *
     * `LaunchOptions.selectSlot` takes a slot id or a position in that game's
     * save list, and both of those are the device's own business — the slot
     * id is its private numbering and the position is whatever order it lists
     * them in. The playthrough id is the one name a save has outside the
     * device holding it, which is why it is sent as well: a build that
     * resolves it opens the right save with nothing to configure, and one
     * that does not ignores the parameter.
     *
     * Never the launcher, even with no slot to name. A link that names a game
     * goes through `Prelaunch`, which runs `SyncEngine:syncNow` on the way in
     * every single time; the launcher only syncs the first time it is opened
     * in a session, so a second trip to it in the same sitting would show the
     * player a save list and quietly sync nothing. Landing on the wrong save
     * of the right game still leaves the Pokémon where it was sent — the sync
     * covers every save on the account, not the one being opened.
     *
     * Returns false if nothing could be started, so a caller can say so rather
     * than look like it did something.
     */
    fun open(
        context: Context,
        gameVersionId: String? = null,
        slot: String? = null,
        playthroughId: String? = null,
    ): Boolean {
        val name = installedPackage(context) ?: return false
        val deepLink = Intent(
            Intent.ACTION_VIEW,
            launchUri(gameVersionId, slot, playthroughId),
        ).apply {
            setPackage(name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (start(context, deepLink)) return true
        val launcher = context.packageManager.getLaunchIntentForPackage(name) ?: return false
        launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, launcher)
    }

    private fun launchUri(gameVersionId: String?, slot: String?, playthroughId: String?): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("launch")
            .apply {
                gameVersionId?.lowercase()?.takeIf { it.isNotBlank() }
                    ?.let { appendQueryParameter("game", it) }
                // Both only alongside a game: neither means anything without
                // the version it belongs to, and upstream reads them together.
                if (gameVersionId != null) {
                    if (!slot.isNullOrBlank()) appendQueryParameter("slot", slot)
                    // The one name for a save that exists outside the device
                    // that holds it. A build that does not know the parameter
                    // ignores it, which is the same as not sending it.
                    if (!playthroughId.isNullOrBlank()) {
                        appendQueryParameter("playthrough", playthroughId)
                    }
                }
            }
            .build()

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess
}
