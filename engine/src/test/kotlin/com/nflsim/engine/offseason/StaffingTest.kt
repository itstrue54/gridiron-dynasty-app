package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.GmProfile
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyEngine
import com.nflsim.engine.season.DynastyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC 4.7: the user's club hires and fires its own staff, from a pool, in the spring. */
class StaffingTest {

    private val base = LeagueGenerator.generate(2026, 5L)
    private val user = base.teams.first().id

    /** A dynasty whose season has just ended. */
    private fun spring(): Dynasty =
        DynastyEngine.start(base, 2026, 5L, user).copy(phase = DynastyPhase.OFFSEASON)

    @Test
    fun `the staff can only change between the season and the offseason`() {
        val inSeason = DynastyEngine.start(base, 2026, 5L, user)
        assertFalse(Staffing.isOpen(inSeason))
        assertFailsWith<IllegalArgumentException> { Staffing.fire(inSeason, StaffJob.HEAD) }
        assertFailsWith<IllegalArgumentException> { Staffing.fireGm(inSeason) }
        assertTrue(Staffing.isOpen(spring()))
    }

    @Test
    fun `the pool is the same every time, best first, and has fresh candidates in it`() {
        val a = Staffing.pool(base, 2026, user, StaffJob.OFFENCE)
        val b = Staffing.pool(base, 2026, user, StaffJob.OFFENCE)
        assertEquals(a, b)
        assertEquals(base.tuning.staff.poolCandidates, a.count { it.id.v <= 0 }, "a generated league has nobody out of work yet")
        assertTrue(a.all { it.role == CoachRole.OFFENSIVE_COORDINATOR })
        assertTrue(a.all { SchemeCatalog[it.scheme].side == com.nflsim.engine.ratings.SchemeSide.OFFENSE })
        assertEquals(a.sortedByDescending { Staffing.worth(it, StaffJob.OFFENCE) }, a)
        val heads = Staffing.pool(base, 2026, user, StaffJob.HEAD)
        assertEquals(heads.sortedByDescending { with(it.ratings) { development + adjustments + discipline + motivation + evaluation } }, heads,
            "a head coach is worth everything but a game plan")
        assertEquals(a.sortedByDescending { it.ratings.gameplan }, a, "a coordinator is worth his game plan")
        assertNotEquals(a.map { it.name }, Staffing.pool(base, 2027, user, StaffJob.OFFENCE).map { it.name }, "next spring brings new names")
    }

    @Test
    fun `a fired coach leaves his job open and joins the pool`() {
        val d = spring()
        val head = d.team.staff.headCoach
        val fired = Staffing.fire(d, StaffJob.HEAD)
        assertEquals(Staffing.VACANT, fired.team.staff.headCoach)
        assertEquals(listOf(StaffJob.HEAD), Staffing.vacancies(fired.league, user))
        val pool = Staffing.pool(fired.league, 2026, user, StaffJob.HEAD)
        assertTrue(pool.any { it.id == head }, "he is out of work, and can be hired again")
        assertEquals(0, fired.league.coach(head).contractYearsLeft)
        assertFailsWith<IllegalArgumentException> { Staffing.fire(fired, StaffJob.HEAD) }
    }

    @Test
    fun `a new coordinator brings his scheme, and his side of the ball learns it again`() {
        val d = Staffing.fire(spring(), StaffJob.OFFENCE)
        val offence = d.team.offenseScheme
        assertEquals(offence, d.team.offenseScheme, "firing him changes nothing until someone is hired")
        val newcomer = Staffing.pool(d.league, 2026, user, StaffJob.OFFENCE).first { it.scheme != offence }
        val hired = Staffing.hire(d, StaffJob.OFFENCE, newcomer)
        val oc = hired.league.coach(hired.team.staff.offCoordinator)
        assertEquals(newcomer.name, oc.name)
        assertTrue(oc.id.v > 0, "he has an id of his own now")
        assertEquals(base.tuning.staff.newContractYears, oc.contractYearsLeft)
        assertEquals(newcomer.scheme, hired.team.offenseScheme)
        assertEquals(d.team.defenseScheme, hired.team.defenseScheme)
        val roster = hired.league.players.filter { it.teamId == user }
        assertTrue(roster.filter { it.position.isOffense }.all { it.yearsInSystem == 0 })
        val before = d.league.players.filter { it.teamId == user && !it.position.isOffense }.associate { it.id to it.yearsInSystem }
        assertTrue(roster.filter { !it.position.isOffense }.all { it.yearsInSystem == before[it.id] }, "the defence is untouched")
        assertFalse(Staffing.pool(hired.league, 2026, user, StaffJob.OFFENCE).any { it.name == newcomer.name }, "he is not in the pool twice")
        assertTrue(hired.league.teams.filter { it.id != user }.all { it == d.league.team(it.id) }, "no other club changes")
    }

