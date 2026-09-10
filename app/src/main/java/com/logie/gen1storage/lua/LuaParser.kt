package com.logie.gen1storage.lua

/** Thrown when a file is not valid Gen1Recomp save source. Carries the byte offset. */
class LuaParseException(val offset: Int, message: String) :
    Exception("parse error at byte $offset: $message")

/**
 * The reader half of upstream `src/core/SaveSerializer.lua`, ported field for
 * field: `return <table>` where a table holds `ident = value` or
 * `[literal] = value` entries and values are numbers, `%q` strings, booleans
 * and tables. Bare array entries and comments are rejected, matching the
 * limits `SaveData.decode` runs with (none — so the upstream defaults apply).
 *
 * Nothing is executed. A tampered save fails to parse instead of running.
 */
class LuaParser(private val source: String) {

    private var pos = 0
    private var depth = 0

    fun parse(): LuaValue.Table {
        skip()
        val word = identifierAt(pos)
        if (word != "return") fail("expected return")
        pos += word.length
        val value = readValue()
        skip()
        if (pos < source.length) fail("trailing content")
        return value as? LuaValue.Table ?: fail("save root must be a table")
    }

    private fun fail(why: String): Nothing = throw LuaParseException(pos + 1, why)

    private fun skip() {
        while (pos < source.length) {
            val c = source[pos]
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++ else break
        }
    }

    private fun peek(): Char = if (pos < source.length) source[pos] else ' '

    // Lua's `%a` / `%w` classes are ASCII in the C locale upstream runs under,
    // so this deliberately does not use Kotlin's Unicode-aware isLetter().
    private fun isNameStart(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c == '_'
    private fun isNamePart(c: Char) = isNameStart(c) || c in '0'..'9'

    private fun identifierAt(start: Int): String? {
        if (start >= source.length || !isNameStart(source[start])) return null
        var end = start + 1
        while (end < source.length && isNamePart(source[end])) end++
        return source.substring(start, end)
    }

    private fun readValue(): LuaValue {
        skip()
        if (pos >= source.length) fail("unexpected end of input")
        val c = peek()
        return when {
            c == '"' -> LuaValue.Str(readString())
            c == '{' -> readTable()
            isNameStart(c) -> {
                val word = identifierAt(pos) ?: fail("expected name")
                pos += word.length
                when (word) {
                    "true" -> LuaValue.Bool(true)
                    "false" -> LuaValue.Bool(false)
                    else -> {
                        pos -= word.length
                        fail("unexpected name '$word'")
                    }
                }
            }
            c == '-' || c == '+' || c == '.' || c in '0'..'9' -> LuaValue.Num(readNumber())
            else -> fail("unexpected character")
        }
    }

    private fun readNumber(): Double {
        val start = pos
        while (pos < source.length && source[pos] !in ",]}" && !source[pos].isWhitespace()) pos++
        val token = source.substring(start, pos)
        return LuaNumbers.parse(token) ?: run { pos = start; fail("malformed number") }
    }

    /** `%q` output: `\"`, `\\`, a backslash-newline pair, `\r`, and `\ddd` escapes. */
    private fun readString(): String {
        val out = StringBuilder()
        var i = pos + 1
        while (true) {
            if (i >= source.length) { pos = i; fail("unterminated string") }
            when (val c = source[i]) {
                '"' -> { pos = i + 1; return out.toString() }
                '\\' -> {
                    if (i + 1 >= source.length) { pos = i; fail("unterminated string") }
                    val next = source[i + 1]
                    if (next in '0'..'9') {
                        var end = i + 1
                        while (end < source.length && end < i + 4 && source[end] in '0'..'9') end++
                        val code = source.substring(i + 1, end).toInt()
                        if (code > 255) { pos = i; fail("escape out of range") }
                        out.append(code.toChar())
                        i = end
                    } else {
                        val decoded = ESCAPES[next] ?: run { pos = i; fail("bad string escape") }
                        out.append(decoded)
                        i += 2
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
    }

    private fun readTable(): LuaValue.Table {
        depth++
        if (depth > MAX_DEPTH) fail("table nesting too deep")
        pos++
        val table = LuaValue.Table()
        skip()
        if (peek() == '}') { pos++; depth--; return table }
        while (true) {
            skip()
            val key: LuaKey
            val c = peek()
            if (c == '[') {
                pos++
                val literal = readValue()
                skip()
                if (peek() != ']') fail("expected ]")
                pos++
                skip()
                if (peek() != '=') fail("expected =")
                pos++
                key = LuaKey.of(literal) ?: fail("nil table key")
            } else if (isNameStart(c)) {
                val ident = identifierAt(pos) ?: fail("expected name")
                pos += ident.length
                skip()
                if (peek() != '=') fail("expected =")
                pos++
                key = LuaKey.Name(ident)
            } else {
                fail("expected key")
            }
            val value = readValue()
            if (table[key] != null) fail("duplicate table key")
            table[key] = value
            if (table.size > MAX_TABLE_ENTRIES) fail("too many table entries")
            skip()
            when (peek()) {
                ',' -> {
                    pos++
                    skip()
                    if (peek() == '}') { pos++; break }
                }
                '}' -> { pos++; break }
                else -> fail("expected , or }")
            }
        }
        depth--
        return table
    }

    companion object {
        /** Upstream `SaveSerializer.MAX_DEPTH`. */
        const val MAX_DEPTH = 128

        /**
         * Upstream applies no entry cap when `SaveData.decode` reads a save; this
         * reader adds a generous one so a hostile file cannot exhaust an Android
         * process's heap.
         */
        const val MAX_TABLE_ENTRIES = 200_000

        private val ESCAPES = mapOf(
            '"' to '"', '\\' to '\\', 'n' to '\n', 'r' to '\r', 't' to '\t',
            'a' to '\u0007', 'b' to '\b', 'f' to '\u000C', 'v' to '\u000B',
            '\n' to '\n', '\r' to '\n',
        )

        fun parse(source: String): LuaValue.Table = LuaParser(source).parse()

        fun parseOrNull(source: String): LuaValue.Table? =
            runCatching { parse(source) }.getOrNull()
    }
}
