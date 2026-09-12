package com.logie.gen1storage.ui

/**
 * How fast the text prints: FAST, MID or SLOW, which is what the games' own
 * OPTIONS screen offers and in that order.
 *
 * The cartridge counts frames between letters — one, three or five at sixty a
 * second — so these are those counts in milliseconds rather than numbers
 * chosen by eye. MID is the game's default and this one's.
 */
enum class TextSpeed(val id: String, val label: String, val letterMillis: Long) {
    FAST("fast", "FAST", 17L),
    MID("mid", "MID", 50L),
    SLOW("slow", "SLOW", 83L);

    /** The next one round, which is how a row that cycles is taken. */
    val next: TextSpeed get() = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * FAST rather than the cartridge's MID.
         *
         * The app has always printed at about one and a half frames a letter,
         * which is nearer FAST than MID, and defaulting to the cartridge's
         * speed would mean every message in the app suddenly taking twice as
         * long for someone who never asked for that. MID is a step away for
         * anyone who wants the cartridge's own pacing.
         */
        val DEFAULT = FAST

        fun fromId(id: String?): TextSpeed = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
