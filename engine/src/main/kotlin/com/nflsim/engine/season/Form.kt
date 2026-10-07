package com.nflsim.engine.season

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.stats.StatLine
import com.nflsim.engine.tuning.TuningTable

/**
 * How a man is playing this month, as against how good he is (SPEC 10.1's
 * benchings need somebody to bench).
 *
 * Form is -100 to 100 and nobody's talent: it moves on what he did last
 * Sunday against what his position is expected to do, decays toward nothing
 * in between, and is worth a few rating points on game day. It is read in
 * exactly two places - the depth chart that picks the eleven, and the
 * ratings the play resolution reads - so a front office still values a man
 * on his ability and a slump cannot get him cut or knock him off a draft
 * board.
 *
 * A quarterback at 70 in November is not suddenly a 74; he is a 70 playing
 * like one, which is the difference the game needs to bench anybody.
 */
object Form {

    /** Rating points form is worth on game day, at its limit. */
    fun points(player: Player, tuning: TuningTable): Int =
        Math.round(player.form / 100f * tuning.form.swing)

    /**
     * The man as he is playing, for game day only: every rating moved by
     * his form. The club's own records - and every valuation, scouting
     * report and draft board reading them - keep his real ratings.
     */
    fun dressed(player: Player, tuning: TuningTable): Player {
        val points = points(player, tuning)
        return if (points == 0) player else player.copy(ratings = player.ratings.shifted(points))
    }

    /**
     * The form a man carries into next week, given the week he just had.
     * [motivation] is his head coach's (SPEC 4.7): under a good motivator a
     * slump fades faster, under a poor one it lingers. A hot streak fades the
     * same either way. Null is a league-average coach.
     */
    fun next(player: Player, line: StatLine?, tuning: TuningTable, motivation: Int? = null): Int {
        val f = tuning.form
        val slump = if (player.form < 0 && motivation != null)
            (1f - tuning.staff.motivationSlump * (motivation - tuning.staff.coachMean) / 100f).coerceIn(0f, 1f / f.decay)
        else 1f
        val decayed = player.form * f.decay * slump
        val week = line?.let { surprise(player.position, it, f) } ?: return decayed.toInt()
        return (decayed + week * f.gain * volume(player.position, line, f))
            .coerceIn(-100f, 100f)
            .toInt()
    }

    /**
     * What the week says, from -100 to 100. Every position the box score
     * measures has a number it is judged against; the ones it does not - the
     * offensive line, the kickers, the long snapper - carry no form, because
     * inventing one would bench a guard for a game nobody watched.
     */
    private fun surprise(position: Position, line: StatLine, f: TuningTable.Form): Float? = when (position) {
        Position.QB -> if (line.passAttempts == 0) null
            else scaled(line.passerRating.toFloat() - f.passerRating, f.passerSpread)
        Position.RB, Position.FB -> if (line.carries == 0) null
            else scaled(line.yardsPerCarry.toFloat() - f.yardsPerCarry, f.yardsPerCarrySpread)
        Position.WR, Position.TE -> if (line.targets == 0) null
            else scaled(line.receivingYards.toFloat() / line.targets - f.yardsPerTarget, f.yardsPerTargetSpread)
        Position.EDGE, Position.DT, Position.LB, Position.CB, Position.S ->
            scaled(defensiveWeek(line) - f.defensiveWeek, f.defensiveSpread)
        else -> null
    }

    /** A defender's afternoon in one number: stops, with the plays that end drives worth more. */
    private fun defensiveWeek(line: StatLine): Float =
        line.combinedTackles + line.sacks * 2f + line.interceptions * 3f

    private fun scaled(over: Float, spread: Float): Float = (over / spread * 100f).coerceIn(-100f, 100f)

    /**
     * How much of the week counts. Two carries say nothing about a back, so a
     * light day moves form a fraction of the way a full one does.
     */
    private fun volume(position: Position, line: StatLine, f: TuningTable.Form): Float = when (position) {
        Position.QB -> (line.passAttempts / f.passAttemptsFull).coerceAtMost(1f)
        Position.RB, Position.FB -> (line.carries / f.carriesFull).coerceAtMost(1f)
        Position.WR, Position.TE -> (line.targets / f.targetsFull).coerceAtMost(1f)
        else -> 1f
    }
}
