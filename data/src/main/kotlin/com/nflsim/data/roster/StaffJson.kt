package com.nflsim.data.roster

import com.nflsim.engine.model.GamePlan
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeSide
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A club's front office and coaching staff as a roster file gives them
 * (SPEC 9.4): its general manager, its head coach, three coordinators and a
 * coach for each position group, and the schemes it runs. Everything past a
 * name is optional; what the file leaves out, the game fills.
 *
 *     "gm": { "name": "...", "aggression": 0.7, "winNow": 0.8, "loyalty": 0.5, "risk": 0.4 },
 *     "offenseScheme": "OFF_WEST_COAST", "defenseScheme": "DEF_43_OVER",
 *     "staff": {
 *       "headCoach": { "name": "...", "age": 60, "ratings": { "development": 80, ... },
 *                      "contractYears": 3, "hotSeat": 20, "tendencies": { "fourthDownAggression": 0.6 } },
 *       "offensiveCoordinator": { ... }, "defensiveCoordinator": { ... },
 *       "specialTeamsCoordinator": { ... },
 *       "positionCoaches": { "QB": "Just A Name", "EDGE": { ... }, ... }
 *     }
 */
object StaffJson {

    data class CoachSpec(
        val name: String,
        val age: Int? = null,
        /** A scheme id, checked against the side the coach works on. */
        val scheme: String? = null,
        val ratings: Map<String, Int> = emptyMap(),
        val contractYears: Int? = null,
        /** 0..100: how close he is to being fired (CoachingCarousel). */
        val hotSeat: Int? = null,
        val tendencies: GamePlan = GamePlan(),
    )

    data class StaffSpec(
        val headCoach: CoachSpec? = null,
        val offCoordinator: CoachSpec? = null,
        val defCoordinator: CoachSpec? = null,
        val stCoordinator: CoachSpec? = null,
        val positionCoaches: Map<PositionGroup, CoachSpec> = emptyMap(),
    )

    data class GmSpec(
        val name: String?,
        val aggression: Float? = null,
        val winNowVsFuture: Float? = null,
        val loyaltyToOwnPlayers: Float? = null,
        val riskTolerance: Float? = null,
    )

    /** The six coach ratings, by the names the file may use. */
    val RATING_KEYS: Map<String, String> = mapOf(
        "development" to "development", "dev" to "development",
        "gameplan" to "gameplan", "gameplanning" to "gameplan",
        "adjustments" to "adjustments", "adj" to "adjustments",
        "discipline" to "discipline",
        "motivation" to "motivation",
        "evaluation" to "evaluation", "eval" to "evaluation",
    )

    private val GROUP_ALIASES: Map<String, PositionGroup> = buildMap {
        PositionGroup.entries.forEach { put(it.name.lowercase(), it) }
        put("quarterbacks", PositionGroup.QB); put("runningbacks", PositionGroup.RB)
        put("widereceivers", PositionGroup.WR); put("tightends", PositionGroup.TE)
        put("offensiveline", PositionGroup.OL); put("ol", PositionGroup.OL)
        put("outsidelinebackers", PositionGroup.EDGE); put("olb", PositionGroup.EDGE); put("de", PositionGroup.EDGE)
        put("defensiveline", PositionGroup.DT); put("dl", PositionGroup.DT)
        put("linebackers", PositionGroup.LB); put("ilb", PositionGroup.LB)
        put("cornerbacks", PositionGroup.CB); put("safeties", PositionGroup.S)
        put("specialteams", PositionGroup.ST)
    }

    fun gm(e: JsonElement?, where: String, problems: MutableList<String>): GmSpec? = when (e) {
        null, is JsonNull -> null
        is JsonPrimitive -> GmSpec(e.content.trim().takeIf { it.isNotEmpty() })
        is JsonObject -> GmSpec(
            name = e.text("name"),
            aggression = e.unit("aggression", where, problems),
            winNowVsFuture = e.unit("winNow", where, problems) ?: e.unit("winNowVsFuture", where, problems),
            loyaltyToOwnPlayers = e.unit("loyalty", where, problems) ?: e.unit("loyaltyToOwnPlayers", where, problems),
            riskTolerance = e.unit("risk", where, problems) ?: e.unit("riskTolerance", where, problems),
        )
        else -> { problems += "$where: gm is not a name or an object"; null }
    }

    fun staff(e: JsonElement?, where: String, problems: MutableList<String>): StaffSpec? {
        val obj = e as? JsonObject ?: return null.also { if (e != null && e !is JsonNull) problems += "$where: staff is not an object" }
        fun one(vararg keys: String, side: SchemeSide) = keys.firstNotNullOfOrNull { k -> obj.entry(k) }
            ?.let { coach(it, "$where ${keys.first()}", side, problems) }
        val positions = (obj.entry("positionCoaches") ?: obj.entry("positions")) as? JsonObject
        val byGroup = mutableMapOf<PositionGroup, CoachSpec>()
        positions?.forEach { (k, v) ->
            val group = GROUP_ALIASES[RosterFormat.normalise(k)]
            if (group == null) { problems += "$where: '$k' is not a position group (${PositionGroup.entries.joinToString(" ")})"; return@forEach }
            val side = if (group in OFFENSIVE_GROUPS) SchemeSide.OFFENSE else SchemeSide.DEFENSE
            coach(v, "$where $k coach", side, problems)?.let { byGroup[group] = it }
        }
        return StaffSpec(
            headCoach = one("headCoach", "head", "hc", side = SchemeSide.OFFENSE),
            offCoordinator = one("offensiveCoordinator", "oc", side = SchemeSide.OFFENSE),
            defCoordinator = one("defensiveCoordinator", "dc", side = SchemeSide.DEFENSE),
            stCoordinator = one("specialTeamsCoordinator", "stc", side = SchemeSide.OFFENSE),
            positionCoaches = byGroup,
        )
    }