    @Test
    fun `a job is filled only when it is open, and only from the pool`() {
        // A real season, so the spring's carousel lets go only the clubs that lost.
        val d = played
        val candidate = Staffing.pool(d.league, 2026, user, StaffJob.HEAD).first()
        assertFailsWith<IllegalArgumentException> { Staffing.hire(d, StaffJob.HEAD, candidate) }
        val open = Staffing.fire(d, StaffJob.HEAD)
        val stranger = candidate.copy(id = CoachId(-99), name = "Nobody Atall")
        assertFailsWith<IllegalArgumentException> { Staffing.hire(open, StaffJob.HEAD, stranger) }
        // A head coach his club keeps this spring - one it lets go can be
        // hired, to join when the offseason starts.
        val market = Staffing.market(open)
        val kept = open.league.teams.filter { it.id != user }
            .first { market.team(it.id).staff.headCoach == it.staff.headCoach }
        assertFailsWith<IllegalArgumentException> { Staffing.hire(open, StaffJob.HEAD, open.league.coach(kept.staff.headCoach)) }
    }

    @Test
    fun `position coaches are hired by group`() {
        val job = StaffJob(CoachRole.POSITION_COACH, com.nflsim.engine.model.PositionGroup.CB)
        val d = Staffing.fire(spring(), job)
        assertEquals(Staffing.VACANT, d.team.staff.positionCoaches[com.nflsim.engine.model.PositionGroup.CB])
        val pick = Staffing.pool(d.league, 2026, user, job).first()
        val hired = Staffing.hire(d, job, pick)
        assertEquals(pick.name, hired.league.coach(hired.team.staff.positionCoaches.getValue(job.group!!)).name)
        assertEquals(d.team.offenseScheme to d.team.defenseScheme, hired.team.offenseScheme to hired.team.defenseScheme)
    }

    @Test
    fun `the front office fills what the user left open, keeping the club's schemes`() {
        var d = spring()
        for (job in listOf(StaffJob.HEAD, StaffJob.OFFENCE, StaffJob.DEFENCE)) d = Staffing.fire(d, job)
        d = Staffing.fireGm(d)
        val filled = Staffing.fillVacancies(d.league, user, 2026)
        assertTrue(Staffing.vacancies(filled, user).isEmpty())
        assertTrue(filled.team(user).gm.name.isNotBlank())
        assertEquals(2027, filled.team(user).gm.since)
        assertEquals(d.team.offenseScheme, filled.team(user).offenseScheme)
        assertEquals(d.team.defenseScheme, filled.team(user).defenseScheme)
        assertEquals(base, Staffing.fillVacancies(base, user, 2026), "a full staff is left alone")
    }

    @Test
    fun `a general manager is fired into the pool and hired from it`() {
        val d = spring()
        val old = d.team.gm
        val fired = Staffing.fireGm(d)
        assertTrue(fired.team.gm.name.isBlank())
        val pool = Staffing.gmPool(fired.league, 2026)
        assertEquals(old, pool.first(), "the newest out of work first")
        assertTrue(pool.none { gm -> fired.league.teams.any { it.gm.name == gm.name && gm.name.isNotBlank() } })
        val pick = pool.last()
        val hired = Staffing.hireGm(fired, pick)
        assertEquals(pick.name, hired.team.gm.name)
        assertEquals(pick.aggression, hired.team.gm.aggression)
        assertEquals(2027, hired.team.gm.since)
        assertFailsWith<IllegalArgumentException> { Staffing.hireGm(hired, pool.first()) }
        assertFailsWith<IllegalArgumentException> { Staffing.hireGm(fired, GmProfile(name = "Nobody Atall")) }
        assertEquals(listOf(old), hired.league.gmPool)
    }

    @Test
    fun `the user's coach is never fired by the carousel, but his seat still warms`() {
        val league = base.copy(coaches = base.coaches.mapValues { (id, c) ->
            if (id == base.team(user).staff.headCoach) c.copy(hotSeat = 90, contractYearsLeft = 1) else c
        })
        val head = league.team(user).staff.headCoach
        val r = CoachingCarousel.run(league, winPct = { 0f }, playoffClubs = emptySet(),
            previousWinPct = league.teams.associate { it.id.v to 1f }, rng = SplitMixRng(1L), userTeam = user)
        assertEquals(head, r.league.team(user).staff.headCoach)
        assertTrue(r.changes.none { it.team == user.v })
        assertTrue(r.changes.isNotEmpty(), "everyone else's was")
        val after = r.league.coach(head)
        assertTrue(after.hotSeat >= CoachingCarousel.fireBar(league.team(user), league.tuning.staff), "the Staff screen still warns")
        assertEquals(league.tuning.staff.extensionYears, after.contractYearsLeft, "a contract that ran out is extended")
    }

