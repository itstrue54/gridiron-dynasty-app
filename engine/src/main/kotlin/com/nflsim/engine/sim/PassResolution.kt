package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.rng.Rng
import kotlin.math.roundToInt

internal object PassResolution {

    private fun weightedRusher(
        rushers: List<Player>,
        scheme: com.nflsim.engine.ratings.Scheme,
        rng: Rng,
    ): Player? {
        if (rushers.isEmpty()) return null
        val weights = rushers.map {
            (rate(it, RatingId.FINESSE_MOVES, scheme) + rate(it, RatingId.POWER_MOVES, scheme))
                .toFloat()
        }
        var roll = rng.nextFloat() * weights.sum()
        weights.forEachIndexed { i, w ->
            roll -= w
            if (roll <= 0f) return rushers[i]
        }
        return rushers.last()
    }


    fun resolve(
        ctx: PlayContext,
        call: OffensivePlayCall.Pass,
        def: DefensivePlayCall,
        rng: Rng,
    ): PlayResult {
        val t = ctx.tuning
        val off = ctx.offense.scheme
        val dfs = ctx.defense.scheme
        val qb = ctx.offense.quarterback
        val values = mutableMapOf<String, Float>()

        // ---- protection -------------------------------------------------
        val blockers = 5 + call.extraProtectors
        val linePass = ctx.offense.line.averageRating(RatingId.PASS_BLOCK, off)
        val rushers = ctx.defense.passRushers.take(def.rushers.coerceAtLeast(1))
        val rushPower = rushers.averageRating(RatingId.POWER_MOVES, dfs)
        val rushFinesse = rushers.averageRating(RatingId.FINESSE_MOVES, dfs)
        val rushStrength = maxOf(rushPower, rushFinesse) * 0.65f + minOf(rushPower, rushFinesse) * 0.35f

        val numbers = (blockers - def.rushers) * t.blocking.extraBlockerValue
        val actionHelp = if (call.playAction) 4.5f else 0f
        val quickRelease = if (call.concept.quick) 9f else 0f

        // Road offences fire late because they cannot hear the snap count.
        val noiseCost = (ctx.crowdNoise / 100f) * t.blocking.crowdNoiseProtectionCost

        val protection = linePass - rushStrength + numbers + actionHelp + quickRelease - noiseCost
        values["protection"] = protection

        val pressureChance = logistic(-protection / t.passing.pressureScale)
        val pressured = rng.nextFloat() < pressureChance
        values["pressureChance"] = pressureChance

        // ---- sack, scramble or throw under duress ------------------------
        if (pressured) {
            val escape = rate(qb, RatingId.BREAK_SACK, off)
            val sackChance = t.passing.sackGivenPressure * (1.35f - escape / 145f)
            if (rng.nextFloat() < sackChance) {
                // Real sacks average about 7 and essentially never pass 15.
                // An uncapped exponential draw produced a 27 yard loss.
                val rawLoss = (3f + rng.exponential(4.0f)).coerceAtMost(15f).roundToInt()
                val loss = -minOf(rawLoss, (ctx.state.yardLine - 1).coerceAtLeast(1))
                // Weighted by rush skill rather than always the best man, or
                // one edge rusher finishes the season with 62 sacks.
                val sacker = weightedRusher(rushers, dfs, rng)
                return PlayResult(
                    outcome = PlayOutcome.SACK,
                    yards = loss,
                    clockRunoff = t.gameFlow.runPlayClockRunoff,
                    passer = qb.id, ballCarrier = qb.id, tackler = sacker?.id,
                    log = SimLog(values + ("sackChance" to sackChance),
                        "${sacker?.lastName ?: "The defense"} gets home. ${qb.name} sacked for ${-loss}."),
                )
            }

            val scrambleUrge = rate(qb, RatingId.SCRAMBLING, off) / 99f
            if (rng.nextFloat() < scrambleUrge * 0.35f) {
                val gain = (rng.gaussian(4.5f, 4f) +
                    (rate(qb, RatingId.SPEED, off) - 70) * 0.09f)
                    .roundToInt().coerceIn(-3, ctx.state.yardsToGoal)
                return PlayResult(
                    outcome = PlayOutcome.SCRAMBLE, yards = gain,
                    clockRunoff = t.gameFlow.runPlayClockRunoff,
                    passer = qb.id, ballCarrier = qb.id,
                    log = SimLog(values, "${qb.name} escapes the rush and picks up $gain."),
                )
            }

            if (rng.nextFloat() < t.passing.throwawayRate * (rate(qb, RatingId.AWARENESS, off) / 99f)) {
                return PlayResult(
                    outcome = PlayOutcome.THROWAWAY, yards = 0,
                    clockRunoff = t.gameFlow.incompleteClockRunoff, passer = qb.id,
                    log = SimLog(values, "${qb.name} throws it away under pressure."),
                )
            }
        }

        // ---- the matchup -------------------------------------------------
        val targets = ctx.offense.skillPlayers
        if (targets.isEmpty()) {
            return PlayResult(PlayOutcome.INCOMPLETE, 0, t.gameFlow.incompleteClockRunoff,
                passer = qb.id, log = SimLog(values, "Nobody open. Incomplete."))
        }
        val targetIndex = call.primaryTarget.coerceIn(0, targets.size - 1)
        val receiver = targets[targetIndex]

        val routeRating = when {
            call.concept.airYards <= 6 -> RatingId.ROUTE_SHORT
            call.concept.airYards <= 16 -> RatingId.ROUTE_MID
            else -> RatingId.ROUTE_DEEP
        }
        val defender = coverageDefenderFor(ctx, receiver, targetIndex)
        val coverageSkill = if (def.coverage.man)
            rate(defender, RatingId.MAN_COVERAGE, dfs) else rate(defender, RatingId.ZONE_COVERAGE, dfs)

        var routeWin = rate(receiver, routeRating, off) +
            rate(receiver, RatingId.RELEASE, off) * 0.22f -
            coverageSkill * 1.10f
        if (!def.coverage.man) routeWin += t.coverage.zoneCushion
        if (def.isBlitz) routeWin += def.extraRushers * t.coverage.blitzCoverageCost
        if (def.doubledTarget == targetIndex) routeWin -= t.coverage.doubleTeamPenalty
        // Deep shots into a loaded shell are harder than the raw matchup says.
        if (call.concept.airYards >= 18) routeWin -= (def.coverage.deepDefenders - 1) * 3.2f
        // The red zone is hard because the field runs out. Safeties who would
        // be playing twenty yards deep are now standing on the goal line.
        if (ctx.state.yardsToGoal <= 20) {
            routeWin -= (20 - ctx.state.yardsToGoal) * t.coverage.redZoneCompression
        }
        values["routeWin"] = routeWin

        val accuracy = when {
            call.concept.airYards <= 6 -> rate(qb, RatingId.THROW_ACC_SHORT, off)
            call.concept.airYards <= 16 -> rate(qb, RatingId.THROW_ACC_MID, off)
            else -> rate(qb, RatingId.THROW_ACC_DEEP, off)
        }

        var completion = t.passing.baseCompletion +
            ((accuracy - 70) * 0.62f + routeWin * 0.55f) / t.passing.completionScale -
            t.passing.depthPenaltyPerYard * call.concept.airYards.coerceAtLeast(0)
        if (pressured) completion *= t.passing.pressureCompletionMult *
            (0.85f + rate(qb, RatingId.THROW_UNDER_PRESSURE, off) / 330f)
        completion = completion.coerceIn(0.02f, 0.95f)
        values["completionChance"] = completion

        // ---- interception -----------------------------------------------
        val intChance = (t.passing.interceptionBase +
            t.passing.interceptionCoverageScale * (-routeWin / 40f).coerceAtLeast(0f) +
            (70 - rate(qb, RatingId.AWARENESS, off)) * 0.00042f)
            .coerceIn(0.002f, 0.22f) * (if (pressured) 1.7f else 1f)
        values["interceptionChance"] = intChance

        if (rng.nextFloat() < intChance) {
            return PlayResult(
                outcome = PlayOutcome.INTERCEPTION, yards = 0,
                clockRunoff = t.gameFlow.incompleteClockRunoff,
                passer = qb.id, target = receiver.id, tackler = defender.id, turnover = true,
                log = SimLog(values, "${defender.name} jumps the ${call.concept.label} and picks off ${qb.lastName}!"),
            )
        }

        if (rng.nextFloat() >= completion) {
            return PlayResult(
                outcome = PlayOutcome.INCOMPLETE, yards = 0,
                clockRunoff = t.gameFlow.incompleteClockRunoff,
                passer = qb.id, target = receiver.id,
                log = SimLog(values,
                    "${qb.lastName}'s ${call.concept.label} for ${receiver.lastName} falls incomplete."),
            )
        }

        // ---- caught ------------------------------------------------------
        val air = (call.concept.airYards + rng.gaussian(0f, 2.2f)).roundToInt()
        val tackling = ctx.defense.secondary.averageRating(RatingId.TACKLE, dfs)
        val yacBase = (rate(receiver, RatingId.ELUSIVENESS, off) +
            rate(receiver, RatingId.BREAK_TACKLE, off)) / 2f
        var yac = (rng.exponential(2.6f) + (yacBase - tackling) * 0.055f) * t.passing.yacScale
        if (call.concept == PassConcept.SCREEN) yac += rng.exponential(4.2f)
        if (!def.coverage.man) yac += 0.8f

        val total = (air + yac).roundToInt().coerceIn(-4, ctx.state.yardsToGoal)
        values["airYards"] = air.toFloat()
        values["yardsAfterCatch"] = yac

        return PlayResult(
            outcome = PlayOutcome.COMPLETION, yards = total,
            clockRunoff = t.gameFlow.completionClockRunoff,
            passer = qb.id, target = receiver.id, ballCarrier = receiver.id, tackler = defender.id,
            log = SimLog(values,
                "${qb.lastName} finds ${receiver.name} on the ${call.concept.label} for $total."),
        )
    }

    /**
     * Who is actually covering him. Receivers draw corners, backs and tight
     * ends draw linebackers and safeties - which is the whole reason a
     * receiving back is worth something.
     */
    private fun coverageDefenderFor(ctx: PlayContext, receiver: Player, index: Int): Player {
        val pool = when (receiver.position) {
            Position.WR -> ctx.defense.corners.ifEmpty { ctx.defense.safeties }
            Position.TE -> (ctx.defense.safeties + ctx.defense.linebackers)
            else -> (ctx.defense.linebackers + ctx.defense.safeties)
        }.ifEmpty { ctx.defense.secondary }.ifEmpty { ctx.defense.frontSeven }

        return pool[index.coerceIn(0, pool.size - 1)]
    }
}
