package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.AttributeBar
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.StatusTag
import com.example.nflsimtext.ui.components.TagTone
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ratingColor
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.OverallWeights
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.SchemeFitGrade
import com.nflsim.engine.ratings.TraitScouting
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.season.Dynasty

/**
 * One player, as his own club sees him (docs/DESIGN.md 5): the ratings his
 * position is judged on, what the staff has learned of his traits, and how he
 * fits the scheme. Nothing the club has not seen is given a number.
 */
@Composable
fun PlayerCardScreen(dynasty: Dynasty, playerId: Int?, onBack: () -> Unit = {}) {
    val c = NdTheme.colors
    val player = playerId?.let { id ->
        dynasty.league.roster(dynasty.userTeamId).firstOrNull { it.id.v == id }
    }
    if (player == null) {
        Column(Modifier.fillMaxSize().padding(NdTheme.spacing.xl)) {
            Text("No player chosen.", style = NdTheme.type.title, color = c.chalk)
            SecondaryButton("Back to the roster", onBack, Modifier.padding(top = NdTheme.spacing.m))
        }
        return
    }

    val team = dynasty.team
    val scheme = SchemeCatalog.tuned(
        if (player.position.isOffense) team.offenseScheme else team.defenseScheme,
        dynasty.league.tuning,
    )
    val fit = schemeFit(player, scheme)
    val seen = TraitScouting.confidence(player.clubYears, team.staff.scoutingDept)
    val lens = lensFor(dynasty, player)
    val ovr = lens.view(overall(player))

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NdTheme.spacing.screen, end = NdTheme.spacing.screen,
            top = NdTheme.spacing.l, bottom = NdTheme.spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.blockGap),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(player.name, style = NdTheme.type.display, color = c.chalk)
                    Text(bio(player, dynasty), style = NdTheme.type.label, color = c.chalkDim)
                }
                Text(
                    "${ovr.point}",
                    style = NdTheme.type.scoreboard,
                    color = ratingColor(ovr.point, c),
                )
            }
            Text(
                if (ovr.exact) "The club knows him."
                else "An estimate. The club has him somewhere from ${ovr.low} to ${ovr.high}.",
                style = NdTheme.type.caption, color = c.chalkDim,
            )
            // Hurt, and how he is playing this month (SPEC 10.1). Form is not
            // talent: it is worth a few points on Sunday and nothing else.
            val form = player.form
            if (player.injuryWeeks > 0 || form <= -FORM_SHOWN || form >= FORM_SHOWN) {
                Row(
                    Modifier.padding(top = NdTheme.spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                ) {
                    if (player.injuryWeeks > 0) {
                        StatusTag(
                            injuryLabel(player.injuryWeeks, dynasty),
                            if (player.injuryWeeks > 4) TagTone.URGENT else TagTone.NEUTRAL,
                        )
                    }
                    if (form >= FORM_SHOWN) {
                        StatusTag(
                            if (form >= FORM_STRONG) "Playing out of his mind" else "In form",
                            TagTone.INFO,
                        )
                    } else if (form <= -FORM_SHOWN) {
                        StatusTag(
                            if (form <= -FORM_STRONG) "Lost his form" else "Out of form",
                            TagTone.NEUTRAL,
                        )
                    }
                }
            }
        }

        item {
            SituationBlock(
                "What the position asks for",
                meta = player.position.label,
            ) {
                OverallWeights.forPosition(player.position).entries
                    .sortedByDescending { it.value }
                    .take(10)
                    .forEach { (rating, _) ->
                        val view = lens.view(player.ratings[rating])
                        AttributeBar(
                            ratingLabel(rating),
                            view.point,
                            band = if (view.exact) null else view.low..view.high,
                            text = view.text,
                        )
                    }
            }
        }

        item {
            SituationBlock("Scheme fit", meta = scheme.name) {
                val inScheme = lens.view(overall(player, scheme))
                AttributeBar(
                    "In this scheme",
                    inScheme.point,
                    band = if (inScheme.exact) null else inScheme.low..inScheme.high,
                    text = inScheme.text,
                )
                Text(
                    "Fit ${SchemeFitGrade.letter(fit)}, against ${ovr.text} on open ground.",
                    style = NdTheme.type.body, color = c.chalkDim,
                    modifier = Modifier.padding(top = NdTheme.spacing.xs),
                )
            }
        }

        item {
            SituationBlock(
                "Traits",
                meta = if (seen >= 0.9f) "Known" else if (seen >= 0.4f) "Scouted" else "Unscouted",
            ) {
                if (seen < 0.4f) {
                    Text(
                        "Hidden. Scout to reveal.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                } else {
                    TRAITS.forEach { (label, value) ->
                        val grade = TraitScouting.grade(value(player), seen, player.id.v, label)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = NdTheme.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label.replaceFirstChar { it.uppercase() },
                                style = NdTheme.type.data, color = c.chalk,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (grade == "?") "Hidden" else grade,
                                style = NdTheme.type.data,
                                color = if (grade == "?") c.chalkDim else c.chalk,
                            )
                        }
                    }
                }
            }
        }

        if (player.careerStats.years > 0) {
            item {
                SituationBlock(
                    "Career",
                    meta = if (player.careerStats.years == 1) "1 season" else "${player.careerStats.years} seasons",
                ) {
                    val sheet = careerSheet(player, dynasty)
                    if (sheet == null) {
                        Text(
                            "No stat line for his position. What he does does not " +
                                "show up in a box score.",
                            style = NdTheme.type.body, color = c.chalkDim,
                        )
                    } else {
                        DataTable(columns = sheet.columns, rows = sheet.rows)
                    }
                }
            }
        }

        player.contract?.let { contract ->
            item {
                SituationBlock("Contract", meta = "Signed ${contract.signedYear}") {
                    val left = contract.signedYear + contract.years - 1 - dynasty.year
                    Text(
                        when {
                            left < 0 -> "Expired."
                            left == 0 -> "Final year."
                            left == 1 -> "1 year left after this one."
                            else -> "$left years left after this one."
                        },
                        style = NdTheme.type.data, color = c.chalk,
                    )
                    Text(
                        "${contract.years} years, ${money(contract.baseSalary.sum() + contract.signingBonus)} total.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                }
            }
        }

        // Every move he has been part of, from the wire (SPEC 4.7).
        val moves = dynasty.league.transactions.filter { it.player == player.id.v }.asReversed()
        if (moves.isNotEmpty()) {
            val abbrev = dynasty.league.teams.associate { it.id.v to it.abbrev }
            item {
                SituationBlock("Moves", meta = "${moves.size}") {
                    DataTable(
                        columns = listOf(
                            ColumnSpec("Year", 0.8f, numeric = true),
                            ColumnSpec("When", 0.9f),
                            ColumnSpec("Club", 0.7f),
                            ColumnSpec("Move", 2.4f),
                            ColumnSpec("Terms", 1.3f, numeric = true),
                        ),
                        rows = moves.map { line ->
                            RowData(listOf(
                                "${line.year}",
                                weekShort(line.week),
                                abbrev[line.team] ?: "-",
                                line.kind.short,
                                terms(line, abbrev),
                            ))
                        },
                    )
                }
            }
        }

        item { SecondaryButton("Back to the roster", onBack) }
    }
}

