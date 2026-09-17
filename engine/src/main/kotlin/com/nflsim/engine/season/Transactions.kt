package com.nflsim.engine.season

import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.offseason.CapManagement

/**
 * Signing a free agent and releasing a player, in season (SPEC 7's market runs
 * in the spring; this is the rest of the year).
 *
 * A street free agent signs for the league minimum on a one-year deal, which
 * is what one actually costs in November: the clubs that wanted to pay him
 * more did so in the spring. Releasing charges the dead money the contract
 * says it does, which is what stops a roster being churned for free.
 */
object Transactions {

    /** The active roster a club has to fit under (SPEC 7 phase 11). */
    const val ROSTER_LIMIT = 53

    sealed interface Outcome {
        /** The league after the move, and a line saying what happened. */
        data class Done(val league: League, val note: String) : Outcome

        /** Nothing happened, and why - in words a club would use. */
        data class Refused(val reason: String) : Outcome
    }

    /** What a free agent costs at this time of year: the minimum, for a year. */
    val askingPrice: Int get() = Contract.MIN_BASE_SALARY

    /** Cap room the club has after what it already owes and its dead money. */
    fun spaceFor(league: League, team: TeamId): Int =
        CapManagement.spaceFor(league.roster(team), league.year, league.team(team).finances.deadMoney)

    /** Everyone available: nobody's player, and not retired. */
    fun freeAgents(league: League): List<Player> = league.players.filter {
        it.teamId == null && it.status != PlayerStatus.RETIRED
    }

    fun sign(league: League, team: TeamId, playerId: PlayerId, year: Int = league.year): Outcome {
        val player = league.playersById[playerId]
            ?: return Outcome.Refused("There is no such player.")
        if (player.teamId != null) {
            return Outcome.Refused("${player.name} is under contract somewhere else.")
        }
        if (player.status == PlayerStatus.RETIRED) {
            return Outcome.Refused("${player.name} has retired.")
        }
        val club = league.team(team)
        if (club.roster.size >= ROSTER_LIMIT) {
            return Outcome.Refused(
                "The roster is full at $ROSTER_LIMIT. Release somebody to sign ${player.lastName}.")
        }
        val space = spaceFor(league, team)
        if (space < askingPrice) {
            return Outcome.Refused(
                "No room under the cap: ${money(space)} against the ${money(askingPrice)} minimum.")
        }
        val signed = player.copy(
            teamId = team,
            status = PlayerStatus.ACTIVE,
            contract = Contract.of(years = 1, totalValue = askingPrice, signedYear = year),
            yearsWithClub = 0,
            yearsInSystem = 0,
        )
        return Outcome.Done(
            league.copy(
                players = league.players.map { if (it.id == playerId) signed else it },
                teams = league.teams.map {
                    if (it.id == team) it.copy(roster = it.roster + playerId) else it
                },
            ),
            "${player.position.label} ${player.name} signs for ${money(askingPrice)}.",
        )
    }

    fun release(league: League, team: TeamId, playerId: PlayerId, year: Int = league.year): Outcome {
        val player = league.playersById[playerId]
            ?: return Outcome.Refused("There is no such player.")
        if (player.teamId != team) {
            return Outcome.Refused("${player.name} does not play for this club.")
        }
        val dead = player.contract?.deadCap(year)
        val thisYear = dead?.thisYear ?: 0
        val nextYear = dead?.nextYear ?: 0
        val cut = player.copy(
            teamId = null,
            status = PlayerStatus.FREE_AGENT,
            contract = null,
        )
        val note = buildString {
            append("${player.position.label} ${player.name} is released")
            if (thisYear > 0) append(", ${money(thisYear)} dead this year")
            if (nextYear > 0) append(" and ${money(nextYear)} next")
            append(".")
        }
        return Outcome.Done(
            league.copy(
                players = league.players.map { if (it.id == playerId) cut else it },
                teams = league.teams.map {
                    if (it.id != team) it
                    else it.copy(
                        roster = it.roster - playerId,
                        finances = it.finances.copy(
                            deadMoney = it.finances.deadMoney + thisYear,
                        ),
                    )
                },
            ),
            note,
        )
    }

    /** Cap figures are in thousands, and nobody reads 840 as money. */
    private fun money(thousands: Int): String =
        if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)
}
