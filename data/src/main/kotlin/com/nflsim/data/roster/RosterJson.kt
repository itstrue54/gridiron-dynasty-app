package com.nflsim.data.roster

import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.Division
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * A roster file as JSON (SPEC 9.4) - the user's own, never shipped. Either
 * shape reads:
 *
 *     { "teams": [ { "abbrev": "KC", "city": "...", "nickname": "...",
 *                    "conference": "AFC", "division": "West",
 *                    "players": [ { "name": "...", "position": "QB", "overall": 90 } ] } ] }
 *
 *     { "players": [ { "name": "...", "position": "QB", "team": "KC" } ] }   (or a bare array)
 *
 * A player takes the CSV importer's fields under the same names and aliases
 * (name or first/last, position, overall, age, number, college, height,
 * weight, archetype, dev) and ratings either as fields or in a "ratings"
 * object. It becomes a row for RosterImporter.importTable, so JSON and CSV
 * are one importer with two doors.
 */
object RosterJson {

    /** A club as the file describes it. Null conference or division: place it anywhere. */
    data class Club(
        val abbrev: String,
        val city: String?,
        val nickname: String?,
        val conference: Conference?,
        val division: Division?,
    )

    data class Parsed(val clubs: List<Club>, val table: List<List<String>>, val problems: List<String>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun looksLikeJson(text: String): Boolean = text.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    fun parse(text: String): Parsed {
        val problems = mutableListOf<String>()
        val root = runCatching { json.parseToJsonElement(text) }.getOrElse {
            return Parsed(emptyList(), emptyList(), listOf("not valid JSON: ${it.message?.lineSequence()?.first()}"))
        }
        val clubs = mutableListOf<Club>()
        val players = mutableListOf<Map<String, String>>()

        fun player(e: JsonElement, team: String?, where: String) {
            val obj = e as? JsonObject ?: run { problems += "$where is not an object"; return }
            val row = mutableMapOf<String, String>()
            obj.forEach { (k, v) ->
                when {
                    k.equals("ratings", ignoreCase = true) && v is JsonObject ->
                        v.forEach { (code, n) -> scalar(n)?.let { row[code] = it } }
                    else -> scalar(v)?.let { row[k] = it }
                }
            }
            if (team != null && row.keys.none { RosterFormat.normalise(it) in RosterFormat.Columns.TEAM }) {
                row["team"] = team
            }
            players += row
        }

        when (root) {
            is JsonArray -> root.forEachIndexed { i, e -> player(e, null, "player ${i + 1}") }
            is JsonObject -> {
                (root["teams"] as? JsonArray)?.forEachIndexed { i, t ->
                    val team = t as? JsonObject ?: run { problems += "team ${i + 1} is not an object"; return@forEachIndexed }
                    val abbrev = team.text("abbrev", "abbreviation", "abbr", "code")?.uppercase()
                    if (abbrev.isNullOrBlank()) { problems += "team ${i + 1} has no abbrev"; return@forEachIndexed }
                    val conference = team.text("conference", "conf")?.let(::conferenceOf)
                    val division = team.text("division", "div")?.let(::divisionOf)
                    if (team.text("conference", "conf") != null && conference == null) {
                        problems += "$abbrev: conference '${team.text("conference", "conf")}' is not AFC/NFC or American/Continental"
                    }
                    if (team.text("division", "div") != null && division == null) {
                        problems += "$abbrev: division '${team.text("division", "div")}' is not East/North/South/West"
                    }
                    clubs += Club(abbrev, team.text("city", "location", "market"),
                        team.text("nickname", "name", "mascot"), conference, division)
                    (team["players"] as? JsonArray ?: team["roster"] as? JsonArray)
                        ?.forEachIndexed { j, p -> player(p, abbrev, "$abbrev player ${j + 1}") }
                }
                (root["players"] as? JsonArray)?.forEachIndexed { i, e -> player(e, null, "player ${i + 1}") }
                if (root["teams"] == null && root["players"] == null) problems += "no \"teams\" or \"players\" in the file"
            }
            else -> problems += "the file is not a JSON object or array"
        }

        // One header across everyone, in first-seen order, then a row each.
        val header = players.flatMap { it.keys }.distinct()
        val table = if (players.isEmpty()) emptyList()
            else listOf(header) + players.map { p -> header.map { p[it] ?: "" } }
        return Parsed(clubs, table, problems)
    }

    /**
     * A file to start from: what every field means, and one example club.
     * Replace the example with your own clubs - any number up to 32 - and
     * leave out whatever you do not know; the game fills the rest.
     */
    fun template(): String = """
{
  "_readme": [
    "Gridiron Dynasty roster file. Your own data: nothing like it ships with the game.",
    "List up to 32 teams. Each takes a place in the league by conference (AFC/NFC) and division (East/North/South/West); clubs you leave out stay fictional.",
    "A player needs a name and a position. Everything else is optional: overall (40-99) generates him to that level, or give ratings for exact numbers.",
    "Positions: QB RB FB WR TE LT LG C RG RT EDGE DT LB CB S K P (DE, OLB, HB, FS, SS and others are understood).",
    "Rating codes (0-99), any subset: spd acc str agi awr prc thp tas tam tad cth srr mrr drr rls rbk pbk tak pow mcv zcv kpw kac, and more - any the game does not know are listed after import.",
    "Up to 53 players per team go on the roster; more go to the practice squad. Short positions are filled for you.",
    "In the app: title screen -> Start with my own rosters -> pick this file."
  ],
  "teams": [
    {
      "abbrev": "EXA",
      "city": "Example City",
      "nickname": "Examples",
      "conference": "AFC",
      "division": "West",
      "players": [
        { "name": "Sam Example", "position": "QB", "number": 12, "age": 27, "overall": 84, "college": "State" },
        { "name": "Riley Sample", "position": "RB", "number": 28, "age": 24, "overall": 78 },
        { "name": "Jordan Model", "position": "WR", "number": 11, "age": 26, "height": "6-1", "weight": 195,
          "ratings": { "spd": 93, "acc": 91, "cth": 86, "srr": 84 } },
        { "name": "Casey Instance", "position": "EDGE", "age": 29, "overall": 88 },
        { "name": "Morgan Test", "position": "CB", "age": 25 }
      ]
    }
  ]
}
""".trimStart()

    private fun scalar(v: JsonElement): String? = when (v) {
        is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> null
    }

    private fun JsonObject.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value }
            ?.let { (it as? JsonPrimitive)?.jsonPrimitive?.content }?.trim()?.takeIf { it.isNotEmpty() }

    fun conferenceOf(s: String): Conference? = when (RosterFormat.normalise(s)) {
        "afc", "american", "americanfootballconference" -> Conference.AMERICAN
        "nfc", "continental", "nationalfootballconference" -> Conference.CONTINENTAL
        else -> null
    }

    fun divisionOf(s: String): Division? {
        val n = RosterFormat.normalise(s).removePrefix("afc").removePrefix("nfc")
        return Division.entries.firstOrNull { it.name.lowercase() == n }
    }
}
