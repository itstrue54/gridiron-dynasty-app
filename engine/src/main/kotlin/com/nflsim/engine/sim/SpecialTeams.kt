package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.tuning.TuningTable
import kotlin.math.roundToInt

data class KickResult(val good: Boolean, val distance: Int, val narrative: String)

data class PuntResult(
    val netYards: Int,
    val touchback: Boolean,
    val returnYards: Int,
    val narrative: String,
)

/**
 * Kicking, punting and returns.
 *
 * Field goals are the place altitude and weather show up most obviously, and
 * the distance curve matters: a 38 yarder should be routine and a 55 yarder
 * should be a real decision. Getting that curve wrong changes fourth-down
 * strategy across the whole league.
 */
object SpecialTeams {

    fun fieldGoal(
        kicker: Player?,
        yardsToGoal: Int,
        scheme: Scheme,
        altitudeFt: Int,
        rng: Rng,
        clutch: Boolean = false,
        st: TuningTable.SpecialTeams = TuningTable.REALISTIC.specialTeams,
        narration: Rng? = null,
        weather: Weather = Weather.INDOORS,
        weatherTuning: TuningTable.Weather = TuningTable.REALISTIC.weather,
    ): KickResult {
        val words = narration ?: rng.split("narration")
        // Snap, hold, and seven yards of backfield, plus the ten yard end zone.
        val distance = yardsToGoal + 17
        if (kicker == null) return KickResult(false, distance, PlayLines.write("fg.no_kicker", words))

        val power = rate(kicker, RatingId.KICK_POWER, scheme)
        val accuracy = rate(kicker, RatingId.KICK_ACCURACY, scheme)

        // Beyond his range it falls apart quickly rather than gradually.
        val range = st.fgRangeBase + (power / 99f) * st.fgRangePower + (altitudeFt / 5280f) * st.fgAltitudeBonus -
            weather.kickRangeLoss(weatherTuning)
        val over = distance - range

        var chance = when {
            distance <= st.chipShotDistance -> st.chipShotChance
            else -> logistic((range - distance) / st.fgCurveWidth) * (st.fgBaseAccuracy + accuracy / st.fgAccuracyScale)
        }
        if (over > 0) chance *= (1f - (over / st.fgBeyondRange)).coerceAtLeast(st.fgBeyondFloor)
        if (clutch) chance *= st.clutchFloor + (kicker.traits.clutch / 99f) * st.clutchRange
        chance *= 1f - weather.kickAccuracyPenalty(weatherTuning)
        chance = chance.coerceIn(st.fgMinChance, st.fgMaxChance)

        val good = rng.nextFloat() < chance
        val text = PlayLines.write(if (good) "fg.good" else "fg.miss", words,
            "kicker" to kicker.lastName, "distance" to distance)
        return KickResult(good, distance, text)
    }

