package com.logie.gen1storage.sound

/**
 * The interface's own sounds.
 *
 * [file] is the name the recording is looked up by, which is the sound's name
 * in pret/pokered's `data/audio/sfx.asm` — the same names the games use.
 */
enum class SoundEffect(val id: String, val label: String, val file: String) {
    OPEN_PC("open-pc", "OPEN PC", "turn_on_pc.ogg"),
    CURSOR("cursor", "CURSOR", "press_ab.ogg"),
    SAVE("save", "SAVE", "save.ogg"),
    OPTIONS("options", "OPTIONS", "start_menu.ogg"),
    LOG_OFF("log-off", "LOG OFF", "turn_off_pc.ogg"),
    SELECT("select", "SELECT", "enter_pc.ogg"),
}
