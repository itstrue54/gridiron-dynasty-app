package com.nflsim.engine.sim

import com.nflsim.engine.model.RatingId
import com.nflsim.engine.rng.Rng
import kotlin.math.roundToInt

/**
 * One snap.
 *
 * The pipeline from docs/SPEC.md section 5.3, kept deliberately verbose so that
 * when a league-wide statistic comes out wrong you can bisect which stage is
 * responsible rather than guessing at a single opaque function.
 *
 *   play call -> personnel -> matchup -> outcome -> penalty -> log
 */
object PlaySimulator {

    fun simPlay(
        context: PlayContext,
        offCall: OffensivePlayCall,
        defCall: DefensivePlayCall,
        rng: Rng,
    ): PlayResult {
        val ctx = if (context.narration != null) context
            else context.copy(narration = rng.split("narration"))
        // Pre-snap flags happen before anybody moves, so they short-circuit.
        preSnapPenalty(ctx, defCall, rng)?.let { return it }

        val result = when (offCall) {
            is OffensivePlayCall.Run -> RunResolution.resolve(ctx, offCall, defCall, rng)
            is OffensivePlayCall.Pass -> PassResolution.resolve(ctx, offCall, defCall, rng)
            is OffensivePlayCall.Kneel -> PlayResult(
                PlayOutcome.KNEEL, -1, ctx.tuning.gameFlow.runPlayClockRunoff,
                ballCarrier = ctx.offense.quarterback.id,
                log = SimLog(narrative = PlayLines.write("kneel", ctx.words, "qb" to ctx.offense.quarterback.name)),
            )
            is OffensivePlayCall.Spike -> PlayResult(
                PlayOutcome.SPIKE, 0, 2, passer = ctx.offense.quarterback.id,
                log = SimLog(narrative = PlayLines.write("spike", ctx.words, "qb" to ctx.offense.quarterback.lastName)),
            )
            else -> PlayResult(
                PlayOutcome.RUN, 0, ctx.tuning.gameFlow.runPlayClockRunoff,
                log = SimLog(narrative = "Special teams are not simulated yet (M3)."),
            )
        }

        return postPlayPenalty(ctx, offCall, result, rng) ?: result
    }

    // ---------------------------------------------------------------

    private fun preSnapPenalty(ctx: PlayContext, def: DefensivePlayCall, rng: Rng): PlayResult? {
        val t = ctx.tuning.penalties
        val offScheme = ctx.offense.scheme

        // Crowd noise is why road offences jump. This is the only place the
        // stadium shows up in a single snap, and it should stay that way.
        val discipline = (ctx.offense.line + listOf(ctx.offense.quarterback))
            .averageRating(RatingId.DISCIPLINE, offScheme)
        val falseStart = (t.perPlayBase * t.falseStartShare) * ctx.offFlags *
            (t.disciplineFactor - discipline / t.disciplineRange) *
            (1f + (ctx.crowdNoise / 100f) * t.crowdNoiseScale)

        if (rng.nextFloat() < falseStart) {
            val guilty = ctx.offense.line.minByOrNull { rate(it, RatingId.DISCIPLINE, offScheme) }
            return PlayResult(
                PlayOutcome.INCOMPLETE, 0, 0,
                penalty = Penalty(PenaltyType.FALSE_START, -5, guilty?.id),
                log = SimLog(narrative = PlayLines.write("penalty.false_start", ctx.words,
                    "who" to (guilty?.lastName ?: "the offense"))),
            )
        }

        val defDiscipline = ctx.defense.passRushers.averageRating(RatingId.DISCIPLINE, ctx.defense.scheme)
        val offside = (t.perPlayBase * t.offsideShare) * ctx.defFlags * (t.disciplineFactor - defDiscipline / t.disciplineRange) *
            (if (def.isBlitz) t.offsideBlitz else 1f)
        if (rng.nextFloat() < offside) {
            val guilty = ctx.defense.passRushers.minByOrNull { rate(it, RatingId.DISCIPLINE, ctx.defense.scheme) }
            return PlayResult(
                PlayOutcome.INCOMPLETE, 0, 0,
                penalty = Penalty(PenaltyType.OFFSIDE, 5, guilty?.id),
                log = SimLog(narrative = PlayLines.write("penalty.offside", ctx.words,
                    "who" to (guilty?.lastName ?: "the defence"))),
            )
        }
        return null
    }

    private fun postPlayPenalty(
        ctx: PlayContext,
        call: OffensivePlayCall,
        result: PlayResult,
        rng: Rng,
    ): PlayResult? {
        if (result.turnover) return null
        val t = ctx.tuning.penalties
        val offScheme = ctx.offense.scheme

        // Holding gets called when a lineman is losing. That is not a
        // coincidence in real football and it should not be here either.
        if (call is OffensivePlayCall.Pass && result.outcome != PlayOutcome.SACK) {
            val protectionStress = result.log["pressureChance"] ?: t.defaultPressure
            val holding = t.perPlayBase * t.passHoldingShare * ctx.offFlags * (t.holdingBase + protectionStress)
            if (rng.nextFloat() < holding) {
                val guilty = ctx.offense.line.minByOrNull { rate(it, RatingId.PASS_BLOCK, offScheme) }
                return result.copy(
                    penalty = Penalty(PenaltyType.OFFENSIVE_HOLDING, -10, guilty?.id),
                    log = result.log.copy(
                        narrative = result.log.narrative + " " + PlayLines.write("penalty.pass_holding",
                            ctx.words, "who" to (guilty?.lastName ?: "the offense"))),
                )
            }
        }

        // Pass interference on a contested throw downfield.
        if (call is OffensivePlayCall.Pass &&
            result.outcome == PlayOutcome.INCOMPLETE &&
            call.concept.airYards >= t.passInterferenceAirYards
        ) {
            val routeWin = result.log["routeWin"] ?: 0f
            val dpiChance = t.perPlayBase * t.passInterferenceShare * ctx.defFlags * (1f + (routeWin / t.passInterferenceSeparation).coerceIn(t.passInterferenceMin, t.passInterferenceMax))
            if (rng.nextFloat() < dpiChance) {
                // coerceIn(min, max) throws when min exceeds max, and inside the
                // six yard line it would. Cap at the goal line first.
                val spot = (call.concept.airYards + rng.gaussian(0f, t.passInterferenceSpotSpread)).roundToInt()
                    .coerceAtMost(ctx.state.yardsToGoal)
                    .coerceAtLeast(1)
                return result.copy(
                    outcome = PlayOutcome.INCOMPLETE,
                    penalty = Penalty(PenaltyType.PASS_INTERFERENCE, spot, result.tackler),
                    log = result.log.copy(
                        narrative = PlayLines.write("penalty.pass_interference", ctx.words, "spot" to spot)),
                )
            }
        }

        if (call is OffensivePlayCall.Run) {
            val holding = t.perPlayBase * t.runHoldingShare * ctx.offFlags
            if (rng.nextFloat() < holding) {
                val guilty = ctx.offense.line.minByOrNull { rate(it, RatingId.RUN_BLOCK, offScheme) }
                return result.copy(
                    penalty = Penalty(PenaltyType.OFFENSIVE_HOLDING, -10, guilty?.id),
                    log = result.log.copy(
                        narrative = result.log.narrative + " " + PlayLines.write("penalty.run_holding",
                            ctx.words, "who" to (guilty?.lastName ?: "the offense"))),
                )
            }
        }
        return null
    }
}
