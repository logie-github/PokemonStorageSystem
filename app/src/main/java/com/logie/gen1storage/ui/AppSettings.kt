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
        const val KEY_SHOW_ALL = "show-all-saves"
        const val KEY_PALETTE = "palette"
        const val KEY_WINDOW_PALETTE = "windows-follow-palette"
        const val KEY_WINDOWS_RIGHT = "windows-on-right"
        const val KEY_CART_PREFIX = "cart-name-"
        const val KEY_SOUND_OFF = "sound-off"
        const val KEY_SOUND_PREFIX = "sound-"
        /** As long as a name can be and still fit a cartridge label. */
        const val MAX_CART_NAME = 10
    }
}
