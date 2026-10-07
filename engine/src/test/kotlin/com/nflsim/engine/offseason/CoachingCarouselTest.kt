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
        val league = LeagueGenerator.generate(2026, 5L)
        val t = league.tuning.staff
        // Everyone fired, so every club hires a whole new staff.
        val r = CoachingCarousel.run(league, winPct = { 0f }, playoffClubs = emptySet(),
            previousWinPct = league.teams.associate { it.id.v to 1f }, rng = SplitMixRng(4L))
        val hired = r.changes.map { c -> r.league.teams.first { it.id.v == c.team } }
        assertTrue(hired.size >= 20, "most clubs changed staffs: ${hired.size}")
        val oc = hired.map { r.league.coach(it.staff.offCoordinator).ratings.gameplan }.average()
        val dc = hired.map { r.league.coach(it.staff.defCoordinator).ratings.gameplan }.average()
        // The best of a few, read for their game plans, beat the candidates' mean on both sides alike.
        assertTrue(oc > t.candidateMean + 5 && dc > t.candidateMean + 5, "offence $oc, defence $dc")
        assertTrue(kotlin.math.abs(oc - dc) < 6, "neither side falls behind: offence $oc, defence $dc")
    }

    @Test
    fun `a generated league's head coaches are all from the offense, as before`() {
        val league = LeagueGenerator.generate(2026, 5L)
        val r = CoachingCarousel.run(league, winPct = { 0f }, playoffClubs = emptySet(),
            previousWinPct = league.teams.associate { it.id.v to 1f }, rng = SplitMixRng(1L))
        r.league.teams.forEach { t ->
            val head = r.league.coaches.getValue(t.staff.headCoach)
            assertEquals(SchemeSide.OFFENSE, side(head.scheme))
            assertEquals(head.scheme, t.offenseScheme, "an offensive head coach's scheme is the club's offense")
        }
    }
}