    fun punt(
        punter: Player?,
        returner: Player?,
        yardLine: Int,
        puntScheme: Scheme,
        returnScheme: Scheme,
        rng: Rng,
        st: TuningTable.SpecialTeams = TuningTable.REALISTIC.specialTeams,
        narration: Rng? = null,
        /** Yards the coaching gives the return (GameSimulator.returnEdge). */
        edge: Float = 0f,
    ): PuntResult {
        val words = narration ?: rng.split("narration")
        val yardsToGoal = 100 - yardLine
        if (punter == null) {
            return PuntResult(st.noPunterYards, false, 0, PlayLines.write("punt.no_punter", words, "gross" to st.noPunterYards))
        }

        val power = rate(punter, RatingId.PUNT_POWER, puntScheme)
        val placement = rate(punter, RatingId.PUNT_ACCURACY, puntScheme)

        var gross = (st.puntBase + (power - 70) * st.puntPowerScale + rng.gaussian(0f, st.puntVariance)).roundToInt()

        // Inside the fifty a punter aims for the coffin corner rather than distance.
        if (yardsToGoal < st.puntAimYards) {
            val aim = (yardsToGoal - st.puntAimMargin - (99 - placement) * st.puntPlacementScale).roundToInt()
            gross = minOf(gross, aim.coerceAtLeast(st.puntAimMin))
        }

        if (yardLine + gross >= 100) {
            val touchbackChance = st.puntTouchbackBase - (placement - 70) * st.puntTouchbackPlacement
            if (rng.nextFloat() < touchbackChance.coerceIn(st.puntTouchbackMin, st.puntTouchbackMax)) {
                return PuntResult(
                    netYards = (80 - yardLine).coerceAtLeast(5),
                    touchback = true, returnYards = 0,
                    narrative = PlayLines.write("punt.touchback", words, "punter" to punter.lastName),
                )
            }
            gross = (99 - yardLine) - 1
        }

        // Returns are rare and mostly short; the occasional one is not.
        var ret = 0
        if (returner != null && rng.nextFloat() < st.puntReturnRate) {
            val speed = rate(returner, RatingId.SPEED, returnScheme)
            val elusive = rate(returner, RatingId.ELUSIVENESS, returnScheme)
            ret = (rng.exponential(st.puntReturnMean) + (speed + elusive - 150) * st.puntReturnSkill + edge)
                .roundToInt().coerceIn(0, 60)
        }

        val net = (gross - ret).coerceAtLeast(1)
        val back = when {
            ret > 12 && returner != null ->
                PlayLines.write("punt.return.named", words, "ret" to ret, "returner" to returner.lastName)
            ret > 0 -> PlayLines.write("punt.return", words, "ret" to ret)
            else -> ""
        }
        val text = PlayLines.write("punt", words, "punter" to punter.lastName, "gross" to gross, "return" to back)
        return PuntResult(net, false, ret, text)
    }

    fun extraPoint(
        kicker: Player?,
        scheme: Scheme,
        rng: Rng,
        st: TuningTable.SpecialTeams = TuningTable.REALISTIC.specialTeams,
    ): Boolean {
        if (kicker == null) return rng.nextFloat() < st.extraPointNoKicker
        val accuracy = rate(kicker, RatingId.KICK_ACCURACY, scheme)
        return rng.nextFloat() < (st.extraPointBase + accuracy / st.extraPointAccuracyScale).coerceAtMost(st.extraPointCeiling)
    }

    /** Where the receiving team starts after a kickoff. */
    fun kickoff(
        returner: Player?,
        returnScheme: Scheme,
        rng: Rng,
        st: TuningTable.SpecialTeams = TuningTable.REALISTIC.specialTeams,
        /** Yards the coaching gives the return (GameSimulator.returnEdge). */
        edge: Float = 0f,
    ): Pair<Int, String> {
        if (rng.nextFloat() < st.kickoffTouchbackRate) {
            return GameState.TOUCHBACK_YARD_LINE to "Touchback."
        }
        val base = st.kickoffReturnBase
        val bonus = if (returner == null) 0 else {
            val speed = rate(returner, RatingId.SPEED, returnScheme)
            ((speed - 70) * st.kickoffReturnSpeed + rng.gaussian(0f, st.kickoffReturnVariance) + edge).roundToInt()
        }
        val spot = (base + bonus).coerceIn(4, 60)
        val text = if (spot >= 45) "A big return out to the $spot." else "Returned to the $spot."
        return spot to text
    }


    fun kickerFor(depth: DepthChart): Player? = depth.starter(Position.K)
    fun punterFor(depth: DepthChart): Player? = depth.starter(Position.P)

    /** The returner the club pinned, else the fastest skill player available. */
    fun returnerFor(depth: DepthChart, scheme: Scheme, punt: Boolean = false): Player? =
        (if (punt) depth.puntReturner else depth.kickReturner)
            ?: (depth.at(Position.WR) + depth.at(Position.RB) + depth.at(Position.CB))
                .maxByOrNull { rate(it, RatingId.SPEED, scheme) }
}
