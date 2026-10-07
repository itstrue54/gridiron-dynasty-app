package com.nflsim.engine.offseason

import com.nflsim.engine.gen.NameGenerator
import com.nflsim.engine.gen.StaffGenerator
import com.nflsim.engine.gen.Tendencies
import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.CoachRatings
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.GmProfile
import com.nflsim.engine.model.League
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.Staff
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyPhase
import kotlin.math.roundToInt

/** One job on a club's staff: a role, and for a position coach the group he coaches. */
data class StaffJob(val role: CoachRole, val group: PositionGroup? = null) {
    init {
        require((role == CoachRole.POSITION_COACH) == (group != null)) { "a position coach, and only he, coaches a group" }
    }

    val label: String get() = when (role) {
        CoachRole.HEAD_COACH -> "Head coach"
        CoachRole.OFFENSIVE_COORDINATOR -> "Offensive coordinator"
        CoachRole.DEFENSIVE_COORDINATOR -> "Defensive coordinator"
        CoachRole.SPECIAL_TEAMS_COORDINATOR -> "Special teams coordinator"
        CoachRole.POSITION_COACH -> "${group!!.name} coach"
    }

    companion object {
        val HEAD = StaffJob(CoachRole.HEAD_COACH)
        val OFFENCE = StaffJob(CoachRole.OFFENSIVE_COORDINATOR)
        val DEFENCE = StaffJob(CoachRole.DEFENSIVE_COORDINATOR)
        val SPECIAL = StaffJob(CoachRole.SPECIAL_TEAMS_COORDINATOR)
        val ALL: List<StaffJob> = listOf(HEAD, OFFENCE, DEFENCE, SPECIAL) +
            PositionGroup.entries.map { StaffJob(CoachRole.POSITION_COACH, it) }
    }
}

/**
 * The user's club hires and fires its own staff (SPEC 4.7), in the spring
 * window between the last playoff game and the start of the offseason.
 *
 * A job is filled from a pool: the coaches out of work in that role - the
 * league's fired head coaches and the coordinators who left with them, and
 * anyone the user lets go - and a handful of fresh candidates, drawn from the
 * league's seed and the year, so the same spring always offers the same men.
 * Fresh candidates are drawn the way the carousel draws its outside ones,
 * below the league's coaching mean, because the user is choosing the best of
 * several. They carry no id until they are hired: [Coach.id] is 0 or below.
 *
 * Firing a coordinator does not change the club's scheme; hiring one does,
 * and the players on his side of the ball start learning it again. Whatever
 * the user leaves open when the offseason starts, the front office fills
 * ([fillVacancies]). The general manager comes from a pool the same way, and
 * the league's owners hire from it too (GmCarousel).
 */
object Staffing {

    /** The id a vacant job holds: the same nobody as [Staff.UNASSIGNED]. */
    val VACANT = CoachId(0)

    fun holder(staff: Staff, job: StaffJob): CoachId = when (job.role) {
        CoachRole.HEAD_COACH -> staff.headCoach
        CoachRole.OFFENSIVE_COORDINATOR -> staff.offCoordinator
        CoachRole.DEFENSIVE_COORDINATOR -> staff.defCoordinator
        CoachRole.SPECIAL_TEAMS_COORDINATOR -> staff.stCoordinator
        CoachRole.POSITION_COACH -> staff.positionCoaches[job.group] ?: VACANT
    }

    private fun Staff.with(job: StaffJob, id: CoachId): Staff = when (job.role) {
        CoachRole.HEAD_COACH -> copy(headCoach = id)
        CoachRole.OFFENSIVE_COORDINATOR -> copy(offCoordinator = id)
        CoachRole.DEFENSIVE_COORDINATOR -> copy(defCoordinator = id)
        CoachRole.SPECIAL_TEAMS_COORDINATOR -> copy(stCoordinator = id)
        CoachRole.POSITION_COACH -> copy(positionCoaches = positionCoaches + (job.group!! to id))
    }

    /** The jobs a club has nobody in. */
    fun vacancies(league: League, team: TeamId): List<StaffJob> {
        val staff = league.team(team).staff
        return StaffJob.ALL.filter { league.coaches[holder(staff, it)] == null }
    }

    /** Whether the user's club can change its staff: after the season, before the offseason runs. */
    fun isOpen(dynasty: Dynasty): Boolean = dynasty.phase == DynastyPhase.OFFSEASON

    /** A coach's six ratings, averaged: how good the front office reads him overall. */
    fun quality(c: Coach): Float = with(c.ratings) {
        (development + gameplan + adjustments + discipline + motivation + evaluation) / 6f
    }

