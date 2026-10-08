package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.tuning.TuningTable
import kotlin.math.roundToInt

data class KickResult(val good: Boolean, val distance: Int, val narrative: String, val blocked: Boolean = false)

data class PuntResult(
    val netYards: Int,
    val touchback: Boolean,
    val returnYards: Int,
    val narrative: String,
    val blocked: Boolean = false,
)

/**
 * How two clubs' special teams meet on a kick (SpecialTeamsUnits), as points
 * off the league's average units: zero for an average matchup.
 */
data class KickMatchup(
    /** The return blockers over the coverage. */
    val returnEdge: Float = 0f,
    /** The gunners over the jammers, on a punt. */
    val gunnerEdge: Float = 0f,
    /** The rush over the protection, on a field goal or punt. */
    val blockEdge: Float = 0f,
    /** The kicking club's snap and hold, off the league's average. */
    val snapEdge: Float = 0f,
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
        matchup: KickMatchup = KickMatchup(),
    ): KickResult {
        val words = narration ?: rng.split("narration")
        // Snap, hold, and seven yards of backfield, plus the ten yard end zone.
        val distance = yardsToGoal + 17
        if (kicker == null) return KickResult(false, distance, PlayLines.write("fg.no_kicker", words))

        // The rush gets a hand on it more often against weak protection.
        val block = (st.fgBlockBase + matchup.blockEdge * st.blockRushScale - matchup.snapEdge * st.snapBlockScale)
            .coerceIn(0f, st.fgBlockBase * 3)
        if (rng.nextFloat() < block) {
            return KickResult(false, distance,
                PlayLines.write("fg.blocked", words, "kicker" to kicker.lastName, "distance" to distance), blocked = true)
        }

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
        // A clean snap and hold lets him kick it; a bad one costs him.
        chance *= 1f + matchup.snapEdge * st.snapScale
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
        matchup: KickMatchup = KickMatchup(),
    ): PuntResult {
        val words = narration ?: rng.split("narration")
        val yardsToGoal = 100 - yardLine
        if (punter == null) {
            return PuntResult(st.noPunterYards, false, 0, PlayLines.write("punt.no_punter", words, "gross" to st.noPunterYards))
        }

        // A punt blocked is recovered behind the line, where the kicking club stood.
        val block = (st.puntBlockBase + matchup.blockEdge * st.blockRushScale - matchup.snapEdge * st.snapBlockScale)
            .coerceIn(0f, st.puntBlockBase * 3)
        if (rng.nextFloat() < block) {
            return PuntResult(-st.puntBlockedLoss, false, 0,
                PlayLines.write("punt.blocked", words, "punter" to punter.lastName), blocked = true)
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
        // Good gunners force a fair catch; good jammers give the returner room.
        var ret = 0
        val returned = (st.puntReturnRate - matchup.gunnerEdge * st.puntReturnGunners).coerceIn(0.1f, 0.8f)
        if (returner != null && rng.nextFloat() < returned) {
            val speed = rate(returner, RatingId.SPEED, returnScheme)
            val elusive = rate(returner, RatingId.ELUSIVENESS, returnScheme)
            ret = (rng.exponential(st.puntReturnMean) + (speed + elusive - 150) * st.puntReturnSkill + edge +
                matchup.returnEdge * st.puntUnitYards)
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
        /** The kicking club's snap and hold off the league's average. */
        snapEdge: Float = 0f,
    ): Boolean {
        if (kicker == null) return rng.nextFloat() < st.extraPointNoKicker
        val accuracy = rate(kicker, RatingId.KICK_ACCURACY, scheme)
        val chance = (st.extraPointBase + accuracy / st.extraPointAccuracyScale).coerceAtMost(st.extraPointCeiling) *
            (1f + snapEdge * st.snapScale)
        return rng.nextFloat() < chance
    }

    /** Where the receiving team starts after a kickoff. */
    fun kickoff(
        returner: Player?,
        returnScheme: Scheme,
        rng: Rng,
        st: TuningTable.SpecialTeams = TuningTable.REALISTIC.specialTeams,
        /** Yards the coaching gives the return (GameSimulator.returnEdge). */
        edge: Float = 0f,
        /** The kicker, whose leg puts it through the end zone more often. */
        kicker: Player? = null,
        kickScheme: Scheme = returnScheme,
        matchup: KickMatchup = KickMatchup(),
    ): Pair<Int, String> {
        val leg = kicker?.let { rate(it, RatingId.KICK_POWER, kickScheme) - st.kickoffPowerAnchor } ?: 0f
        if (rng.nextFloat() < (st.kickoffTouchbackRate + leg * st.kickoffPowerTouchback).coerceIn(0.2f, 0.95f)) {
            return GameState.TOUCHBACK_YARD_LINE to "Touchback."
        }
        val base = st.kickoffReturnBase
        val bonus = if (returner == null) 0 else {
            val speed = rate(returner, RatingId.SPEED, returnScheme)
            ((speed - 70) * st.kickoffReturnSpeed + rng.gaussian(0f, st.kickoffReturnVariance) + edge +
                matchup.returnEdge * st.kickoffUnitYards).roundToInt()
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
