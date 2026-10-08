package com.nflsim.engine.season

import com.nflsim.engine.gen.Tendencies
import com.nflsim.engine.model.GamePlan
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.sim.GameResult
import com.nflsim.engine.sim.GameTeam
import com.nflsim.engine.tuning.TuningTable

/**
 * A week of games with injuries and wear carried from one week to the next
 * (SPEC 5.5). The dynasty and the season simulator both play their weeks
 * through here, so the two agree on who dresses and who is hurt.
 */
object WeekRunner {

    /**
     * Game-day teams from the league as it stands: players out injured do not
     * dress, and of the rest each club dresses what the CBA allows (GameDay).
     */
    fun teams(league: League, tuning: TuningTable): Map<TeamId, GameTeam> =
        league.teams.associate { team ->
            val offence = SchemeCatalog.tuned(team.offenseScheme, tuning)
            val defence = SchemeCatalog.tuned(team.defenseScheme, tuning)
            team.id to GameTeam(
                team = team,
                roster = dressed(league, team, tuning).map { Form.dressed(it, tuning) },
                offScheme = offence,
                defScheme = defence,
                aggression = GamePlan.defaultAggression(team.id.v),
                staffPlan = Tendencies.of(team.staff, league.coaches),
                adjustments = league.coaches[team.staff.headCoach]?.ratings?.adjustments ?: 50,
                discipline = league.coaches[team.staff.headCoach]?.ratings?.discipline,
                offGameplan = league.coaches[team.staff.offCoordinator]?.ratings?.gameplan,
                defGameplan = league.coaches[team.staff.defCoordinator]?.ratings?.gameplan,
                stGameplan = league.coaches[team.staff.stCoordinator]?.ratings?.gameplan,
            )
        }

    /**
     * Who a club dresses this week (GameDay): its fit men less the scratches,
     * and any practice-squad men it calls up - on the club for the game, so
     * the game knows whose they are, though the league keeps them on the squad.
     */
    fun dressed(league: League, team: com.nflsim.engine.model.Team, tuning: TuningTable): List<Player> {
        val offence = SchemeCatalog.tuned(team.offenseScheme, tuning)
        val defence = SchemeCatalog.tuned(team.defenseScheme, tuning)
        val squad = team.practiceSquad.mapNotNull { league.playersById[it] }
        return GameDay.actives(
            eligible(league.roster(team.id)), offence, defence,
            named = team.depthPins.inactive, squad = squad, callUp = team.depthPins.callUp,
            st = tuning.specialTeams,
        ).map { if (it.teamId == null) it.copy(teamId = team.id) else it }
    }

    /**
     * The league after a week: weeks out count down, the week's injuries
     * land, and wear moves with the snaps each player took - less for one
     * durable under load - easing off by the week between games.
     */
    fun afterWeek(
        league: League,
        results: List<GameResult>,
        tuning: TuningTable,
        userTeam: TeamId? = null,
        weeksLeft: Int = Schedule.WEEKS,
    ): League {
        val inj = tuning.injuries
        val hurt = results.flatMap { it.injuries }.associate { it.player to it.gamesOut }
        val snaps = results.flatMap { it.snaps.entries }.associate { it.key to it.value }
        // What each man did on Sunday, for his form (SPEC 10.1).
        val lines = results.fold(emptyMap<Int, com.nflsim.engine.stats.StatLine>()) { acc, r ->
            acc + r.boxScore.players.mapValues { (id, line) -> (acc[id] ?: com.nflsim.engine.stats.StatLine()) + line }
        }
        val played = results.flatMap { r -> listOf(r.home, r.away) }.toSet()
        val motivation = league.teams.associate { it.id to league.coaches[it.staff.headCoach]?.ratings?.motivation }
        // The squad men each club that played called up, one more game each.
        val calledUp = league.teams.filter { it.id in played }.flatMap { team ->
            val squad = team.practiceSquad.map { it.v }.toSet()
            dressed(league, team, tuning).map { it.id.v }.filter { it in squad }
        }.toSet()
        val players = league.players.map { p ->
            val load = (snaps[p.id.v] ?: 0) * inj.wearPerSnap * (1.5f - p.traits.durabilityUnderLoad / 100f)
            val wear = (p.wear * inj.wearKept + load).toInt().coerceIn(0, 100)
            val weeks = hurt[p.id.v] ?: (p.injuryWeeks - 1).coerceAtLeast(0)
            // Only clubs that played move: a bye week neither builds form nor loses it.
            val form = if (p.teamId in played) Form.next(p, lines[p.id.v], tuning, motivation[p.teamId]) else p.form
            val elevations = p.elevations + if (p.id.v in calledUp) 1 else 0
            if (weeks == p.injuryWeeks && wear == p.wear && form == p.form && elevations == p.elevations) p
            else p.copy(injuryWeeks = weeks, wear = wear, form = form, elevations = elevations)
        }
        return RosterMoves.afterWeek(league.copy(players = players), tuning, userTeam, weeksLeft)
    }

    /**
     * The players fit to dress: everyone not out injured - and where every
     * player at a position is out, the least hurt of them plays through it,
     * as a club with nobody else would. GameDay chooses the actives from them.
     */
    fun eligible(everyone: List<Player>): List<Player> {
        // Injured reserve does not dress, healed or not, until he is activated -
        // unless the club has left nobody else at his position, when he is the
        // emergency body like anyone else least hurt.
        val roster = everyone.filterNot(RosterMoves::onReserve)
        val fit = roster.filter { it.injuryWeeks == 0 }
        val uncovered = everyone.map { it.position }.toSet() - fit.map { it.position }.toSet()
        return fit + uncovered.mapNotNull { pos ->
            (roster.filter { it.position == pos }.ifEmpty { everyone.filter { it.position == pos } })
                .minByOrNull { it.injuryWeeks }
        }
    }

    /** Everyone healthy and fresh, with no call-ups used: a season, and a replayed one, start here. */
    fun healthy(league: League): League = league.copy(players = league.players.map {
        when {
            RosterMoves.onReserve(it) ->
                it.copy(status = PlayerStatus.ACTIVE, injuryWeeks = 0, wear = 0, form = 0, elevations = 0)
            it.injuryWeeks == 0 && it.wear == 0 && it.form == 0 && it.elevations == 0 -> it
            else -> it.copy(injuryWeeks = 0, wear = 0, form = 0, elevations = 0)
        }
    })
}
