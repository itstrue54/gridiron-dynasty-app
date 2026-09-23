package com.nflsim.engine.season

import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Transaction
import com.nflsim.engine.model.TransactionKind
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

    /** What a free agent costs for a whole season: the league minimum. */
    val askingPrice: Int get() = Contract.MIN_BASE_SALARY

    /**
     * What he costs signed now. Salary is paid by the week, one eighteenth a
     * game (CBA Article 26), so a man signed for the last six weeks costs a
     * third of the minimum - which is how a capped-out club still finds a
     * body in December.
     */
    fun price(weeksLeft: Int): Int =
        askingPrice * weeksLeft.coerceIn(1, Schedule.WEEKS) / Schedule.WEEKS

    /** Cap room the club has after what it already owes and its dead money. */
    fun spaceFor(league: League, team: TeamId): Int =
        CapManagement.spaceFor(league.roster(team), league.year, league.team(team).finances.deadMoney)

    /** Everyone on the street: nobody's player, nobody's squad, and not retired. */
    fun freeAgents(league: League): List<Player> = league.players.filter(PracticeSquads::unattached)

    /** Other clubs' squad players, each with the club training him. */
    fun poachable(league: League, team: TeamId): List<Pair<Player, TeamId>> =
        league.teams.filter { it.id != team }.flatMap { club ->
            club.practiceSquad.map { league.player(it) to club.id }
        }

    fun sign(
        league: League,
        team: TeamId,
        playerId: PlayerId,
        year: Int = league.year,
        weeksLeft: Int = Schedule.WEEKS,
        /** The game week this comes before, for the wire; 0 out of season. */
        week: Int = 0,
    ): Outcome {
        val cost = price(weeksLeft)
        val player = league.playersById[playerId]
            ?: return Outcome.Refused("There is no such player.")
        if (player.teamId != null) {
            return Outcome.Refused("${player.name} is under contract somewhere else.")
        }
        if (player.status == PlayerStatus.RETIRED) {
            return Outcome.Refused("${player.name} has retired.")
        }
        val club = league.team(team)
        if (RosterMoves.active(league, team).size >= ROSTER_LIMIT) {
            return Outcome.Refused(
                "The roster is full at $ROSTER_LIMIT. Release somebody to sign ${player.lastName}.")
        }
        val space = spaceFor(league, team)
        if (space < cost) {
            return Outcome.Refused(
                "No room under the cap: ${money(space)} against the ${money(cost)} he would cost.")
        }
        // A squad player is a free agent his club happens to train: anyone
        // may sign him to a 53, his own club included.
        val from = PracticeSquads.clubOf(league, playerId)
        val signed = player.copy(
            teamId = team,
            status = PlayerStatus.ACTIVE,
            // A street deal: one year, all base salary, nothing guaranteed.
            contract = Contract(years = 1, baseSalary = listOf(cost), signedYear = year),
            yearsWithClub = 0,
            yearsInSystem = 0,
        )
        return done(
            Transaction.of(league.year, week, when (from) { null -> TransactionKind.SIGNED; team -> TransactionKind.PROMOTED; else -> TransactionKind.SIGNED_OFF_SQUAD }, team, player, amount = cost, years = 1, other = from?.takeIf { it != team }),

            league.copy(
                players = league.players.map { if (it.id == playerId) signed else it },
                teams = league.teams.map {
                    val squad = it.practiceSquad - playerId
                    if (it.id == team) it.copy(roster = it.roster + playerId, practiceSquad = squad)
                    else it.copy(practiceSquad = squad)
                },
            ),
            when (from) {
                null -> "${player.position.label} ${player.name} signs for ${money(cost)}."
                team -> "${player.position.label} ${player.name} is promoted from the practice squad."
                else -> "${player.position.label} ${player.name} is signed off " +
                    "${league.team(from).abbrev}'s practice squad for ${money(cost)}."
            },
        )
    }

    /** Off injured reserve and back on the 53, once he is healthy and there is room. */
    fun activate(league: League, team: TeamId, playerId: PlayerId, week: Int = 0): Outcome {
        val player = league.playersById[playerId]
            ?: return Outcome.Refused("There is no such player.")
        if (player.teamId != team || !RosterMoves.onReserve(player)) {
            return Outcome.Refused("${player.name} is not on this club's injured reserve.")
        }
        if (player.injuryWeeks > 0) {
            return Outcome.Refused(
                "${player.name} is still hurt: ${player.injuryWeeks} more " +
                    (if (player.injuryWeeks == 1) "week." else "weeks."))
        }
        if (RosterMoves.active(league, team).size >= ROSTER_LIMIT) {
            return Outcome.Refused(
                "The 53 is full. Release somebody to bring ${player.lastName} back.")
        }
        return done(
            Transaction.of(league.year, week, TransactionKind.ACTIVATED, team, player),

            league.copy(players = league.players.map {
                if (it.id == playerId) it.copy(status = PlayerStatus.ACTIVE) else it
            }),
            "${player.position.label} ${player.name} comes off injured reserve.",
        )
    }

    /** Onto the club's practice squad: off the 53, so no cap and no roster spot. */
    fun signToPracticeSquad(league: League, team: TeamId, playerId: PlayerId, week: Int = 0): Outcome {
        val player = league.playersById[playerId]
            ?: return Outcome.Refused("There is no such player.")
        if (!PracticeSquads.unattached(player)) {
            return Outcome.Refused(
                if (player.status == PlayerStatus.PRACTICE_SQUAD)
                    "${player.name} is on a practice squad; sign him to the 53 instead."
                else "${player.name} is not a free agent.")
        }
        val squad = league.team(team).practiceSquad.map { league.player(it) }
        if (!PracticeSquads.hasRoom(squad, player)) {
            return Outcome.Refused(when {
                squad.size >= PracticeSquads.SIZE ->
                    "The practice squad is full at ${PracticeSquads.SIZE}."
                PracticeSquads.isVeteran(player) &&
                    squad.count(PracticeSquads::isVeteran) >= PracticeSquads.VETERANS ->
                    "All ${PracticeSquads.VETERANS} veteran places are taken; " +
                        "${player.lastName} has ${player.accruedSeasons} accrued seasons."
                else -> "The squad already carries ${PracticeSquads.PER_POSITION} at ${player.position.label}."
            })
        }
        return done(
            Transaction.of(league.year, week, TransactionKind.TO_SQUAD, team, player),

            league.copy(
                players = league.players.map {
                    if (it.id == playerId) it.copy(status = PlayerStatus.PRACTICE_SQUAD) else it
                },
                teams = league.teams.map {
                    if (it.id == team) it.copy(practiceSquad = it.practiceSquad + playerId) else it
                },
            ),
            "${player.position.label} ${player.name} joins the practice squad.",
        )
    }

    fun releaseFromPracticeSquad(league: League, team: TeamId, playerId: PlayerId, week: Int = 0): Outcome {
        val player = league.playersById[playerId]
            ?: return Outcome.Refused("There is no such player.")
        if (playerId !in league.team(team).practiceSquad) {
            return Outcome.Refused("${player.name} is not on this club's practice squad.")
        }
        return done(
            Transaction.of(league.year, week, TransactionKind.OFF_SQUAD, team, player),

            league.copy(
                players = league.players.map {
                    if (it.id == playerId) it.copy(status = PlayerStatus.FREE_AGENT) else it
                },
                teams = league.teams.map {
                    if (it.id == team) it.copy(practiceSquad = it.practiceSquad - playerId) else it
                },
            ),
            "${player.position.label} ${player.name} is released from the practice squad.",
        )
    }

    fun release(league: League, team: TeamId, playerId: PlayerId, year: Int = league.year, week: Int = 0): Outcome {
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
        return done(
            Transaction.of(league.year, week, TransactionKind.RELEASED, team, player, amount = thisYear),

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

    private fun done(entry: Transaction, league: League, note: String) =
        Outcome.Done(league.logged(entry), note)

    /**
     * What restructuring a man's deal would do (SPEC 8.3): base salary moved
     * into bonus, which spreads it over the contract. It is the cheapest
     * thing a club can do this year and the most expensive thing to have
     * done three years from now, because the money does not go away - it
     * follows him, and it follows a release as dead money.
     *
     * Null when there is nothing to move: a man on the minimum has no base
     * to shift, and a deal in its last year has nowhere to shift it to.
     */
    data class Restructure(
        val player: Player,
        /** Cap room it frees this year. */
        val frees: Int,
        /** What it adds to each year of the deal, this one included. */
        val addsPerYear: Int,
        /** Dead money if he is released afterwards, against what it is now. */
        val deadBefore: Int,
        val deadAfter: Int,
    )

    fun restructurePreview(player: Player, year: Int = 0): Restructure? {
        val contract = player.contract ?: return null
        val i = contract.yearIndex(year)
        if (i !in 0 until contract.years || contract.years - i < 2) return null
        val room = contract.baseSalary[i] - Contract.MIN_BASE_SALARY
        if (room <= 0) return null
        val moved = (room * CapManagement.RESTRUCTURE_SHARE).toInt()
        if (moved <= 0) return null
        val after = contract.restructure(year, moved)
        val frees = contract.capHit(year) - after.capHit(year)
        if (frees <= 0) return null
        return Restructure(
            player = player,
            frees = frees,
            addsPerYear = after.proratedBonus - contract.proratedBonus,
            deadBefore = contract.deadCap(year).thisYear,
            deadAfter = after.deadCap(year).thisYear,
        )
    }

    /** Moves what the club can move, at the share a front office moves it. */
    fun restructure(league: League, team: TeamId, playerId: PlayerId, week: Int = 0): Outcome {
        val man = league.playersById[playerId]
            ?: return Outcome.Refused("There is no such player.")
        if (man.teamId != team) return Outcome.Refused("${man.name} does not play for this club.")
        val preview = restructurePreview(man, league.year)
            ?: return Outcome.Refused(
                "Nothing to move: ${man.lastName} is on the minimum, or his deal is in its last year.")
        val moved = (man.contract!!.baseSalary[man.contract!!.yearIndex(league.year)] -
            Contract.MIN_BASE_SALARY) * CapManagement.RESTRUCTURE_SHARE
        val after = man.contract!!.restructure(league.year, moved.toInt())
        return done(
            Transaction.of(league.year, week, TransactionKind.RESTRUCTURED, team, man,
                amount = preview.frees),
            league.copy(players = league.players.map {
                if (it.id == playerId) it.copy(contract = after) else it
            }),
            "${man.position.label} ${man.name} is restructured: ${money(preview.frees)} freed now, " +
                "${money(preview.addsPerYear)} on every year of the deal.",
        )
    }

    /** Cap figures are in thousands, and nobody reads 840 as money. */
    private fun money(thousands: Int): String =
        if (thousands >= 1_000) "$%.1fM".format(thousands / 1_000.0) else "$%dk".format(thousands)
}
