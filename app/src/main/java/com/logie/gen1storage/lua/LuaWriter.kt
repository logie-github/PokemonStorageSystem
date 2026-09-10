package com.logie.gen1storage.lua

/**
 * The writer half of upstream `src/core/SaveSerializer.lua`, reproduced so the
 * bytes this app commits are the bytes the game itself would have written for
 * the same table.
 *
 * The three things that make output deterministic upstream are reproduced here
 * exactly:
 *  - keys sort by Lua type name first (`"number" < "string"`, so numeric keys
 *    lead), then by value;
 *  - two-space indent per level, `pad .. "  " .. key .. " = " .. value`, joined
 *    with `",\n"` and closed with `",\n" .. pad .. "}"`; an empty table is `{}`;
 *  - numbers go through `tostring` (see [LuaNumbers]) and strings through `%q`.
 */
object LuaWriter {

    /** `SaveSerializer.encode`: the whole file, trailing newline included. */
    fun encode(table: LuaValue.Table): String = "return " + encodeValue(table) + "\n"

    fun encodeValue(value: LuaValue): String = StringBuilder().also { serialize(value, 0, it) }.toString()

    private fun serialize(value: LuaValue, indent: Int, out: StringBuilder) {
        when (value) {
            is LuaValue.Num -> out.append(LuaNumbers.format(value.value))
            is LuaValue.Bool -> out.append(if (value.value) "true" else "false")
            is LuaValue.Str -> quote(value.value, out)
            is LuaValue.Table -> serializeTable(value, indent, out)
        }
    }

    private fun serializeTable(table: LuaValue.Table, indent: Int, out: StringBuilder) {
        if (table.isEmpty()) { out.append("{}"); return }
        val pad = "  ".repeat(indent)
        out.append("{\n")
        val sorted = table.entries().sortedWith(ENTRY_ORDER)
        for ((index, entry) in sorted.withIndex()) {
            if (index > 0) out.append(",\n")
            out.append(pad).append("  ")
            appendKey(entry.first, out)
            out.append(" = ")
            serialize(entry.second, indent + 1, out)
        }
        out.append(",\n").append(pad).append("}")
    }

    private fun appendKey(key: LuaKey, out: StringBuilder) {
        when (key) {
            is LuaKey.Name ->
                // `k:match("^[%a_][%w_]*$")` — anything else is bracketed.
                if (IDENTIFIER.matches(key.value)) out.append(key.value)
                else { out.append('['); quote(key.value, out); out.append(']') }
            is LuaKey.Index -> {
                out.append('[')
                out.append(LuaNumbers.format(key.value))
                out.append(']')
            }
        }
    }

    /**
     * Lua 5.1 / LuaJIT `string.format("%q", s)` (lstrlib.c addquoted): `"` `\`
     * and newline take a backslash prefix — the newline literally, so `%q`
     * output spans lines — carriage return becomes `\r`, NUL becomes `\000`,
     * and every other byte is written through.
     */
    private fun quote(value: String, out: StringBuilder) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"', '\\' -> out.append('\\').append(c)
                '\n' -> out.append("\\\n")
                '\r' -> out.append("\\r")
                '\u0000' -> out.append("\\000")
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /**
     * `table.sort(keys, function(a, b) ... end)` from upstream: different Lua
     * types compare by type name, same types compare by value. String order is
     * byte order (C locale `strcoll`), so compare UTF-8 bytes unsigned rather
     * than UTF-16 code units.
     */
    private val ENTRY_ORDER = Comparator<Pair<LuaKey, LuaValue>> { a, b ->
        val left = a.first
        val right = b.first
        when {
            left is LuaKey.Index && right is LuaKey.Index -> left.value.compareTo(right.value)
            left is LuaKey.Name && right is LuaKey.Name -> compareBytes(left.value, right.value)
            // "number" < "string"
            left is LuaKey.Index -> -1
            else -> 1
        }
    }

    private fun compareBytes(a: String, b: String): Int {
        val left = a.toByteArray(Charsets.UTF_8)
        val right = b.toByteArray(Charsets.UTF_8)
        val shared = minOf(left.size, right.size)
        for (i in 0 until shared) {
            val diff = (left[i].toInt() and 0xFF) - (right[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return left.size - right.size
    }

    private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
}
