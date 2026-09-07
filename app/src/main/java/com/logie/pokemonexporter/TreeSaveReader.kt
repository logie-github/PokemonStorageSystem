package com.logie.packageexporter

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class TreeSaveReader(private val context: Context) {
    suspend fun scan(treeUri: Uri): Pair<List<ReadSave>, List<String>> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Selected folder is unavailable")
        val saves = mutableListOf<ReadSave>()
        val diagnostics = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var files = 0
        suspend fun walk(directory: DocumentFile, relative: String, depth: Int) {
            if (depth > 12 || !visited.add(directory.uri.toString())) return
            val entries = directory.listFiles()
            diagnostics += "${if (relative.isEmpty()) "." else relative}/[${entries.joinToString(",") { it.name ?: "?" }}]"
            for (entry in entries) {
                val name = entry.name ?: continue
                val child = if (relative.isEmpty()) name else "$relative/$name"
                if (entry.isDirectory) walk(entry, child, depth + 1)
                else if (entry.isFile && looksLikeSave(child)) {
                    files++
                    val label = "${name.removeSuffix(".lua")} · $child"
                    saves += try {
                        val data = context.contentResolver.openInputStream(entry.uri)?.use { input ->
                            require(entry.length() <= 32L * 1024 * 1024) { "Save exceeds 32 MB" }
                            SaveParser(input.readBytes().toString(Charsets.UTF_8)).parse()
                        } ?: error("Cannot open file")
                        require(data["player"] is Map<*, *> && data["party"] is Map<*, *>) { "Missing player or party table" }
                        ReadSave(entry.uri.toString(), label, data, null)
                    } catch (e: Exception) { ReadSave(entry.uri.toString(), label, null, e.message ?: "Cannot parse save") }
                }
            }
        }
        walk(root, "", 0)
        diagnostics += "Selected folder: ${root.uri}"
        diagnostics += "Save-like Lua files checked: $files"
        return saves to diagnostics
    }

    private fun looksLikeSave(path: String): Boolean {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        return name == "save.lua" || name.matches(Regex("save_[a-z0-9_-]+\\.lua")) ||
            name.matches(Regex("slot[0-9]+\\.lua")) || lower.contains("/saves/")
    }
}
