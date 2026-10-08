package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeSide
import com.nflsim.engine.rng.SplitMixRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 8.2: the coaching carousel hires around a head coach from either side of the ball. */
class CoachingCarouselTest {

    private fun side(scheme: String) = SchemeCatalog[scheme].side

    @Test
    fun `a head coach from the defense runs the defense, and the club hires its offense`() {
        val base = LeagueGenerator.generate(2026, 5L)
        // Every head coach in the league out of work and from the defense, so
        // the clubs that fire theirs can only rehire one of them.
        val out = base.coaches.values.filter { it.role == CoachRole.HEAD_COACH }
            .mapIndexed { i, c -> c.copy(id = com.nflsim.engine.model.CoachId(90_000 + i), scheme = SchemeCatalog.defensive[i % SchemeCatalog.defensive.size].id, hotSeat = 0) }
        val league = base.copy(coaches = base.coaches + out.associateBy { it.id })
        // A season that gets everyone fired: nobody won a game.
        val r = CoachingCarousel.run(league, winPct = { 0f }, playoffClubs = emptySet(),
            previousWinPct = league.teams.associate { it.id.v to 1f }, rng = SplitMixRng(1L))
        val hiredDefensive = r.league.teams.filter { side(r.league.coaches.getValue(it.staff.headCoach).scheme) == SchemeSide.DEFENSE }
        r.league.teams.forEach { t ->
            assertEquals(SchemeSide.OFFENSE, side(t.offenseScheme), "${t.abbrev} runs an offense on offense")
            assertEquals(SchemeSide.DEFENSE, side(t.defenseScheme), "${t.abbrev} runs a defense on defense")
            assertEquals(t.offenseScheme, r.league.coaches.getValue(t.staff.offCoordinator).scheme)
            assertEquals(t.defenseScheme, r.league.coaches.getValue(t.staff.defCoordinator).scheme)
        }
        assertTrue(r.changes.isNotEmpty(), "somebody was fired")
        assertTrue(hiredDefensive.isNotEmpty(), "somebody hired a head coach from the defense")
        hiredDefensive.forEach { t ->
            val head = r.league.coaches.getValue(t.staff.headCoach)
            assertEquals(head.scheme, t.defenseScheme, "${t.abbrev}'s defense is its head coach's")
            assertEquals(head.id, r.league.coaches.getValue(t.staff.defCoordinator).tree, "his coordinator is from his tree")
        }
    }

    @Test
    fun `a new staff's coordinators are both chosen for their game plans`() {
        // Everyone fired, so every club hires a whole new staff - in ten
        // leagues, since one league's thirty staffs swing a side by several
        // points on their own.
        var oc = 0.0; var dc = 0.0; var hiredClubs = 0
        var t = LeagueGenerator.generate(2026, 1L).tuning.staff
        for (seed in 1L..10L) {
            val league = LeagueGenerator.generate(2026, seed)
            t = league.tuning.staff
            val r = CoachingCarousel.run(league, winPct = { 0f }, playoffClubs = emptySet(),
                previousWinPct = league.teams.associate { it.id.v to 1f }, rng = SplitMixRng(seed))
            val hired = r.changes.map { c -> r.league.teams.first { it.id.v == c.team } }
            oc += hired.sumOf { r.league.coach(it.staff.offCoordinator).ratings.gameplan }
            dc += hired.sumOf { r.league.coach(it.staff.defCoordinator).ratings.gameplan }
            hiredClubs += hired.size
        }
        oc /= hiredClubs; dc /= hiredClubs
        // The best of a few, read for their game plans, beat the candidates' mean on both sides alike.
        assertTrue(oc > t.candidateMean + 5 && dc > t.candidateMean + 5, "offence $oc, defence $dc")
        assertTrue(kotlin.math.abs(oc - dc) < 4, "neither side falls behind: offence $oc, defence $dc")
    }

    @Test
    fun `clubs promote other clubs' coordinators to head coach, and those clubs replace them`() {
        val league = LeagueGenerator.generate(2026, 5L)
        val r = CoachingCarousel.run(league, winPct = { 0f }, playoffClubs = emptySet(),
            previousWinPct = league.teams.associate { it.id.v to 1f }, rng = SplitMixRng(4L))
        assertTrue(r.promotions.any { it.toRole == CoachRole.HEAD_COACH }, "somebody hired a coordinator away")
        // A position coach promoted to another club's coordinator is that club's coordinator now.
        r.promotions.filter { it.toRole != CoachRole.HEAD_COACH }.forEach { p ->
            val club = r.league.teams.first { it.id.v == p.team }
            val job = StaffJob(p.toRole)
            val holder = r.league.coaches[Staffing.holder(club.staff, job)]
            assertTrue(holder == null || holder.name == p.name || r.changes.any { it.team == p.team },
                "${p.name} became ${club.abbrev}'s ${job.label} (unless that club changed staffs again after)")
        }
        r.promotions.filter { it.toRole == CoachRole.HEAD_COACH }.forEach { p ->
            val club = r.league.teams.first { it.id.v == p.team }
            val head = r.league.coach(club.staff.headCoach)
            assertEquals(p.name, head.name)
            assertEquals(CoachRole.HEAD_COACH, head.role)
            assertTrue(head.tendencies.fourthDownAggression != null)
            val from = r.league.teams.first { it.id.v == p.from }
            assertTrue(r.league.coaches.containsKey(from.staff.offCoordinator) && r.league.coaches.containsKey(from.staff.defCoordinator),
                "${from.abbrev} has both coordinators")
            assertTrue(from.staff.offCoordinator != head.id && from.staff.defCoordinator != head.id)
        }
        assertEquals(r.promotions.count { it.toRole == CoachRole.HEAD_COACH }, r.changes.count { it.promotedFrom != null })
        // Nobody holds two jobs.
        val jobs = r.league.teams.flatMap { listOf(it.staff.headCoach, it.staff.offCoordinator, it.staff.defCoordinator) }
        assertEquals(jobs.size, jobs.toSet().size)
    }

