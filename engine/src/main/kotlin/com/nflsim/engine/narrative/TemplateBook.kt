package com.nflsim.engine.narrative

import com.nflsim.engine.rng.Rng
import kotlinx.serialization.json.Json

/**
 * A JSON file under `narrative/`: for each kind of line, the ways of saying it
 * (SPEC 10.4).
 *
 * A line picks one of its templates with the [Rng] it is handed and fills
 * its `{slot}`s. A slot left unfilled is a bug in the file or the caller,
 * and says so rather than printing braces to the player.
 */
class TemplateBook(private val resource: String) {

    val templates: Map<String, List<String>> by lazy {
        json.decodeFromString<Map<String, List<String>>>(read(resource))
    }

    /** One way of saying [key], with [slots] filled in. */
    fun write(key: String, rng: Rng, vararg slots: Pair<String, Any>): String {
        val ways = templates[key] ?: error("$resource has no \"$key\"")
        var line = ways[rng.nextInt(ways.size)]
        slots.forEach { (name, value) -> line = line.replace("{$name}", value.toString()) }
        check('{' !in line || !SLOT.containsMatchIn(line)) { "\"$key\" left a slot unfilled: $line" }
        return line
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val SLOT = Regex("""\{(\w+)\}""")

        /** The slot names in a template. */
        fun slots(template: String): Set<String> =
            SLOT.findAll(template).map { it.groupValues[1] }.toSet()

        private fun read(resource: String): String =
            TemplateBook::class.java.getResourceAsStream(resource)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("$resource not found on the classpath")
    }
}
