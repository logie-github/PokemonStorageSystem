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
     * The colour palette everything is drawn through, by [GbPalette] id.
     * Defaults to the untinted look.
     */
    var paletteId: String
        get() = prefs.getString(KEY_PALETTE, null) ?: GbPalette.ORIGINAL.id
        set(value) = prefs.edit().putString(KEY_PALETTE, value).apply()

    /**
     * Whether the windows take the palette too. Off keeps every box black on
     * white, the way the cartridge draws them whatever the screen is tinted.
     */
    var windowsFollowPalette: Boolean
        get() = prefs.getBoolean(KEY_WINDOW_PALETTE, false)
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
     * Call this app's storage BILL'S PC rather than naming its author.
     *
     * Off, because the machine is this app and it says so. On, it takes the
     * name the cartridge's own storage system carries, for a player who would
     * rather the whole thing read as the game it sits beside.
     */
    var billsPc: Boolean
        get() = prefs.getBoolean(KEY_BILLS_PC, false)
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
     * D-pad, wherever the finger lands — and lists stop scrolling under the
     * finger, because a gesture cannot be both a scroll and a step.
     *
     * Off by default. Someone who wants this knows they want it, and someone
     * who does not should never meet a list that will not scroll.
     */
    var swipeControls: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_CONTROLS, false)
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
        const val KEY_SHOW_ALL = "show-all-saves"
        const val KEY_SHOW_ALL_ITEMS = "show-all-items"
        const val KEY_PALETTE = "palette"
        const val KEY_WINDOW_PALETTE = "windows-follow-palette"
        const val KEY_WINDOWS_RIGHT = "windows-on-right"
        const val KEY_CART_PREFIX = "cart-name-"
        const val KEY_TRAINER_PREFIX = "trainer-sprite-"
        const val KEY_GAME_SLOT_ID_PREFIX = "game-slot-id-"
        const val KEY_BILLS_PC = "bills-pc"
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
