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

/**
 * A coach the user agreed to hire in the spring window who joins when the
 * offseason starts (Staffing.hire): a man his club is letting go, a
 * coordinator promoted from another club, or anyone hired into a job a
 * promotion will open. Until then no other club looks at him. [candidate]
 * carries a fresh candidate, who has no id until he is hired.
 */
@kotlinx.serialization.Serializable
data class PendingHire(
    val role: CoachRole,
    val group: PositionGroup? = null,
    val coach: Int,
    val candidate: Coach? = null,
) {
    val job: StaffJob get() = StaffJob(role, group)
}

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

    /** A staff with [id] in [job]: [VACANT] to open it. */
    internal fun staffWith(staff: Staff, job: StaffJob, id: CoachId): Staff = staff.with(job, id)

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
     * What a man is worth in [job], as the sim uses him (SPEC 4.7): a
     * head coach's development, adjustments, discipline, motivation and
     * evaluation - everything but a game plan, which is his coordinators' -
     * a coordinator's game plan, and a position coach's development.
     */
    fun worth(c: Coach, job: StaffJob): Float = with(c.ratings) {
        when (job.role) {
            CoachRole.HEAD_COACH -> (development + adjustments + discipline + motivation + evaluation) / 5f
            CoachRole.POSITION_COACH -> development.toFloat()
            else -> gameplan.toFloat()
        }
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

    /** This spring's coaching carousel, exactly as the offseason will run it (OffseasonEngine.springCarousel). */
    fun spring(dynasty: Dynasty): CoachingCarousel.Result = OffseasonEngine.springCarousel(dynasty)

    /**
     * The league as this spring's carousel will leave it: who the other
     * clubs let go, promote and hire. The user's window hires from it.
     */
    fun market(dynasty: Dynasty): League = spring(dynasty).league

    /** The man the user agreed to hire for [job], if any. */
    fun pending(dynasty: Dynasty, job: StaffJob): Coach? =
        dynasty.pendingHires.firstOrNull { it.job == job }?.let { it.candidate ?: dynasty.league.coaches[CoachId(it.coach)] }

    /** Where a man in the user's pool comes from, which decides when he can join. */
    sealed interface Source {
        /** A fresh candidate this spring. */
        data object Candidate : Source
        data object OutOfWork : Source
        /** Still working for [club], which lets him go this spring. */
        data class LetGo(val club: com.nflsim.engine.model.Team) : Source
        /** [club]'s coordinator, whom it cannot stop taking a head coaching job (the NFL's anti-tampering policy). */
        data class Promotion(val club: com.nflsim.engine.model.Team, val job: StaffJob) : Source
        /** The user's own coordinator, promoted from within. */
        data class Own(val job: StaffJob) : Source
    }

    fun source(dynasty: Dynasty, coach: Coach, market: League = market(dynasty)): Source {
        if (coach.id.v <= 0) return Source.Candidate
        val (club, job) = dynasty.league.teams.firstNotNullOfOrNull { club -> jobOf(club.staff, coach.id)?.let { club to it } }
            ?: return Source.OutOfWork
        return when {
            club.id == dynasty.userTeamId -> Source.Own(job)
            coach.id !in employed(market) -> Source.LetGo(club)
            else -> Source.Promotion(club, job)
        }
    }

    private fun jobOf(staff: Staff, id: CoachId): StaffJob? = StaffJob.ALL.firstOrNull { holder(staff, it) == id }

    /**
     * Whether [job] on the user's staff can be filled: it is open now, or
     * will be once another club has promoted its man away - and nobody is
     * agreed for it yet.
     */
    fun isVacant(dynasty: Dynasty, job: StaffJob, market: League = market(dynasty)): Boolean =
        pending(dynasty, job) == null && (
            dynasty.league.coaches[holder(dynasty.team.staff, job)] == null ||
                market.coaches[holder(market.team(dynasty.userTeamId).staff, job)] == null)

    /**
     * Why each job on the user's staff opens this spring, in words, for the
     * window: a coordinator another club promotes, a coach who retires.
     */
    fun leavingNotes(dynasty: Dynasty, spring: CoachingCarousel.Result = spring(dynasty)): Map<StaffJob, String> {
        fun club(id: Int) = dynasty.league.teams.firstOrNull { it.id.v == id }?.name
        val promoted = departures(dynasty, spring).mapValues { (_, p) -> "${p.name} leaves to be the ${club(p.team) ?: "new"} head coach." }
        val retired = spring.retirements.filter { it.team == dynasty.userTeam }.associate { it.job to "${it.name} retires." }
        return retired + promoted
    }

    /** The user's coordinators other clubs promote to head coach this spring, by the job each leaves. */
    fun departures(dynasty: Dynasty, spring: CoachingCarousel.Result = spring(dynasty)): Map<StaffJob, Promotion> =
        spring.promotions.filter { it.from == dynasty.userTeam }.associateBy { StaffJob(it.role) }

    /**
     * Who the user's club can hire for [job] this spring, best first: the
     * coaches out of work as the [market] will stand - the men the league
     * lets go this spring among them - and the year's fresh candidates. For
     * head coach, every club's coordinators too, his own among them: a
     * promotion no club can block. Less anyone the club has already agreed
     * to hire.
     */
    fun pool(dynasty: Dynasty, job: StaffJob, market: League = market(dynasty)): List<Coach> {
        val promised = dynasty.pendingHires.mapTo(mutableSetOf()) { CoachId(it.coach) }
        val open = pool(market, dynasty.year, dynasty.userTeamId, job)
        val coordinators = if (job != StaffJob.HEAD) emptyList() else dynasty.league.teams
            .flatMap { listOf(it.staff.offCoordinator, it.staff.defCoordinator) }
            .mapNotNull { dynasty.league.coaches[it] }
            .filter { c -> open.none { it.id == c.id } }
        // A man the carousel hires and lets go again in the same spring exists
        // only in its preview: the offseason makes him again under another id,
        // so he is nobody the user can agree to hire.
        return (open + coordinators)
            .filter { it.id !in promised && (it.id.v <= 0 || it.id in dynasty.league.coaches) }
            .sortedByDescending { worth(it, job) }
    }

    /**
     * Whether an agreement still holds against [market]: its job opens (the
     * carousel leaves it empty on the user's staff) and its man is still
     * there to be had - out of work, or for head coach another club's
     * coordinator.
     */
    private fun holds(p: PendingHire, dynasty: Dynasty, market: League): Boolean {
        if (market.coaches[holder(market.team(dynasty.userTeamId).staff, p.job)] != null) return false
        if (p.candidate != null) return true
        val id = CoachId(p.coach)
        if (id !in market.coaches) return false
        if (id !in employed(market)) return true
        return p.job == StaffJob.HEAD && market.teams.any { it.id != dynasty.userTeamId &&
            (it.staff.offCoordinator == id || it.staff.defCoordinator == id) }
    }

    /** The dynasty less any agreement a staff move has undone ([holds]). */
    private fun tidy(dynasty: Dynasty, market: League = market(dynasty)): Dynasty {
        val kept = dynasty.pendingHires.filter { holds(it, dynasty, market) }
        return if (kept.size == dynasty.pendingHires.size) dynasty else dynasty.copy(pendingHires = kept)
    }

    /**
     * The user's staff as it will stand once the carousel has run and the men
     * he agreed to hire have joined: what the Staff screen shows in the
     * window. A coordinator another club promotes away has left it.
     */
    fun withPending(dynasty: Dynasty, market: League = market(dynasty)): Dynasty {
        var league = market
        for (p in dynasty.pendingHires) {
            if (!holds(p, dynasty, market)) continue
            val coach = p.candidate ?: market.coaches[CoachId(p.coach)] ?: continue
            league = place(league, dynasty.userTeamId, p.job, coach)
        }
        return dynasty.copy(league = league)
    }

    /**
     * The user's club once the offseason's carousel has run ([league] is its
     * result): the men he agreed to hire join - a coordinator promoted from
     * another club leaves it, and it hires his replacement - then the front
     * office fills whatever is still open ([fillVacancies]).
     */
    fun settle(league: League, dynasty: Dynasty): League {
        var out = league
        val user = dynasty.userTeamId
        val rng = SplitMixRng(dynasty.seed + dynasty.year).split("settle")
        for (p in dynasty.pendingHires) {
            if (out.coaches[holder(out.team(user).staff, p.job)] != null) continue
            val coach = p.candidate ?: out.coaches[CoachId(p.coach)] ?: continue
            if (p.candidate == null) {
                val club = out.teams.firstOrNull { jobOf(it.staff, coach.id) != null }
                if (club != null) {
                    val left = jobOf(club.staff, coach.id)!!
                    // Only a promotion takes a man from his club: a coordinator to head coach.
                    if (club.id == user || p.job != StaffJob.HEAD || left !in setOf(StaffJob.OFFENCE, StaffJob.DEFENCE)) continue
                    out = replaceAway(out, club.id, left, rng.split("replace|${coach.id.v}"))
                }
            }
            out = place(out, user, p.job, coach)
        }
        return fillVacancies(out, user, dynasty.year)
    }

    /** A club whose coordinator the user promoted away hires his replacement (CoachingCarousel.replacement). */
    private fun replaceAway(league: League, clubId: TeamId, job: StaffJob, rng: com.nflsim.engine.rng.Rng): League {
        val t = league.tuning.staff
        val club = league.team(clubId)
        var next = (league.coaches.keys.maxOfOrNull { it.v } ?: 0) + 1
        val fill = CoachingCarousel.replacement(league, club, job, t, rng) { role, scheme ->
            CoachingCarousel.newCoach(CoachId(next++), role, scheme, t, rng)
        }
        val after = CoachingCarousel.withCoordinator(club, job, fill)
        val offChanged = after.offenseScheme != club.offenseScheme
        val defChanged = after.defenseScheme != club.defenseScheme
        return league.copy(
            teams = league.teams.map { if (it.id == clubId) after else it },
            coaches = league.coaches + (fill.id to fill),
            players = if (!offChanged && !defChanged) league.players else league.players.map { p ->
                if (p.teamId == clubId && (if (p.position.isOffense) offChanged else defChanged))
                    p.copy(yearsInSystem = 0, yearsWithClub = p.clubYears) else p
            },
        )
    }

    /**
     * The user lets a coach go: his job is open, and he joins the pool. A man
     * the user had agreed to hire is let go of the same way, before he ever
     * joins.
     */
    fun fire(dynasty: Dynasty, job: StaffJob): Dynasty {
        require(isOpen(dynasty)) { "a club changes its staff between the season and the offseason" }
        if (pending(dynasty, job) != null) return tidy(dynasty.copy(pendingHires = dynasty.pendingHires.filterNot { it.job == job }))
        val league = dynasty.league
        val team = dynasty.team
        val id = holder(team.staff, job)
        val coach = requireNotNull(league.coaches[id]) { "nobody holds the ${job.label} job" }
        // A man let go is one more out of work: the spring can change, and an agreement with it.
        return tidy(dynasty.copy(league = league.copy(
            teams = league.teams.map { if (it.id == team.id) it.copy(staff = it.staff.with(job, VACANT)) else it },
            coaches = league.coaches + (id to coach.copy(hotSeat = 0, contractYearsLeft = 0)),
        )))
    }

    /**
     * The user hires [candidate], from this spring's [pool], into a job that
     * is open or will be ([isVacant]). He joins now if he is free and the job
     * is open now; a man his club is letting go, a coordinator promoted from
     * another club, or anyone hired into a job another club's promotion will
     * open joins when the offseason starts. The user's own coordinator,
     * promoted to head coach, moves up now and leaves his old job open.
     */
    fun hire(dynasty: Dynasty, job: StaffJob, candidate: Coach, market: League = market(dynasty)): Dynasty {
        require(isOpen(dynasty)) { "a club changes its staff between the season and the offseason" }
        require(isVacant(dynasty, job, market)) { "the ${job.label} job is not open" }
        require(pool(dynasty, job, market).any { it.id == candidate.id && it.name == candidate.name }) {
            "${candidate.name} is not available for the ${job.label} job"
        }
        val openNow = dynasty.league.coaches[holder(dynasty.team.staff, job)] == null
        fun agreed(): Dynasty {
            val p = PendingHire(job.role, job.group, candidate.id.v, candidate.takeIf { it.id.v <= 0 })
            val next = dynasty.copy(pendingHires = dynasty.pendingHires + p)
            // Taking a man another club wanted changes whom it hires instead,
            // and that can keep at home the coordinator whose job this was.
            val after = market(next)
            require(holds(p, next, after)) {
                "Hiring ${candidate.name} changes whom the other clubs hire, and the ${job.label.lowercase()} job no longer opens."
            }
            return tidy(next, after)
        }
        return when (val from = source(dynasty, candidate, market)) {
            is Source.Own -> {
                require(openNow) { "the ${job.label} job is not open" }
                val league = dynasty.league
                val freed = league.copy(teams = league.teams.map {
                    if (it.id == dynasty.userTeamId) it.copy(staff = it.staff.with(from.job, VACANT)) else it
                })
                tidy(dynasty.copy(league = place(freed, dynasty.userTeamId, job, league.coach(candidate.id))))
            }
            is Source.LetGo, is Source.Promotion -> agreed()
            // A man out of work now joins as he is, not as the spring will have aged him.
            else -> if (openNow) tidy(dynasty.copy(league = place(dynasty.league, dynasty.userTeamId, job,
                dynasty.league.coaches[candidate.id] ?: candidate))) else agreed()
        }
    }

    /**
     * Puts [coach] in [job] on a new contract. A man moving to a new role
     * takes that role's levers, drawn from the league's seed and his id so
     * the window's view and the offseason agree. A coordinator brings his
     * scheme, and the players on his side start learning it if it is new to
     * them.
     */
    private fun place(league: League, team: TeamId, job: StaffJob, coach: Coach): League {
        val t = league.tuning.staff
        val id = if (coach.id.v > 0) coach.id else CoachId((league.coaches.keys.maxOfOrNull { it.v } ?: 0) + 1)
        val tendencies = if (coach.role == job.role) coach.tendencies
            else Tendencies.draw(job.role, coach.scheme, SplitMixRng(league.seed).split("role|${id.v}|${job.role}"))
        val hired = coach.copy(id = id, role = job.role, hotSeat = 0, contractYearsLeft = t.newContractYears, tendencies = tendencies)
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
