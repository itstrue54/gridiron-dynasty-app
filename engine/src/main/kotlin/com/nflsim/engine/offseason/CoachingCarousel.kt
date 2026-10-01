package com.nflsim.engine.offseason

import com.nflsim.engine.gen.NameGenerator
import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.CoachRatings
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeSide
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.rng.Rng
import kotlinx.serialization.Serializable
import com.nflsim.engine.rng.shuffled
import kotlin.math.exp
import kotlin.math.sqrt
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
    /** A coach out of work hired again, rather than an outside candidate. */
    val rehired: Boolean = false,
    val keptOffense: Boolean = false,
    val keptDefense: Boolean = false,
)

/**
 * SPEC 7 phase 2: the coaching carousel.
 *
 * A head coach's seat warms with a losing season, and more with one worse
 * than the last; a winning season or a playoff run cools it. A club fires
 * him when the seat passes its GM's bar - a win-now front office has less
 * patience - or lets him go when his contract runs out after a losing year.
 *
 * The club hires the best of a few outside candidates and the head coaches
 * out of work, as its view of them sees it, and he brings his schemes: his
 * offence, and a defensive coordinator with a defence. Some candidates run
 * the club's own schemes, for continuity; the rest lean to schemes the
 * roster suits better than the others, and the club counts that fit when it
 * chooses. A coach out of work carries the stigma of his firing, and a club
 * looks at only a couple of them. Players on a side
 * whose scheme changed start learning it again. Position coaches stay, and
 * nobody is poached. Coaches out of work age, and leave the pool at 68.
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
        val t = league.tuning.staff
        val coaches = league.coaches.toMutableMap()
        var nextId = (coaches.keys.maxOfOrNull { it.v } ?: 0) + 1
        val changes = mutableListOf<CoachingChange>()
        // Which sides of the ball changed scheme, per club.
        val relearn = mutableMapOf<TeamId, Pair<Boolean, Boolean>>()
        val employed = league.teams.flatMap { club ->
            listOf(club.staff.headCoach, club.staff.offCoordinator, club.staff.defCoordinator, club.staff.stCoordinator) +
                club.staff.positionCoaches.values
        }.toMutableSet()
        coaches.replaceAll { id, c -> if (id in employed) c else c.copy(age = c.age + 1) }
        coaches.entries.removeIf { (id, c) -> id !in employed && c.age >= t.retireAge }

        val teams = league.teams.map { team ->
            val hc = coaches[team.staff.headCoach] ?: return@map team
            val now = winPct(team.id)
            val before = previousWinPct[team.id.v] ?: 0.5f
            val seat = (hc.hotSeat * t.seatCooling + (0.5f - now) * t.seatBelow500 + (before - now) * t.seatWorseThanLast -
                (if (team.id.v in playoffClubs) t.seatPlayoffRelief else 0f)).roundToInt().coerceIn(0, 100)
            val contractLeft = hc.contractYearsLeft - 1
            if (seat < fireBar(team, t) && !(contractLeft <= 0 && now < 0.5f)) {
                coaches[hc.id] = hc.copy(
                    hotSeat = seat, age = hc.age + 1,
                    contractYearsLeft = if (contractLeft <= 0) t.extensionYears else contractLeft,
                )
                return@map team
            }

            val hireRng = rng.split("hire|${team.id.v}")
            fun candidate(role: CoachRole, scheme: String): Coach {
                val (first, last) = NameGenerator.fullName(hireRng)
                fun stat() = (t.candidateMean + hireRng.gaussian(0f, t.candidateSpread)).roundToInt().coerceIn(t.candidateFloor, 100)
                return Coach(
                    id = CoachId(nextId++), name = "$first $last", age = 38 + hireRng.nextInt(20),
                    role = role, scheme = scheme,
                    ratings = CoachRatings(stat(), stat(), stat(), stat(), stat(), stat()),
                    tendencies = com.nflsim.engine.gen.Tendencies.draw(role, scheme, hireRng.split("tendencies|$nextId")),
                )
            }
            fun quality(c: Coach) = with(c.ratings) {
                (development + gameplan + adjustments + discipline + motivation + evaluation) / 6f
            }
            // What the roster is built for: how well each scheme suits the
            // club's players on that side of the ball.
            val roster = league.players.filter { it.teamId == team.id }
            // As z-scores across the catalog: schemes differ in fit by a few
            // hundredths, so raw fit would barely tell them apart.
            fun fits(schemes: List<Scheme>, offense: Boolean): Map<String, Float> {
                val side = roster.filter { it.position.isOffense == offense }
                val raw = schemes.associate { s ->
                    s.id to if (side.isEmpty()) 0.0 else side.map { schemeFit(it, s).toDouble() }.average()
                }
                val mean = raw.values.average()
                val sd = sqrt(raw.values.map { (it - mean) * (it - mean) }.average()).coerceAtLeast(1e-6)
                return raw.mapValues { ((it.value - mean) / sd).toFloat() }
            }
            val offFit = fits(SchemeCatalog.offensive, true)
            val defFit = fits(SchemeCatalog.defensive, false)
            fun draw(fit: Map<String, Float>, current: String): String {
                if (hireRng.nextFloat() < t.continuity) return current
                val weights = fit.mapValues { (_, z) -> exp(t.fitZ * z) }
                var r = hireRng.nextFloat().toDouble() * weights.values.sum()
                for ((id, w) in weights) { r -= w; if (r <= 0) return id }
                return weights.keys.last()
            }
            fun read(c: Coach, fit: Map<String, Float>) =
                quality(c) + t.fitWeight * (fit[c.scheme] ?: 0f) + hireRng.gaussian(0f, t.evalNoise)

            val outside = (1..t.candidates).map { candidate(CoachRole.HEAD_COACH, draw(offFit, team.offenseScheme)) }
            val outOfWork = coaches.values
                .filter { it.id !in employed && it.role == CoachRole.HEAD_COACH }
                .shuffled(hireRng).take(t.rehireLook)
            // A head coach from the defense - a roster file can bring one - is
            // read against the defense his scheme would run.
            fun defensive(c: Coach) = SchemeCatalog[c.scheme].side == SchemeSide.DEFENSE
            val chosen = (outside + outOfWork).maxBy {
                read(it, if (defensive(it)) defFit else offFit) - if (it in outOfWork) t.stigma else 0f
            }
            val head = chosen.copy(contractYearsLeft = t.newContractYears, hotSeat = 0)
            // He brings his scheme to his side of the ball through a
            // coordinator from his tree, and the club finds the best it can
            // for the other side.
            val (oc, dc) = if (!defensive(head)) {
                candidate(CoachRole.OFFENSIVE_COORDINATOR, head.scheme).copy(tree = head.id) to
                    (1..t.coordinatorCandidates).map { candidate(CoachRole.DEFENSIVE_COORDINATOR, draw(defFit, team.defenseScheme)) }
                        .maxBy { read(it, defFit) }
                        .copy(tree = head.id)
            } else {
                (1..t.coordinatorCandidates).map { candidate(CoachRole.OFFENSIVE_COORDINATOR, draw(offFit, team.offenseScheme)) }
                    .maxBy { read(it, offFit) }
                    .copy(tree = head.id) to
                    candidate(CoachRole.DEFENSIVE_COORDINATOR, head.scheme).copy(tree = head.id)
            }
            listOf(head, oc, dc).forEach { coaches[it.id] = it }
            // Out of work, and a candidate for the next club that fires someone.
            coaches[hc.id] = hc.copy(hotSeat = 0, age = hc.age + 1, contractYearsLeft = 0)
            employed -= hc.id
            employed += listOf(head.id, oc.id, dc.id)

            val offChanged = oc.scheme != team.offenseScheme
            val defChanged = dc.scheme != team.defenseScheme
            relearn[team.id] = offChanged to defChanged
            changes += CoachingChange(
                team.id.v, hc.name, head.name, (now * 1000).roundToInt(),
                oc.scheme, dc.scheme, offChanged || defChanged, rehired = chosen in outOfWork,
                keptOffense = !offChanged, keptDefense = !defChanged,
            )
            // The club runs its coordinators' schemes: his own is one of them.
            team.copy(
                offenseScheme = oc.scheme,
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
    /**
     * The pressure a head coach is fired at, at this club. An impatient front
     * office fires sooner, which is why the bar belongs to the club and not to
     * the league - and why anything showing a hot seat has to ask for it
     * rather than guess a number.
     */
    fun fireBar(team: Team, t: com.nflsim.engine.tuning.TuningTable.Staff): Int =
        (t.fireBar - team.gm.winNowVsFuture * t.winNowImpatience).roundToInt()
}
