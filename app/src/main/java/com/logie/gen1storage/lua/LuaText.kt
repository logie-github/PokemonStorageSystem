package com.logie.gen1storage.lua

/**
 * Save files are byte strings, not text. Lua has no string encoding: `%q`
 * passes every byte outside `"`, `\`, newline, carriage return and NUL through
 * untouched, so a save can hold any byte sequence a player's name produced.
 *
 * Decoding a save as UTF-8 would replace anything invalid with U+FFFD and make
 * a byte-exact rewrite impossible. Everything in this app therefore moves save
 * bytes through ISO-8859-1, which maps 0x00..0xFF onto U+0000..U+00FF and back
 * with no loss. [displayText] re-interprets such a string as UTF-8 at the UI
 * boundary, which is what LÖVE actually wrote for a non-ASCII name.
 */
object LuaText {

    fun decode(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    fun encode(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    /** Byte-preserving string -> human-readable text. Falls back to the raw string. */
    fun displayText(raw: String): String {
        if (raw.all { it.code < 0x80 }) return raw
        val bytes = raw.toByteArray(Charsets.ISO_8859_1)
        val decoded = String(bytes, Charsets.UTF_8)
        return if (decoded.contains('�')) raw else decoded
    }
}
