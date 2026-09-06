package com.nflsim.engine.sim

import com.nflsim.engine.rng.Rng

/**
 * The coordinator's brain, v0.
 *
 * You do not call plays in this game - you set tendencies and personnel, and
 * this decides. For now the tendencies come straight off the scheme; the full
 * Tendencies object with per-down curves, adaptation and a game plan screen is
 * SPEC section 5.4 and lands at M3.
 */
object PlayCaller {

    fun offense(ctx: PlayContext, rng: Rng): OffensivePlayCall {
        val s = ctx.state
        var passRate = ctx.offense.scheme.basePassRate

        // Down and distance move a coordinator more than anything else.
        passRate += when (s.down) {
            1 -> -0.06f
            2 -> 0.02f
            3 -> if (s.distance >= 4) 0.30f else 0.05f
            else -> if (s.distance >= 3) 0.35f else -0.10f
        }
        passRate += ((s.distance - 8) * 0.016f).coerceIn(-0.14f, 0.22f)

        // Trailing teams throw. Leading teams bleed clock.
        passRate += (-s.scoreDiff * 0.011f).coerceIn(-0.16f, 0.24f)
        if (s.twoMinuteDrill && s.scoreDiff <= 0) passRate += 0.28f
        if (s.goalToGo && s.yardsToGoal <= 2) passRate -= 0.22f

        passRate = passRate.coerceIn(0.08f, 0.94f)

        return if (rng.nextFloat() < passRate) pass(ctx, rng) else run(ctx, rng)
    }

    private fun run(ctx: PlayContext, rng: Rng): OffensivePlayCall.Run {
        val s = ctx.state
        if (s.goalToGo && s.yardsToGoal <= 1 && rng.nextFloat() < 0.4f) {
            return OffensivePlayCall.Run(RunConcept.QB_SNEAK, Personnel.GOAL_LINE)
        }
        // Scheme decides what the run game looks like.
        val zoneTeam = ctx.offense.scheme.id.contains("ZONE") ||
            ctx.offense.scheme.id.contains("SPREAD")
        val concept = if (zoneTeam) {
            listOf(RunConcept.INSIDE_ZONE, RunConcept.INSIDE_ZONE, RunConcept.OUTSIDE_ZONE,
                   RunConcept.OUTSIDE_ZONE, RunConcept.DRAW, RunConcept.TOSS)
        } else {
            listOf(RunConcept.POWER, RunConcept.POWER, RunConcept.COUNTER,
                   RunConcept.DUO, RunConcept.INSIDE_ZONE, RunConcept.TRAP)
        }.let { it[rng.nextInt(it.size)] }

        val personnel = when {
            s.goalToGo && s.yardsToGoal <= 3 -> Personnel.GOAL_LINE
            s.distance <= 2 -> Personnel.P_12
            else -> Personnel.P_11
        }
        return OffensivePlayCall.Run(concept, personnel, playAction = false)
    }

    private fun pass(ctx: PlayContext, rng: Rng): OffensivePlayCall.Pass {
        val s = ctx.state

        // How deep the coordinator is actually trying to throw.
        //
        // Depth follows DOWN first and distance second. Tying it to distance
        // alone meant first and ten pulled from the same pool as third and ten,
        // so offences threw digs and corners on early downs. That produced a
        // league completing 62% at 13.0 yards a catch - both wrong, in
        // opposite directions, from one cause.
        val targetDepth = when (s.down) {
            1 -> 7 + rng.nextInt(7)                                  // 7-13
            2 -> (s.distance * 0.9f).toInt().coerceIn(5, 17)
            else -> (s.distance + 1).coerceIn(5, 25)
        }

        // Shot plays. Uncommon, and they are what makes a defence respect the
        // deep third - without them coverage sits on everything underneath.
        val shot = rng.nextFloat() < ctx.offense.scheme.playActionRate * 0.65f
        val depth = if (shot) targetDepth + 10 else targetDepth

        val available = PassConcept.entries.filter { it.airYards <= s.yardsToGoal + 2 }
        val pool = available
            .filter { it.airYards >= depth - 6 && it.airYards <= depth + 6 }
            .ifEmpty { available.sortedBy { kotlin.math.abs(it.airYards - depth) }.take(3) }
            .ifEmpty { listOf(PassConcept.FLAT) }

        val concept = pool[rng.nextInt(pool.size)]

        val playAction = !concept.quick && s.down <= 2 &&
            rng.nextFloat() < ctx.offense.scheme.playActionRate

        // Deep shots keep extra help in; quick game empties the pocket.
        val extraProtectors = when {
            concept.airYards >= 20 -> if (rng.nextFloat() < 0.55f) 1 else 0
            concept.quick -> 0
            else -> if (rng.nextFloat() < 0.25f) 1 else 0
        }

        // Progression: the best receiver is not always the read.
        val target = when {
            rng.nextFloat() < 0.42f -> 0
            rng.nextFloat() < 0.55f -> 1
            else -> 2
        }

        return OffensivePlayCall.Pass(concept, Personnel.P_11, playAction, extraProtectors, target)
    }

    fun defense(ctx: PlayContext, rng: Rng): DefensivePlayCall {
        val s = ctx.state
        val scheme = ctx.defense.scheme

        // Defences get heavier near the goal line because they can: there is no
        // deep third to protect, so those bodies come into the box. This is the
        // real reason the red zone is hard, more than coverage tightening.
        val front = when {
            s.yardsToGoal <= 3 -> DefensiveFront.GOAL_LINE
            s.down == 3 && s.distance >= 8 -> DefensiveFront.DIME_FOUR_ONE
            s.distance >= 7 -> DefensiveFront.NICKEL_FOUR_TWO
            scheme.id.contains("34_TWO") -> DefensiveFront.THREE_FOUR_TWO_GAP
            scheme.id.contains("34_ONE") -> DefensiveFront.THREE_FOUR_ONE_GAP
            scheme.id.contains("335") -> DefensiveFront.THREE_THREE_FIVE
            scheme.id.contains("425") -> DefensiveFront.NICKEL_FOUR_TWO
            scheme.id.contains("UNDER") -> DefensiveFront.FOUR_THREE_UNDER
            else -> DefensiveFront.FOUR_THREE_OVER
        }

        val playMan = rng.nextFloat() < scheme.manZoneSplit
        val coverage = if (playMan) {
            listOf(Coverage.COVER_1, Coverage.COVER_1, Coverage.COVER_2_MAN, Coverage.COVER_0)
        } else {
            listOf(Coverage.COVER_3, Coverage.COVER_3, Coverage.COVER_2_ZONE,
                   Coverage.COVER_4, Coverage.TAMPA_2, Coverage.COVER_6)
        }.let { it[rng.nextInt(it.size)] }

        var blitzRate = scheme.blitzRate
        if (s.down == 3 && s.distance >= 6) blitzRate += 0.10f
        if (s.goalToGo) blitzRate += 0.06f
        val extraRushers = if (rng.nextFloat() < blitzRate) 1 + rng.nextInt(2) else 0

        // Selling out against the run when it is obviously coming.
        var boxAdd = when {
            s.down <= 2 && s.distance <= 3 -> 1
            s.distance >= 12 -> -1
            else -> 0
        }
        if (s.yardsToGoal <= 12) boxAdd += 1

        return DefensivePlayCall(
            front = front,
            coverage = coverage,
            extraRushers = extraRushers,
            boxAdd = boxAdd,
            doubledTarget = if (rng.nextFloat() < 0.12f) 0 else null,
        )
    }
}