    /**
     * What a man is worth in [job], as the sim uses him: a head coach's
     * development, in-game adjustments and discipline (his side's flags,
     * SPEC 5.8), a position coach's development,
     * and for the rest - whose scheme and tendencies are what they bring -
     * his ratings overall.
     */
    fun worth(c: Coach, job: StaffJob): Float = when (job.role) {
        CoachRole.HEAD_COACH -> (c.ratings.development + c.ratings.adjustments + c.ratings.discipline) / 3f
        CoachRole.POSITION_COACH -> c.ratings.development.toFloat()
        else -> quality(c)
    }

    private fun employed(league: League): Set<CoachId> = league.teams.flatMapTo(mutableSetOf()) { club ->
        listOf(club.staff.headCoach, club.staff.offCoordinator, club.staff.defCoordinator, club.staff.stCoordinator) +
            club.staff.positionCoaches.values
    }

    /**
     * Who a club can hire for [job] this spring, best first by [worth]: the
     * coaches out of work in that role, and the year's fresh candidates.
     */
    fun pool(league: League, year: Int, team: TeamId, job: StaffJob): List<Coach> {
        val working = employed(league)
        val taken = league.coaches.values.mapTo(mutableSetOf()) { it.name }
        val outOfWork = league.coaches.values.filter { it.id !in working && it.role == job.role }
        val fresh = fresh(league, year, team, job).filter { it.name !in taken }
        return (outOfWork + fresh).sortedByDescending { worth(it, job) }
    }

    /**
     * The year's outside candidates for one job. A head coach or coordinator
     * may run any scheme on his side of the ball (a head coach, either side);
     * a special teams or position coach works in the club's.
     */
    private fun fresh(league: League, year: Int, team: TeamId, job: StaffJob): List<Coach> {
        val t = league.tuning.staff
        val club = league.team(team)
        val rng = SplitMixRng(league.seed).split("staff-pool|$year|${job.role}|${job.group}")
        return (1..t.poolCandidates).map { i ->
            val scheme = when (job.role) {
                CoachRole.HEAD_COACH -> SchemeCatalog.all[rng.nextInt(SchemeCatalog.all.size)].id
                CoachRole.OFFENSIVE_COORDINATOR -> SchemeCatalog.offensive[rng.nextInt(SchemeCatalog.offensive.size)].id
                CoachRole.DEFENSIVE_COORDINATOR -> SchemeCatalog.defensive[rng.nextInt(SchemeCatalog.defensive.size)].id
                CoachRole.SPECIAL_TEAMS_COORDINATOR -> club.offenseScheme
                CoachRole.POSITION_COACH ->
                    if (job.group in StaffGenerator.OFFENSIVE_GROUPS) club.offenseScheme else club.defenseScheme
            }
            val (first, last) = NameGenerator.fullName(rng)
            fun stat() = (t.candidateMean + rng.gaussian(0f, t.candidateSpread)).roundToInt().coerceIn(t.candidateFloor, 100)
            Coach(
                id = CoachId(-i), name = "$first $last", age = 38 + rng.nextInt(20),
                role = job.role, scheme = scheme,
                ratings = CoachRatings(stat(), stat(), stat(), stat(), stat(), stat()),
                contractYearsLeft = 0,
                tendencies = Tendencies.draw(job.role, scheme, rng.split("tendencies|$i")),
            )
        }
    }

    /** The user lets a coach go. His job is open, and he joins the pool. */
    fun fire(dynasty: Dynasty, job: StaffJob): Dynasty {
        require(isOpen(dynasty)) { "a club changes its staff between the season and the offseason" }
        val league = dynasty.league
        val team = dynasty.team
        val id = holder(team.staff, job)
        val coach = requireNotNull(league.coaches[id]) { "nobody holds the ${job.label} job" }
        return dynasty.copy(league = league.copy(
            teams = league.teams.map { if (it.id == team.id) it.copy(staff = it.staff.with(job, VACANT)) else it },
            coaches = league.coaches + (id to coach.copy(hotSeat = 0, contractYearsLeft = 0)),
        ))
    }

    /** The user hires [candidate], from this spring's [pool], into an open job. */
    fun hire(dynasty: Dynasty, job: StaffJob, candidate: Coach): Dynasty {
        require(isOpen(dynasty)) { "a club changes its staff between the season and the offseason" }
        require(dynasty.league.coaches[holder(dynasty.team.staff, job)] == null) { "the ${job.label} job is not open" }
        require(pool(dynasty.league, dynasty.year, dynasty.userTeamId, job).any { it.id == candidate.id && it.name == candidate.name }) {
            "${candidate.name} is not available for the ${job.label} job"
        }
        return dynasty.copy(league = place(dynasty.league, dynasty.userTeamId, job, candidate))
    }

