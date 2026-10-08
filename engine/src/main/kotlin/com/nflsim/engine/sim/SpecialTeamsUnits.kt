package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.tuning.TuningTable

/**
 * The jobs on a kicking or return unit, each read from the ratings a coach
 * looks for in it (TuningTable.SpecialTeams.roleWeights): a coverage man runs
 * and tackles, a gunner beats his jam and gets downfield, a return blocker
 * blocks in space, a jammer slows a gunner, a rusher gets a hand on a kick,
 * a protector keeps the rush off it.
 */
enum class StRole { COVERAGE, GUNNER, BLOCKER, JAMMER, RUSHER, PROTECTOR }

/**
 * A club's special teams for a kick: who plays on each unit, and how good
 * each unit is - the average of its men in their role, on the 0-99 scale.
 *
 * Units are picked from the men dressed and fit, the best in each role
 * first. Starters are picked for coverage and returns only when they are
 * clearly the better man (`starterPenalty`), as clubs keep their starters
 * off those units; protection and the field-goal rush use whoever is best.
 * The specialists - quarterbacks, kickers, punters, snappers - play only
 * their own parts. The club's core special teamers (DepthPins.specialTeams)
 * play on every coverage and return unit.
 */
data class SpecialTeamsUnits(
    val kickCoverage: List<Player>,
    val kickReturn: List<Player>,
    val gunners: List<Player>,
    val puntCoverage: List<Player>,
    val jammers: List<Player>,
    val puntReturn: List<Player>,
    val protection: List<Player>,
    val rush: List<Player>,
    val snapper: Player?,
    val holder: Player?,
    private val ratings: Map<String, Float>,
) {
    val kickCoverageRating: Float get() = ratings.getValue("kickCoverage")
    val kickReturnRating: Float get() = ratings.getValue("kickReturn")
    val gunnerRating: Float get() = ratings.getValue("gunners")
    val puntCoverageRating: Float get() = ratings.getValue("puntCoverage")
    val jammerRating: Float get() = ratings.getValue("jammers")
    val puntReturnRating: Float get() = ratings.getValue("puntReturn")
    val protectionRating: Float get() = ratings.getValue("protection")
    val rushRating: Float get() = ratings.getValue("rush")
    /** The snap and the hold together: the snapper's and the holder's overall at their positions. */
    val snapRating: Float get() = ratings.getValue("snap")

    /** Points this club's kick return blocking beats [kicking]'s coverage by, each off its league average. */
    fun kickReturnEdge(kicking: SpecialTeamsUnits, st: TuningTable.SpecialTeams): Float =
        (kickReturnRating - st.kickReturnAnchor) - (kicking.kickCoverageRating - st.kickCoverageAnchor)

    /** Points this club's punt return blocking beats [punting]'s coverage by. */
    fun puntReturnEdge(punting: SpecialTeamsUnits, st: TuningTable.SpecialTeams): Float =
        (puntReturnRating - st.puntReturnAnchor) - (punting.puntCoverageRating - st.puntCoverageAnchor)

    /** Points [punting]'s gunners beat this club's jammers by: the more, the more fair catches. */
    fun gunnerEdge(punting: SpecialTeamsUnits, st: TuningTable.SpecialTeams): Float =
        (punting.gunnerRating - st.gunnerAnchor) - (jammerRating - st.jammerAnchor)

    /** Points this club's rush beats [kicking]'s protection by, on a field goal or a punt. */
    fun blockEdge(kicking: SpecialTeamsUnits, st: TuningTable.SpecialTeams): Float =
        (rushRating - st.rushAnchor) - (kicking.protectionRating - st.protectionAnchor)

    companion object {
        const val KICK_COVERAGE = 10
        const val KICK_RETURN = 10
        const val GUNNERS = 2
        const val PUNT_COVERAGE = 7
        const val JAMMERS = 2
        const val PUNT_RETURN = 7
        const val PROTECTION = 9
        const val RUSH = 9

        /** Who starts at each position, by depth: kept off coverage and returns unless clearly the better man. */
        private val STARTERS = mapOf(
            Position.RB to 1, Position.WR to 3, Position.TE to 1,
            Position.LT to 1, Position.LG to 1, Position.C to 1, Position.RG to 1, Position.RT to 1,
            Position.EDGE to 2, Position.DT to 2, Position.LB to 2, Position.CB to 3, Position.S to 2,
        )
        private val SPECIALISTS = setOf(Position.QB, Position.K, Position.P, Position.LS)

        /** A man's worth in [role]: the weighted average of the ratings it reads. */
        fun value(p: Player, role: StRole, scheme: Scheme, st: TuningTable.SpecialTeams): Float {
            val weights = st.roleWeights.getValue(role)
            return weights.entries.sumOf { (id, w) -> (rate(p, id, scheme) * w).toDouble() }.toFloat() / weights.values.sum()
        }

        /**
         * How much a man is worth to the kick coverage of a club dressing
         * [dressed]: his coverage, less the starter penalty if he starts at his
         * position (by rating, as a depth chart nobody has pinned sorts it).
         * The unit is the ten worth most; GameDay reads it to see whether a
         * squad man would make it.
         */
        fun coverageWorth(dressed: List<Player>, offence: Scheme, defence: Scheme, st: TuningTable.SpecialTeams): Map<Int, Float> {
            fun scheme(p: Player) = if (p.position.isOffense) offence else defence
            val starters = STARTERS.flatMap { (pos, n) ->
                dressed.filter { it.position == pos }.sortedByDescending { overall(it, scheme(it)) }.take(n)
            }.map { it.id.v }.toSet()
            return dressed.filter { it.position !in SPECIALISTS }.associate { p ->
                p.id.v to value(p, StRole.COVERAGE, scheme(p), st) - (if (p.id.v in starters) st.starterPenalty else 0f)
            }
        }

        fun of(team: GameTeam, st: TuningTable.SpecialTeams, out: Set<Int> = emptySet()): SpecialTeamsUnits {
            val fit = team.roster.filter { it.id.v !in out }
            val pool = fit.filter { it.position !in SPECIALISTS }
            fun scheme(p: Player) = if (p.position.isOffense) team.offScheme else team.defScheme
            val starters = STARTERS.flatMap { (pos, n) ->
                val chart = if (pos.isOffense) team.offDepth else team.defDepth
                chart.at(pos).filter { it.id.v !in out }.take(n)
            }.map { it.id.v }.toSet()
            val core = team.team.depthPins.specialTeams.toSet()
            // Every man's worth in every role, read once: the picks below sort on them.
            val values = pool.associate { p -> p.id.v to StRole.entries.map { role -> value(p, role, scheme(p), st) } }
            fun valueOf(p: Player, role: StRole) = values.getValue(p.id.v)[role.ordinal]
            fun worth(p: Player, role: StRole): Float {
                val v = valueOf(p, role)
                val covers = role != StRole.PROTECTOR && role != StRole.RUSHER
                return v - (if (covers && p.id.v in starters) st.starterPenalty else 0f) +
                    (if (covers && p.id.v in core) st.corePinBonus else 0f)
            }
            fun pick(role: StRole, n: Int, from: List<Player> = pool, positions: Set<Position>? = null): List<Player> =
                from.filter { positions == null || it.position in positions }
                    .map { it to worth(it, role) }.sortedByDescending { it.second }.take(n).map { it.first }
            fun rating(men: List<Player>, role: StRole, anchor: Float): Float =
                if (men.isEmpty()) anchor - st.noSnapperPenalty else men.map { valueOf(it, role) }.average().toFloat()

            val outside = setOf(Position.WR, Position.CB, Position.S, Position.RB)
            val blockers = setOf(Position.LT, Position.LG, Position.C, Position.RG, Position.RT, Position.TE)
            val front = setOf(Position.EDGE, Position.DT, Position.LB)
            // The returners field the ball; they don't block for themselves.
            val kickReturner = SpecialTeams.returnerFor(team.offDepth, team.offScheme)
            val puntReturner = SpecialTeams.returnerFor(team.offDepth, team.offScheme, punt = true)
            val gunners = pick(StRole.GUNNER, GUNNERS, positions = outside)
            val jammers = pick(StRole.JAMMER, JAMMERS, pool - setOfNotNull(puntReturner), positions = outside)
            val kickCoverage = pick(StRole.COVERAGE, KICK_COVERAGE)
            val kickReturn = pick(StRole.BLOCKER, KICK_RETURN, pool - setOfNotNull(kickReturner))
            val puntCoverage = pick(StRole.COVERAGE, PUNT_COVERAGE, pool - gunners.toSet())
            val puntReturn = pick(StRole.BLOCKER, PUNT_RETURN, pool - jammers.toSet() - setOfNotNull(puntReturner))
            val protection = pick(StRole.PROTECTOR, PROTECTION, positions = blockers)
            val rush = pick(StRole.RUSHER, RUSH, positions = front)
            val snapper = fit.filter { it.position == Position.LS }.maxByOrNull { overall(it, team.offScheme) }
            val holder = fit.filter { it.position == Position.P }.maxByOrNull { overall(it, team.offScheme) }
            val snap = listOfNotNull(snapper, holder).map { overall(it, team.offScheme).toFloat() }
                .ifEmpty { listOf(st.snapAnchor - st.noSnapperPenalty) }.average().toFloat()

            return SpecialTeamsUnits(
                kickCoverage, kickReturn, gunners, puntCoverage, jammers, puntReturn, protection, rush,
                snapper, holder,
                mapOf(
                    "kickCoverage" to rating(kickCoverage, StRole.COVERAGE, st.kickCoverageAnchor),
                    "kickReturn" to rating(kickReturn, StRole.BLOCKER, st.kickReturnAnchor),
                    "gunners" to rating(gunners, StRole.GUNNER, st.gunnerAnchor),
                    "puntCoverage" to rating(puntCoverage, StRole.COVERAGE, st.puntCoverageAnchor),
                    "jammers" to rating(jammers, StRole.JAMMER, st.jammerAnchor),
                    "puntReturn" to rating(puntReturn, StRole.BLOCKER, st.puntReturnAnchor),
                    "protection" to rating(protection, StRole.PROTECTOR, st.protectionAnchor),
                    "rush" to rating(rush, StRole.RUSHER, st.rushAnchor),
                    "snap" to snap,
                ),
            )
        }
    }
}
