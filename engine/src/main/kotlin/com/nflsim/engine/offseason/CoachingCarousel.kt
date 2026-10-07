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
    /** The club whose coordinator he was, when the new head coach was promoted from another club's staff. */
    val promotedFrom: Int? = null,
)

/**
 * A coordinator another club hired away to be its head coach - a promotion,
 * which the NFL's anti-tampering policy never lets a club block.
 */
@Serializable
data class Promotion(
    /** The club that made him its head coach. */
    val team: Int,
    /** The club he left. */
    val from: Int,
    val name: String,
    /** The job he left. */
    val role: CoachRole,
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
 * out of work, for what the job uses (Staffing.worth, as the user's pool
 * reads them) and as its view of them sees it, and he brings his schemes: his
 * offence, and a defensive coordinator with a defence. Some candidates run
 * the club's own schemes, for continuity; the rest lean to schemes the
 * roster suits better than the others, and the club counts that fit when it
 * chooses. A coach out of work carries the stigma of his firing, and a club
 * looks at only a couple of them. Players on a side
 * whose scheme changed start learning it again. Position coaches stay, and
 * nobody is poached. Coaches out of work age, and leave the pool at 68.
 *
 * The user's club is the exception: nobody fires its coach but the user. Its
 * seat still warms and cools, for the Staff screen's advice, and a contract
 * that runs out is extended - the user lets a coach go in the spring window
 * before this runs (Staffing), or keeps him.
 *
 * Candidates are drawn below the league's coaching mean on purpose. The
 * best of several is hired, and drawn at the mean the league's coaching
 * would climb every year and take development with it (SPEC 7.1).
 */
object CoachingCarousel {

    /**
     * [gmChanges] rides along from GmCarousel, which runs straight after, to
     * the offseason's report; [promotions] are the coordinators other clubs
     * hired away to be their head coaches.
     */
    data class Result(
        val league: League,
        val changes: List<CoachingChange>,
        val gmChanges: List<GmChange> = emptyList(),
        val promotions: List<Promotion> = emptyList(),
    )

    fun run(
        league: League,
        winPct: (TeamId) -> Float,
        playoffClubs: Set<Int>,
        previousWinPct: Map<Int, Float>,
        rng: Rng,
        /** The user's club: its seat still warms, as advice, but its staffing is the user's (offseason.Staffing). */
        userTeam: TeamId? = null,
        /** Coaches the user's club has agreed to hire (Staffing.PendingHire): no other club looks at them. */
        reserved: Set<CoachId> = emptySet(),
    ): Result {
        val t = league.tuning.staff
        val coaches = league.coaches.toMutableMap()
        var nextId = (coaches.keys.maxOfOrNull { it.v } ?: 0) + 1
        val changes = mutableListOf<CoachingChange>()
        val promotions = mutableListOf<Promotion>()
        // The clubs as they stand, changed in turn: a club whose coordinator
        // another club promotes is changed from that club's turn.
        val clubs = league.teams.associateBy { it.id }.toMutableMap()
        // Which sides of the ball changed scheme, per club.
        val relearn = mutableMapOf<TeamId, Pair<Boolean, Boolean>>()
        fun relearned(id: TeamId, off: Boolean, def: Boolean) {
            val (o, d) = relearn[id] ?: (false to false)
            relearn[id] = (o || off) to (d || def)
        }
        val employed = league.teams.flatMap { club ->
            listOf(club.staff.headCoach, club.staff.offCoordinator, club.staff.defCoordinator, club.staff.stCoordinator) +
                club.staff.positionCoaches.values
        }.toMutableSet()
        // Men hired this spring are nobody's to promote until next spring.
        val newThisSpring = mutableSetOf<CoachId>()
        coaches.replaceAll { id, c -> if (id in employed) c else c.copy(age = c.age + 1) }
        coaches.entries.removeIf { (id, c) -> id !in employed && c.age >= t.retireAge }

        for (teamId in league.teams.map { it.id }) {
            val team = clubs.getValue(teamId)
            val hc = coaches[team.staff.headCoach] ?: continue
            val now = winPct(team.id)
            val before = previousWinPct[team.id.v] ?: 0.5f
            val seat = (hc.hotSeat * t.seatCooling + (0.5f - now) * t.seatBelow500 + (before - now) * t.seatWorseThanLast -
                (if (team.id.v in playoffClubs) t.seatPlayoffRelief else 0f)).roundToInt().coerceIn(0, 100)
            val contractLeft = hc.contractYearsLeft - 1
            if (team.id == userTeam || seat < fireBar(team, t) && !(contractLeft <= 0 && now < 0.5f)) {
                coaches[hc.id] = hc.copy(
                    hotSeat = seat, age = hc.age + 1,
                    contractYearsLeft = if (contractLeft <= 0) t.extensionYears else contractLeft,
                )
                continue
            }

            val hireRng = rng.split("hire|${team.id.v}")
            fun candidate(role: CoachRole, scheme: String): Coach = newCoach(CoachId(nextId++), role, scheme, t, hireRng)
            val offFit = fits(league, team.id, SchemeCatalog.offensive, true)
            val defFit = fits(league, team.id, SchemeCatalog.defensive, false)
            fun draw(fit: Map<String, Float>, current: String) = drawScheme(fit, current, t, hireRng)
            // What he is worth in the job, as the user's pool reads it
            // (Staffing.worth), with the club's eye for its roster's scheme
            // and the noise of a front office judging men it has not worked with.
            fun read(c: Coach, job: StaffJob, fit: Map<String, Float>) =
                Staffing.worth(c, job) + t.fitWeight * (fit[c.scheme] ?: 0f) + hireRng.gaussian(0f, t.evalNoise)

            val outside = (1..t.candidates).map { candidate(CoachRole.HEAD_COACH, draw(offFit, team.offenseScheme)) }
            val outOfWork = coaches.values
                .filter { it.id !in employed && it.id !in reserved && it.role == CoachRole.HEAD_COACH }
                .shuffled(hireRng).take(t.rehireLook)
            // Other clubs' coordinators: a club cannot stop a man taking a
            // head coaching job (the NFL's anti-tampering policy - a promotion
            // is never blocked), so a few of them are on every list.
            val promotable = clubs.values.filter { it.id != team.id }.flatMap { club ->
                listOf(StaffJob.OFFENCE to club.staff.offCoordinator, StaffJob.DEFENCE to club.staff.defCoordinator)
                    .mapNotNull { (job, id) -> coaches[id]?.let { Triple(club.id, job, it) } }
            }.filter { (_, _, c) -> c.id !in reserved && c.id !in newThisSpring }
                .shuffled(hireRng).take(t.poachLook)
            val fromClub = promotable.associate { (club, job, c) -> c.id to (club to job) }
            // A head coach from the defense is read against the defense his scheme would run.
            fun defensive(c: Coach) = SchemeCatalog[c.scheme].side == SchemeSide.DEFENSE
            val chosen = (outside + outOfWork + promotable.map { it.third }).maxBy {
                read(it, StaffJob.HEAD, if (defensive(it)) defFit else offFit) - if (it in outOfWork) t.stigma else 0f
            }
            var head = chosen.copy(contractYearsLeft = t.newContractYears, hotSeat = 0)
            fromClub[chosen.id]?.let { (sourceId, job) ->
                // Promoted from another club's staff: a head coach's levers now,
                // and his old club fills the job he left.
                head = head.copy(role = CoachRole.HEAD_COACH, tree = chosen.tree,
                    tendencies = com.nflsim.engine.gen.Tendencies.draw(CoachRole.HEAD_COACH, chosen.scheme, hireRng.split("promoted|${chosen.id.v}")))
                val source = clubs.getValue(sourceId)
                promotions += Promotion(team = team.id.v, from = sourceId.v, name = chosen.name, role = job.role)
                if (sourceId == userTeam) {
                    // The user's club fills its own (Staffing.settle).
                    clubs[sourceId] = source.copy(staff = Staffing.staffWith(source.staff, job, Staffing.VACANT))
                } else {
                    val fill = replacement(league, source, job, t, hireRng) { role, scheme -> candidate(role, scheme) }
                    coaches[fill.id] = fill
                    employed += fill.id
                    newThisSpring += fill.id
                    clubs[sourceId] = withCoordinator(source, job, fill)
                    relearned(sourceId, job == StaffJob.OFFENCE && fill.scheme != source.offenseScheme,
                        job == StaffJob.DEFENCE && fill.scheme != source.defenseScheme)
                }
            }
            // He brings his scheme to his side of the ball through the best of
            // a few coordinators from his tree who run it, and the club finds
            // the best it can for the other side. Both sides are chosen from
            // as many: a side that took whoever came would fall behind the
            // other's game plans, dynasty by dynasty.
            fun best(role: CoachRole, job: StaffJob, fit: Map<String, Float>, scheme: () -> String) =
                (1..t.coordinatorCandidates).map { candidate(role, scheme()) }
                    .maxBy { read(it, job, fit) }
                    .copy(tree = head.id)
            val (oc, dc) = if (!defensive(head)) {
                best(CoachRole.OFFENSIVE_COORDINATOR, StaffJob.OFFENCE, offFit) { head.scheme } to
                    best(CoachRole.DEFENSIVE_COORDINATOR, StaffJob.DEFENCE, defFit) { draw(defFit, team.defenseScheme) }
            } else {
                best(CoachRole.OFFENSIVE_COORDINATOR, StaffJob.OFFENCE, offFit) { draw(offFit, team.offenseScheme) } to
                    best(CoachRole.DEFENSIVE_COORDINATOR, StaffJob.DEFENCE, defFit) { head.scheme }
            }
            listOf(head, oc, dc).forEach { coaches[it.id] = it }
            // Out of work, and a candidate for the next club that fires someone.
            coaches[hc.id] = hc.copy(hotSeat = 0, age = hc.age + 1, contractYearsLeft = 0)
            employed -= hc.id
            employed += listOf(head.id, oc.id, dc.id)
            newThisSpring += listOf(head.id, oc.id, dc.id)

            val offChanged = oc.scheme != team.offenseScheme
            val defChanged = dc.scheme != team.defenseScheme
            relearned(team.id, offChanged, defChanged)
            changes += CoachingChange(
                team.id.v, hc.name, head.name, (now * 1000).roundToInt(),
                oc.scheme, dc.scheme, offChanged || defChanged, rehired = chosen in outOfWork,
                keptOffense = !offChanged, keptDefense = !defChanged,
                promotedFrom = fromClub[chosen.id]?.first?.v,
            )
            // The club runs its coordinators' schemes: his own is one of them.
            // Its staff is read again, in case its own coordinator was the one promoted.
            val current = clubs.getValue(team.id)
            clubs[team.id] = current.copy(
                offenseScheme = oc.scheme,
                defenseScheme = dc.scheme,
                staff = current.staff.copy(headCoach = head.id, offCoordinator = oc.id, defCoordinator = dc.id),
            )
        }

        val players = league.players.map { p ->
            val (off, def) = p.teamId?.let { relearn[it] } ?: return@map p
            if (if (p.position.isOffense) off else def) p.copy(yearsInSystem = 0, yearsWithClub = p.clubYears) else p
        }
        val teams = league.teams.map { clubs.getValue(it.id) }
        return Result(league.copy(teams = teams, coaches = coaches, players = players), changes, promotions = promotions)
    }

    /**
     * The coordinator a club hires when another club promotes its own away:
     * the best of a few, read for his game plan and the club's roster, and as
     * likely as the carousel's to keep the club's scheme.
     */
    internal fun replacement(
        league: League,
        club: Team,
        job: StaffJob,
        t: com.nflsim.engine.tuning.TuningTable.Staff,
        rng: Rng,
        candidate: (CoachRole, String) -> Coach,
    ): Coach {
        val offence = job == StaffJob.OFFENCE
        val fit = fits(league, club.id, if (offence) SchemeCatalog.offensive else SchemeCatalog.defensive, offence)
        val current = if (offence) club.offenseScheme else club.defenseScheme
        return (1..t.coordinatorCandidates).map { candidate(job.role, drawScheme(fit, current, t, rng)) }
            .maxBy { Staffing.worth(it, job) + t.fitWeight * (fit[it.scheme] ?: 0f) + rng.gaussian(0f, t.evalNoise) }
    }

    /** A club with [coach] as its coordinator for [job], running his scheme on that side. */
    internal fun withCoordinator(club: Team, job: StaffJob, coach: Coach): Team =
        if (job == StaffJob.OFFENCE) club.copy(offenseScheme = coach.scheme, staff = club.staff.copy(offCoordinator = coach.id))
        else club.copy(defenseScheme = coach.scheme, staff = club.staff.copy(defCoordinator = coach.id))

    /** An outside candidate, drawn below the league's coaching mean (see the class notes). */
    internal fun newCoach(id: CoachId, role: CoachRole, scheme: String, t: com.nflsim.engine.tuning.TuningTable.Staff, rng: Rng): Coach {
        val (first, last) = NameGenerator.fullName(rng)
        fun stat() = (t.candidateMean + rng.gaussian(0f, t.candidateSpread)).roundToInt().coerceIn(t.candidateFloor, 100)
        return Coach(
            id = id, name = "$first $last", age = 38 + rng.nextInt(20),
            role = role, scheme = scheme,
            ratings = CoachRatings(stat(), stat(), stat(), stat(), stat(), stat()),
            tendencies = com.nflsim.engine.gen.Tendencies.draw(role, scheme, rng.split("tendencies|${id.v}")),
        )
    }

    /**
     * What a club's roster is built for: how well each scheme suits its
     * players on one side of the ball, as z-scores across the catalog -
     * schemes differ in fit by a few hundredths, so raw fit would barely
     * tell them apart.
     */
    internal fun fits(league: League, club: TeamId, schemes: List<Scheme>, offense: Boolean): Map<String, Float> {
        val side = league.players.filter { it.teamId == club && it.position.isOffense == offense }
        val raw = schemes.associate { s ->
            s.id to if (side.isEmpty()) 0.0 else side.map { schemeFit(it, s).toDouble() }.average()
        }
        val mean = raw.values.average()
        val sd = sqrt(raw.values.map { (it - mean) * (it - mean) }.average()).coerceAtLeast(1e-6)
        return raw.mapValues { ((it.value - mean) / sd).toFloat() }
    }

    /** A candidate's scheme: the club's own for continuity, else one the roster suits. */
    internal fun drawScheme(fit: Map<String, Float>, current: String, t: com.nflsim.engine.tuning.TuningTable.Staff, rng: Rng): String {
        if (rng.nextFloat() < t.continuity) return current
        val weights = fit.mapValues { (_, z) -> exp(t.fitZ * z) }
        var r = rng.nextFloat().toDouble() * weights.values.sum()
        for ((id, w) in weights) { r -= w; if (r <= 0) return id }
        return weights.keys.last()
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