    /**
     * Puts [coach] in [job] on a new contract. A coordinator brings his
     * scheme, and the players on his side start learning it if it is new to
     * them.
     */
    private fun place(league: League, team: TeamId, job: StaffJob, coach: Coach): League {
        val t = league.tuning.staff
        val id = if (coach.id.v > 0) coach.id else CoachId((league.coaches.keys.maxOfOrNull { it.v } ?: 0) + 1)
        val hired = coach.copy(id = id, role = job.role, hotSeat = 0, contractYearsLeft = t.newContractYears)
        val club = league.team(team)
        val offence = if (job == StaffJob.OFFENCE) hired.scheme else club.offenseScheme
        val defence = if (job == StaffJob.DEFENCE) hired.scheme else club.defenseScheme
        val offChanged = offence != club.offenseScheme
        val defChanged = defence != club.defenseScheme
        return league.copy(
            teams = league.teams.map {
                if (it.id == team) it.copy(offenseScheme = offence, defenseScheme = defence, staff = it.staff.with(job, id)) else it
            },
            coaches = league.coaches + (id to hired),
            players = if (!offChanged && !defChanged) league.players else league.players.map { p ->
                if (p.teamId == team && (if (p.position.isOffense) offChanged else defChanged))
                    p.copy(yearsInSystem = 0, yearsWithClub = p.clubYears) else p
            },
        )
    }

    /**
     * What the front office does with the jobs the user left open when the
     * offseason starts: hires the best man in the pool, and for a coordinator
     * the best who runs the club's own scheme if there is one, so nobody's
     * scheme changes unless the user chose it. An open general manager's
     * chair goes to the first name on the owner's list.
     */
    fun fillVacancies(league: League, team: TeamId, year: Int): League {
        var out = league
        for (job in vacancies(league, team)) {
            val pool = pool(out, year, team, job)
            val current = when (job) {
                StaffJob.OFFENCE -> out.team(team).offenseScheme
                StaffJob.DEFENCE -> out.team(team).defenseScheme
                else -> null
            }
            val chosen = pool.firstOrNull { current != null && it.scheme == current } ?: pool.firstOrNull() ?: continue
            out = place(out, team, job, chosen)
        }
        if (out.team(team).gm.name.isBlank()) {
            gmPool(out, year).firstOrNull()?.let { out = placeGm(out, team, it, year + 1) }
        }
        return out
    }

    // ---- the general manager ----------------------------------------

    /** Who a club can hire as general manager this spring: those out of work, newest first, then the year's candidates. */
    fun gmPool(league: League, year: Int): List<GmProfile> {
        val taken = (league.teams.map { it.gm.name } + league.gmPool.map { it.name }).toSet()
        return league.gmPool + freshGms(league, year, league.tuning.staff.poolCandidates, "gm-pool|$year")
            .filter { it.name !in taken }
    }

    /** Fresh general managers, drawn as the league's first ones were. */
    internal fun freshGms(league: League, year: Int, count: Int, label: String): List<GmProfile> {
        val rng = SplitMixRng(league.seed).split(label)
        return (1..count).map { i ->
            val (first, last) = NameGenerator.fullName(rng.split("name|$i"))
            GmProfile.generate(rng.split("style|$i")).copy(name = "$first $last", since = year + 1)
        }
    }

    /** The user lets his general manager go; he joins the pool, and the front office runs on no one's style until the chair is filled. */
    fun fireGm(dynasty: Dynasty): Dynasty {
        require(isOpen(dynasty)) { "a club changes its staff between the season and the offseason" }
        val gm = dynasty.team.gm
        require(gm.name.isNotBlank()) { "the club has no general manager" }
        val league = dynasty.league
        return dynasty.copy(league = league.copy(
            teams = league.teams.map { if (it.id == dynasty.userTeamId) it.copy(gm = GmProfile()) else it },
            gmPool = (listOf(gm) + league.gmPool).take(league.tuning.staff.gmPoolLimit),
        ))
    }

    fun hireGm(dynasty: Dynasty, gm: GmProfile): Dynasty {
        require(isOpen(dynasty)) { "a club changes its staff between the season and the offseason" }
        require(dynasty.team.gm.name.isBlank()) { "the general manager's chair is not open" }
        require(gm in gmPool(dynasty.league, dynasty.year)) { "${gm.name} is not available" }
        return dynasty.copy(league = placeGm(dynasty.league, dynasty.userTeamId, gm, dynasty.year + 1))
    }

    private fun placeGm(league: League, team: TeamId, gm: GmProfile, season: Int): League = league.copy(
        teams = league.teams.map { if (it.id == team) it.copy(gm = gm.copy(since = season)) else it },
        gmPool = league.gmPool.filterNot { it.name == gm.name },
    )
}
