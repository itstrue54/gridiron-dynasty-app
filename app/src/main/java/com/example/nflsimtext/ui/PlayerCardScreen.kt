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
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.StatusTag
import com.example.nflsimtext.ui.components.TagTone
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ratingColor
import com.nflsim.engine.model.Player
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
                    "${overall(player)}",
                    style = NdTheme.type.scoreboard,
                    color = ratingColor(overall(player), c),
                )
            }
            if (player.injuryWeeks > 0) {
                Row(Modifier.padding(top = NdTheme.spacing.s)) {
                    StatusTag(
                        if (player.injuryWeeks == 1) "Out 1 week" else "Out ${player.injuryWeeks} weeks",
                        if (player.injuryWeeks > 4) TagTone.URGENT else TagTone.NEUTRAL,
                    )
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
                        AttributeBar(ratingLabel(rating), player.ratings[rating])
                    }
            }
        }

        item {
            SituationBlock("Scheme fit", meta = scheme.name) {
                AttributeBar("In this scheme", overall(player, scheme))
                Text(
                    "Fit ${SchemeFitGrade.letter(fit)}, against ${overall(player)} on open ground.",
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

        item { SecondaryButton("Back to the roster", onBack) }
    }
}

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
