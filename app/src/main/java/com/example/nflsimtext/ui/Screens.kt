package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.Division
import com.nflsim.engine.model.Position
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.Schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Hub - the screen you live on
// ---------------------------------------------------------------------------

@Composable
fun HubScreen(dynasty: Dynasty, store: DynastyStore, scope: CoroutineScope) {
    val team = dynasty.team
    val record = dynasty.record()
    val next = dynasty.nextGame()

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(team.name, fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${team.divisionName}  ·  ${dynasty.year}",
                    fontFamily = Mono, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(record.recordText, fontFamily = Mono, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${record.pointsFor} for, ${record.pointsAgainst} against  " +
                        "(${if (record.pointDifferential >= 0) "+" else ""}${record.pointDifferential})",
                    fontFamily = Mono, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                when {
                    dynasty.phase == DynastyPhase.OFFSEASON -> {
                        val champ = dynasty.champion?.let {
                            dynasty.league.team(com.nflsim.engine.model.TeamId(it)).name
                        }
                        Text("Season complete.", fontFamily = Mono, fontSize = 14.sp)
                        champ?.let {
                            Text("Champion: $it", fontFamily = Mono, fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    next == null && dynasty.phase == DynastyPhase.REGULAR_SEASON ->
                        Text("Week ${dynasty.week} · bye", fontFamily = Mono, fontSize = 14.sp)
                    next != null -> {
                        val home = next.home == dynasty.userTeamId
                        val opponent = dynasty.league.team(next.opponentOf(dynasty.userTeamId)!!)
                        Text("Week ${dynasty.week}", fontFamily = Mono, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${if (home) "vs" else "at"} ${opponent.name}",
                            fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${dynasty.standings().record(opponent.id).recordText}  ·  " +
                                SchemeCatalog[opponent.offenseScheme].name,
                            fontFamily = Mono, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Text("Playoffs", fontFamily = Mono, fontSize = 14.sp)
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { scope.launch { store.advance() } },
                    enabled = !store.busy && dynasty.phase != DynastyPhase.OFFSEASON,
                ) {
                    Text(
                        when (dynasty.phase) {
                            DynastyPhase.PLAYOFFS -> "Play the postseason"
                            DynastyPhase.OFFSEASON -> "Season over"
                            else -> "Advance week"
                        }
                    )
                }
            }
        }

        val last = dynasty.userResults().lastOrNull()
        if (last != null) {
            item { SectionHeader("Last result") }
            item {
                val us = last.scoreFor(dynasty.userTeamId)
                val them = last.scoreAgainst(dynasty.userTeamId)
                val opponent = dynasty.league.team(last.opponentOf(dynasty.userTeamId)!!)
                val verdict = when {
                    us > them -> "W"
                    us < them -> "L"
                    else -> "T"
                }
                TableRow {
                    Cell(verdict, 3f, bold = true)
                    Cell("$us-$them", 8f, bold = true)
                    Cell(if (last.home == dynasty.userTeamId) "vs" else "at", 4f, dim = true)
                    Cell(opponent.name, 26f)
                }
            }
        }

        item { SectionHeader("${team.divisionName}") }
        val divisionOrder = dynasty.standings().division(team.conference, team.division)
        items(divisionOrder) { id ->
            val r = dynasty.standings().record(id)
            TableRow(highlight = id == dynasty.userTeamId) {
                Cell(dynasty.league.team(id).abbrev, 6f, bold = id == dynasty.userTeamId)
                Cell(dynasty.league.team(id).nickname, 20f)
                Cell(r.recordText, 9f)
                Cell("${if (r.pointDifferential >= 0) "+" else ""}${r.pointDifferential}", 7f, dim = true)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------

@Composable
fun StandingsScreen(dynasty: Dynasty) {
    val standings = dynasty.standings()
    LazyColumn(Modifier.fillMaxWidth()) {
        Conference.entries.forEach { conference ->
            item { SectionHeader(conference.label + " Conference") }
            Division.entries.forEach { division ->
                item {
                    Text(
                        division.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontFamily = Mono, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                items(standings.division(conference, division)) { id ->
                    val r = standings.record(id)
                    TableRow(highlight = id == dynasty.userTeamId) {
                        Cell(dynasty.league.team(id).abbrev, 6f, bold = id == dynasty.userTeamId)
                        Cell(dynasty.league.team(id).nickname, 18f)
                        Cell(r.recordText, 9f)
                        Cell("${r.pointsFor}", 6f, dim = true)
                        Cell("${r.pointsAgainst}", 6f, dim = true)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------

@Composable
fun RosterScreen(dynasty: Dynasty) {
    val team = dynasty.team
    val offense = SchemeCatalog[team.offenseScheme]
    val defense = SchemeCatalog[team.defenseScheme]
    val order = listOf(
        Position.QB, Position.RB, Position.FB, Position.WR, Position.TE,
        Position.LT, Position.LG, Position.C, Position.RG, Position.RT,
        Position.EDGE, Position.DT, Position.LB, Position.CB, Position.S,
        Position.K, Position.P, Position.LS,
    )
    val roster = dynasty.league.roster(team.id)
        .sortedWith(compareBy({ order.indexOf(it.position) }, { -overall(it) }))

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(team.name, fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Offense: ${offense.name}", fontFamily = Mono, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Defense: ${defense.name}", fontFamily = Mono, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            TableRow {
                Cell("POS", 6f, dim = true)
                Cell("NAME", 24f, dim = true)
                Cell("AGE", 5f, dim = true)
                Cell("OVR", 5f, dim = true)
                Cell("FIT", 5f, dim = true)
            }
        }
        item { Rule() }
        items(roster) { p ->
            val scheme = if (p.position.isOffense) offense else defense
            val fit = schemeFit(p, scheme)
            TableRow {
                Cell(p.position.label, 6f, dim = true)
                Cell(p.name, 24f)
                Cell("${p.age(dynasty.year)}", 5f, dim = true)
                Cell("${overall(p)}", 5f, bold = true)
                Cell(
                    "${overall(p, scheme)}", 5f,
                    bold = fit >= 0.95f,
                    dim = fit <= 0.55f,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------

@Composable
fun ScheduleScreen(dynasty: Dynasty) {
    val games = dynasty.schedule.forTeam(dynasty.userTeamId).sortedBy { it.week }
    val bye = dynasty.schedule.byeWeek(dynasty.userTeamId)

    LazyColumn(Modifier.fillMaxWidth()) {
        item { SectionHeader("${dynasty.year} schedule") }
        items((1..Schedule.WEEKS).toList()) { week ->
            val game = games.firstOrNull { it.week == week }
            if (game == null) {
                TableRow(highlight = week == bye) {
                    Cell("$week", 4f, dim = true)
                    Cell("BYE", 30f, dim = true)
                }
            } else {
                val opponent = dynasty.league.team(game.opponentOf(dynasty.userTeamId)!!)
                val played = dynasty.results.firstOrNull {
                    it.week == week && it.involves(dynasty.userTeamId)
                }
                val outcome = played?.let {
                    val us = it.scoreFor(dynasty.userTeamId)
                    val them = it.scoreAgainst(dynasty.userTeamId)
                    "${if (us > them) "W" else if (us < them) "L" else "T"} $us-$them"
                } ?: ""
                TableRow(highlight = week == dynasty.week) {
                    Cell("$week", 4f, dim = true)
                    Cell(if (game.home == dynasty.userTeamId) "vs" else "at", 4f, dim = true)
                    Cell(opponent.name, 24f)
                    Cell(outcome, 10f, bold = outcome.startsWith("W"))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------

@Composable
fun BoxScoreScreen(dynasty: Dynasty) {
    val game = dynasty.lastGame
    if (game == null) {
        Column(Modifier.padding(24.dp)) {
            Text("No games played yet.", fontFamily = Mono, fontSize = 14.sp)
        }
        return
    }

    val home = dynasty.league.team(game.home)
    val away = dynasty.league.team(game.away)
    val h = game.boxScore.home
    val a = game.boxScore.away
    val ids = dynasty.league.roster(dynasty.userTeamId).associateBy { it.id.v }

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(away.name, fontFamily = Mono, fontSize = 16.sp)
                    Text("${game.awayScore}", fontFamily = Mono, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(home.name, fontFamily = Mono, fontSize = 16.sp)
                    Text("${game.homeScore}", fontFamily = Mono, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }

        item { SectionHeader("Team stats") }
        item {
            TableRow {
                Cell("", 20f); Cell(away.abbrev, 9f, dim = true); Cell(home.abbrev, 9f, dim = true)
            }
        }
        item { Rule() }
        val rows = listOf(
            "First downs" to (a.firstDowns.toString() to h.firstDowns.toString()),
            "Total yards" to (a.totalYards.toString() to h.totalYards.toString()),
            "Rushing" to ("${a.rushAttempts}-${a.rushYards}" to "${h.rushAttempts}-${h.rushYards}"),
            "Passing" to (a.passYards.toString() to h.passYards.toString()),
            "Comp-Att" to ("${a.completions}-${a.passAttempts}" to "${h.completions}-${h.passAttempts}"),
            "Sacked" to (a.sacksAllowed.toString() to h.sacksAllowed.toString()),
            "3rd down" to ("${a.thirdDownConversions}-${a.thirdDownAttempts}"
                to "${h.thirdDownConversions}-${h.thirdDownAttempts}"),
            "Turnovers" to (a.turnovers.toString() to h.turnovers.toString()),
            "Penalties" to ("${a.penalties}-${a.penaltyYards}" to "${h.penalties}-${h.penaltyYards}"),
            "Possession" to (a.possessionText to h.possessionText),
        )
        items(rows) { (label, values) ->
            TableRow {
                Cell(label, 20f, dim = true)
                Cell(values.first, 9f)
                Cell(values.second, 9f)
            }
        }

        item { SectionHeader("${dynasty.team.nickname} leaders") }
        val mine = game.boxScore.players.filterKeys { it in ids }
        val passer = mine.entries.filter { it.value.passAttempts > 0 }
            .maxByOrNull { it.value.passYards }
        passer?.let { (id, s) ->
            item {
                TableRow {
                    Cell("PASS", 7f, dim = true)
                    Cell(ids[id]!!.name, 22f)
                    Cell("${s.completions}/${s.passAttempts}", 8f)
                    Cell("${s.passYards} yd", 8f)
                    Cell("${s.passTouchdowns}TD", 6f)
                }
            }
        }
        items(mine.entries.filter { it.value.carries > 0 }
            .sortedByDescending { it.value.rushYards }.take(3).toList()) { (id, s) ->
            TableRow {
                Cell("RUSH", 7f, dim = true)
                Cell(ids[id]!!.name, 22f)
                Cell("${s.carries} car", 8f)
                Cell("${s.rushYards} yd", 8f)
                Cell("${s.rushTouchdowns}TD", 6f)
            }
        }
        items(mine.entries.filter { it.value.receptions > 0 }
            .sortedByDescending { it.value.receivingYards }.take(4).toList()) { (id, s) ->
            TableRow {
                Cell("REC", 7f, dim = true)
                Cell(ids[id]!!.name, 22f)
                Cell("${s.receptions} rec", 8f)
                Cell("${s.receivingYards} yd", 8f)
                Cell("${s.receivingTouchdowns}TD", 6f)
            }
        }

        item { SectionHeader("Scoring drives") }
        items(game.drives.filter { it.isScore }) { d ->
            TableRow {
                Cell("Q${d.startQuarter}", 5f, dim = true)
                Cell(if (d.offense.name == "HOME") home.abbrev else away.abbrev, 6f)
                Cell(d.ending.label, 18f)
                Cell("${d.plays} pl", 7f, dim = true)
                Cell("${d.yards} yd", 7f, dim = true)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
