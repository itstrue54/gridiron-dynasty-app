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
    // The league as this spring's carousel will leave it: who the other
    // clubs let go (Staffing.market). The staff is shown with the men the
    // user agreed to hire already in their jobs.
    val spring = remember(dynasty) { Staffing.spring(dynasty) }
    val market = spring.league
    val shown = remember(dynasty, market) { Staffing.withPending(dynasty, market) }
    val holder = shown.league.coaches[Staffing.holder(shown.team.staff, job)]
    val agreed = dynasty.pendingHires.firstOrNull { it.job == job }
    // His coordinator, leaving for another club's head coaching job.
    val departing = remember(spring) { Staffing.departures(dynasty, spring)[job] }
    val pool = remember(dynasty, job, market) {
        if (holder == null) Staffing.pool(dynasty, job, market) else emptyList()
    }
    // Where each man comes from, which decides when he can join.
    val sources = remember(pool) { pool.associate { it.id to Staffing.source(dynasty, it, market) } }
    fun club(id: Int) = dynasty.league.teams.firstOrNull { it.id.v == id }
    val agreedNote = agreed?.let { p ->
        val why = if (p.candidate != null) "" else when (val from = Staffing.source(dynasty, dynasty.league.coach(com.nflsim.engine.model.CoachId(p.coach)), market)) {
            is Staffing.Source.LetGo -> ", once the ${from.club.name} let him go"
            is Staffing.Source.Promotion -> ": the ${from.club.name} cannot stop a promotion to head coach, and hire his replacement"
            else -> ""
        }
        "Joins when the offseason starts$why. "
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
                SituationBlock(if (agreed != null) "Hired" else "In the job", meta = "${holder.age}, ${contract(holder)}") {
                    Text(holder.name, style = NdTheme.type.title.copy(fontWeight = FontWeight.W600), color = c.chalk)
                    Text(
                        (agreedNote ?: "") + "Comes from ${SchemeCatalog[holder.scheme].name}.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
                    )
                    Ratings(holder)
                    tendencyLines(job, holder).forEach {
                        Text(it, style = NdTheme.type.caption, color = c.chalkDim, modifier = Modifier.padding(top = NdTheme.spacing.xs))
                    }
                    if (open && agreed != null) {
                        // Nothing to confirm: he never joined.
                        SecondaryButton("Change your mind", { scope.launch { store.fireCoach(job) } },
                            Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s), enabled = !store.busy)
                    } else if (open) {
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
            departing?.let { d ->
                item {
                    SituationBlock("Leaving", situation = Situation.RED_ZONE) {
                        Text(
                            "${d.name} leaves to be the ${club(d.team)?.name ?: "new"} head coach. No club can stop a " +
                                "promotion to head coach. Whoever you hire for his job joins when the offseason starts.",
                            style = NdTheme.type.body, color = c.chalk,
                        )
                    }
                }
            }
            item {
                val letGo = sources.values.count { it is Staffing.Source.LetGo }
                val coordinators = sources.values.count { it is Staffing.Source.Promotion || it is Staffing.Source.Own }
                val outOfWork = sources.values.count { it is Staffing.Source.OutOfWork }
                Column {
                    Text("Candidates", style = NdTheme.type.headline, color = c.chalk)
                    Text(
                        "$outOfWork out of work, $letGo let go this spring" +
                            (if (job == StaffJob.HEAD) ", $coordinators coordinators" else "") +
                            " and ${sources.values.count { it is Staffing.Source.Candidate }} new, best for the job first" +
                            (if (pool.size > POOL_SHOWN) " (the top $POOL_SHOWN)" else "") +
                            ". Tap one to look closer.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                }
            }
            item {
                // What the job uses (Staffing.worth), as columns: a
                // coordinator's scheme, and the rating the pool is sorted by.
                // A scheme reads as text, so it goes before the numbers:
                // after a right-aligned age it runs into it.
                val schemes = side != null
                val develops = job.role == CoachRole.HEAD_COACH || job.role == CoachRole.POSITION_COACH
                // The rating the pool is sorted by, under the name of what it is.
                val worth = when (job.role) {
                    CoachRole.HEAD_COACH -> "Rating"
                    CoachRole.POSITION_COACH -> null
                    else -> "Game plan"
                }
                DataTable(
                    columns = listOfNotNull(
                        ColumnSpec("Coach", 2.0f, wrap = true),
                        if (schemes) ColumnSpec("Scheme", 1.7f, wrap = true) else null,
                        ColumnSpec("Age", 0.7f, numeric = true),
                        if (develops) ColumnSpec("Develops", 1.1f, numeric = true, tier = true) else null,
                        worth?.let { ColumnSpec(it, 1.1f, numeric = true, tier = true) },
                    ),
                    rows = pool.take(POOL_SHOWN).map { man ->
                        RowData(
                            listOfNotNull(
                                // Where he comes from, when it is not open market.
                                when (val from = sources[man.id]) {
                                    is Staffing.Source.LetGo -> "${man.name} (${from.club.abbrev})"
                                    is Staffing.Source.Promotion -> "${man.name} (${from.club.abbrev} ${short(from.job)})"
                                    is Staffing.Source.Own -> "${man.name} (your ${short(from.job)})"
                                    else -> man.name
                                },
                                if (schemes) SchemeCatalog[man.scheme].name else null,
                                "${man.age}",
                                if (develops) "${man.ratings.development}" else null,
                                worth?.let { "${Staffing.worth(man, job).roundToInt()}" },
                            ),
                            highlight = side != null && man.scheme == side.second,
                            onClick = { looking = man },
                        )
                    },
                )
                Text(
                    ratingNote(job) +
                        (if (sources.values.any { it is Staffing.Source.LetGo }) " A club alone in brackets is letting him go this spring." else "") +
                        (if (job == StaffJob.HEAD) " A coordinator's club cannot stop a promotion to head coach. A man from another club joins when the offseason starts." else "") +
                        (if (job.role == CoachRole.OFFENSIVE_COORDINATOR || job.role == CoachRole.DEFENSIVE_COORDINATOR || job.role == CoachRole.SPECIAL_TEAMS_COORDINATOR)
                            " Other clubs' coordinators are not here: a club can refuse a move sideways." else "") +
                        (side?.let { (name, scheme) ->
                        " Highlighted: runs the $name you run now, ${SchemeCatalog[scheme].name}."
                    } ?: ""),
                    style = NdTheme.type.caption, color = c.chalkDim,
                    modifier = Modifier.padding(top = NdTheme.spacing.xs),
                )
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
                    "${man.age}. " + when (val from = sources[man.id]) {
                        is Staffing.Source.LetGo -> "The ${from.club.name} are letting him go: he joins when the offseason starts. "
                        is Staffing.Source.Promotion -> "The ${from.club.name}' ${from.job.label.lowercase()}. They cannot stop a promotion " +
                            "to head coach: he joins when the offseason starts, and they hire his replacement. " +
                            (market.teams.firstOrNull { it.staff.headCoach == man.id }?.let { "The ${it.name} mean to make him their head coach: hire him first and he is yours. " } ?: "")
                        is Staffing.Source.Own -> "Your ${from.job.label.lowercase()}. Promote him now, and his job is open for you to fill. "
                        is Staffing.Source.OutOfWork -> "Out of work. "
                        else -> "A candidate this spring. "
                    } +
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

/** A coordinator's job in two letters, for a tag beside his name. */
private fun short(job: StaffJob) = when (job.role) {
    CoachRole.OFFENSIVE_COORDINATOR -> "OC"
    CoachRole.DEFENSIVE_COORDINATOR -> "DC"
    CoachRole.SPECIAL_TEAMS_COORDINATOR -> "STC"
    CoachRole.HEAD_COACH -> "HC"
    CoachRole.POSITION_COACH -> "${job.group!!.name} coach"
}

/** What a job does, in a line, so the user knows what he is choosing. */
internal fun duties(job: StaffJob): String = when (job.role) {
    CoachRole.HEAD_COACH -> "Develops every player alongside his position coach, adjusts during games, keeps flags down, " +
        "shortens slumps, lends the scouts his eye for talent, and decides fourth downs."
    CoachRole.OFFENSIVE_COORDINATOR -> "Brings the offence's scheme and calls its plays when you set no game plan of your own. " +
        "His game plan is an edge on every snap."
    CoachRole.DEFENSIVE_COORDINATOR -> "Brings the defence's scheme and calls its blitzes and coverages when you set no game plan of your own. " +
        "His game plan is an edge on every snap."
    CoachRole.SPECIAL_TEAMS_COORDINATOR -> "Coaches the kicking units: his game plan is worth yards on every return, yours and theirs."
    CoachRole.POSITION_COACH -> "Develops the ${job.group!!.name} players, alongside the head coach."
}

/** What the pool's Rating column means for this job (Staffing.worth). */
internal fun ratingNote(job: StaffJob): String = when (job.role) {
    CoachRole.HEAD_COACH -> "Rating: everything he does but a game plan, together."
    CoachRole.POSITION_COACH -> "Sorted by how well he develops players."
    else -> "Sorted by his game plan."
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