    @Test
    fun `owners fire general managers after two losing seasons, but never the user's`() {
        val t = base.tuning.staff
        val losers = base.teams.take(8).map { it.id }.toSet()
        val r = GmCarousel.run(
            base, winPct = { if (it in losers) 0.2f else 0.6f },
            previousWinPct = base.teams.associate { it.id.v to if (it.id in losers) 0.3f else 0.6f },
            userTeam = user, newYear = 2027, rng = SplitMixRng(1L),
        )
        assertEquals((losers - user).map { it.v }.sorted(), r.changes.map { it.team }.sorted())
        r.changes.forEach { c ->
            val gm = r.league.team(TeamId(c.team)).gm
            assertEquals(c.hired, gm.name)
            assertEquals(2027, gm.since)
        }
        assertEquals(base.team(user).gm, r.league.team(user).gm)
        // The fired join the pool, newest first, unless a club further down
        // the list hired one straight back.
        val working = r.league.teams.map { it.gm.name }.toSet()
        assertEquals(r.changes.map { it.fired }.reversed().filter { it !in working }, r.league.gmPool.map { it.name })
        assertEquals(r.league.teams.size, r.league.teams.map { it.gm.name }.toSet().size, "no two clubs share a general manager")

        // A man hired this spring gets his seasons before he is judged.
        val again = GmCarousel.run(
            r.league, winPct = { if (it in losers) 0.2f else 0.6f },
            previousWinPct = base.teams.associate { it.id.v to if (it.id in losers) 0.3f else 0.6f },
            userTeam = user, newYear = 2027 + t.gmTenure - 1, rng = SplitMixRng(2L),
        )
        assertTrue(again.changes.isEmpty())
    }

    /** A season played to its end, once for the tests that need a real spring. */
    private val played: Dynasty by lazy {
        var d = DynastyEngine.start(base, 2026, 5L, user)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d
    }

    @Test
    fun `the pool has the coaches the league lets go this spring, and hiring one waits for the offseason`() {
        val spring = OffseasonEngine.springCarousel(played)
        assertTrue(spring.changes.isNotEmpty(), "somebody is fired this spring")
        val open = Staffing.fire(played, StaffJob.HEAD)
        val pool = Staffing.pool(open, StaffJob.HEAD)
        val letGo = pool.filter { Staffing.source(open, it) is Staffing.Source.LetGo }
        assertTrue(pool.all { it.id.v <= 0 || it.id in open.league.coaches },
            "nobody the carousel only makes this spring: the offseason makes him again under another id")
        // His coordinators, let go with him, are head coaching candidates too.
        val firedHeads = spring.changes.map { it.fired }.filter { name -> name in pool.map { it.name } }
        assertTrue(firedHeads.isNotEmpty())
        assertTrue(letGo.map { it.name }.containsAll(firedHeads), "every head coach let go and not hired straight back is in the pool, marked by his club")
        assertTrue(letGo.isNotEmpty())

        val pick = letGo.first()
        val agreed = Staffing.hire(open, StaffJob.HEAD, pick)
        assertEquals(Staffing.VACANT, agreed.team.staff.headCoach, "he still works for his club until it lets him go")
        assertEquals(pick.name, Staffing.pending(agreed, StaffJob.HEAD)?.name)
        assertEquals(pick.name, agreed.league.coach(Staffing.withPending(agreed).team.staff.headCoach).name, "the Staff screen shows him")
        assertTrue(Staffing.pool(agreed, StaffJob.HEAD).none { it.id == pick.id }, "he is not offered twice")
        assertFailsWith<IllegalArgumentException> { Staffing.hire(agreed, StaffJob.HEAD, pool.last()) }

        // Changing his mind before the offseason undoes it.
        assertTrue(Staffing.fire(agreed, StaffJob.HEAD).pendingHires.isEmpty())

        // And the offseason makes it so: what the window showed is what happens.
        val (next, report) = OffseasonEngine.run(agreed)
        assertEquals(pick.id, next.league.team(user).staff.headCoach)
        assertTrue(next.pendingHires.isEmpty(), "a finished offseason leaves no agreement behind")
        assertEquals(OffseasonEngine.springCarousel(agreed).changes, report.coachingChanges,
            "the carousel the window shows, after the user's moves, is the offseason's")
        assertEquals(spring.changes.map { it.fired }, report.coachingChanges.map { it.fired },
            "who is let go never depends on what the user does")
        assertTrue(next.league.teams.count { it.staff.headCoach == pick.id } == 1, "no other club took him")
    }

