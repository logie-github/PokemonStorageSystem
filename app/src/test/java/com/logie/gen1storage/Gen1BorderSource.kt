package com.logie.gen1storage

import java.io.File

/**
 * Reads the border tiles back out of the source that draws them.
 *
 * The tiles are private to the drawing code, where they belong — nothing else
 * has any business reading them. Parsing the file is how the test checks the
 * transcription without widening that surface just to be testable.
 */
object Gen1BorderSource {

    private val source: String by lazy {
        val candidates = listOf(
            "app/src/main/java/com/logie/gen1storage/ui/Gen1Border.kt",
            "src/main/java/com/logie/gen1storage/ui/Gen1Border.kt",
        )
        candidates.map(::File).firstOrNull { it.isFile }?.readText()
            ?: error("Gen1Border.kt not found from ${File("").absolutePath}")
    }

    private val row = Regex("\"([.#]{8})\"")
    private val number = Regex("\\d+")

    fun tile(name: String): List<String> =
        row.findAll(listBody("private val $name = listOf(")).map { it.groupValues[1] }.toList()

    fun edgeRows(): List<Int> = numbers("private val EDGE_ROWS = listOf(")

    fun edgeColumns(): List<Int> = numbers("private val EDGE_COLUMNS = listOf(")

    private fun numbers(marker: String): List<Int> =
        number.findAll(listBody(marker)).map { it.value.toInt() }.toList()

    private fun listBody(marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "$marker not found in Gen1Border.kt" }
        val open = start + marker.length
        return source.substring(open, source.indexOf(')', open))
    }
}
