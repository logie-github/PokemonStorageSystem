package com.logie.gen1storage.lua

import java.util.Locale

/**
 * Reproduces LuaJIT's `tostring(number)`, which is `sprintf("%.14g", n)`.
 *
 * Upstream `SaveSerializer.serialize` emits numbers with plain `tostring`, so
 * matching that formatting exactly is what makes a parse -> serialize cycle
 * byte-identical to what the game itself would have written.
 */
object LuaNumbers {

    private const val PRECISION = 14

    fun format(value: Double): String {
        require(value.isFinite()) { "cannot serialize a non-finite number" }
        if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
        // %.14g renders any integer with at most 14 significant digits verbatim;
        // this is the overwhelmingly common case in a save (ids, levels, stats).
        if (value == Math.floor(value) && Math.abs(value) < 1e14) {
            return value.toLong().toString()
        }
        val exponent = scientificExponent(value)
        return if (exponent < -4 || exponent >= PRECISION) {
            val text = String.format(Locale.ROOT, "%.${PRECISION - 1}e", value)
            val mantissa = trimZeros(text.substringBefore('e'))
            mantissa + text.substring(text.indexOf('e'))
        } else {
            trimZeros(String.format(Locale.ROOT, "%.${PRECISION - 1 - exponent}f", value))
        }
    }

    /** The decimal exponent `%g` uses to choose between `%e` and `%f` styles. */
    private fun scientificExponent(value: Double): Int {
        val text = String.format(Locale.ROOT, "%.${PRECISION - 1}e", value)
        return text.substring(text.indexOf('e') + 1).toInt()
    }

    private fun trimZeros(text: String): String {
        if (!text.contains('.')) return text
        return text.trimEnd('0').trimEnd('.')
    }

    /**
     * Mirrors Lua's `tonumber` for the tokens this grammar can contain. Returns
     * null for anything `tonumber` would reject, which is how the parser fails
     * closed on a malformed literal.
     */
    fun parse(token: String): Double? {
        if (token.isEmpty()) return null
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        // Lua accepts hex integer literals; the writer never emits them, but
        // tonumber would take them, so the reader stays compatible.
        val hex = Regex("^([-+]?)0[xX]([0-9a-fA-F]+)$").matchEntire(trimmed)
        if (hex != null) {
            val magnitude = hex.groupValues[2].toULongOrNull(16)?.toDouble() ?: return null
            return if (hex.groupValues[1] == "-") -magnitude else magnitude
        }
        if (!Regex("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$").matches(trimmed)) return null
        return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
