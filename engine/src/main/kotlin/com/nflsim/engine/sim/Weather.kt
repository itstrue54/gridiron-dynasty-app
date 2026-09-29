package com.nflsim.engine.sim

import com.nflsim.engine.model.Stadium
import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

enum class Precipitation { NONE, RAIN, SNOW }

/**
 * A game's weather at kickoff (SPEC 5.10): temperature, wind and whatever is
 * falling. Drawn once a game from its own stream, from the home stadium's
 * region and the month; a dome plays indoors, where none of it matters.
 */
@Serializable
data class Weather(
    val tempF: Int = 72,
    val windMph: Int = 0,
    val precipitation: Precipitation = Precipitation.NONE,
    val indoors: Boolean = true,
) {
    /** "34°, snow, wind 14 mph", or "Indoors". */
    val description: String get() = if (indoors) "Indoors" else buildList {
        add("$tempF°")
        when (precipitation) {
            Precipitation.RAIN -> add("rain")
            Precipitation.SNOW -> add("snow")
            Precipitation.NONE -> Unit
        }
        add(if (windMph < 5) "calm" else "wind $windMph mph")
    }.joinToString(", ")

    private val windPast get() = { t: com.nflsim.engine.tuning.TuningTable.Weather -> (windMph - t.windCalm).coerceAtLeast(0) }

    /** Completion lost on a throw of [airYards]: wind on the deep ball, and whatever is falling on every ball. */
    fun passPenalty(airYards: Int, t: com.nflsim.engine.tuning.TuningTable.Weather): Float {
        if (indoors) return 0f
        val wind = if (airYards >= t.deepAirYards) windPast(t) * t.deepPassPerMph else 0f
        return wind + when (precipitation) {
            Precipitation.RAIN -> t.rainCompletion
            Precipitation.SNOW -> t.snowCompletion
            Precipitation.NONE -> 0f
        }
    }

    /** What a carry's fumble chance is multiplied by. */
    fun fumbleFactor(t: com.nflsim.engine.tuning.TuningTable.Weather): Float = if (indoors) 1f else when (precipitation) {
        Precipitation.RAIN -> t.rainFumble
        Precipitation.SNOW -> t.snowFumble
        Precipitation.NONE -> 1f
    }

    /** A field goal's accuracy lost, as a share of its chance. */
    fun kickAccuracyPenalty(t: com.nflsim.engine.tuning.TuningTable.Weather): Float {
        if (indoors) return 0f
        return windPast(t) * t.kickAccuracyPerMph + when (precipitation) {
            Precipitation.RAIN -> t.rainKick
            Precipitation.SNOW -> t.snowKick
            Precipitation.NONE -> 0f
        }
    }

    /** Yards off a kicker's range: wind, and cold. */
    fun kickRangeLoss(t: com.nflsim.engine.tuning.TuningTable.Weather): Float {
        if (indoors) return 0f
        return windPast(t) * t.kickRangePerMph + (t.kickColdBelow - tempF).coerceAtLeast(0) * t.kickRangePerDegree
    }

    companion object {
        /** A dome: every effect off. */
        val INDOORS = Weather()

        /** Snow, not rain, at this temperature or colder. */
        const val SNOW_AT = 33

        /**
         * The month a week of the season falls in: September through the
         * first weeks of January, the playoffs into February.
         */
        fun monthOf(week: Int): String = when {
            week <= 4 -> "SEP"
            week <= 8 -> "OCT"
            week <= 13 -> "NOV"
            week <= 17 -> "DEC"
            week <= 21 -> "JAN"
            else -> "FEB"
        }

        /** The weather for a game at [stadium] in [week]. */
        fun draw(stadium: Stadium, week: Int, rng: Rng): Weather {
            if (stadium.domed) return INDOORS
            val region = climate[stadium.climate] ?: climate.getValue("temperate")
            val m = region.getValue(monthOf(week))
            val temp = (m.tempF + rng.gaussian(0f, m.tempSd)).roundToInt()
            val wet = rng.nextFloat() < m.precip
            val wind = (m.windMph + rng.gaussian(0f, m.windSd)).roundToInt().coerceIn(0, 40)
            val falling = when {
                !wet -> Precipitation.NONE
                temp <= SNOW_AT -> Precipitation.SNOW
                else -> Precipitation.RAIN
            }
            return Weather(temp, wind, falling, indoors = false)
        }

        private data class Month(val tempF: Float, val tempSd: Float, val precip: Float, val windMph: Float, val windSd: Float)

        private val climate: Map<String, Map<String, Month>> by lazy {
            val text = Weather::class.java.getResourceAsStream("/climate.json")!!.bufferedReader().use { it.readText() }
            val regions = Json.parseToJsonElement(text).jsonObject.getValue("regions").jsonObject
            regions.mapValues { (_, months) ->
                (months as JsonObject).mapValues { (_, v) ->
                    val o = v.jsonObject
                    Month(o.getValue("tempF").jsonPrimitive.float, o.getValue("tempSd").jsonPrimitive.float,
                        o.getValue("precip").jsonPrimitive.float, o.getValue("windMph").jsonPrimitive.float,
                        o.getValue("windSd").jsonPrimitive.float)
                }
            }
        }

        /** Every region the table knows: a stadium's climate should be one of them. */
        val regions: Set<String> get() = climate.keys
    }
}
