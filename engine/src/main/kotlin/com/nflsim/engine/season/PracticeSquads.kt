package com.nflsim.engine.season

import com.nflsim.engine.gen.PlayerGenerator
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.offseason.rosterValue
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.tuning.TuningTable

/**
 * Sixteen men per club who practise all week and do not dress on Sunday
 * (CBA Article 33, as amended in 2020).
 *
 * A practice squad player is a free agent with a club that trains him: he
 * has no contract with it, so any other club may sign him to its 53 and he
 * goes. That is the whole point of the squad for everyone else - it is where
 * the league keeps its next injury replacement.
 *
 * Here a squad player has no team on his record (teamId is null, as for any
 * free agent) and PRACTICE_SQUAD as his status; which club holds him is on
 * the club (Team.practiceSquad). Squads dissolve each spring and are chosen
 * again after the cut to 53, from whoever the cut left.
 *
 * Not modelled: squad pay, which counts against the real cap at roughly
 * $230k a man. It is under half a percent of the cap per club, and charging
 * it would mean contracts for men who can leave any Tuesday.
 */
object PracticeSquads {

    const val SIZE = 16

    /** Squad places for men past the development window (the 2020 CBA's six). */
    const val VETERANS = 6

    /** Accrued seasons a man can have and still count as developmental. */
    const val DEVELOPMENTAL_SEASONS = 2

    /** Nobody stashes a whole position room on the squad. */
    const val PER_POSITION = 3

    fun isVeteran(p: Player): Boolean = p.accruedSeasons > DEVELOPMENTAL_SEASONS

    /** The club training him, or null for anyone not on a squad. */
    fun clubOf(league: League, id: PlayerId): TeamId? =
        league.teams.firstOrNull { id in it.practiceSquad }?.id

    /** Unsigned, unretired, and nobody's squad. */
    fun unattached(p: Player): Boolean =
        p.teamId == null && p.status != PlayerStatus.RETIRED && p.status != PlayerStatus.PRACTICE_SQUAD

    /** Whether a club's squad has a place for him, by the CBA's count. */
    fun hasRoom(squad: List<Player>, p: Player): Boolean =
        squad.size < SIZE &&
            (!isVeteran(p) || squad.count(::isVeteran) < VETERANS) &&
            squad.count { it.position == p.position } < PER_POSITION

    /** Everyone back on the street; the spring does this before free agency. */
    fun dissolve(players: List<Player>): List<Player> = players.map {
        if (it.status == PlayerStatus.PRACTICE_SQUAD) it.copy(status = PlayerStatus.FREE_AGENT) else it
    }

    /**
     * Every club fills its squad from the unattached, one man a turn and the
     * order snaking so the first club in the list does not take the sixteen
     * best. Where the street runs dry, camp bodies are signed off campuses
     * (generated), which is where most real squad players come from anyway.
     */
    fun fill(
        teams: List<Team>,
        players: List<Player>,
        year: Int,
        tuning: TuningTable,
        rng: Rng,
    ): Pair<List<Player>, Map<TeamId, List<PlayerId>>> {
        val byId = players.associateBy { it.id }.toMutableMap()
        val squads = teams.associate { t ->
            t.id to t.practiceSquad.mapNotNull { byId[it] }.toMutableList()
        }
        val pool = players.filter(::unattached).toMutableList()

        val schemes = teams.associate { t ->
            t.id to (SchemeCatalog.tuned(t.offenseScheme, tuning) to SchemeCatalog.tuned(t.defenseScheme, tuning))
        }
        fun value(team: Team, p: Player): Float {
            val (off, def) = schemes.getValue(team.id)
            return rosterValue(p, if (p.position.isOffense) off else def, year, team.gm.winNowVsFuture)
        }

        var nextId = (players.maxOfOrNull { it.id.v } ?: 0) + 1
        for (round in 0 until SIZE) {
            val order = if (round % 2 == 0) teams else teams.reversed()
            order.forEach { team ->
                val squad = squads.getValue(team.id)
                if (squad.size >= SIZE) return@forEach
                val pick = pool.filter { hasRoom(squad, it) }.maxByOrNull { value(team, it) }
                    ?: campBody(PlayerId(nextId++), squad, year, rng)
                pool.remove(pick)
                val signed = pick.copy(status = PlayerStatus.PRACTICE_SQUAD, contract = null)
                byId[signed.id] = signed
                squad += signed
            }
        }
        return byId.values.sortedBy { it.id.v } to squads.mapValues { (_, s) -> s.map { it.id } }
    }

    /** A league with every squad filled; for new leagues and saves from before squads. */
    fun fill(league: League, seed: Long): League {
        val (players, squads) = fill(
            league.teams, league.players, league.year, league.tuning,
            SplitMixRng(seed).split("practice-squads|${league.year}"))
        return league.copy(
            players = players,
            teams = league.teams.map { it.copy(practiceSquad = squads.getValue(it.id)) },
        )
    }

    /** An undrafted rookie at the first position the squad is short of. */
    private fun campBody(id: PlayerId, squad: List<Player>, year: Int, rng: Rng): Player {
        val position = CAMP_POSITIONS.first { pos -> squad.count { it.position == pos } < PER_POSITION }
        return PlayerGenerator.generate(
            id, position,
            targetOverall = CAMP_OVERALL + rng.nextInt(CAMP_OVERALL_SPREAD),
            year = year, rng = rng.split("camp|${id.v}"),
            ageBias = CAMP_AGE_BIAS,
        ).copy(accruedSeasons = 0, yearsInSystem = 0, status = PlayerStatus.PRACTICE_SQUAD)
    }

    /** Where a club wants its spare bodies, in order: the positions that get hurt. */
    private val CAMP_POSITIONS = listOf(
        Position.WR, Position.CB, Position.LB, Position.DT, Position.EDGE, Position.RB,
        Position.LG, Position.RT, Position.S, Position.TE, Position.QB, Position.C,
        Position.WR, Position.CB, Position.LB, Position.DT, Position.EDGE, Position.S,
        Position.LT, Position.RG, Position.RB, Position.TE, Position.FB,
    )

    private const val CAMP_OVERALL = 51
    private const val CAMP_OVERALL_SPREAD = 10
    /** Undrafted rookies are 22 or 23; the generator centres on 26. */
    private const val CAMP_AGE_BIAS = -4
}
