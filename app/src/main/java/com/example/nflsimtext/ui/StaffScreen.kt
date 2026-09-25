package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.nflsimtext.ui.components.AttributeBar
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.StatusTag
import com.example.nflsimtext.ui.components.TagTone
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.GamePlan
import com.nflsim.engine.offseason.CoachingCarousel
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.season.Dynasty
import kotlin.math.roundToInt

/**
 * Who coaches the club (SPEC 4.7). A staff is not decoration: the head coach's
 * development rating is what makes young players improve, his coordinators'
 * tendencies are what the play caller reads when the club has set no game plan
 * of its own, and a hot seat is what gets him fired in the spring.
 */
@Composable
fun StaffScreen(dynasty: Dynasty, onBack: () -> Unit = {}) {
    val c = NdTheme.colors
    val team = dynasty.team
    val staff = team.staff
    fun coach(id: com.nflsim.engine.model.CoachId): Coach? = dynasty.league.coaches[id]

    val head = coach(staff.headCoach)
    // The pressure this club fires at, rather than a number picked here.
    val bar = CoachingCarousel.fireBar(team)
    val offence = coach(staff.offCoordinator)
    val defence = coach(staff.defCoordinator)
    val special = coach(staff.stCoordinator)

    ScreenList {
        item {
            Column {
                Text("Staff", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Your coaches develop the players, call the games off their own " +
                        "tendencies, and keep or lose their jobs by the results.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
                // The user is the general manager; the club's own is the man he replaced.
                if (team.gm.name.isNotBlank()) {
                    Text(
                        "You run the front office in ${team.gm.name}'s place.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
            }
        }

        item {
            SituationBlock(
                "Head coach",
                meta = head?.let { "${it.age}, ${contract(it)}" } ?: "vacant",
                situation = if (head != null && head.hotSeat >= bar - WARNING) Situation.RED_ZONE
                else Situation.NORMAL,
            ) {
                if (head == null) {
                    Text("Nobody is in charge.", style = NdTheme.type.body, color = c.chalkDim)
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            head.name,
                            style = NdTheme.type.title.copy(fontWeight = FontWeight.W600),
                            color = c.chalk,
                            modifier = Modifier.padding(end = NdTheme.spacing.s),
                        )
                        if (head.hotSeat >= bar - WARNING) StatusTag("Hot seat", TagTone.URGENT)
                    }
                    Text(
                        "Runs ${SchemeCatalog[head.scheme].name}. " +
                            "Pressure ${head.hotSeat} against the $bar this club fires at.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
                    )
                    Ratings(head)
                    head.tendencies.fourthDownAggression?.let { aggression ->
                        Text(
                            "Fourth downs: " + when {
                                aggression >= 0.66f -> "he goes for it."
                                aggression >= 0.33f -> "he weighs it up."
                                else -> "he takes the points."
                            },
                            style = NdTheme.type.caption, color = c.chalkDim,
                            modifier = Modifier.padding(top = NdTheme.spacing.xs),
                        )
                    }
                }
            }
        }

        item {
            SituationBlock("Offensive coordinator", meta = SchemeCatalog[team.offenseScheme].name) {
                Coordinator(offence, offenceLines(offence?.tendencies))
            }
        }

        item {
            SituationBlock("Defensive coordinator", meta = SchemeCatalog[team.defenseScheme].name) {
                Coordinator(defence, defenceLines(defence?.tendencies))
            }
        }

        item {
            SituationBlock("Special teams", meta = special?.let { "${it.age}" } ?: "vacant") {
                Coordinator(special, emptyList())
            }
        }

        val position = staff.positionCoaches.entries
            .mapNotNull { (group, id) -> coach(id)?.let { group to it } }
            .sortedBy { it.first.name }
        if (position.isNotEmpty()) {
            item {
                SituationBlock("Position coaches", meta = "${position.size} of them") {
                    DataTable(
                        columns = listOf(
                            ColumnSpec("Group", 0.9f),
                            ColumnSpec("Coach", 2.2f),
                            ColumnSpec("Develops", 1.1f, numeric = true, tier = true),
                        ),
                        rows = position.map { (group, man) ->
                            RowData(listOf(group.name, man.name, "${man.ratings.development}"))
                        },
                    )
                }
            }
        }

        item {
            SituationBlock("The building", meta = "What the club spends on") {
                AttributeBar("Scouting", staff.scoutingDept)
                AttributeBar("Training", staff.trainingStaff)
                AttributeBar("Medical", staff.medicalStaff)
                Text(
                    "Scouting narrows what you know about players; training and " +
                        "medical work on development and injuries.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                )
            }
        }

        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

/** A coordinator: who he is, and what he calls when the club leaves him to it. */
@Composable
private fun Coordinator(coach: Coach?, lines: List<String>) {
    val c = NdTheme.colors
    if (coach == null) {
        Text("Vacant.", style = NdTheme.type.body, color = c.chalkDim)
        return
    }
    Text(
        coach.name,
        style = NdTheme.type.title.copy(fontWeight = FontWeight.W600),
        color = c.chalk,
    )
    Text(
        "${coach.age}, ${contract(coach)}. Comes from ${SchemeCatalog[coach.scheme].name}.",
        style = NdTheme.type.caption, color = c.chalkDim,
        modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
    )
    Ratings(coach)
    if (lines.isNotEmpty()) {
        Column(
            Modifier.padding(top = NdTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.xs),
        ) {
            lines.forEach { Text(it, style = NdTheme.type.caption, color = c.chalkDim) }
        }
    }
}

/** The six things a coach is rated on (SPEC 4.7). */
@Composable
private fun Ratings(coach: Coach) {
    AttributeBar("Develops players", coach.ratings.development)
    AttributeBar("Game plan", coach.ratings.gameplan)
    AttributeBar("Adjustments", coach.ratings.adjustments)
    AttributeBar("Discipline", coach.ratings.discipline)
    AttributeBar("Motivation", coach.ratings.motivation)
    AttributeBar("Evaluation", coach.ratings.evaluation)
}

/** What he calls on offence, left to himself. */
private fun offenceLines(plan: GamePlan?): List<String> = if (plan == null) emptyList() else listOfNotNull(
    plan.passRate?.let { "Throws it ${percent(it)} of the time." },
    plan.playActionRate?.let { "Play action on ${percent(it)} of early downs." },
    plan.deepShotRate?.let { "Takes a deep shot ${percent(it)} of the time." },
    plan.trailingPassScale?.let { "Chases a deficit ${"%.2f".format(it)} times as hard as most." },
)

/** And on defence. */
private fun defenceLines(plan: GamePlan?): List<String> = if (plan == null) emptyList() else listOfNotNull(
    plan.blitzRate?.let { "Blitzes ${percent(it)} of the time." },
    plan.manZoneSplit?.let { "Plays man on ${percent(it)} of snaps." },
    plan.doubleTeamRate?.let { "Doubles the best receiver ${percent(it)} of the time." },
)

private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

private fun contract(coach: Coach) = when (coach.contractYearsLeft) {
    0 -> "out of contract"
    1 -> "last year of his deal"
    else -> "${coach.contractYearsLeft} years left"
}

/** Within this much of the club's firing bar is worth saying out loud. */
private const val WARNING = 10
