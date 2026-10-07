package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.StatusTag
import com.example.nflsimtext.ui.components.TagTone
import com.example.nflsimtext.ui.theme.NdTheme
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

    // Before the first offseason there is no report, but there is a cap:
    // the front office's own numbers are the reason to open this tab.
    if (report == null) {
        ScreenList {
            item {
                Column {
                    Text("Front office", style = NdTheme.type.display, color = NdTheme.colors.chalk)
                    Text(
                        "Your cap as it stands. Play the season through the playoffs and the year " +
                            "turns over: players retire and develop, contracts expire, the draft runs, " +
                            "and the market opens. Its report lands here.",
                        style = NdTheme.type.body, color = NdTheme.colors.chalkDim,
                    )
                }
            }
            item { CapSheet(dynasty) }
        }
        return
    }

    ScreenList {
        item {
            Column {
                Text(
                    "The ${report.year} offseason",
                    style = NdTheme.type.display, color = NdTheme.colors.chalk,
                )
                Text(
                    "${report.retirementCount} retired, ${report.draftedCount} drafted, " +
                        "${report.capCasualties} released.",
                    style = NdTheme.type.body, color = NdTheme.colors.chalkDim,
                )
            }
        }

        // ---- your cap sheet, which is the whole game ----
        item { CapSheet(dynasty) }

        // ---- what players said ----
        val yours = report.wishes.filter { it.team == team.id.v }
        if (yours.isNotEmpty()) {
            item {
                Section("Your locker room") {
                    yours.take(6).forEach { w ->
                        Line(
                            "${w.name}  ${w.position} ${w.overall}",
                            if (w.intent == Intent.TRADE_REQUEST) "Trade request" else "",
                            warn = w.intent == Intent.TRADE_REQUEST,
                        )
                        Text(w.note, style = NdTheme.type.caption, color = NdTheme.colors.chalkDim)
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
                            "${t.reason}. $from carries ${money(t.deadMoney)} dead.",
                            style = NdTheme.type.caption, color = NdTheme.colors.chalkDim,
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
                            val sch = if (it.position.isOffense) SchemeCatalog.tuned(team.offenseScheme, dynasty.league.tuning)
                                      else SchemeCatalog.tuned(team.defenseScheme, dynasty.league.tuning)
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
                    Text("Signings", style = NdTheme.type.label, color = NdTheme.colors.chalkDim)
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

        if (report.coachingChanges.isNotEmpty()) {
            item {
                Section("Coaching carousel") {
                    report.coachingChanges.forEach { c ->
                        val club = dynasty.league.teams.firstOrNull { it.id.v == c.team }?.abbrev ?: ""
                        // A head coach promoted from another club's staff, with the club he left.
                        val from = c.promotedFrom?.let { id -> dynasty.league.teams.firstOrNull { it.id.v == id }?.abbrev }
                        Line(
                            "$club  ${c.fired} ${if (c.retired) "retires" else "out"}, ${c.hired} in" + (from?.let { " from $it" } ?: ""),
                            if (c.schemeChanged) "new schemes" else "same schemes",
                            bold = c.team == team.id.v,
                        )
                    }
                }
            }
        }

        if (report.gmChanges.isNotEmpty()) {
            item {
                Section("Front offices") {
                    report.gmChanges.forEach { g ->
                        val club = dynasty.league.teams.firstOrNull { it.id.v == g.team }?.abbrev ?: ""
                        Line("$club  ${g.fired} out, ${g.hired} in", if (g.rehired) "hired again" else "new GM")
                    }
                }
            }
        }

        val awards = report.awards
        val winners = listOfNotNull(
            awards.mostValuablePlayer?.let { "MVP" to it },
            awards.offensivePlayerOfTheYear?.let { "OPOY" to it },
            awards.defensivePlayerOfTheYear?.let { "DPOY" to it },
            awards.offensiveRookieOfTheYear?.let { "OROY" to it },
            awards.defensiveRookieOfTheYear?.let { "DROY" to it },
            awards.comebackPlayerOfTheYear?.let { "CPOY" to it },
            awards.coachOfTheYear?.let { "COY" to it },
        )
        if (winners.isNotEmpty()) {
            item {
                Section("Awards") {
                    winners.forEach { (label, w) ->
                        val club = dynasty.league.teams.firstOrNull { it.id.v == w.team }?.abbrev ?: ""
                        Line("$label  ${w.name}  ${w.position}", club, bold = w.team == team.id.v)
                    }
                    val yours = awards.honours.filter { it.club == team.id.v }
                    Line("Your All-Pros", "${yours.count { it.tier == 1 }} first team, ${yours.count { it.tier == 2 }} second")
                    Line("Your Pro Bowlers", "${yours.count { it.tier == 3 }}")
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

    }
}

/** Each part of the offseason is its own block (docs/DESIGN.md 5). */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) =
    SituationBlock(title) { content() }

@Composable
private fun Line(
    left: String,
    right: String,
    bold: Boolean = false,
    warn: Boolean = false,
) {
    val c = NdTheme.colors
    val style = if (bold) NdTheme.type.data.copy(fontWeight = FontWeight.W600) else NdTheme.type.data
    Row(
        Modifier.fillMaxWidth().padding(vertical = NdTheme.spacing.xs),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(left, style = style, color = c.chalk, modifier = Modifier.weight(1f))
        if (warn && right.isNotEmpty()) StatusTag(right, TagTone.URGENT)
        else Text(right, style = style, color = c.chalk)
    }
}

/** Cap figures are in thousands. Nobody reads 184250. */
private fun money(thousands: Int): String = when {
    thousands >= 1_000 -> "$%.1fM".format(thousands / 1_000.0)
    else -> "$%dk".format(thousands)
}

/** The club's cap, and where it goes: the five biggest hits, read as the staff knows them (SPEC 4.6). */
@Composable
private fun CapSheet(dynasty: Dynasty) {
    val team = dynasty.team
    val roster = dynasty.league.roster(team.id)
    val cap = CapManagement.capFor(dynasty.year)
    val committed = roster.sumOf { it.capHit(dynasty.year) }
    val dead = team.finances.deadMoney
    val carried = team.finances.carryover
    Section("Your cap sheet") {
        Line("salary cap", money(cap))
        if (carried > 0) Line("carried over", money(carried))
        Line("committed", money(committed))
        if (dead > 0) Line("dead money", money(dead), warn = true)
        Line("space", money(cap + carried - committed - dead), bold = true)
        Spacer(Modifier.height(8.dp))
        Text("Biggest hits", style = NdTheme.type.label, color = NdTheme.colors.chalkDim)
        roster.sortedByDescending { it.capHit(dynasty.year) }.take(5).forEach { p ->
            val sch = if (p.position.isOffense) SchemeCatalog.tuned(team.offenseScheme, dynasty.league.tuning)
                      else SchemeCatalog.tuned(team.defenseScheme, dynasty.league.tuning)
            Line(
                "${p.name}  ${p.position.label} ${lensFor(dynasty, p).view(overall(p, sch)).text}",
                money(p.capHit(dynasty.year)),
            )
        }
    }
}
