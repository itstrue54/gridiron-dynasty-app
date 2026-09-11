package com.nflsim.engine.offseason

import com.nflsim.engine.gen.NameGenerator
import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.CoachRatings
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** One club's new head coach, for the news screen. */
@Serializable
data class CoachingChange(
    val team: Int,
    val fired: String,
    val hired: String,
    /** The club's win percentage, in thousandths, the season he went. */
    val record: Int,
    val offense: String,
    val defense: String,
    /** Whether the new staff brought a scheme the club did not already run. */
    val schemeChanged: Boolean,
)

/**
 * SPEC 7 phase 2: the coaching carousel.
 *
 * A head coach's seat warms with a losing season, and more with one worse
 * than the last; a winning season or a playoff run cools it. A club fires
 * him when the seat passes its GM's bar - a win-now front office has less
 * patience - or lets him go when his contract runs out after a losing year.
 *
 * The club hires the best of a few outside candidates, as its view of them
 * sees it, and he brings his schemes: his offence, and a defensive
 * coordinator with a defence. Players on a side whose scheme changed start
 * learning it again. Position coaches stay, and nobody is poached.
 *
 * Candidates are drawn below the league's coaching mean on purpose. The
 * best of several is hired, and drawn at the mean the league's coaching
 * would climb every year and take development with it (SPEC 7.1).
 */
object CoachingCarousel {

    data class Result(val league: League, val changes: List<CoachingChange>)

    fun run(
        league: League,
        winPct: (TeamId) -> Float,
        playoffClubs: Set<Int>,
        previousWinPct: Map<Int, Float>,
        rng: Rng,
    ): Result {
        val coaches = league.coaches.toMutableMap()
        var nextId = (coaches.keys.maxOfOrNull { it.v } ?: 0) + 1
        val changes = mutableListOf<CoachingChange>()
        // Which sides of the ball changed scheme, per club.
        val relearn = mutableMapOf<TeamId, Pair<Boolean, Boolean>>()

        val teams = league.teams.map { team ->
            val hc = coaches[team.staff.headCoach] ?: return@map team
            val now = winPct(team.id)
            val before = previousWinPct[team.id.v] ?: 0.5f
            val seat = (hc.hotSeat * COOLING + (0.5f - now) * BELOW_500 + (before - now) * WORSE_THAN_LAST -
                (if (team.id.v in playoffClubs) PLAYOFF_RELIEF else 0f)).roundToInt().coerceIn(0, 100)
            val contractLeft = hc.contractYearsLeft - 1
            if (seat < fireBar(team) && !(contractLeft <= 0 && now < 0.5f)) {
                coaches[hc.id] = hc.copy(
                    hotSeat = seat, age = hc.age + 1,
                    contractYearsLeft = if (contractLeft <= 0) EXTENSION else contractLeft,
                )
                return@map team
            }

            val hireRng = rng.split("hire|${team.id.v}")
            fun candidate(role: CoachRole, scheme: String): Coach {
                val (first, last) = NameGenerator.fullName(hireRng)
                fun stat() = (CANDIDATE_MEAN + hireRng.gaussian(0f, 20f)).roundToInt().coerceIn(30, 100)
                return Coach(
                    id = CoachId(nextId++), name = "$first $last", age = 38 + hireRng.nextInt(20),
                    role = role, scheme = scheme,
                    ratings = CoachRatings(stat(), stat(), stat(), stat(), stat(), stat()),
                )
            }
            fun quality(c: Coach) = with(c.ratings) {
                (development + gameplan + adjustments + discipline + motivation + evaluation) / 6f
            }
            fun anyOf(schemes: List<com.nflsim.engine.ratings.Scheme>) = schemes[hireRng.nextInt(schemes.size)].id

            val head = (1..CANDIDATES)
                .map { candidate(CoachRole.HEAD_COACH, anyOf(SchemeCatalog.offensive)) }
                .maxBy { quality(it) + hireRng.gaussian(0f, EVAL_NOISE) }
                .copy(contractYearsLeft = NEW_CONTRACT)
            val oc = candidate(CoachRole.OFFENSIVE_COORDINATOR, head.scheme).copy(tree = head.id)
            val dc = candidate(CoachRole.DEFENSIVE_COORDINATOR, anyOf(SchemeCatalog.defensive)).copy(tree = head.id)
            listOf(head, oc, dc).forEach { coaches[it.id] = it }
            // Out of work, and available to nobody yet: there is no poaching.
            coaches[hc.id] = hc.copy(hotSeat = 0, age = hc.age + 1, contractYearsLeft = 0)

            val offChanged = head.scheme != team.offenseScheme
            val defChanged = dc.scheme != team.defenseScheme
            relearn[team.id] = offChanged to defChanged
            changes += CoachingChange(
                team.id.v, hc.name, head.name, (now * 1000).roundToInt(),
                head.scheme, dc.scheme, offChanged || defChanged,
            )
            team.copy(
                offenseScheme = head.scheme,
                defenseScheme = dc.scheme,
                staff = team.staff.copy(headCoach = head.id, offCoordinator = oc.id, defCoordinator = dc.id),
            )
        }

        val players = league.players.map { p ->
            val (off, def) = p.teamId?.let { relearn[it] } ?: return@map p
            if (if (p.position.isOffense) off else def) p.copy(yearsInSystem = 0, yearsWithClub = p.clubYears) else p
        }
        return Result(league.copy(teams = teams, coaches = coaches, players = players), changes)
    }

    /** The hot seat at which a club fires its coach: 55 for a patient GM, 35 for the most win-now. */
    private fun fireBar(team: Team): Int = (FIRE_BAR - team.gm.winNowVsFuture * WIN_NOW_IMPATIENCE).roundToInt()

    /** How the seat moves: what carries over, and what a losing season adds. */
    private const val COOLING = 0.6f
    private const val BELOW_500 = 120f
    private const val WORSE_THAN_LAST = 80f
    private const val PLAYOFF_RELIEF = 20f

    // Swept over five seeds: 75 -> 4.2 head coaches replaced a year, 65 -> 4.6,
    // 55 -> 5.5, against the NFL's five to ten. Development did not move.
    private const val FIRE_BAR = 55f
    private const val WIN_NOW_IMPATIENCE = 20f

    /** Years on a kept coach's extension, and on a new hire's deal. */
    private const val EXTENSION = 3
    private const val NEW_CONTRACT = 5

    /** Candidates per vacancy, their mean rating, and how noisily a club reads them. */
    private const val CANDIDATES = 4
    private const val CANDIDATE_MEAN = 58f
    private const val EVAL_NOISE = 8f
}
