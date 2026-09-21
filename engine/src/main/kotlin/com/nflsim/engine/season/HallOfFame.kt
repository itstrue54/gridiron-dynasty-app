package com.nflsim.engine.season

import com.nflsim.engine.model.HallOfFamer
import com.nflsim.engine.model.LeagueHistory
import com.nflsim.engine.model.RetiredCareer
import com.nflsim.engine.stats.StatLine

/**
 * The hall of fame (SPEC 10's history screen).
 *
 * A career is weighed against what its own position is asked to do - a back
 * who ran for 12,000 yards and a corner who never carried the ball once are
 * both candidates - and then against what the league said about him while he
 * played: Pro Bowls, and the hardware in the record of each season.
 *
 * Nobody goes in the year he retires. The wait is short by the NFL's standards
 * because a dynasty is played in an evening, not over a career.
 */
object HallOfFame {

    /** Seasons a man waits after his last, before he can be voted in. */
    const val WAIT = 3

    /** What a career has to be worth: about fourteen leading seasons' worth. */
    const val BAR = 14f

    /** How many go in together, however good the class is. */
    const val CLASS_SIZE = 3

    /** Everyone voted in this year, newest class first in the history. */
    fun induct(history: LeagueHistory, year: Int): List<HallOfFamer> {
        val already = history.hallOfFame.map { it.player }.toSet()
        val eligible = history.retired.filter {
            it.player !in already && it.year <= year - WAIT && it.career.years >= 5
        }
        return eligible
            .map { it to score(it, history) }
            .filter { it.second >= BAR }
            .sortedByDescending { it.second }
            .take(CLASS_SIZE)
            .map { (man, score) ->
                HallOfFamer(
                    player = man.player,
                    name = man.name,
                    position = man.position,
                    inducted = year,
                    retired = man.year,
                    seasons = man.career.years,
                    proBowls = man.proBowls,
                    headline = headline(man),
                    score = score,
                )
            }
    }

    /**
     * What a career was worth, in very good seasons. A season that would lead
     * the league at the position counts for about one; the accolades a man
     * actually won count for as much again, because the league watched him
     * play and we did not.
     */
    fun score(man: RetiredCareer, history: LeagueHistory): Float {
        val production = man.career.seasons.sumOf { season(man.position, it.stats).toDouble() }.toFloat()
        val awards = history.seasons.sumOf { record ->
            val a = record.awards ?: return@sumOf 0
            listOfNotNull(
                a.mostValuablePlayer?.takeIf { it.player == man.player }?.let { MVP },
                a.offensivePlayerOfTheYear?.takeIf { it.player == man.player }?.let { PLAYER_OF_YEAR },
                a.defensivePlayerOfTheYear?.takeIf { it.player == man.player }?.let { PLAYER_OF_YEAR },
                a.comebackPlayerOfTheYear?.takeIf { it.player == man.player }?.let { COMEBACK },
            ).sum() + a.honours.filter { it.player == man.player }.sumOf {
                when (it.tier) {
                    1 -> FIRST_TEAM
                    2 -> SECOND_TEAM
                    else -> 0
                }
            }
        }
        return production + awards + man.proBowls * PRO_BOWL
    }

    /**
     * One season, as a fraction of what leading the league at that position
     * looks like: about 1.0 for the best season anyone has at it.
     *
     * The divisors are measured, not guessed. Guessing them put eight corners
     * in a nineteen-man hall, because a corner's 166 tackles and nine
     * interceptions were being scored as nearly three leading seasons at once,
     * while a tight end could not reach one however good he was. Re-measured
     * when tackles moved from corners to safeties: leaders of about 140 at
     * linebacker, 115 at safety and 95 at corner.
     */
    private fun season(position: String, s: StatLine): Float = when (position) {
        "QB" -> s.passYards / 4_800f * 0.6f + s.passTouchdowns / 40f * 0.4f
        "RB", "FB" -> s.rushYards / 2_000f * 0.7f + s.rushTouchdowns / 16f * 0.3f
        "WR" -> s.receivingYards / 1_700f * 0.7f + s.receivingTouchdowns / 14f * 0.3f
        "TE" -> s.receivingYards / 1_100f * 0.7f + s.receivingTouchdowns / 10f * 0.3f
        "EDGE", "DT" -> s.sacks / 18f * 0.7f + s.tackles / 65f * 0.3f
        "LB" -> s.tackles / 140f * 0.6f + s.interceptions / 8f * 0.2f + s.sacks / 10f * 0.2f
        "CB" -> s.interceptions / 9f * 0.5f + s.tackles / 95f * 0.5f
        "S" -> s.interceptions / 6f * 0.5f + s.tackles / 115f * 0.5f
        // A guard has no stat line at all: his case is the hardware he won.
        else -> 0f
    }

    /** The number he is remembered by. */
    fun headline(man: RetiredCareer): Int = maxOf(
        man.career.total { it.passYards },
        man.career.total { it.rushYards },
        man.career.total { it.receivingYards },
        man.career.total { it.tackles },
    )

    private const val MVP = 4
    private const val PLAYER_OF_YEAR = 3
    private const val COMEBACK = 1
    private const val FIRST_TEAM = 2
    private const val SECOND_TEAM = 1
    private const val PRO_BOWL = 0.5f
}