    /** A scheme by id or by name, on the side it has to be; null (and a problem) otherwise. */
    fun scheme(raw: String?, side: SchemeSide, where: String, problems: MutableList<String>): String? {
        if (raw.isNullOrBlank()) return null
        val key = RosterFormat.normalise(raw)
        val found: Scheme? = SchemeCatalog.all.firstOrNull {
            RosterFormat.normalise(it.id) == key || RosterFormat.normalise(it.name) == key
        }
        return when {
            found == null -> null.also { problems += "$where: unknown scheme '$raw'; known are ${SchemeCatalog.all.joinToString(", ") { it.id }}" }
            found.side != side -> null.also { problems += "$where: ${found.id} is not a${if (side == SchemeSide.OFFENSE) "n offensive" else " defensive"} scheme" }
            else -> found.id
        }
    }

    /** Head coach and special teams take the offensive scheme, as a generated staff does. */
    private val OFFENSIVE_GROUPS = setOf(PositionGroup.QB, PositionGroup.RB, PositionGroup.WR, PositionGroup.TE, PositionGroup.OL)

    private fun coach(e: JsonElement, where: String, side: SchemeSide, problems: MutableList<String>): CoachSpec? {
        if (e is JsonPrimitive) return e.content.trim().takeIf { it.isNotEmpty() }?.let { CoachSpec(it) }
        val obj = e as? JsonObject ?: run { problems += "$where is not a name or an object"; return null }
        val name = obj.text("name") ?: run { problems += "$where has no name"; return null }
        val ratings = mutableMapOf<String, Int>()
        (obj.entry("ratings") as? JsonObject)?.forEach { (k, v) ->
            val key = RATING_KEYS[RosterFormat.normalise(k)]
            val n = (v as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
            when {
                key == null -> problems += "$where ($name): unknown coach rating '$k' (${RATING_KEYS.values.distinct().joinToString(" ")})"
                n == null -> problems += "$where ($name): $k is not a number"
                n !in 0..100 -> { problems += "$where ($name): $k $n is outside 0-100, clamped"; ratings[key] = n.coerceIn(0, 100) }
                else -> ratings[key] = n
            }
        }
        val t = obj.entry("tendencies") as? JsonObject
        fun lever(k: String) = t?.unit(k, "$where ($name)", problems)
        return CoachSpec(
            name = name,
            age = obj.whole("age", 20..95, "$where ($name)", problems, clamp = false),
            scheme = scheme(obj.text("scheme"), side, "$where ($name)", problems),
            ratings = ratings,
            contractYears = obj.whole("contractYears", 0..10, "$where ($name)", problems, clamp = true),
            hotSeat = obj.whole("hotSeat", 0..100, "$where ($name)", problems, clamp = true),
            tendencies = GamePlan(
                passRate = lever("passRate"), playActionRate = lever("playActionRate"),
                deepShotRate = lever("deepShotRate"), trailingPassScale = t?.text("trailingPassScale")?.toFloatOrNull(),
                twoMinutePassBoost = lever("twoMinutePassBoost"), blitzRate = lever("blitzRate"),
                manZoneSplit = lever("manZoneSplit"), doubleTeamRate = lever("doubleTeamRate"),
                fourthDownAggression = lever("fourthDownAggression"),
            ),
        )
    }

    private fun JsonObject.entry(key: String): JsonElement? =
        entries.firstOrNull { RosterFormat.normalise(it.key) == RosterFormat.normalise(key) }?.value

    private fun JsonObject.text(key: String): String? =
        (entry(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A whole number in [range]. Out of range it is clamped, or with [clamp]
     * false left to the game (an age of 200 is a typo, not a very old coach);
     * either way it is said.
     */
    private fun JsonObject.whole(key: String, range: IntRange, where: String, problems: MutableList<String>, clamp: Boolean): Int? {
        val raw = text(key) ?: return null
        val v = raw.toDoubleOrNull()?.toInt() ?: return null.also { problems += "$where: $key '$raw' is not a number" }
        if (v in range) return v
        return if (clamp) {
            problems += "$where: $key $v is outside ${range.first}-${range.last}, clamped"
            v.coerceIn(range)
        } else {
            problems += "$where: $key $v is outside ${range.first}-${range.last}, left to the game"
            null
        }
    }

    /** A 0..1 value; out of range is clamped and said. */
    private fun JsonObject.unit(key: String, where: String, problems: MutableList<String>): Float? {
        val raw = text(key) ?: return null
        val v = raw.toFloatOrNull() ?: return null.also { problems += "$where: $key '$raw' is not a number" }
        if (v !in 0f..1f) problems += "$where: $key $v is outside 0-1, clamped"
        return v.coerceIn(0f, 1f)
    }
}