    /** The played spring with every club looking at every coordinator, and the user's offensive coordinator the obvious pick. */
    private fun coveted(): Dynasty {
        val l = played.league
        val oc = l.coach(l.team(user).staff.offCoordinator)
        return played.copy(league = l.copy(
            tuning = l.tuning.copy(staff = l.tuning.staff.copy(poachLook = 100)),
            coaches = l.coaches + (oc.id to oc.copy(ratings = com.nflsim.engine.model.CoachRatings(100, 100, 100, 100, 100, 100))),
        ))
    }

    @Test
    fun `other clubs can promote the user's coordinator, and the window says so and lets him line up a replacement`() {
        val d = coveted()
        val oc = d.league.coach(d.team.staff.offCoordinator)
        val spring = Staffing.spring(d)
        assertEquals(oc.name, Staffing.departures(d, spring)[StaffJob.OFFENCE]?.name, "a club cannot block a promotion")
        assertEquals(Staffing.VACANT, spring.league.team(user).staff.offCoordinator, "he has left in the window's view")
        assertTrue(Staffing.isVacant(d, StaffJob.OFFENCE, spring.league))
        assertEquals(Staffing.VACANT, Staffing.withPending(d, spring.league).team.staff.offCoordinator)

        // A fresh candidate hired for the job his promotion opens joins when the offseason starts.
        val pick = Staffing.pool(d, StaffJob.OFFENCE, spring.league).first { it.id.v <= 0 }
        val agreed = Staffing.hire(d, StaffJob.OFFENCE, pick, spring.league)
        assertEquals(oc.id, agreed.team.staff.offCoordinator, "he is still the user's until the offseason")
        assertEquals(pick.name, Staffing.pending(agreed, StaffJob.OFFENCE)?.name)
        val (next, report) = OffseasonEngine.run(agreed)
        assertEquals(pick.name, next.league.coach(next.league.team(user).staff.offCoordinator).name)
        val promotion = report.promotions.single { it.from == user.v && it.role == CoachRole.OFFENSIVE_COORDINATOR }
        assertEquals(oc.id, next.league.team(TeamId(promotion.team)).staff.headCoach)
        assertEquals(CoachRole.HEAD_COACH, next.league.coach(oc.id).role)
    }

    @Test
    fun `whoever the user agrees to hire for a job a promotion opens, the job opens and he joins`() {
        val d = coveted()
        val market = Staffing.market(d)
        // Every man in the pool, the ones other clubs might want included.
        for (pick in Staffing.pool(d, StaffJob.OFFENCE, market).take(6)) {
            val agreed = try { Staffing.hire(d, StaffJob.OFFENCE, pick, market) } catch (e: IllegalArgumentException) { continue }
            assertEquals(Staffing.VACANT, Staffing.market(agreed).team(user).staff.offCoordinator, "the job still opens with ${pick.name} agreed")
            val (next, _) = OffseasonEngine.run(agreed)
            assertEquals(pick.name, next.league.coach(next.league.team(user).staff.offCoordinator).name)
        }
    }

    @Test
    fun `a coach retiring from the user's staff is shown in the window, and his replacement joins`() {
        val dc = played.league.coach(played.team.staff.defCoordinator)
        val d = played.copy(league = played.league.copy(coaches = played.league.coaches + (dc.id to dc.copy(age = 80))))
        val spring = Staffing.spring(d)
        assertEquals("${dc.name} retires.", Staffing.leavingNotes(d, spring)[StaffJob.DEFENCE])
        val pick = Staffing.pool(d, StaffJob.DEFENCE, spring.league).first()
        val agreed = Staffing.hire(d, StaffJob.DEFENCE, pick, spring.league)
        val (next, report) = OffseasonEngine.run(agreed)
        assertEquals(pick.name, next.league.coach(next.league.team(user).staff.defCoordinator).name)
        assertTrue(report.coachRetirements.any { it.name == dc.name && it.team == user.v })
    }