/** Form shown from here, and called extreme from here (-100..100). */
private const val FORM_SHOWN = 25
private const val FORM_STRONG = 60

private val TRAITS: List<Pair<String, (Player) -> Int>> = listOf(
    "coachability" to { p: Player -> p.traits.coachability },
    "work ethic" to { p: Player -> p.traits.workEthic },
    "football iq" to { p: Player -> p.traits.footballIq },
    "consistency" to { p: Player -> p.traits.consistency },
    "clutch" to { p: Player -> p.traits.clutch },
    "injury proneness" to { p: Player -> p.traits.injuryProneness },
)

private fun bio(player: Player, dynasty: Dynasty): String = buildString {
    append(player.position.label)
    player.jersey?.let { append(", #$it") }
    append(", age ${player.age(dynasty.year)}")
    append(", ${dynasty.team.name}")
}

private fun money(dollars: Int): String = when {
    dollars >= 1_000_000 -> "$%.1fm".format(dollars / 1_000_000.0)
    dollars >= 1_000 -> "$%dk".format(dollars / 1_000)
    else -> "$$dollars"
}

/** THROW_ACC_SHORT reads as "Throw accuracy short" on a card. */
internal fun ratingLabel(rating: RatingId): String = rating.name
    .split('_')
    .joinToString(" ") { word ->
        when (word) {
            "ACC" -> "accuracy"
            "IQ" -> "IQ"
            "RESIST" -> "resistance"
            "RECOGNITION" -> "recognition"
            else -> word.lowercase()
        }
    }
    .replaceFirstChar { it.uppercase() }

