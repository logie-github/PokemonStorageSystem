package com.logie.gen1storage.lua

/**
 * The value model for the restricted Lua-source grammar Gen1Recomp saves use.
 *
 * Upstream `src/core/SaveSerializer.lua` writes literals, `%q` strings and
 * keyed tables only, and reads that same grammar back with a data-only parser.
 * Nothing here executes Lua; a save is data.
 */
sealed interface LuaValue {
    data class Num(val value: Double) : LuaValue {
        override fun toString() = LuaNumbers.format(value)
    }

    data class Str(val value: String) : LuaValue

    data class Bool(val value: Boolean) : LuaValue

    /**
     * A Lua table. Insertion order is preserved for round-trip diagnostics, but
     * [LuaWriter] re-sorts on write exactly as upstream `serialize` does, so the
     * bytes never depend on the order values were added in.
     */
    class Table(entries: Map<LuaKey, LuaValue> = emptyMap()) : LuaValue {
        private val backing = LinkedHashMap<LuaKey, LuaValue>(entries)

        val keys: Set<LuaKey> get() = backing.keys
        val size: Int get() = backing.size
        fun isEmpty() = backing.isEmpty()
        fun entries(): List<Pair<LuaKey, LuaValue>> = backing.entries.map { it.key to it.value }

        operator fun get(key: LuaKey): LuaValue? = backing[key]
        operator fun get(key: String): LuaValue? = backing[LuaKey.Name(key)]
        operator fun get(index: Int): LuaValue? = backing[LuaKey.Index(index.toDouble())]

        operator fun set(key: LuaKey, value: LuaValue?) {
            if (value == null) backing.remove(key) else backing[key] = value
        }

        operator fun set(key: String, value: LuaValue?) = set(LuaKey.Name(key), value)
        operator fun set(index: Int, value: LuaValue?) = set(LuaKey.Index(index.toDouble()), value)

        fun remove(key: LuaKey): LuaValue? = backing.remove(key)

        /**
         * The 1..n contiguous prefix of integer keys — Lua's `ipairs` view, which
         * is how `save.party` and each `save.boxes[n]` are stored and how `#list`
         * is measured by every upstream caller.
         */
        fun array(): List<LuaValue> {
            val out = ArrayList<LuaValue>()
            var i = 1
            while (true) {
                val value = backing[LuaKey.Index(i.toDouble())] ?: break
                out.add(value)
                i++
            }
            return out
        }

        /** Replaces the 1..n array part with [values], clearing any stale tail. */
        fun setArray(values: List<LuaValue>) {
            var i = 1
            while (backing.remove(LuaKey.Index(i.toDouble())) != null) i++
            values.forEachIndexed { index, value -> backing[LuaKey.Index((index + 1).toDouble())] = value }
        }

        fun deepCopy(): Table {
            val copy = Table()
            for ((key, value) in backing) copy[key] = if (value is Table) value.deepCopy() else value
            return copy
        }

        override fun equals(other: Any?) = other is Table && other.backing == backing
        override fun hashCode() = backing.hashCode()
        override fun toString() = LuaWriter.encodeValue(this)

        companion object {
            fun ofArray(values: List<LuaValue>): Table = Table().apply { setArray(values) }
        }
    }
}

/** A table key. The grammar admits numbers and strings only. */
sealed interface LuaKey {
    data class Index(val value: Double) : LuaKey
    data class Name(val value: String) : LuaKey

    companion object {
        fun of(value: LuaValue): LuaKey? = when (value) {
            is LuaValue.Num -> Index(value.value)
            is LuaValue.Str -> Name(value.value)
            else -> null
        }
    }
}

// ------- convenience accessors used across the parser, transfer engine and UI

fun LuaValue?.asTable(): LuaValue.Table? = this as? LuaValue.Table
fun LuaValue?.asString(): String? = (this as? LuaValue.Str)?.value
fun LuaValue?.asDouble(): Double? = (this as? LuaValue.Num)?.value
fun LuaValue?.asInt(): Int? = (this as? LuaValue.Num)?.value?.let {
    if (it.isFinite() && it >= Int.MIN_VALUE.toDouble() && it <= Int.MAX_VALUE.toDouble()) it.toInt() else null
}
fun LuaValue?.asBoolean(): Boolean? = (this as? LuaValue.Bool)?.value

fun luaNum(value: Int): LuaValue.Num = LuaValue.Num(value.toDouble())
fun luaNum(value: Double): LuaValue.Num = LuaValue.Num(value)
fun luaStr(value: String): LuaValue.Str = LuaValue.Str(value)
