package com.nflsim.engine.offseason

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.CoachRatings
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 4.7: a coach's ratings move with his age - up while he learns, level in his prime, slowly down after. */
class CoachCareerTest {

    private val t = TuningTable.REALISTIC.staff
    private val base = LeagueGenerator.generate(2026, 5L).coaches.values.first()

    private fun meanChange(age: Int): Double {
        val rng = SplitMixRng(7L)
        val start = base.copy(age = age, ratings = CoachRatings(60, 60, 60, 60, 60, 60))
        return (0 until 400).map { i ->
            val c = CoachCareer.older(start.copy(id = com.nflsim.engine.model.CoachId(1000 + i)), t, rng)
            with(c.ratings) { (development + gameplan + adjustments + discipline + motivation + evaluation) / 6.0 } - 60
        }.average()
    }

    @Test
    fun `a young coach improves, a coach in his prime holds, and an old one slips only slowly`() {
        val young = meanChange(35); val prime = meanChange(50); val old = meanChange(64)
        assertTrue(young > 0.8, "young: $young")
        assertTrue(kotlin.math.abs(prime) < 0.2, "prime: $prime")
        assertTrue(old < 0 && old > -0.8, "old slips, slowly: $old")
        assertTrue(young > -old * 2, "growth outpaces decline")
    }

    @Test
    fun `a year on is a year older, and the same man always takes the same year`() {
        val a = CoachCareer.older(base, t, SplitMixRng(1L))
        val b = CoachCareer.older(base, t, SplitMixRng(1L))
        assertEquals(base.age + 1, a.age)
        assertEquals(a, b)
    }

    @Test
    fun `a new coach is drawn where his age puts him`() {
        assertTrue(CoachCareer.hireMean(65f, 32, t) < CoachCareer.hireMean(65f, 50, t))
        assertEquals(65f + t.careerPeakLift, CoachCareer.hireMean(65f, 50, t))
        assertTrue(CoachCareer.hireMean(65f, 66, t) < CoachCareer.hireMean(65f, 50, t))
        assertEquals("Still improving.", CoachCareer.stage(40, t))
        assertEquals("In his prime.", CoachCareer.stage(50, t))
    }
}
