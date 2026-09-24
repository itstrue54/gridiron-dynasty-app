package com.nflsim.engine.season

import com.nflsim.engine.rng.Rng
import kotlinx.serialization.json.Json

/**
 * The words news is written in, loaded from `narrative/news.json` (SPEC 10.4).
 *
 * Each kind of story has several ways to say it, and a headline picks one
 * with the [Rng] it is handed. `{slot}`s in a template are filled from what
 * the story is about; a slot left unfilled is a bug in the file or the
 * caller, and says so rather than printing braces to the player.
 */
object Headlines {

    private val json = Json { ignoreUnknownKeys = true }

    val templates: Map<String, List<String>> by lazy {
        json.decodeFromString<Map<String, List<String>>>(read("/narrative/news.json"))
    }

    /** One way of telling the [key] story, with [slots] filled in. */
    fun write(key: String, rng: Rng, vararg slots: Pair<String, Any>): String {
        val ways = templates[key] ?: error("narrative/news.json has no \"$key\"")
        var line = ways[rng.nextInt(ways.size)]
        slots.forEach { (name, value) -> line = line.replace("{$name}", value.toString()) }
        check(!SLOT.containsMatchIn(line)) { "\"$key\" left a slot unfilled: $line" }
        return line
    }

    /** The slot names in a template. */
    fun slots(template: String): Set<String> =
        SLOT.findAll(template).map { it.groupValues[1] }.toSet()

    private val SLOT = Regex("""\{(\w+)\}""")

    private fun read(resource: String): String =
        Headlines::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("$resource not found on the classpath")
}
