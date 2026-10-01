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
        val rushStrength = maxOf(rushPower, rushFinesse) * t.passing.rushBlendStrong + minOf(rushPower, rushFinesse) * t.passing.rushBlendWeak

        val numbers = (blockers - def.rushers) * t.blocking.extraBlockerValue
        val actionHelp = if (call.playAction) t.passing.playActionProtection else 0f
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
            val sackChance = t.passing.sackGivenPressure * (t.passing.sackEscapeBase - escape / t.passing.sackEscapeScale)
            if (rng.nextFloat() < sackChance) {
                // Real sacks average about 7 and essentially never pass 15.
                // An uncapped exponential draw produced a 27 yard loss.
                val rawLoss = (t.passing.sackLossBase + rng.exponential(t.passing.sackLossMean)).coerceAtMost(15f).roundToInt()
                val loss = -minOf(rawLoss, (ctx.state.yardLine - 1).coerceAtLeast(1))
                // Weighted by rush skill rather than always the best man, or
                // one edge rusher finishes the season with 62 sacks.
                val sacker = weightedRusher(rushers, dfs, rng)
                // Strip-sack: the ball comes out and the defence has it.
                if (rng.nextFloat() < t.passing.stripSackLost) {
                    return PlayResult(
                        outcome = PlayOutcome.SACK,
                        yards = loss,
                        clockRunoff = t.gameFlow.runPlayClockRunoff,
                        passer = qb.id, ballCarrier = qb.id, tackler = sacker?.id,
                        turnover = true,
                        log = SimLog(values + ("sackChance" to sackChance),
                            PlayLines.write("pass.sack.fumble", ctx.words,
                                "qb" to qb.name, "sacker" to (sacker?.lastName ?: "the rush"))),
                    )
                }
                return PlayResult(
                    outcome = PlayOutcome.SACK,
                    yards = loss,
                    clockRunoff = t.gameFlow.runPlayClockRunoff,
                    passer = qb.id, ballCarrier = qb.id, tackler = sacker?.id,
                    log = SimLog(values + ("sackChance" to sackChance),
                        if (sacker != null) PlayLines.write("pass.sack", ctx.words,
                            "sacker" to sacker.lastName, "qb" to qb.name, "loss" to -loss)
                        else PlayLines.write("pass.sack.team", ctx.words, "qb" to qb.name, "loss" to -loss)),
                )
            }

            val scrambleUrge = rate(qb, RatingId.SCRAMBLING, off) / 99f
            if (rng.nextFloat() < scrambleUrge * t.passing.scrambleRate) {
                val gain = (rng.gaussian(t.passing.scrambleMean, t.passing.scrambleSpread) +
                    (rate(qb, RatingId.SPEED, off) - 70) * t.passing.scrambleSpeed)
                    .roundToInt().coerceIn(-3, ctx.state.yardsToGoal)
                return PlayResult(
                    outcome = PlayOutcome.SCRAMBLE, yards = gain,
                    clockRunoff = t.gameFlow.runPlayClockRunoff,
                    passer = qb.id, ballCarrier = qb.id,
                    log = SimLog(values,
                        if (gain > 0) PlayLines.write("pass.scramble", ctx.words, "qb" to qb.name, "gain" to gain)
                        else PlayLines.write("pass.scramble.stopped", ctx.words, "qb" to qb.name)),
                )
            }

            if (rng.nextFloat() < t.passing.throwawayRate * (rate(qb, RatingId.AWARENESS, off) / 99f)) {
                return PlayResult(
                    outcome = PlayOutcome.THROWAWAY, yards = 0,
                    clockRunoff = t.gameFlow.incompleteClockRunoff, passer = qb.id,
                    log = SimLog(values, PlayLines.write("pass.throwaway", ctx.words, "qb" to qb.name)),
                )
            }
        }

        // ---- the matchup -------------------------------------------------
        val targets = ctx.offense.skillPlayers
        if (targets.isEmpty()) {
            return PlayResult(PlayOutcome.INCOMPLETE, 0, t.gameFlow.incompleteClockRunoff,
                passer = qb.id, log = SimLog(values, PlayLines.write("pass.nobody_open", ctx.words)))
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
            rate(receiver, RatingId.RELEASE, off) * t.coverage.releaseWeight -
            coverageSkill * t.coverage.coverageWeight
        if (!def.coverage.man) routeWin += t.coverage.zoneCushion
        if (def.isBlitz) routeWin += def.extraRushers * t.coverage.blitzCoverageCost
        if (def.doubledTarget == targetIndex) routeWin -= t.coverage.doubleTeamPenalty
        // Deep shots into a loaded shell are harder than the raw matchup says.
        if (call.concept.airYards >= 18) routeWin -= (def.coverage.deepDefenders - 1) * t.coverage.deepHelp
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
            ((accuracy - 70) * t.passing.accuracyWeight + routeWin * t.passing.separationWeight) / t.passing.completionScale -
            t.passing.depthPenaltyPerYard * call.concept.airYards.coerceAtLeast(0) -
            ctx.weather.passPenalty(call.concept.airYards, t.weather)
        if (pressured) completion *= t.passing.pressureCompletionMult *
            (t.passing.poiseBase + rate(qb, RatingId.THROW_UNDER_PRESSURE, off) / t.passing.poiseScale)
        completion = completion.coerceIn(t.passing.completionMin, t.passing.completionMax)
        values["completionChance"] = completion

        // ---- interception -----------------------------------------------
        val intChance = (t.passing.interceptionBase +
            t.passing.interceptionCoverageScale * (-routeWin / t.passing.interceptionCoverageRange).coerceAtLeast(0f) +
            (70 - rate(qb, RatingId.AWARENESS, off)) * t.passing.interceptionAwareness)
            .coerceIn(t.passing.interceptionMin, t.passing.interceptionMax) * (if (pressured) t.passing.interceptionPressure else 1f)
        values["interceptionChance"] = intChance

        if (rng.nextFloat() < intChance) {
            return PlayResult(
                outcome = PlayOutcome.INTERCEPTION, yards = 0,
                clockRunoff = t.gameFlow.incompleteClockRunoff,
                passer = qb.id, target = receiver.id, tackler = defender.id, turnover = true,
                log = SimLog(values, PlayLines.write("pass.interception", ctx.words,
                    "defender" to defender.name, "concept" to call.concept.label, "qb" to qb.lastName)),
            )
        }

        if (rng.nextFloat() >= completion) {
            return PlayResult(
                outcome = PlayOutcome.INCOMPLETE, yards = 0,
                clockRunoff = t.gameFlow.incompleteClockRunoff,
                passer = qb.id, target = receiver.id,
                log = SimLog(values, PlayLines.write("pass.incomplete", ctx.words,
                    "qb" to qb.lastName, "concept" to call.concept.label, "receiver" to receiver.lastName)),
            )
        }

        // ---- caught ------------------------------------------------------
        val air = (call.concept.airYards + rng.gaussian(0f, t.passing.airYardsSpread)).roundToInt()
        val tackling = ctx.defense.secondary.averageRating(RatingId.TACKLE, dfs)
        val yacBase = (rate(receiver, RatingId.ELUSIVENESS, off) +
            rate(receiver, RatingId.BREAK_TACKLE, off)) / 2f
        var yac = (rng.exponential(t.passing.yacMean) + (yacBase - tackling) * t.passing.yacTackling) * t.passing.yacScale
        if (call.concept == PassConcept.SCREEN) yac += rng.exponential(t.passing.screenYac)
        if (!def.coverage.man) yac += t.passing.zoneYac

        val total = (air + yac).roundToInt().coerceIn(-4, ctx.state.yardsToGoal)
        values["airYards"] = air.toFloat()
        values["yardsAfterCatch"] = yac

        // The man in coverage makes most of these; the rest are pursuit, which
        // is why a corner does not lead the league in tackles.
        val stopper = if (rng.nextFloat() < t.tackling.coverageShare) defender
        else RunResolution.tacklerFor(ctx, total, rng) ?: defender
        val helper = RunResolution.assisterFor(ctx, total, rng, stopper)

        // A catch can be lost, too: ball security, as on a carry. Not once he
        // is in the end zone.
        val catchFumble = t.passing.catchFumbleBase *
            (t.rushing.fumbleSecurityBase - rate(receiver, RatingId.BALL_SECURITY, off) / 99f) *
            ctx.weather.fumbleFactor(t.weather)
        values["catchFumbleChance"] = catchFumble
        if (total < ctx.state.yardsToGoal && rng.nextFloat() < catchFumble) {
            return PlayResult(
                outcome = PlayOutcome.COMPLETION, yards = total,
                clockRunoff = t.gameFlow.completionClockRunoff,
                passer = qb.id, target = receiver.id, ballCarrier = receiver.id, tackler = stopper.id,
                turnover = true,
                log = SimLog(values, PlayLines.write("pass.complete.fumble", ctx.words,
                    "qb" to qb.lastName, "receiver" to receiver.name, "yards" to total)),
            )
        }

        return PlayResult(
            outcome = PlayOutcome.COMPLETION, yards = total,
            clockRunoff = t.gameFlow.completionClockRunoff,
            passer = qb.id, target = receiver.id, ballCarrier = receiver.id, tackler = stopper.id,
            assister = helper?.id,
            log = SimLog(values, PlayLines.write(
                if (total > 0) "pass.complete" else "pass.complete.nothing", ctx.words,
                "qb" to qb.lastName, "receiver" to receiver.name, "concept" to call.concept.label,
                "yards" to total, "yardage" to PlayLines.yardage(total))),
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
