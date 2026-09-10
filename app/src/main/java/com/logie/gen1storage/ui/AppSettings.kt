package com.logie.gen1storage.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * The player's own preferences. Both default to off: the app should behave the
 * ordinary way until someone asks for something else.
 */
class AppSettings(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences("gen1storage-settings", Context.MODE_PRIVATE))

    /** Swipe controls, in the shape the TM35 Metronome mod defines them. */
    var swipeControls: Boolean
        get() = prefs.getBoolean(KEY_SWIPE, false)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE, value).apply()

    /**
     * Load every save on the account at once and show one combined list, so a
     * Pokémon can be found without remembering which playthrough it is in.
     * Off by default because it fetches every save rather than just the one
     * being opened.
     */
    var showAllSaves: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ALL, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ALL, value).apply()

    private companion object {
        const val KEY_SWIPE = "swipe-controls"
        const val KEY_SHOW_ALL = "show-all-saves"
    }
}