/** A career table, shaped to the position: a guard has no stat line. */
private class CareerSheet(val columns: List<ColumnSpec>, val rows: List<RowData>)

private fun careerSheet(player: Player, dynasty: Dynasty): CareerSheet? {
    fun club(id: Int?) = dynasty.league.teams.firstOrNull { it.id.v == id }?.abbrev ?: "--"
    val seasons = player.careerStats.seasons.sortedByDescending { it.year }
    val year = ColumnSpec("Year", 1.0f)
    val team = ColumnSpec("Club", 0.9f)
    return when (player.position) {
        Position.QB -> CareerSheet(
            listOf(year, team, ColumnSpec("Comp", 1.2f, numeric = true),
                ColumnSpec("Yards", 1.1f, numeric = true),
                ColumnSpec("TD", 0.6f, numeric = true), ColumnSpec("Int", 0.6f, numeric = true)),
            seasons.map { s ->
                RowData(listOf(
                    "${s.year}", club(s.team),
                    "${s.stats.completions}/${s.stats.passAttempts}",
                    "${s.stats.passYards}", "${s.stats.passTouchdowns}",
                    "${s.stats.interceptionsThrown}",
                ))
            },
        )
        Position.RB, Position.FB -> CareerSheet(
            listOf(year, team, ColumnSpec("Carries", 1.1f, numeric = true),
                ColumnSpec("Yards", 1.1f, numeric = true), ColumnSpec("TD", 0.6f, numeric = true)),
            seasons.map { s ->
                RowData(listOf("${s.year}", club(s.team), "${s.stats.carries}",
                    "${s.stats.rushYards}", "${s.stats.rushTouchdowns}"))
            },
        )
        Position.WR, Position.TE -> CareerSheet(
            listOf(year, team, ColumnSpec("Catches", 1.1f, numeric = true),
                ColumnSpec("Yards", 1.1f, numeric = true), ColumnSpec("TD", 0.6f, numeric = true)),
            seasons.map { s ->
                RowData(listOf("${s.year}", club(s.team), "${s.stats.receptions}",
                    "${s.stats.receivingYards}", "${s.stats.receivingTouchdowns}"))
            },
        )
        Position.EDGE, Position.DT, Position.LB, Position.CB, Position.S -> CareerSheet(
            listOf(year, team, ColumnSpec("Tackles", 1.1f, numeric = true),
                ColumnSpec("Sacks", 0.9f, numeric = true), ColumnSpec("Int", 0.6f, numeric = true)),
            seasons.map { s ->
                // Tackles read combined, the way a leaderboard does.
                RowData(listOf("${s.year}", club(s.team), "${s.stats.combinedTackles}",
                    "${s.stats.sacks}", "${s.stats.interceptions}"))
            },
        )
        else -> null
    }
}
