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
        val d = spring()
        val candidate = Staffing.pool(d.league, 2026, user, StaffJob.HEAD).first()
        assertFailsWith<IllegalArgumentException> { Staffing.hire(d, StaffJob.HEAD, candidate) }
        val open = Staffing.fire(d, StaffJob.HEAD)
        val stranger = candidate.copy(id = CoachId(-99), name = "Nobody Atall")
        assertFailsWith<IllegalArgumentException> { Staffing.hire(open, StaffJob.HEAD, stranger) }
        val employed = d.league.coach(d.league.teams[1].staff.headCoach)
        assertFailsWith<IllegalArgumentException> { Staffing.hire(open, StaffJob.HEAD, employed) }
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

    @Test
    fun `the offseason fills the jobs the user left open before anything reads the staff`() {
        var d = DynastyEngine.start(base, 2026, 5L, user)
        while (d.phase != DynastyPhase.OFFSEASON) d = DynastyEngine.advance(d)
        d = Staffing.fire(Staffing.fire(d, StaffJob.HEAD), StaffJob.SPECIAL)
        val (next, _) = OffseasonEngine.run(d)
        assertTrue(Staffing.vacancies(next.league, user).isEmpty())
        assertTrue(next.league.team(user).staff.headCoach != d.league.team(user).staff.headCoach)
    }
}
