package com.logie.gen1storage.sound

/**
 * The interface's own sounds, each bundled as a raw resource.
 *
 * The resource names carry the sound's name in the games' own audio data —
 * `turn_on_pc`, `press_ab` and so on — so which recording is which stays
 * obvious from the file alone.
 */
enum class SoundEffect(val id: String, val label: String, val resourceName: String) {
    OPEN_PC("open-pc", "OPEN PC", "sfx_turn_on_pc"),
    CURSOR("cursor", "CURSOR", "sfx_press_ab"),
    SAVE("save", "SAVE", "sfx_save"),
    OPTIONS("options", "OPTIONS", "sfx_start_menu"),
    LOG_OFF("log-off", "LOG OFF", "sfx_turn_off_pc"),
    SELECT("select", "SELECT", "sfx_enter_pc"),

    /**
     * A deposit or a withdrawal actually going through, which is the one
     * moment in the app worth hearing confirmed.
     */
    TRANSFER("transfer", "TRANSFER", "sfx_withdraw_deposit"),
}
