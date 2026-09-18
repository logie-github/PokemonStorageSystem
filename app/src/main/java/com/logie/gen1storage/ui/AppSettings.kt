package com.logie.gen1storage.ui

import android.content.Context
import android.content.SharedPreferences
import com.logie.gen1storage.sound.SoundEffect

/**
 * The player's own preferences. Every one defaults to the plain behaviour: the
 * app should look and act ordinary until someone asks for something else.
 */
class AppSettings(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences("gen1storage-settings", Context.MODE_PRIVATE))

    /**
     * Load every save on the account at once and show one combined list, so a
     * Pokémon can be found without remembering which playthrough it is in.
     * Off by default because it fetches every save rather than just the one
     * being opened.
     */
    var showAllSaves: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ALL, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ALL, value).apply()

    /**
     * Show every save's items in the item PC at once, the way [showAllSaves]
     * does for Pokémon. Off by default, for the same reason: it fetches every
     * save rather than the one in the machine.
     */
    var showAllItems: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ALL_ITEMS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ALL_ITEMS, value).apply()

    /**
     * Let Android copy this app into the player's Google account, the way it
     * backs up any app that allows it.
     *
     * Off, and nothing leaves the device. On, the boxes and the settings go
     * into the account's backup and come back when the app is installed again
     * — see `StorageBackupAgent`, which is what reads this, and [restoredUids]
     * for what happens to a Pokémon that comes back from one.
     *
     * Off by default. A copy of someone's Pokémon leaving their phone is not
     * something to start doing without being asked.
     */
    var cloudBackup: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_BACKUP, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_BACKUP, value).apply()

    /**
     * Pokémon that came back from a backup and have not been checked yet.
     *
     * A backup is the boxes as they were at one moment, and the cartridges
     * have moved on since. One that was transferred into a save after the
     * backup was taken is in that save now *and* in the copy being restored,
     * which is the one thing this app is built never to allow.
     *
     * So every Pokémon a restore brings back is listed here, and each one is
     * dropped from the list the first time a save is read that does not hold
     * it. A Pokémon found in a save is taken out of the PC instead: the
     * cartridge owns it, and the PC's copy is a picture of one that has
     * already left.
     */
    var restoredUids: Set<String>
        get() = prefs.getStringSet(KEY_RESTORED_UIDS, emptySet()).orEmpty()
        set(value) = prefs.edit().apply {
            if (value.isEmpty()) remove(KEY_RESTORED_UIDS)
            else putStringSet(KEY_RESTORED_UIDS, value)
        }.apply()

    /**
     * The colour palette everything is drawn through, by [GbPalette] id.
     * Defaults to the Game Boy Color's own pastel mix, free from the start
     * the same as the tint-free look is — a new install reads as a Game Boy
     * Color already turned on, not as a monochrome one waiting to be told to
     * do something else.
     */
    var paletteId: String
        get() = prefs.getString(KEY_PALETTE, null) ?: GbPalette.GBC_PASTEL.id
        set(value) = prefs.edit().putString(KEY_PALETTE, value).apply()

    /**
     * Whether the windows take the palette too.
     *
     * On, which makes a chosen palette the whole screen rather than the
     * ground behind boxes that stayed black on white. Turning it off is the
     * BLACK ON WHITE BOXES row, and that is the cartridge's own look: it drew
     * its text boxes black on white whatever the screen was tinted.
     */
    var windowsFollowPalette: Boolean
        get() = prefs.getBoolean(KEY_WINDOW_PALETTE, true)
        set(value) = prefs.edit().putBoolean(KEY_WINDOW_PALETTE, value).apply()

    /**
     * Which edge the menus and lists sit against. The games put them right;
     * a left-handed grip wants them left.
     */
    var windowsOnRight: Boolean
        get() = prefs.getBoolean(KEY_WINDOWS_RIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_WINDOWS_RIGHT, value).apply()

    /**
     * What a player called a cartridge, if they called it anything. Kept per
     * save so a renamed cart survives a restart, unlike which one is loaded.
     */
    fun cartName(key: String): String? =
        prefs.getString(KEY_CART_PREFIX + key, null)?.takeIf { it.isNotBlank() }

    fun setCartName(key: String, name: String) {
        val trimmed = name.trim().take(MAX_CART_NAME)
        prefs.edit().apply {
            if (trimmed.isEmpty()) remove(KEY_CART_PREFIX + key)
            else putString(KEY_CART_PREFIX + key, trimmed)
        }.apply()
    }

    /**
     * How fast the text prints, as the games' own OPTIONS put it.
     *
     * MID is the cartridge's default and this one's. Kept as the enum's name
     * rather than a number of milliseconds, so a stored preference survives
     * the speeds themselves being retuned.
     */
    var textSpeed: TextSpeed
        get() = TextSpeed.fromId(prefs.getString(KEY_TEXT_SPEED, null))
        set(value) = prefs.edit().putString(KEY_TEXT_SPEED, value.id).apply()

    /**
     * Whether Generation II sprites take the chosen palette rather than the
     * colours the Game Boy Color gave them.
     *
     * Off: a Gold sprite is the colours in its own file, and a shiny one the
     * pair the cartridge swapped in. On: tinted like the Generation I art,
     * with a shiny reading the palette backwards.
     */
    var gbcFollowsPalette: Boolean
        get() = prefs.getBoolean(KEY_GBC_FOLLOWS_PALETTE, false)
        set(value) = prefs.edit().putBoolean(KEY_GBC_FOLLOWS_PALETTE, value).apply()

    /**
     * Which trainer a playthrough's card wears, by its sprite id.
     *
     * Kept per save and on disk, because it is a choice about a playthrough
     * rather than about this sitting — unlike which card is inserted, which
     * is deliberately forgotten. Null is not "none chosen yet" being treated
     * as a choice: a card with nothing set falls back to showing the party's
     * lead, which is what it showed before trainers could be picked at all.
     */
    fun trainerSprite(key: String): String? =
        prefs.getString(KEY_TRAINER_PREFIX + key, null)?.takeIf { it.isNotBlank() }

    /**
     * A slot id the server told the app about, kept because the account
     * listing will not say it twice. Learnt rather than asked for.
     */
    fun gameSlotId(key: String): String? =
        prefs.getString(KEY_GAME_SLOT_ID_PREFIX + key, null)?.takeIf { it.isNotBlank() }

    fun setGameSlotId(key: String, slot: String?) {
        prefs.edit().apply {
            if (slot.isNullOrBlank()) remove(KEY_GAME_SLOT_ID_PREFIX + key)
            else putString(KEY_GAME_SLOT_ID_PREFIX + key, slot)
        }.apply()
    }

    fun setTrainerSprite(key: String, id: String?) {
        prefs.edit().apply {
            if (id.isNullOrBlank()) remove(KEY_TRAINER_PREFIX + key)
            else putString(KEY_TRAINER_PREFIX + key, id)
        }.apply()
    }

    /**
     * Whether the player has been shown around the machine once already.
     *
     * False on a fresh install, which is the only thing that starts the
     * introduction — it is a first run, not a thing to meet again every time
     * the app opens. REPLAY TUTORIAL in OPTIONS is what puts it back to false,
     * so someone who wants it again can have it and nobody else ever sees it
     * twice.
     */
    var tutorialSeen: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_TUTORIAL_SEEN, value).apply()

    /**
     * Call this app's storage BILL'S PC rather than naming its author.
     *
     * On. The machine in the games is Bill's, the man himself is the one who
     * shows a new install round it (see [Gen1Tutorial]), and a player who
     * opens this app is opening the Pokémon Storage System rather than a
     * piece of software by someone they have never heard of. Turning it off
     * is the row in OPTIONS, and puts the author's name back.
     */
    var billsPc: Boolean
        get() = prefs.getBoolean(KEY_BILLS_PC, true)
        set(value) = prefs.edit().putBoolean(KEY_BILLS_PC, value).apply()

    /**
     * Whether the PC will trade with itself, and so evolve the four that only
     * evolve by trading.
     *
     * Off. It is the one thing in this app that changes a Pokémon into another
     * Pokémon, and that should be asked for rather than found.
     */
    var tradeEvolution: Boolean
        get() = prefs.getBoolean(KEY_TRADE_EVOLUTION, false)
        set(value) = prefs.edit().putBoolean(KEY_TRADE_EVOLUTION, value).apply()

    /**
     * Whether a trade plays the cartridge's own animation on the way through.
     *
     * Off, because it takes about eight seconds and the result is the same
     * either way. On, the link cable is drawn, the ball travels down it
     * ticking, and the Pokémon comes out the far end.
     */
    var tradeAnimation: Boolean
        get() = prefs.getBoolean(KEY_TRADE_ANIMATION, false)
        set(value) = prefs.edit().putBoolean(KEY_TRADE_ANIMATION, value).apply()

    /**
     * Drive the app by swiping rather than by reaching for what you want.
     *
     * Off, and the app is tapped and scrolled: a tap takes what is under it
     * and a list is dragged the way any list is. On, a swipe anywhere is the
     * D-pad, wherever the finger lands, and lists stop scrolling under the
     * finger, because a gesture cannot be both a scroll and a step.
     *
     * On by default. This app is a Game Boy with the buttons taken away, and
     * a D-pad under the thumb is how it was meant to be driven; the row in
     * OPTIONS gives the ordinary tapping and scrolling back to anyone who
     * would rather have it.
     */
    var swipeControls: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_CONTROLS, true)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_CONTROLS, value).apply()

    /**
     * Stop the interface moving. Off by default; on, nothing animates and
     * everything still draws.
     */
    var reduceMotion: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_REDUCE_MOTION, value).apply()

    /**
     * The short tick under the finger when the cursor moves or a row is taken.
     * On, because a glass screen otherwise gives back nothing at all.
     */
    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /**
     * Whether a shared Pokémon comes out on paper.
     *
     * On: the card is set on a torn sheet the way a Game Boy Printer handed
     * one over. Off gives the print alone, which is what a player wants when
     * it is going somewhere that will crop it anyway.
     */
    var printerBorder: Boolean
        get() = prefs.getBoolean(KEY_PRINTER_BORDER, true)
        set(value) = prefs.edit().putBoolean(KEY_PRINTER_BORDER, value).apply()

    fun motionEnabled(motion: Motion): Boolean =
        !reduceMotion && prefs.getBoolean(KEY_MOTION_PREFIX + motion.id, true)

    fun setMotionEnabled(motion: Motion, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MOTION_PREFIX + motion.id, enabled).apply()
    }

    /** One switch over the lot, for when none of it is wanted. */
    var soundOff: Boolean
        get() = prefs.getBoolean(KEY_SOUND_OFF, false)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_OFF, value).apply()

    fun soundEnabled(effect: SoundEffect): Boolean =
        !soundOff && prefs.getBoolean(KEY_SOUND_PREFIX + effect.id, true)

    fun setSoundEnabled(effect: SoundEffect, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND_PREFIX + effect.id, enabled).apply()
    }

    private companion object {
        const val KEY_TEXT_SPEED = "text-speed"
        const val KEY_CLOUD_BACKUP = "cloud-backup"
        const val KEY_GBC_FOLLOWS_PALETTE = "gbc-follows-palette"
        const val KEY_RESTORED_UIDS = "restored-uids"
        const val KEY_SHOW_ALL = "show-all-saves"
        const val KEY_SHOW_ALL_ITEMS = "show-all-items"
        const val KEY_PALETTE = "palette"
        const val KEY_WINDOW_PALETTE = "windows-follow-palette"
        const val KEY_WINDOWS_RIGHT = "windows-on-right"
        const val KEY_CART_PREFIX = "cart-name-"
        const val KEY_TRAINER_PREFIX = "trainer-sprite-"
        const val KEY_GAME_SLOT_ID_PREFIX = "game-slot-id-"
        const val KEY_BILLS_PC = "bills-pc"
        const val KEY_TUTORIAL_SEEN = "tutorial-seen"
        const val KEY_TRADE_EVOLUTION = "trade-evolution"
        const val KEY_TRADE_ANIMATION = "trade-animation"
        const val KEY_REDUCE_MOTION = "reduce-motion"
        const val KEY_SWIPE_CONTROLS = "swipe-controls"
        const val KEY_MOTION_PREFIX = "motion-"
        const val KEY_HAPTICS = "haptics"
        const val KEY_PRINTER_BORDER = "printer-border"
        const val KEY_SOUND_OFF = "sound-off"
        const val KEY_SOUND_PREFIX = "sound-"
        /** As long as a name can be and still fit a cartridge label. */
        const val MAX_CART_NAME = 10
    }
}
