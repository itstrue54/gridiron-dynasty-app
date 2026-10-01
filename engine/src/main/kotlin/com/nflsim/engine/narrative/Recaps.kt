package com.nflsim.engine.narrative

import com.nflsim.engine.model.Team
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side
import kotlin.math.abs

/**
 * A game, told (SPEC 10.4): who won and how, then the three to five plays
 * that moved the result most, by win-probability swing, in the order they
 * happened. Written from the play log alone, so any game whose plays are
 * kept can be recapped, and from `narrative/recaps.json`.
 */
object Recaps {

    data class Recap(val lead: String, val moments: List<Moment>)

    /** A play that mattered: where it is in the log, how far it moved home's chance, and how it reads. */
    data class Moment(val index: Int, val swing: Float, val text: String)

    private val book = TemplateBook("/narrative/recaps.json")

    val templates: Map<String, List<String>> get() = book.templates

    /** At least this many plays are told, if the game has them. */
    const val MIN_MOMENTS = 3

    /** At most this many. */
    const val MAX_MOMENTS = 5

    /** Past the minimum, a play has to move the game this much to be told. */
    private const val TELLING_SWING = 0.08f

    /** A win decided by this many or fewer is close. */
    private const val CLOSE_MARGIN = 3

    /** One by this many or more is a rout. */
    private const val ROUT_MARGIN = 21

    /** A winner whose chance fell this low came back. */
    private const val COMEBACK_CHANCE = 0.2f

    /**
     * The same game always reads the same way, wherever it is shown. A game
     * is known by its clubs, its score and how many plays it ran, which is
     * all the game screen and the archive both have.
     */
    fun wordsFor(seed: Long, plays: List<PlayLog>, home: Int, away: Int, homeScore: Int, awayScore: Int): Rng =
        SplitMixRng(seed).split("recap|$home|$away|$homeScore|$awayScore|${plays.size}")

    /**
     * Each snap's swing, by index: from its chance to the next snap's (or the
     * result after the last). The log also carries lines that are not snaps -
     * the weather before kickoff, a timeout - and they are never key plays;
     * whatever moved across one belongs to the snap before it.
     */
    fun snapSwings(plays: List<PlayLog>, homeScore: Int, awayScore: Int): List<Pair<Int, Float>> {
        val curve = WinProbability.curve(plays, homeScore, awayScore)
        val snaps = plays.indices.filter { !isAside(plays[it].text) }
        return snaps.mapIndexed { k, i -> i to curve[snaps.getOrElse(k + 1) { plays.size }] - curve[i] }
    }

    /** The plays that moved the game most, by index, in the order they were played. */
    fun keyPlays(plays: List<PlayLog>, homeScore: Int, awayScore: Int): List<Pair<Int, Float>> {
        val swings = snapSwings(plays, homeScore, awayScore)
            .sortedByDescending { abs(it.second) }
        return swings.filterIndexed { rank, (_, swing) ->
            rank < MIN_MOMENTS || (rank < MAX_MOMENTS && abs(swing) >= TELLING_SWING)
        }.sortedBy { it.first }
    }

    fun write(
        plays: List<PlayLog>,
        home: Team,
        away: Team,
        homeScore: Int,
        awayScore: Int,
        words: Rng,
    ): Recap? {
        if (plays.isEmpty()) return null
        val lead = lead(plays, home, away, homeScore, awayScore, words)
        val moments = keyPlays(plays, homeScore, awayScore).map { (i, swing) ->
            val play = plays[i]
            val offense = if (play.offense == Side.HOME) home else away
            val us = if (play.offense == Side.HOME) play.homeScore else play.awayScore
            val them = if (play.offense == Side.HOME) play.awayScore else play.homeScore
            val situation = book.write(
                when {
                    us < them -> "situation.trailing"
                    us > them -> "situation.leading"
                    else -> "situation.level"
                },
                words, "us" to us, "them" to them, "gap" to abs(us - them),
                "points" to points(abs(us - them)),
            )
            // A fourth down is why a short gain or loss can swing a game, so say it.
            val fourth = if (play.down != 4) "" else book.write("fourth", words,
                "distance" to if (play.yardLine + play.distance >= 100) "goal" else play.distance)
            Moment(i, swing, book.write("moment", words,
                "clock" to play.clockText, "period" to period(play.quarter), "team" to offense.city,
                "situation" to situation, "fourth" to fourth, "play" to play.text))
        }
        return Recap(lead, moments)
    }

    private fun lead(plays: List<PlayLog>, home: Team, away: Team, homeScore: Int, awayScore: Int, words: Rng): String {
        if (homeScore == awayScore) {
            return book.write("lead.tie", words, "home" to home.name, "away" to away.name, "score" to homeScore)
        }
        val homeWon = homeScore > awayScore
        val winner = if (homeWon) home else away
        val loser = if (homeWon) away else home
        val margin = abs(homeScore - awayScore)
        val curve = WinProbability.curve(plays, homeScore, awayScore)
        val lowest = curve.minOf { if (homeWon) it else 1f - it }
        val deficit = plays.maxOf { if (homeWon) it.awayScore - it.homeScore else it.homeScore - it.awayScore }
        val key = when {
            lowest <= COMEBACK_CHANCE && deficit > 0 -> "lead.comeback"
            margin >= ROUT_MARGIN -> "lead.blowout"
            margin <= CLOSE_MARGIN -> "lead.close"
            else -> "lead.default"
        }
        return book.write(key, words,
            "winner" to winner.name, "loser" to loser.name,
            "ws" to maxOf(homeScore, awayScore), "ls" to minOf(homeScore, awayScore), "deficit" to deficit)
    }

    /** "1 point", "4 points". */
    private fun points(n: Int): String = if (n == 1) "1 point" else "$n points"

    /** "the fourth quarter", "overtime". */
    private fun period(quarter: Int): String = when (quarter) {
        1 -> "the first quarter"
        2 -> "the second quarter"
        3 -> "the third quarter"
        4 -> "the fourth quarter"
        else -> "overtime"
    }

    /** Lines in the log that are not a snap: written from these play-line keys. */
    private val asides: List<Regex> by lazy {
        listOf("timeout", "weather").flatMap { key ->
            com.nflsim.engine.sim.PlayLines.templates.getValue(key).map { way ->
                Regex(way.split(Regex("""\{\w+\}""")).joinToString(".+") { Regex.escape(it) })
            }
        }
    }

    private fun isAside(text: String): Boolean = asides.any { it.matches(text) }
}
