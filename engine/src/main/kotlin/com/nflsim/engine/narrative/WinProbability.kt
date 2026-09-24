package com.nflsim.engine.narrative

import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The home side's chance of winning from where a play starts (SPEC 10.4).
 *
 * The final margin is taken as normal: centred on today's margin plus what
 * the ball is worth where it sits, spread by how much game is left. It reads
 * the log after the fact and moves nothing in the sim, which is why its
 * numbers live here with the recap and not in the tuning table.
 */
object WinProbability {

    /** A whole game's spread of final margins, in points. */
    private const val MARGIN_SD = 13.5f

    /** What the ball is worth first-and-ten at the offence's own goal line, in points. */
    private const val EP_OWN_GOAL = -1.5f

    /** And what each yard up the field adds. */
    private const val EP_PER_YARD = 0.075f

    /** What each down used up costs. */
    private const val EP_PER_DOWN = 0.5f

    private const val QUARTER_SECONDS = com.nflsim.engine.sim.GameState.QUARTER_SECONDS

    /** Home's chance before [play] is run. */
    fun home(play: PlayLog): Float {
        val left = if (play.quarter <= 4) (4 - play.quarter) * QUARTER_SECONDS + play.clock else play.clock
        val worth = EP_OWN_GOAL + EP_PER_YARD * play.yardLine - EP_PER_DOWN * (play.down - 1).coerceAtLeast(0)
        val margin = (play.homeScore - play.awayScore) + if (play.offense == Side.HOME) worth else -worth
        val spread = MARGIN_SD * sqrt(left.coerceAtLeast(1) / (4f * QUARTER_SECONDS))
        return phi(margin / spread)
    }

    /** Home's chance once it is over: won, lost or tied. */
    fun final(homeScore: Int, awayScore: Int): Float = when {
        homeScore > awayScore -> 1f
        homeScore < awayScore -> 0f
        else -> 0.5f
    }

    /** Home's chance before each play, and at the final whistle after the last. */
    fun curve(plays: List<PlayLog>, homeScore: Int, awayScore: Int): List<Float> =
        plays.map(::home) + final(homeScore, awayScore)

    /** Standard normal CDF (Abramowitz and Stegun 7.1.26), good to 1e-7. */
    private fun phi(z: Float): Float {
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val erf = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t +
            0.254829592) * t * exp(-x * x)
        val half = 0.5 * (1.0 + erf)
        return (if (z >= 0) half else 1.0 - half).toFloat()
    }
}
