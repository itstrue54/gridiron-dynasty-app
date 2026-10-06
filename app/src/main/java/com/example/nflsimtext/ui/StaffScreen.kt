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
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.GmProfile
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.offseason.StaffJob
import com.nflsim.engine.model.GamePlan
import com.nflsim.engine.offseason.CoachingCarousel
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.season.Dynasty
import kotlin.math.roundToInt

/**
 * Who coaches the club (SPEC 4.7). A staff is not decoration: the head coach's
 * development rating is what makes young players improve, his coordinators'
 * tendencies are what the play caller reads when the club has set no game plan
 * of its own. The hot seat is advice: the user hires and fires, in the spring
 * window before the offseason starts (offseason.Staffing).
 */
@Composable
fun StaffScreen(
    dynasty: Dynasty,
    /** The spring window is open (DynastyStore.staffingOpen): each job can be changed. */
    open: Boolean = false,
    /** Opens one job to change it; null is the general manager's chair. */
    onJob: (StaffJob?) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val c = NdTheme.colors
    val team = dynasty.team
    val staff = team.staff
    fun coach(id: com.nflsim.engine.model.CoachId): Coach? = dynasty.league.coaches[id]

    val head = coach(staff.headCoach)
    // The pressure this club fires at, rather than a number picked here.
    val bar = CoachingCarousel.fireBar(team, dynasty.league.tuning.staff)
    val offence = coach(staff.offCoordinator)
    val defence = coach(staff.defCoordinator)
    val special = coach(staff.stCoordinator)

    ScreenList {
        item {
            Column {
                Text("Staff", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Your coaches develop the players and call the games off their own " +
                        "tendencies. Who coaches is your call: nobody fires them but you.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }

        item {
            if (open) {
                SituationBlock("The hiring window is open", situation = Situation.THIRD_DOWN) {
                    Text(
                        "Until you start the offseason, you can let anyone here go and hire " +
                            "from the pool: the coaches out of work and this spring's candidates. " +
                            "A job you leave open, the front office fills when the offseason starts.",
                        style = NdTheme.type.body, color = c.chalk,
                    )
                }
            } else {
                Text(
                    if (dynasty.phase == com.nflsim.engine.season.DynastyPhase.OFFSEASON)
                        "The offseason is under way. You can hire and fire again after next season."
                    else "You hire and fire after the season, before you start the offseason.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                )
            }
        }

        item {
            SituationBlock("General manager", meta = team.gm.since.takeIf { it > 0 }?.let { "since $it" }) {
                if (team.gm.name.isBlank()) {
                    Text("Vacant.", style = NdTheme.type.body, color = c.chalkDim)
                } else {
                    Text(team.gm.name, style = NdTheme.type.title.copy(fontWeight = FontWeight.W600), color = c.chalk)
                    Text(
                        "You make the calls. He runs what you hand him - injured places, " +
                            "the practice squad, answering demands - in his own style:",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
                    )
                    gmStyle(team.gm).forEach { Text("· $it", style = NdTheme.type.caption, color = c.chalk) }
                }
                if (open) ChangeButton(if (team.gm.name.isBlank()) "Hire a general manager" else "Change the general manager") { onJob(null) }
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
                        "Runs ${SchemeCatalog[head.scheme].name}. " + jobSecurity(head.hotSeat, bar),
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
                if (open) ChangeButton(if (head == null) "Hire a head coach" else "Change the head coach") { onJob(StaffJob.HEAD) }
            }
        }

        item {
            SituationBlock("Offensive coordinator", meta = SchemeCatalog[team.offenseScheme].name) {
                Coordinator(offence, offenceLines(offence?.tendencies))
                if (open) ChangeButton(if (offence == null) "Hire an offensive coordinator" else "Change the offensive coordinator") { onJob(StaffJob.OFFENCE) }
            }
        }

        item {
            SituationBlock("Defensive coordinator", meta = SchemeCatalog[team.defenseScheme].name) {
                Coordinator(defence, defenceLines(defence?.tendencies))
                if (open) ChangeButton(if (defence == null) "Hire a defensive coordinator" else "Change the defensive coordinator") { onJob(StaffJob.DEFENCE) }
            }
        }

        item {
            SituationBlock("Special teams", meta = special?.let { "${it.age}" } ?: "vacant") {
                Coordinator(special, emptyList())
                if (open) ChangeButton(if (special == null) "Hire a special teams coordinator" else "Change the special teams coordinator") { onJob(StaffJob.SPECIAL) }
            }
        }

        // Every group, open or filled, in the order the roster lists them.
        val position = PositionGroup.entries.map { group -> group to staff.positionCoaches[group]?.let(::coach) }
        item {
            SituationBlock("Position coaches", meta = "${position.count { it.second != null }} of ${position.size}") {
                DataTable(
                    columns = listOf(
                        ColumnSpec("Group", 0.9f),
                        ColumnSpec("Coach", 2.2f, wrap = true),
                        ColumnSpec("Develops", 1.1f, numeric = true, tier = true),
                    ),
                    rows = position.map { (group, man) ->
                        RowData(
                            listOf(group.name, man?.name ?: "Vacant", man?.ratings?.development?.toString() ?: "-"),
                            highlight = open && man == null,
                            onClick = if (open) ({ onJob(StaffJob(CoachRole.POSITION_COACH, group)) }) else null,
                        )
                    },
                )
                if (open) {
                    Text(
                        "Tap a group to change its coach.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.xs),
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
internal fun Coordinator(coach: Coach?, lines: List<String>) {
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
internal fun Ratings(coach: Coach) {
    AttributeBar("Develops players", coach.ratings.development)
    AttributeBar("Game plan", coach.ratings.gameplan)
    AttributeBar("Adjustments", coach.ratings.adjustments)
    AttributeBar("Discipline", coach.ratings.discipline)
    AttributeBar("Motivation", coach.ratings.motivation)
    AttributeBar("Evaluation", coach.ratings.evaluation)
}

/** What he calls on offence, left to himself. */
internal fun offenceLines(plan: GamePlan?): List<String> = if (plan == null) emptyList() else listOfNotNull(
    plan.passRate?.let { "Throws it ${percent(it)} of the time." },
    plan.playActionRate?.let { "Play action on ${percent(it)} of early downs." },
    plan.deepShotRate?.let { "Takes a deep shot ${percent(it)} of the time." },
    plan.trailingPassScale?.let { "Chases a deficit ${"%.2f".format(it)} times as hard as most." },
)

/** And on defence. */
internal fun defenceLines(plan: GamePlan?): List<String> = if (plan == null) emptyList() else listOfNotNull(
    plan.blitzRate?.let { "Blitzes ${percent(it)} of the time." },
    plan.manZoneSplit?.let { "Plays man on ${percent(it)} of snaps." },
    plan.doubleTeamRate?.let { "Doubles the best receiver ${percent(it)} of the time." },
)

private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

internal fun contract(coach: Coach) = when (coach.contractYearsLeft) {
    0 -> "out of contract"
    1 -> "last year of his deal"
    else -> "${coach.contractYearsLeft} years left"
}

/** The button that opens a job to change it, under its block. */
@Composable
private fun ChangeButton(text: String, onClick: () -> Unit) =
    SecondaryButton(text, onClick, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))

/** A general manager's style, in words: what he does with what you hand him. */
internal fun gmStyle(gm: GmProfile): List<String> = listOfNotNull(
    when {
        gm.winNowVsFuture >= 0.66f -> "Builds to win now: keeps veterans and spends ahead."
        gm.winNowVsFuture <= 0.33f -> "Builds for later: favours youth and keeps room."
        else -> "Weighs this season against the next few."
    },
    when {
        gm.aggression >= 0.66f -> "Will put a big share of the cap on one star."
        gm.aggression <= 0.33f -> "Spreads the money: no huge contracts."
        else -> "Pays stars, within reason."
    },
    when {
        gm.loyaltyToOwnPlayers >= 0.66f -> "Fights to keep his own players off the market."
        gm.loyaltyToOwnPlayers <= 0.33f -> "Lets his own walk rather than overpay."
        else -> null
    },
    when {
        gm.riskTolerance >= 0.66f -> "Carries dead money and bets on bounce-backs."
        gm.riskTolerance <= 0.33f -> "Cuts his losses quickly."
        else -> null
    },
)

/** Within this much of the club's firing bar is worth saying out loud. */
private const val WARNING = 10

/**
 * Where a head coach stands, in words, with the numbers behind it: the
 * pressure losing seasons build, and the level at which a club like this
 * one lets a coach go. Advice only: nobody fires the user's coaches but him.
 */
internal fun jobSecurity(pressure: Int, bar: Int): String {
    val standing = when {
        pressure >= bar - WARNING -> "He is on the hot seat: another bad season and most clubs would let him go."
        pressure * 2 >= bar -> "Losing has put some pressure on him."
        else -> "His job is safe."
    }
    return "$standing Pressure $pressure; a club like yours fires a coach at $bar."
}
