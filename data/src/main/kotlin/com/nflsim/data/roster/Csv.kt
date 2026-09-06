package com.nflsim.data.roster

/**
 * A small RFC-4180 CSV reader and writer.
 *
 * Deliberately dependency-free: :data stays importable anywhere, and a roster
 * file is not worth pulling a parser library in for. Handles quoted fields,
 * embedded commas, embedded newlines, and doubled quotes.
 */
object Csv {

    fun parse(text: String): List<List<String>> {
        // Strip full-line comments BEFORE parsing, not after. A comment is prose,
        // and prose contains apostrophes and quote marks - one stray " in a
        // comment would otherwise open a quoted field and swallow the whole file.
        val body = text.lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")

        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() { row += field.toString(); field.clear() }
        fun endRow() { endField(); rows += row.toList(); row.clear() }

        while (i < body.length) {
            val c = body[i]
            when {
                inQuotes && c == '"' && i + 1 < body.length && body[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> endField()
                !inQuotes && c == '\r' -> { /* handled by the \n that follows */ }
                !inQuotes && c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows.filter { cells -> cells.any { it.isNotBlank() } }
    }

    fun write(rows: List<List<String>>): String =
        rows.joinToString("\n") { row -> row.joinToString(",") { escape(it) } }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + value.replace("\"", "\"\"") + "\""
        else value
}