    @Test
    fun `the user can promote another club's coordinator to head coach, but not hire one across`() {
        val d = Staffing.fire(played, StaffJob.HEAD)
        val market = Staffing.market(d)
        val other = d.league.teams.first { it.id != user && market.team(it.id).staff.offCoordinator == it.staff.offCoordinator }
        val target = d.league.coach(other.staff.offCoordinator)
        val pool = Staffing.pool(d, StaffJob.HEAD, market)
        assertTrue(pool.any { it.id == target.id }, "every club's coordinators are head coaching candidates")
        assertEquals(Staffing.Source.Promotion(other, StaffJob.OFFENCE), Staffing.source(d, target, market))

        val agreed = Staffing.hire(d, StaffJob.HEAD, target, market)
        assertEquals(Staffing.VACANT, agreed.team.staff.headCoach)
        val (next, _) = OffseasonEngine.run(agreed)
        val head = next.league.coach(next.league.team(user).staff.headCoach)
        assertEquals(target.id, head.id)
        assertEquals(CoachRole.HEAD_COACH, head.role)
        assertTrue(head.tendencies.fourthDownAggression != null, "a head coach decides fourth downs")
        assertNotEquals(target.id, next.league.team(other.id).staff.offCoordinator, "his old club hired his replacement")
        assertTrue(next.league.coaches.containsKey(next.league.team(other.id).staff.offCoordinator))

        // A lateral move is his club's to refuse: no club's coordinator is offered as a coordinator.
        val firedOc = Staffing.fire(played, StaffJob.OFFENCE)
        val stillCoordinating = Staffing.market(firedOc).teams.filter { it.id != user }.map { it.staff.offCoordinator }.toSet()
        assertTrue(Staffing.pool(firedOc, StaffJob.OFFENCE).none { it.id in stillCoordinating })
    }

    @Test
    fun `the user can promote his own coordinator to head coach`() {
        val d = Staffing.fire(played, StaffJob.HEAD)
        val oc = d.league.coach(d.team.staff.offCoordinator)
        assertEquals(Staffing.Source.Own(StaffJob.OFFENCE), Staffing.source(d, oc))
        val promoted = Staffing.hire(d, StaffJob.HEAD, oc)
        assertEquals(oc.id, promoted.team.staff.headCoach, "from within, at once")
        assertEquals(CoachRole.HEAD_COACH, promoted.league.coach(oc.id).role)
        assertEquals(Staffing.VACANT, promoted.team.staff.offCoordinator, "and his old job is open")
    }

    /** The played spring after a year in which every club lost: owners whose clubs lost again fire their men. */
    private fun twoBadYears(): Dynasty = played.copy(lastOffseason = OffseasonReport(
        year = 2025, moneyByTeam = played.league.teams.associate { it.id.v to TeamMoney(winPermille = 200) }))

    @Test
    fun `the general manager pool has the men the owners let go this spring, and hiring one waits for the offseason`() {
        val d = Staffing.fireGm(twoBadYears())
        val market = Staffing.gmMarket(d)
        val pool = Staffing.gmPool(d, market)
        val letGo = pool.filter { Staffing.gmLeaving(d, it) != null }
        assertTrue(letGo.isNotEmpty(), "owners of clubs that lost twice let their men go")
        val pick = letGo.first()
        val club = Staffing.gmLeaving(d, pick)!!
        val agreed = Staffing.hireGm(d, pick, market)
        assertEquals(pick.name, agreed.pendingGm)
        assertTrue(agreed.team.gm.name.isBlank(), "he works for the ${club.name} until they let him go")
        assertEquals(pick.name, Staffing.pendingGm(agreed)?.name)
        assertTrue(Staffing.gmPool(agreed).none { it.name == pick.name }, "he is not offered twice")
        assertTrue(Staffing.fireGm(agreed).pendingGm == null, "changing his mind undoes it")

        val (next, report) = OffseasonEngine.run(agreed)
        assertEquals(pick.name, next.league.team(user).gm.name)
        assertEquals(null, next.pendingGm, "a finished offseason leaves no agreement behind")
        assertTrue(report.gmChanges.any { it.fired == pick.name }, "his club let him go")
        assertEquals(1, next.league.teams.count { it.gm.name == pick.name }, "no owner took him first")
    }

    @Test
    fun `the offseason fills the jobs the user left open before anything reads the staff`() {
        var d = played
        d = Staffing.fire(Staffing.fire(d, StaffJob.HEAD), StaffJob.SPECIAL)
        val (next, _) = OffseasonEngine.run(d)
        assertTrue(Staffing.vacancies(next.league, user).isEmpty())
        assertTrue(next.league.team(user).staff.headCoach != d.league.team(user).staff.headCoach)
    }
}