    @Test
    fun `every coach ages a year, and the old retire from their jobs and are replaced`() {
        val base = LeagueGenerator.generate(2026, 5L)
        val user = base.teams.first().id
        // A few men past any retirement age, at the user's club and another.
        val other = base.teams[1]
        val old = listOf(base.team(user).staff.offCoordinator, base.team(user).staff.headCoach,
            other.staff.defCoordinator, other.staff.positionCoaches.getValue(com.nflsim.engine.model.PositionGroup.QB), other.staff.headCoach)
        val league = base.copy(coaches = base.coaches.mapValues { (id, c) -> if (id in old) c.copy(age = 80) else c })
        val r = CoachingCarousel.run(league, winPct = { 0.6f }, playoffClubs = emptySet(),
            previousWinPct = league.teams.associate { it.id.v to 0.6f }, rng = SplitMixRng(2L), userTeam = user)
        // Nobody fired on a winning season: every staying man is a year older.
        val stayed = r.league.coaches.filterKeys { it in league.coaches && it !in old }
        assertTrue(stayed.isNotEmpty())
        stayed.forEach { (id, c) -> assertEquals(league.coach(id).age + 1, c.age, c.name) }
        // And a year on in his contract: one that runs out is extended.
        val t = league.tuning.staff
        // (A position coach promoted from within has a new contract in a new job.)
        stayed.filter { (id, c) -> c.role == league.coach(id).role }.forEach { (id, c) ->
            val left = league.coach(id).contractYearsLeft - 1
            assertEquals(if (left <= 0) t.extensionYears else left, c.contractYearsLeft, c.name)
        }
        // The old are gone, and listed.
        old.forEach { assertTrue(it !in r.league.coaches, "${league.coach(it).name} retired") }
        assertTrue(r.retirements.map { it.name }.containsAll(old.map { league.coach(it).name }))
        // Anyone else who retired is a generated man who reached a retirement age.
        r.retirements.forEach { ret ->
            val was = league.coaches.values.first { it.name == ret.name }
            assertTrue(was.age + 1 >= t.retireFrom, "${ret.name} retired at ${was.age + 1}")
        }
        // The user's jobs are his to fill; the other club's are filled.
        assertEquals(com.nflsim.engine.offseason.Staffing.VACANT, r.league.team(user).staff.offCoordinator)
        assertEquals(com.nflsim.engine.offseason.Staffing.VACANT, r.league.team(user).staff.headCoach)
        val o = r.league.team(other.id)
        listOf(o.staff.headCoach, o.staff.defCoordinator, o.staff.positionCoaches.getValue(com.nflsim.engine.model.PositionGroup.QB))
            .forEach { assertTrue(it in r.league.coaches && it !in old) }
        assertTrue(r.changes.single { it.team == other.id.v }.retired)
    }

    @Test
    fun `a head coach runs his side of the ball, and only a promoted defensive coordinator comes from the defense`() {
        val league = LeagueGenerator.generate(2026, 5L)
        val r = CoachingCarousel.run(league, winPct = { 0f }, playoffClubs = emptySet(),
            previousWinPct = league.teams.associate { it.id.v to 1f }, rng = SplitMixRng(1L))
        val promotedFromDefence = r.promotions.filter { it.role == CoachRole.DEFENSIVE_COORDINATOR }.map { it.name }.toSet()
        r.league.teams.forEach { t ->
            val head = r.league.coaches.getValue(t.staff.headCoach)
            if (head.name in promotedFromDefence) {
                assertEquals(SchemeSide.DEFENSE, side(head.scheme))
                assertEquals(head.scheme, t.defenseScheme, "a defensive head coach's scheme is the club's defense")
            } else {
                assertEquals(SchemeSide.OFFENSE, side(head.scheme), "${t.abbrev}: generated head coaches are from the offense")
                assertEquals(head.scheme, t.offenseScheme, "an offensive head coach's scheme is the club's offense")
            }
        }
    }
}
