package com.logie.gen1storage.ui

import android.content.Context
import android.content.SharedPreferences

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

    private companion object {
        const val KEY_SHOW_ALL = "show-all-saves"
        const val KEY_PALETTE = "palette"
        const val KEY_WINDOW_PALETTE = "windows-follow-palette"
        const val KEY_WINDOWS_RIGHT = "windows-on-right"
    }
}
