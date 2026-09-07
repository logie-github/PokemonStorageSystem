package com.logie.packageexporter

/** Parses the literal-only grammar emitted by both upstream SaveSerializer modules. */
class SaveParser(private val source: String) {
    private var pos = 0
    fun parse(): Map<Any, Any?> {
        space()
        require(source.startsWith("return", pos)) { "Expected return at $pos" }
        pos += 6
        val result = value(0).table()
        space()
        require(pos == source.length) { "Unexpected content at $pos" }
        return result
    }
    private fun space() { while (pos < source.length && source[pos].isWhitespace()) pos++ }
    private fun take(c: Char): Boolean { space(); return if (source.getOrNull(pos) == c) { pos++; true } else false }
    private fun expect(c: Char) { require(take(c)) { "Expected $c at $pos" } }
    private fun word(): String {
        space(); val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        require(pos > start) { "Expected key at $pos" }
        return source.substring(start, pos)
    }
    private fun value(depth: Int): Any? {
        require(depth < 128) { "Save nesting too deep" }
        space()
        return when (source.getOrNull(pos)) {
            '{' -> {
                pos++
                val result = linkedMapOf<Any, Any?>()
                while (!take('}')) {
                    val key = if (take('[')) { val k = value(depth + 1) ?: error("Nil key"); expect(']'); k } else word()
                    expect('=')
                    result[key] = value(depth + 1)
                    if (!take(',')) { expect('}'); break }
                }
                result
            }
            '"', '\'' -> string()
            else -> {
                val start = pos
                while (pos < source.length && !source[pos].isWhitespace() && source[pos] !in ",}]") pos++
                val token = source.substring(start, pos)
                when (token) { "true" -> true; "false" -> false; "nil" -> null
                    else -> token.toDoubleOrNull() ?: error("Invalid literal at $start") }
            }
        }
    }
    private fun string(): String {
        val quote = source[pos++]
        val out = StringBuilder()
        while (pos < source.length) {
            val c = source[pos++]
            if (c == quote) return out.toString()
            if (c != '\\') { out.append(c); continue }
            require(pos < source.length) { "Unterminated escape" }
            val e = source[pos++]
            if (e.isDigit()) {
                val start = pos - 1
                while (pos < source.length && pos - start < 3 && source[pos].isDigit()) pos++
                val code = source.substring(start, pos).toInt()
                require(code <= 255) { "Invalid escape" }; out.append(code.toChar())
            } else out.append(when (e) {
                'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'a' -> 7.toChar()
                'b' -> 8.toChar(); 'f' -> 12.toChar(); 'v' -> 11.toChar()
                '\\', '"', '\'', '\n' -> e
                '\r' -> { if (source.getOrNull(pos) == '\n') pos++; '\n' }
                else -> error("Unsupported escape at $pos")
            })
        }
        error("Unterminated string")
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.table(): Map<Any, Any?> = this as? Map<Any, Any?> ?: error("Expected a save table")
fun Map<Any, Any?>.child(key: Any): Map<Any, Any?> = (this[key] as? Map<*, *>)?.let { @Suppress("UNCHECKED_CAST") (it as Map<Any, Any?>) }.orEmpty()
fun Map<Any, Any?>.numbered(): List<Pair<Int, Map<Any, Any?>>> =
    entries.mapNotNull { (key, value) -> (key as? Number)?.toInt()?.let { it to value.table() } }.sortedBy { it.first }

data class ReadSave(val path: String, val label: String, val data: Map<Any, Any?>?, val error: String?)

class SaveReader(private val fs: PackageFileSystem) {
    suspend fun scan(): Pair<List<ReadSave>, List<String>> {
        val saves = mutableListOf<ReadSave>()
        val diagnostics = mutableListOf<String>()
        val packages = listOf("com.theboisclub.pokemonred.androidfixes", "com.theboisclub.pokemonred", "com.underdecodedhd.gen2recomp")
        for (pkg in packages) {
            val root = "${PackageFileService.DATA_ROOT}/$pkg/files/save/pokemon-love2d"
            try {
                val entries = fs.list(root)
                val options = entries.firstOrNull { it.name == "options.lua" }?.let {
                    runCatching { SaveParser(fs.readText(it)).parse() }.getOrDefault(emptyMap())
                }.orEmpty()
                suspend fun read(entry: PackageEntry, version: String, slot: String) {
                    val name = options.child("saveSlots").child(version).child("names")[slot]?.toString() ?: slot
                    val label = "$version · $name · $pkg"
                    saves += try {
                        val data = SaveParser(fs.readText(entry)).parse()
                        require(data["party"] is Map<*, *> && data["player"] is Map<*, *>) { "Missing player or party table" }
                        ReadSave(entry.path, label, data, null)
                    } catch (e: Exception) { ReadSave(entry.path, label, null, e.message ?: "Cannot read save") }
                }
                for (entry in entries.filter { !it.directory && Regex("save(?:_[A-Za-z0-9_-]+)?\\.lua").matches(it.name) }) {
                    read(entry, entry.name.removeSuffix(".lua").removePrefix("save_").let { if (it == "save") "red" else it }, "Legacy save")
                }
                entries.firstOrNull { it.name == "saves" && it.directory }?.let { dir ->
                    for (version in fs.list(dir.path).filter { it.directory }) {
                        for (entry in fs.list(version.path).filter { !it.directory && Regex("slot[0-9]+\\.lua").matches(it.name) }) {
                            read(entry, version.name, entry.name.removeSuffix(".lua"))
                        }
                    }
                }
                diagnostics += "$pkg: scanned ${entries.size} root entries"
            } catch (e: Exception) { diagnostics += "$pkg: ${e.message}" }
        }
        return saves to diagnostics
    }
}
