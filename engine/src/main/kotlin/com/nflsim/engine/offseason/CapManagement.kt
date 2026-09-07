package com.nflsim.engine.offseason

import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamFinances
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable
import kotlin.math.pow

@Serializable
data class Release(
    val player: Int,
    val name: String,
    val position: String,
    val team: Int,
    val overall: Int,
    val savings: Int,
    val deadMoney: Int,
)

/**
 * The salary cap, and what it does to a good roster.
 *
 * Without this a league has no parity mechanism at all. Draft order alone is
 * far too weak - a team that is good stays good, because nothing ever takes a
 * player away from it. Ten simulated seasons produced four different champions
 * and one team with five titles before the cap existed.
 *
 * What creates turnover is not the cap number itself but the fact that
 * *winning is expensive*: good players earn more, a roster full of them cannot
 * be kept, and letting one go carries dead money that makes next year harder
 * too (docs/SPEC.md 8.1).
 */
object CapManagement {

    /** The cap grows every year, which is what lets contracts age well. */
    fun capFor(year: Int, baseYear: Int = 2026): Int =
        (TeamFinances.LEAGUE_CAP * 1.068.pow((year - baseYear).toDouble())).toInt()

    fun committed(roster: List<Player>, year: Int): Int = roster.sumOf { it.capHit(year) }

    fun spaceFor(roster: List<Player>, year: Int, deadMoney: Int = 0): Int =
        capFor(year) - committed(roster, year) - deadMoney

    /**
     * Cuts until every team is legal.
     *
     * Value here is cap hit per point of ability, so the player released is the
     * one costing the most for what he gives - an ageing star on a big deal
     * rather than a cheap rookie. That is the decision a real front office
     * faces, and it is why a championship roster comes apart.
     */
    fun enforce(
        league: League,
        players: List<Player>,
        year: Int,
        scheme: (TeamId?, Position) -> Scheme,
        rng: Rng,
    ): Triple<List<Player>, Map<Int, Int>, List<Release>> {
        val byTeam = players.filter { it.teamId != null }.groupBy { it.teamId!! }
        val kept = mutableListOf<Player>()
        val released = mutableListOf<Player>()
        val notes = mutableListOf<Release>()
        val deadMoneyByTeam = mutableMapOf<Int, Int>()
        val cap = capFor(year)

        league.teams.forEach { team ->
            val roster = (byTeam[team.id] ?: emptyList()).toMutableList()
            var dead = team.finances.deadMoney
            var guard = 0

            // A front office restructures before it releases anybody. It is
            // the cheapest thing to do this year and the most expensive thing
            // to have done three years from now, which is the trap the cap is
            // supposed to set (SPEC 8.1).
            var restructured = 0
            while (committed(roster, year) + dead > cap && restructured < team.gm.restructures) {
                val target = roster
                    .filter { it.contract?.isActive(year) == true && it.capHit(year) > BIG_DEAL }
                    .maxByOrNull { it.capHit(year) } ?: break
                val room = (target.contract!!.baseSalary
                    .getOrElse(target.contract!!.yearIndex(year)) { 0 } - Contract.MIN_BASE_SALARY)
                if (room <= 0) break
                val moved = (room * RESTRUCTURE_SHARE).toInt()
                if (moved <= 0) break
                roster[roster.indexOf(target)] = target.copy(
                    contract = target.contract!!.restructure(year, moved))
                restructured++
            }

            while (committed(roster, year) + dead > cap && roster.size > MIN_ROSTER && guard < 60) {
                guard++
                val candidate = roster
                    .filter { canRelease(it, roster, scheme, team.id) }
                    .maxByOrNull { p ->
                        val ability = overall(p, scheme(team.id, p.position)).coerceAtLeast(35)
                        // Cost per point of ability, with a nudge toward moving
                        // on from players already past their best.
                        val age = (p.age(year) - 28).coerceAtLeast(0)
                        p.capHit(year).toFloat() / ability + age * 55f + rng.gaussian(0f, 40f)
                    } ?: break

                val deadCap = candidate.contract?.deadCap(year)?.thisYear ?: 0
                val savings = candidate.capHit(year) - deadCap

                roster.remove(candidate)
                dead += deadCap
                released += candidate.copy(
                    teamId = null, contract = null, status = PlayerStatus.FREE_AGENT)
                notes += Release(
                    candidate.id.v, candidate.name, candidate.position.label, team.id.v,
                    overall(candidate, scheme(team.id, candidate.position)),
                    savings, deadCap,
                )
            }

            deadMoneyByTeam[team.id.v] = dead
            kept += roster
        }

        val untouched = players.filter { it.teamId == null }
        return Triple(kept + released + untouched, deadMoneyByTeam, notes)
    }

