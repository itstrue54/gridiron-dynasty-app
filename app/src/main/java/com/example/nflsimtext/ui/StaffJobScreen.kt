package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.components.ActionDialog
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.Coach
import com.nflsim.engine.model.CoachRole
import com.nflsim.engine.model.GmProfile
import com.nflsim.engine.offseason.StaffJob
import com.nflsim.engine.offseason.Staffing
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.season.Dynasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The most of a pool the screen lists, best first. */
private const val POOL_SHOWN = 25

/**
 * One job on the user's staff (SPEC 4.7, offseason.Staffing): who holds it,
 * and in the spring window, letting him go and hiring from the pool. [job]
 * null is the general manager's chair.
 */
@Composable
fun StaffJobScreen(
    dynasty: Dynasty,
    job: StaffJob?,
    store: DynastyStore,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    if (job == null) GmJob(dynasty, store, scope, onBack) else CoachJob(dynasty, job, store, scope, onBack)
}

@Composable
private fun CoachJob(dynasty: Dynasty, job: StaffJob, store: DynastyStore, scope: CoroutineScope, onBack: () -> Unit) {
    val c = NdTheme.colors
    val open = store.staffingOpen
    val team = dynasty.team
    val holder = dynasty.league.coaches[Staffing.holder(team.staff, job)]
    val pool = remember(dynasty, job) {
        if (holder == null) Staffing.pool(dynasty.league, dynasty.year, team.id, job) else emptyList()
    }
    // The side of the ball a coordinator's scheme decides, and what it runs now.
    val side = when (job) {
        StaffJob.OFFENCE -> "offence" to team.offenseScheme
        StaffJob.DEFENCE -> "defence" to team.defenseScheme
        else -> null
    }
    var firing by remember { mutableStateOf(false) }
    var looking by remember { mutableStateOf<Coach?>(null) }

    ScreenList {
        item {
            Column {
                Text(job.label, style = NdTheme.type.display, color = c.chalk)
                Text(duties(job), style = NdTheme.type.body, color = c.chalkDim)
            }
        }
        if (holder != null) {
            item {
                SituationBlock("In the job", meta = "${holder.age}, ${contract(holder)}") {
                    Text(holder.name, style = NdTheme.type.title.copy(fontWeight = FontWeight.W600), color = c.chalk)
                    Text(
                        "Comes from ${SchemeCatalog[holder.scheme].name}.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
                    )
                    Ratings(holder)
                    tendencyLines(job, holder).forEach {
                        Text(it, style = NdTheme.type.caption, color = c.chalkDim, modifier = Modifier.padding(top = NdTheme.spacing.xs))
                    }
                    if (open) {
                        SecondaryButton("Let him go", { firing = true },
                            Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s), enabled = !store.busy)
                    } else {
                        Text(
                            "You can let him go after the season, before you start the offseason.",
                            style = NdTheme.type.caption, color = c.chalkDim,
                            modifier = Modifier.padding(top = NdTheme.spacing.s),
                        )
                    }
                }
            }
        } else if (open) {
            item {
                val outOfWork = pool.count { it.id.v > 0 }
                Column {
                    Text("Candidates", style = NdTheme.type.headline, color = c.chalk)
                    Text(
                        "$outOfWork out of work and ${pool.size - outOfWork} new this spring, best for the job first" +
                            (if (pool.size > POOL_SHOWN) " (the top $POOL_SHOWN)" else "") +
                            ". Tap one to look closer.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
            }
            item {
                // The two figures that matter in this job (Staffing.worth).
                val (third, fourth) = when (job.role) {
                    CoachRole.HEAD_COACH -> "Develops" to "Adjusts"
                    CoachRole.OFFENSIVE_COORDINATOR, CoachRole.DEFENSIVE_COORDINATOR -> "Scheme" to "Rating"
                    else -> "Develops" to "Rating"
                }
                DataTable(
                    // A scheme reads as text, so it goes before the numbers:
                    // after a right-aligned age it runs into it.
                    columns = if (third == "Scheme") listOf(
                        ColumnSpec("Coach", 2.0f, wrap = true),
                        ColumnSpec(third, 1.7f, wrap = true),
                        ColumnSpec("Age", 0.7f, numeric = true),
                        ColumnSpec(fourth, 1.0f, numeric = true, tier = true),
                    ) else listOf(
                        ColumnSpec("Coach", 2.2f, wrap = true),
                        ColumnSpec("Age", 0.7f, numeric = true),
                        ColumnSpec(third, 1.1f, numeric = true, tier = true),
                        ColumnSpec(fourth, 1.0f, numeric = true, tier = true),
                    ),
                    rows = pool.take(POOL_SHOWN).map { man ->
                        RowData(
                            if (third == "Scheme") listOf(
                                man.name, SchemeCatalog[man.scheme].name, "${man.age}",
                                "${Staffing.quality(man).roundToInt()}",
                            ) else listOf(
                                man.name, "${man.age}", "${man.ratings.development}",
                                if (fourth == "Adjusts") "${man.ratings.adjustments}" else "${Staffing.quality(man).roundToInt()}",
                            ),
                            highlight = side != null && man.scheme == side.second,
                            onClick = { looking = man },
                        )
                    },
                )
                if (side != null) {
                    Text(
                        "Highlighted: runs the ${side.first} you run now, ${SchemeCatalog[side.second].name}.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.xs),
                    )
                }
            }
        } else {
            item {
                Text(
                    "Vacant. The front office fills it when the offseason starts.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }
        item { SecondaryButton("Back to the staff", onBack, Modifier.fillMaxWidth()) }
    }

    if (firing && holder != null) {
        ActionDialog("Let ${holder.name} go?", onDismiss = { firing = false }, situation = Situation.RED_ZONE) {
            Text(
                "The job stays open until you hire someone. If it is still open when the " +
                    "offseason starts, the front office fills it." +
                    (side?.let { (name, scheme) ->
                        " Your $name keeps running ${SchemeCatalog[scheme].name} until his replacement brings a scheme of his own."
                    } ?: ""),
                style = NdTheme.type.body, color = c.chalk,
            )
            PrimaryButton("Let him go", {
                firing = false
                scope.launch { store.fireCoach(job) }
            }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
            SecondaryButton("Keep him", { firing = false }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.xs))
        }
    }

    looking?.let { man ->
        ActionDialog(man.name, onDismiss = { looking = null }) {
            // A candidate is a long read at a large text size: it scrolls.
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "${man.age}. " + (if (man.id.v > 0) "Out of work. " else "A candidate this spring. ") +
                        "Comes from ${SchemeCatalog[man.scheme].name}.",
                    style = NdTheme.type.caption, color = c.chalkDim,
                    modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
                )
                Ratings(man)
                tendencyLines(job, man).forEach {
                    Text(it, style = NdTheme.type.caption, color = c.chalkDim, modifier = Modifier.padding(top = NdTheme.spacing.xs))
                }
                if (side != null && man.scheme != side.second) {
                    Text(
                        "Hiring him changes your ${side.first} to ${SchemeCatalog[man.scheme].name}: " +
                            "your ${side.first}'s players start learning it again.",
                        style = NdTheme.type.body, color = c.chalk,
                        modifier = Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
                PrimaryButton("Hire ${man.name}", {
                    looking = null
                    scope.launch {
                        store.hireCoach(job, man)
                        onBack()
                    }
                }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s), enabled = !store.busy)
                SecondaryButton("Not him", { looking = null }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.xs))
            }
        }
    }
}

@Composable
private fun GmJob(dynasty: Dynasty, store: DynastyStore, scope: CoroutineScope, onBack: () -> Unit) {
    val c = NdTheme.colors
    val open = store.staffingOpen
    val gm = dynasty.team.gm
    val vacant = gm.name.isBlank()
    val pool = remember(dynasty) { if (vacant) Staffing.gmPool(dynasty.league, dynasty.year) else emptyList() }
    var firing by remember { mutableStateOf(false) }
    var looking by remember { mutableStateOf<GmProfile?>(null) }

    ScreenList {
        item {
            Column {
                Text("General manager", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "You make the calls. He runs what you hand the front office - injured places, " +
                        "the practice squad, answering demands - and he runs it his way.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }
        if (!vacant) {
            item {
                SituationBlock("In the chair", meta = gm.since.takeIf { it > 0 }?.let { "since $it" }) {
                    Text(gm.name, style = NdTheme.type.title.copy(fontWeight = FontWeight.W600), color = c.chalk)
                    gmStyle(gm).forEach { Text("· $it", style = NdTheme.type.body, color = c.chalk) }
                    if (open) {
                        SecondaryButton("Let him go", { firing = true },
                            Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s), enabled = !store.busy)
                    } else {
                        Text(
                            "You can let him go after the season, before you start the offseason.",
                            style = NdTheme.type.caption, color = c.chalkDim,
                            modifier = Modifier.padding(top = NdTheme.spacing.s),
                        )
                    }
                }
            }
        } else if (open) {
            item {
                val outOfWork = dynasty.league.gmPool.size
                Column {
                    Text("Candidates", style = NdTheme.type.headline, color = c.chalk)
                    Text(
                        "$outOfWork out of work and ${pool.size - outOfWork} new this spring. Tap one to look closer.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
            }
            item {
                DataTable(
                    columns = listOf(
                        ColumnSpec("Name", 2.2f, wrap = true),
                        ColumnSpec("Builds for", 1.3f),
                        ColumnSpec("Spends", 1.2f),
                    ),
                    rows = pool.map { man ->
                        RowData(
                            listOf(
                                man.name,
                                when {
                                    man.winNowVsFuture >= 0.66f -> "Now"
                                    man.winNowVsFuture <= 0.33f -> "Later"
                                    else -> "Both"
                                },
                                when {
                                    man.aggression >= 0.66f -> "Big"
                                    man.aggression <= 0.33f -> "Careful"
                                    else -> "Fair"
                                },
                            ),
                            onClick = { looking = man },
                        )
                    },
                )
            }
        } else {
            item {
                Text("Vacant. The owner fills the chair when the offseason starts.", style = NdTheme.type.body, color = c.chalkDim)
            }
        }
        item { SecondaryButton("Back to the staff", onBack, Modifier.fillMaxWidth()) }
    }

    if (firing && !vacant) {
        ActionDialog("Let ${gm.name} go?", onDismiss = { firing = false }, situation = Situation.RED_ZONE) {
            Text(
                "The chair stays open until you hire someone. If it is still open when the " +
                    "offseason starts, the owner fills it.",
                style = NdTheme.type.body, color = c.chalk,
            )
            PrimaryButton("Let him go", {
                firing = false
                scope.launch { store.fireGm() }
            }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
            SecondaryButton("Keep him", { firing = false }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.xs))
        }
    }

    looking?.let { man ->
        ActionDialog(man.name, onDismiss = { looking = null }) {
            Text(
                if (dynasty.league.gmPool.any { it.name == man.name }) "Out of work." else "A candidate this spring.",
                style = NdTheme.type.caption, color = c.chalkDim,
                modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
            )
            gmStyle(man).forEach { Text("· $it", style = NdTheme.type.body, color = c.chalk) }
            PrimaryButton("Hire ${man.name}", {
                looking = null
                scope.launch {
                    store.hireGm(man)
                    onBack()
                }
            }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s), enabled = !store.busy)
            SecondaryButton("Not him", { looking = null }, Modifier.fillMaxWidth().padding(top = NdTheme.spacing.xs))
        }
    }
}

/** What a job does, in a line, so the user knows what he is choosing. */
internal fun duties(job: StaffJob): String = when (job.role) {
    CoachRole.HEAD_COACH -> "Develops every player alongside his position coach, adjusts during games, and decides fourth downs."
    CoachRole.OFFENSIVE_COORDINATOR -> "Brings the offence's scheme, and calls its plays when you set no game plan of your own."
    CoachRole.DEFENSIVE_COORDINATOR -> "Brings the defence's scheme, and calls its blitzes and coverages when you set no game plan of your own."
    CoachRole.SPECIAL_TEAMS_COORDINATOR -> "Coaches the kicking units."
    CoachRole.POSITION_COACH -> "Develops the ${job.group!!.name} players, alongside the head coach."
}

/** What a coach calls left to himself, for the job he would do. */
private fun tendencyLines(job: StaffJob, coach: Coach): List<String> = when (job.role) {
    CoachRole.OFFENSIVE_COORDINATOR -> offenceLines(coach.tendencies)
    CoachRole.DEFENSIVE_COORDINATOR -> defenceLines(coach.tendencies)
    CoachRole.HEAD_COACH -> listOfNotNull(coach.tendencies.fourthDownAggression?.let { a ->
        "Fourth downs: " + when {
            a >= 0.66f -> "he goes for it."
            a >= 0.33f -> "he weighs it up."
            else -> "he takes the points."
        }
    })
    else -> emptyList()
}
