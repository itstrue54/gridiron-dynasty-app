package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.offseason.CapManagement
import com.nflsim.engine.offseason.Intent
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.Dynasty

/**
 * The offseason, as news.
 *
 * Everything on this screen already happened in the engine - retirements, the
 * draft, the market, players asking out. It ran invisibly for two milestones,
 * which meant the most interesting part of a dynasty was something only a
 * terminal could see.
 */
@Composable
fun OffseasonScreen(dynasty: Dynasty) {
    val report = dynasty.lastOffseason
    val team = dynasty.team

    if (report == null) {
        Column(Modifier.padding(16.dp)) {
            Text("No offseason yet", fontFamily = Mono, fontSize = 18.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Play a season through the playoffs and the year will turn over: " +
                    "players retire and develop, contracts expire, the draft runs, " +
                    "and the market opens.",
                fontFamily = Mono, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val roster = dynasty.league.roster(team.id)
    val cap = CapManagement.capFor(dynasty.year)
    val committed = roster.sumOf { it.capHit(dynasty.year) }
    val dead = team.finances.deadMoney

    LazyColumn(Modifier.fillMaxWidth()) {

        item {
            Column(Modifier.padding(16.dp)) {
                Text("The ${report.year} offseason", fontFamily = Mono, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold)
                Text(
                    "${report.retirementCount} retired  ·  ${report.draftedCount} drafted  " +
                        "·  ${report.capCasualties} released",
                    fontFamily = Mono, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- your cap sheet, which is the whole game ----
        item {
            Section("Your cap sheet") {
                Line("salary cap", money(cap))
                Line("committed", money(committed))
                if (dead > 0) Line("dead money", money(dead), warn = true)
                Line("space", money(cap - committed - dead), bold = true)
                Spacer(Modifier.height(8.dp))
                Text("BIGGEST HITS", fontFamily = Mono, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                roster.sortedByDescending { it.capHit(dynasty.year) }.take(5).forEach { p ->
                    val sch = if (p.position.isOffense) SchemeCatalog[team.offenseScheme]
                              else SchemeCatalog[team.defenseScheme]
                    Line(
                        "${p.name}  ${p.position.label} ${overall(p, sch)}",
                        money(p.capHit(dynasty.year)),
                    )
                }
            }
        }

        // ---- what players said ----
        val yours = report.wishes.filter { it.team == team.id.v }
        if (yours.isNotEmpty()) {
            item {
                Section("Your locker room") {
                    yours.take(6).forEach { w ->
                        Line(
                            "${w.name}  ${w.position} ${w.overall}",
                            if (w.intent == Intent.TRADE_REQUEST) "TRADE REQUEST" else "",
                            warn = w.intent == Intent.TRADE_REQUEST,
                        )
                        Text(w.note, fontFamily = Mono, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        if (report.trades.isNotEmpty()) {
            item {
                Section("Trades") {
                    report.trades.forEach { t ->
                        val from = dynasty.league.team(TeamId(t.from)).abbrev
                        val to = dynasty.league.team(TeamId(t.to)).abbrev
                        Line("${t.name}  ${t.position} ${t.overall}", "$from → $to")
                        Text(
                            "${t.reason}   ·   $from carries ${money(t.deadMoney)} dead",
                            fontFamily = Mono, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // ---- your draft ----
        if (report.yourPicks.isNotEmpty()) {
            item {
                Section("Your draft") {
                    report.yourPicks.forEach { pick ->
                        val p = dynasty.league.playersById[
                            com.nflsim.engine.model.PlayerId(pick.player)]
                        val grade = p?.let {
                            val sch = if (it.position.isOffense) SchemeCatalog[team.offenseScheme]
                                      else SchemeCatalog[team.defenseScheme]
                            "${it.position.label} ${overall(it, sch)}"
                        } ?: ""
                        Line("R${pick.round}.${pick.overallPick}  ${p?.name ?: "-"}", grade)
                    }
                }
            }
        }

        // ---- the market ----
        if (report.signings.isNotEmpty()) {
            item {
                Section("Around the league") {
                    Text("SIGNINGS", fontFamily = Mono, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    report.signings.take(8).forEach { s ->
                        val to = dynasty.league.team(TeamId(s.team)).abbrev
                        Line(
                            "${s.name}  ${s.position}  →  $to",
                            "${money(s.value)}/yr  ${s.years}yr",
                            bold = s.team == team.id.v,
                        )
                    }
                }
            }
        }

        if (report.releases.isNotEmpty()) {
            item {
                Section("Cap casualties") {
                    report.releases.take(8).forEach { r ->
                        val from = dynasty.league.team(TeamId(r.team)).abbrev
                        Line(
                            "${r.name}  ${r.position} ${r.overall}  ($from)",
                            "saves ${money(r.savings)}",
                            warn = r.team == team.id.v,
                        )
                    }
                }
            }
        }

        if (report.retirements.isNotEmpty()) {
            item {
                Section("Retirements") {
                    report.retirements.take(8).forEach { r ->
                        Line("${r.name}  ${r.position} ${r.overall}", "age ${r.age}")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(title.uppercase(), fontFamily = Mono, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Line(
    left: String,
    right: String,
    bold: Boolean = false,
    warn: Boolean = false,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            left, fontFamily = Mono, fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            right, fontFamily = Mono, fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (warn) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Cap figures are in thousands. Nobody reads 184250. */
private fun money(thousands: Int): String = when {
    thousands >= 1_000 -> "$%.1fM".format(thousands / 1_000.0)
    else -> "$%dk".format(thousands)
}
