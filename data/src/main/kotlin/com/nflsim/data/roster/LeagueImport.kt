package com.nflsim.data.roster

import com.nflsim.engine.gen.LeagueGenerator
import com.nflsim.engine.model.Contract
import com.nflsim.engine.model.League
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.PracticeSquads
import com.nflsim.engine.season.Transactions

/**
 * A league built from the user's own roster file (SPEC 9.4): the generated
 * league, with his clubs laid over it. Each club he names takes a slot - by
 * its abbreviation if the generated league has one, else by conference and
 * division - and his players become its roster. A club he does not name
 * stays fictional, so a file with one team works as well as one with 32.
 *
 * Contracts come from the players they replace: his best quarterback takes
 * the generated starter's deal, his second the backup's, so a club's payroll
 * and the cap stay what the league was built around. Positions he leaves
 * short keep generated players, so every club can take the field; past 53,
 * his extras go to the practice squad.
 */
object LeagueImport {

    data class Result(
        /** Null when nothing usable was in the file. */
        val league: League?,
        val report: ImportReport?,
        /** What was placed where, and anything that did not go as written. */
        val notes: List<String>,
        val errors: List<String>,
    ) {
        val ok: Boolean get() = league != null && errors.isEmpty()
    }

    fun build(text: String, year: Int, seed: Long): Result {
        val errors = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val (clubsInFile, table) = if (RosterJson.looksLikeJson(text)) {
            val parsed = RosterJson.parse(text)
            errors += parsed.problems
            parsed.clubs to parsed.table
        } else {
            emptyList<RosterJson.Club>() to Csv.parse(text)
        }
        // Nothing to read: say why once - a file that is not JSON is not also "empty".
        if (table.size < 2) {
            return Result(null, null, notes, errors.ifEmpty { listOf("no players in the file") })
        }

        // A flat list names its clubs only by the team each player is on.
        val teamColumn = table.first().indexOfFirst { RosterFormat.normalise(it) in RosterFormat.Columns.TEAM }
        val clubs = clubsInFile.ifEmpty {
            if (teamColumn < 0) emptyList()
            else table.drop(1).mapNotNull { it.getOrNull(teamColumn)?.trim()?.uppercase()?.takeIf(String::isNotEmpty) }
                .distinct().map { RosterJson.Club(it, null, null, null, null) }
        }
        if (clubs.isEmpty()) {
            return Result(null, null, notes, errors + "no clubs: give each player a \"team\", or list \"teams\"")
        }
        if (clubs.size > 32) return Result(null, null, notes, errors + "${clubs.size} clubs; a league has 32")
        clubs.groupBy { it.abbrev }.filter { it.value.size > 1 }.keys.forEach { errors += "$it is in the file twice" }

        val base = LeagueGenerator.generate(year, seed)
        val slots = placeClubs(base, clubs, notes)

        val result = RosterImporter.importTable(
            table, year, seed,
            firstPlayerId = (base.players.maxOf { it.id.v }) + 1,
            teamIdFor = { abbrev -> slots.entries.firstOrNull { it.value.abbrev == abbrev }?.key },
            rowOffset = if (RosterJson.looksLikeJson(text)) 1 else 2,
        )
        errors += result.report.errors.map { "player ${it.row} (${it.player}): ${it.message}" }

        // The clubs, renamed, with every other club's abbreviation kept clear of theirs.
        val taken = slots.values.map { it.abbrev }.toSet()
        var teams = base.teams.map { t ->
            val club = slots[t.id]
            when {
                club != null -> t.copy(abbrev = club.abbrev, city = club.city ?: t.city, nickname = club.nickname ?: t.nickname)
                t.abbrev in taken -> t.copy(abbrev = t.abbrev + "X").also { notes += "the fictional ${t.name} are ${it.abbrev} now: ${t.abbrev} is yours" }
                else -> t
            }
        }

        val imported = result.players.groupBy { it.teamId }
        val byId = base.players.associateBy { it.id }.toMutableMap()
        val dropped = mutableSetOf<Int>()
        val free = mutableListOf<Player>()

        teams = teams.map { team ->
            if (team.id !in slots) return@map team
            val mine = imported[team.id].orEmpty()
            if (mine.isEmpty()) { notes += "${team.abbrev}: no players in the file, kept the generated roster"; return@map team }
            val generated = team.roster.mapNotNull { byId[it] }
            val onRoster = mutableListOf<Player>()
            // Position by position: his men take the generated men's deals in order.
            (mine.map { it.position } + generated.map { it.position }).distinct().forEach { pos ->
                val his = mine.filter { it.position == pos }.sortedByDescending { overall(it) }
                val theirs = generated.filter { it.position == pos }.sortedByDescending { it.capHit(year) }
                his.forEachIndexed { k, p ->
                    val deal = theirs.getOrNull(k)?.contract ?: Contract.of(2, Contract.MIN_BASE_SALARY * 2, year)
                    theirs.getOrNull(k)?.let { dropped += it.id.v }
                    onRoster += p.copy(teamId = team.id, status = PlayerStatus.ACTIVE, contract = deal)
                }
                // Short at a position: the generated men he did not replace stay.
                onRoster += theirs.drop(his.size)
            }
            // Past 53, the least of his extras go to the practice squad, then to the street.
            val ranked = onRoster.sortedByDescending { overall(it) }
            val keep = ranked.take(Transactions.ROSTER_LIMIT)
            val over = ranked.drop(Transactions.ROSTER_LIMIT)
            val squad = over.take(PracticeSquads.SIZE).map { it.copy(teamId = null, status = PlayerStatus.PRACTICE_SQUAD, contract = null) }
            free += over.drop(PracticeSquads.SIZE).map { it.copy(teamId = null, contract = null) }
            // The generated squad makes way for his.
            val squadIds = if (squad.isEmpty()) team.practiceSquad
                else (squad.map { it.id } + team.practiceSquad.filterNot { it.v in dropped }).take(PracticeSquads.SIZE)
            team.practiceSquad.filterNot { it in squadIds }.forEach { dropped += it.v }
            (keep + squad).forEach { byId[it.id] = it }
            notes += "${team.abbrev}: ${mine.size} players from the file" +
                if (over.isNotEmpty()) ", ${squad.size} to the practice squad" else ""
            team.copy(roster = keep.map { it.id }, practiceSquad = squadIds)
        }
        // Players named to no club in the file are free agents.
        result.players.filter { it.teamId == null }.forEach { free += it.copy(status = PlayerStatus.ACTIVE, contract = null) }
        free.forEach { byId[it.id] = it }
        dropped.forEach { byId.remove(com.nflsim.engine.model.PlayerId(it)) }

        val league = base.copy(teams = teams, players = byId.values.sortedBy { it.id.v })
        return Result(league, result.report, notes, errors)
    }

    /** Which generated slot each of his clubs takes. */
    private fun placeClubs(base: League, clubs: List<RosterJson.Club>, notes: MutableList<String>): Map<TeamId, RosterJson.Club> {
        val out = linkedMapOf<TeamId, RosterJson.Club>()
        fun free(t: Team) = t.id !in out
        clubs.forEach { club ->
            val fits = { t: Team ->
                (club.conference == null || t.conference == club.conference) &&
                    (club.division == null || t.division == club.division)
            }
            val slot = base.teams.firstOrNull { free(it) && it.abbrev == club.abbrev && fits(it) }
                ?: base.teams.firstOrNull { free(it) && fits(it) }
                ?: base.teams.firstOrNull { free(it) && (club.conference == null || it.conference == club.conference) }
                    ?.also { notes += "${club.abbrev}: its division was full, placed in the ${it.divisionName}" }
                ?: base.teams.first(::free)
                    .also { notes += "${club.abbrev}: its conference was full, placed in the ${it.divisionName}" }
            out[slot.id] = club
        }
        return out
    }
}
