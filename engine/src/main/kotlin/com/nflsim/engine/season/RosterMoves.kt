package com.nflsim.engine.season

import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.model.Transaction
import com.nflsim.engine.model.TransactionKind
import com.nflsim.engine.offseason.TeamNeeds
import com.nflsim.engine.offseason.rosterValue
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable

/**
 * The Tuesday transactions wire: what a club does between games when men
 * get hurt.
 *
 * A player out for [IR_WEEKS] or more goes on injured reserve. He stays on
 * the club's books - his contract still counts - but not against the 53, so
 * the club can fill his place. Every club does that; it is the medical
 * staff's call, not a football one.
 *
 * The rest is the front office's, and the league's own clubs make it here:
 * fill the open place at the position that most needs a body, from their
 * own practice squad first and the street second; top the squad back up;
 * and when a man comes off reserve, cut whoever was keeping his place warm.
 * The user's club is left to make those calls itself, except that a healed
 * man walks back into an empty place.
 */
object RosterMoves {

    /** The NFL's minimum stay on injured reserve, in games. */
    const val IR_WEEKS = 4

    fun onReserve(p: Player): Boolean = p.status == PlayerStatus.IR

    /** Everyone who counts against the 53. */
    fun active(league: League, team: TeamId): List<Player> =
        league.roster(team).filterNot(::onReserve)

    /** A man on reserve who is healthy again and waiting for a place. */
    fun readyToReturn(p: Player): Boolean = onReserve(p) && p.injuryWeeks == 0

    fun afterWeek(
        league: League,
        tuning: TuningTable,
        userTeam: TeamId? = null,
        weeksLeft: Int = Schedule.WEEKS,
    ): League {
        // The wire dates a move by the game it comes before.
        val wire = Schedule.WEEKS - weeksLeft + 1
        var l = placeOnReserve(league, wire)
        l.teams.forEach { team ->
            l = if (team.id != userTeam) manage(l, team.id, tuning, weeksLeft, wire)
                else returnWhereRoom(l, team.id, wire)
        }
        return l
    }

    /**
     * The user's healed men come back on their own while there is a place
     * for them, as they did before reserve existed. Only a club that filled
     * his place has a decision to make, and that one is left to the user.
     */
    private fun returnWhereRoom(start: League, team: TeamId, wire: Int): League {
        var league = start
        league.roster(team).filter(::readyToReturn).forEach { back ->
            val outcome = Transactions.activate(league, team, back.id, week = wire)
            if (outcome is Transactions.Outcome.Done) league = outcome.league
        }
        return league
    }

    /** Everyone out long enough goes on reserve, at every club. */
    private fun placeOnReserve(league: League, wire: Int): League {
        val clubOf = league.teams.flatMap { t -> t.roster.map { it to t.id } }.toMap()
        val moves = mutableListOf<Transaction>()
        val players = league.players.map {
            val club = clubOf[it.id]
            if (club != null && it.status == PlayerStatus.ACTIVE && it.injuryWeeks >= IR_WEEKS) {
                moves += Transaction.of(league.year, wire, TransactionKind.INJURED_RESERVE, club, it)
                it.copy(status = PlayerStatus.IR)
            } else it
        }
        return league.copy(players = players).logged(*moves.toTypedArray())
    }

    private fun manage(start: League, team: TeamId, tuning: TuningTable, weeksLeft: Int, wire: Int): League {
        var league = start
        val club = league.team(team)
        val offence = SchemeCatalog.tuned(club.offenseScheme, tuning)
        val defence = SchemeCatalog.tuned(club.defenseScheme, tuning)
        fun value(p: Player) = rosterValue(
            p, if (p.position.isOffense) offence else defence, league.year, club.gm.winNowVsFuture)
        fun apply(outcome: Transactions.Outcome): Boolean {
            if (outcome is Transactions.Outcome.Done) league = outcome.league
            return outcome is Transactions.Outcome.Done
        }

        // Back off reserve: he takes his place again and the stopgap goes.
        league.roster(team).filter(::readyToReturn).forEach { back ->
            val active = active(league, team)
            if (active.size >= Transactions.ROSTER_LIMIT) {
                val spare = active.filter { it.position == back.position }.minByOrNull(::value)
                    ?: active.minByOrNull(::value)
                    ?: return@forEach
                apply(Transactions.release(league, team, spare.id, week = wire))
            }
            apply(Transactions.activate(league, team, back.id, week = wire))
        }

        // Fill the open places where the club is thinnest.
        var guard = 0
        while (active(league, team).size < Transactions.ROSTER_LIMIT && guard++ < Transactions.ROSTER_LIMIT) {
            val healthy = active(league, team).filter { it.injuryWeeks == 0 }.groupingBy { it.position }.eachCount()
            val need = TeamNeeds.ROSTER_TEMPLATE.maxByOrNull { (pos, required) -> required - (healthy[pos] ?: 0) }!!.key
            val squad = league.team(team).practiceSquad.map { league.player(it) }
            val pick = squad.filter { it.position == need }.maxByOrNull(::value)
                ?: Transactions.freeAgents(league).filter { it.position == need }.maxByOrNull(::value)
                ?: squad.maxByOrNull(::value)
                ?: break
            if (!apply(Transactions.sign(league, team, pick.id, weeksLeft = weeksLeft, week = wire))) break
        }

        // And the squad back to sixteen: the street first, then camp bodies.
        // The seed moves with the league, so the same week signs the same men.
        if (league.team(team).practiceSquad.size < PracticeSquads.SIZE) {
            val (players, squads) = PracticeSquads.fill(
                listOf(league.team(team)), league.players, league.year, tuning,
                SplitMixRng(league.seed).split("squad|${team.v}|${league.year}|${league.players.size}"),
            )
            league = league.copy(
                players = players,
                teams = league.teams.map {
                    if (it.id == team) it.copy(practiceSquad = squads.getValue(team)) else it
                },
            )
        }
        return league
    }
}
