package com.logie.gen1storage.sound

/**
 * The interface's own sounds, each bundled as a raw resource.
 *
 * The resource names say which moment a recording marks — `turn_on_pc`,
 * `press_ab` and so on — so which is which stays obvious from the file alone.
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

    /**
     * The two the trade animation makes, rendered from pokered's own note data
     * through the Game Boy pulse channel it was written for — see
     * `tools/synth_gb_sfx.py`. The cable's open end is SFX_HEAL_HP; the ball
     * moving along it ticks SFX_TINK once per step, as `trade.asm` does.
     */
    TRADE_CABLE("trade-cable", "TRADE CABLE", "sfx_heal_hp"),
    TRADE_BALL("trade-ball", "TRADE BALL", "sfx_tink"),
}
