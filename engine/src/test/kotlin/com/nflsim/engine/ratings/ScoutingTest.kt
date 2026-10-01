package com.nflsim.engine.ratings

import com.nflsim.engine.model.Position
import kotlin.test.Test
import kotlin.test.assertTrue

/** docs/SPEC.md 4.6: a club cannot watch everyone, so where it looks matters. */
class ScoutingTest {

    private val dept = 60

    @Test
    fun `naming a position buys certainty about it`() {
        val focused = Scouting.prospect(5, Position.QB, dept, setOf(Position.QB), SC)
        val spread = Scouting.prospect(5, Position.QB, dept, emptySet(), SC)
        val ignored = Scouting.prospect(5, Position.QB, dept, setOf(Position.CB), SC)
        assertTrue(focused > spread, "watching quarterbacks should beat watching everyone ($focused vs $spread)")
        assertTrue(spread > ignored, "spreading should beat ignoring the position ($spread vs $ignored)")
    }

    @Test
    fun `watching two positions is thinner than watching one`() {
        val one = Scouting.prospect(5, Position.QB, dept, setOf(Position.QB), SC)
        val two = Scouting.prospect(5, Position.QB, dept, setOf(Position.QB, Position.EDGE), SC)
        assertTrue(two < one, "a split focus should read each position less surely ($two vs $one)")
    }

    @Test
    fun `a better department buys more of everything`() {
        val poor = Scouting.prospect(5, Position.QB, 20, setOf(Position.QB), SC)
        val rich = Scouting.prospect(5, Position.QB, 95, setOf(Position.QB), SC)
        assertTrue(rich > poor, "a better department should know more ($rich vs $poor)")
    }

    @Test
    fun `a focused club goes into the draft with a narrower band`() {
        val focused = Scouting.lens(5, 1, Position.QB, dept, setOf(Position.QB), SC).view(80)
        val ignored = Scouting.lens(5, 2, Position.QB, dept, setOf(Position.CB), SC).view(80)
        assertTrue(focused.high - focused.low < ignored.high - ignored.low,
            "the club watching quarterbacks should have the tighter read")
    }

    @Test
    fun `no amount of scouting makes a prospect a certainty`() {
        val every = Position.entries.toSet()
        val most = Position.entries.map { Scouting.prospect(it.ordinal, it, 99, setOf(it), SC) }
        assertTrue(most.all { it <= SC.ceiling }, "confidence ran to ${most.max()}")
        assertTrue(Scouting.prospect(5, Position.QB, 99, every, SC) < SC.exactAt,
            "watching everything should leave a club sure of nothing in particular")
    }
}

private val SC = com.nflsim.engine.tuning.TuningTable.REALISTIC.scouting