    /** Never cut below the positional minimum - somebody has to snap the ball. */
    private fun canRelease(
        player: Player,
        roster: List<Player>,
        scheme: (TeamId?, Position) -> Scheme,
        teamId: TeamId,
    ): Boolean {
        val atPosition = roster.count { it.position == player.position }
        return atPosition > TeamNeeds.requiredStarters(player.position)
    }

    /**
     * Releasing a player who is not worth his cap hit, to fund one who is.
     *
     * Teams that only cut to become compliant never cut anybody, because a
     * team that is never over the cap is never forced to act. Real releases
     * are a value judgement made in February: this player costs more than he
     * is worth, and the money buys somebody better.
     */
    fun pruneBadValue(
        league: League,
        players: List<Player>,
        year: Int,
        scheme: (TeamId?, Position) -> Scheme,
        price: (Player, Scheme) -> Int,
        deadMoney: Map<Int, Int>,
    ): Triple<List<Player>, Map<Int, Int>, List<Release>> {
        val byTeam = players.filter { it.teamId != null }.groupBy { it.teamId!! }
        val kept = mutableListOf<Player>()
        val released = mutableListOf<Player>()
        val notes = mutableListOf<Release>()
        val dead = deadMoney.toMutableMap()

        league.teams.forEach { team ->
            val roster = (byTeam[team.id] ?: emptyList()).toMutableList()
            var cuts = 0

            while (cuts < MAX_VALUE_CUTS) {
                val candidate = roster
                    .filter { p ->
                        val worth = price(p, scheme(team.id, p.position))
                        val hit = p.capHit(year)
                        val saving = hit - (p.contract?.deadCap(year)?.thisYear ?: 0)
                        hit > worth * team.gm.patience &&
                            hit > BIG_DEAL &&
                            saving > MEANINGFUL_SAVING &&
                            roster.count { it.position == p.position } >
                                TeamNeeds.requiredStarters(p.position)
                    }
                    .maxByOrNull { p -> p.capHit(year) - price(p, scheme(team.id, p.position)) }
                    ?: break

                val deadCap = candidate.contract?.deadCap(year)?.thisYear ?: 0
                val saving = candidate.capHit(year) - deadCap
                roster.remove(candidate)
                dead[team.id.v] = (dead[team.id.v] ?: 0) + deadCap
                released += candidate.copy(
                    teamId = null, contract = null, status = PlayerStatus.FREE_AGENT)
                notes += Release(
                    candidate.id.v, candidate.name, candidate.position.label, team.id.v,
                    overall(candidate, scheme(team.id, candidate.position)),
                    saving, deadCap,
                )
                cuts++
            }
            kept += roster
        }

        val untouched = players.filter { it.teamId == null }
        return Triple(kept + released + untouched, dead, notes)
    }

    private const val MIN_ROSTER = 46

    /** Cap hit worth restructuring or cutting over, in thousands. */
    private const val BIG_DEAL = 6_000

    /** How much of a restructurable base salary gets converted. */
    private const val RESTRUCTURE_SHARE = 0.6f

    private const val MEANINGFUL_SAVING = 2_500

    private const val MAX_VALUE_CUTS = 3
}
