package com.example.nflsimtext.ui

import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.ratings.SchemeFitGrade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nflsim.engine.model.DepthPins
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.SchemeCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.nflsimtext.ui.components.RatingValue
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.StatusTag
import com.example.nflsimtext.ui.components.TagTone
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.GameDay
import com.nflsim.engine.season.WeekRunner
import com.nflsim.engine.sim.DefensiveFront
import com.nflsim.engine.sim.SpecialTeamsUnits
import com.nflsim.engine.sim.DepthChart
import com.nflsim.engine.sim.Personnel
import com.nflsim.engine.sim.SpecialTeams
import com.nflsim.engine.sim.packageKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val CHART_ORDER = listOf(
    Position.QB, Position.RB, Position.FB, Position.WR, Position.TE,
    Position.LT, Position.LG, Position.C, Position.RG, Position.RT,
    Position.EDGE, Position.DT, Position.LB, Position.CB, Position.S,
    Position.K, Position.P, Position.LS,
)

/** A package spot the base chart does not decide: the players past the starters a package adds. */
private data class PackageSpot(val label: String, val keys: List<String>, val position: Position, val count: Int)

private val SPOTS = listOf(
    PackageSpot("Three receivers (11)", listOf(packageKey(Personnel.P_11)), Position.WR, 3),
    PackageSpot("Four receivers (10)", listOf(packageKey(Personnel.P_10)), Position.WR, 4),
    PackageSpot("Two tight ends (12, 22)", listOf(packageKey(Personnel.P_12), packageKey(Personnel.P_22)), Position.TE, 2),
    PackageSpot("Three tight ends (13, goal line)",
        listOf(packageKey(Personnel.P_13), packageKey(Personnel.GOAL_LINE)), Position.TE, 3),
    PackageSpot("Third corner (nickel, 3-4, 3-3-5)",
        listOf(DefensiveFront.NICKEL_FOUR_TWO, DefensiveFront.THREE_FOUR_TWO_GAP,
            DefensiveFront.THREE_FOUR_ONE_GAP, DefensiveFront.THREE_THREE_FIVE).map { packageKey(it) },
        Position.CB, 3),
    PackageSpot("Dime corners", listOf(packageKey(DefensiveFront.DIME_FOUR_ONE)), Position.CB, 4),
    PackageSpot("Three interior linemen (3-4, goal line)",
        listOf(DefensiveFront.THREE_FOUR_TWO_GAP, DefensiveFront.THREE_FOUR_ONE_GAP, DefensiveFront.GOAL_LINE)
            .map { packageKey(it) }, Position.DT, 3),
    PackageSpot("Goal-line linebackers", listOf(packageKey(DefensiveFront.GOAL_LINE)), Position.LB, 4),
)

/**
 * SPEC 5.5's depth chart, as pins over the automatic order. Moving a player
 * pins everyone down to him in the new order; below that the chart keeps
 * sorting itself, so trades, signings and the draft need no upkeep. Auto
 * clears a position's pins.
 */
@Composable
fun DepthChartScreen(dynasty: Dynasty, store: DynastyStore, scope: CoroutineScope, onBack: () -> Unit) {
    val team = dynasty.team
    val roster = dynasty.league.roster(team.id)
    val offense = SchemeCatalog.tuned(team.offenseScheme, dynasty.league.tuning)
    val defense = SchemeCatalog.tuned(team.defenseScheme, dynasty.league.tuning)
    var pins by remember { mutableStateOf(team.depthPins) }
    var open by remember { mutableStateOf<String?>(null) }
    fun save(p: DepthPins) {
        pins = p
        scope.launch { store.setDepthPins(p) }
    }
    val offChart = DepthChart.auto(roster, offense, pins)
    val defChart = DepthChart.auto(roster, defense, pins)
    fun chartFor(position: Position) = if (position.isOffense || position in SPECIALISTS) offChart else defChart
    fun schemeFor(position: Position): Scheme = if (position.isOffense || position in SPECIALISTS) offense else defense

    /** The new pin list after moving index [from] to [to]: everyone down to the lower of the two. */
    fun moved(list: List<Player>, from: Int, to: Int): List<Int> {
        val order = list.toMutableList()
        val p = order.removeAt(from)
        order.add(to, p)
        return order.take(maxOf(from, to) + 1).map { it.id.v }
    }

    ScreenList {
        item {
            Column {
                Text("Depth chart", style = NdTheme.type.display, color = NdTheme.colors.chalk)
                Text(
                    "Move a player to pin him and everyone above him. Below the pins the chart " +
                        "keeps sorting itself by scheme-adjusted overall.",
                    style = NdTheme.type.body, color = NdTheme.colors.chalkDim,
                )
            }
        }
        CHART_ORDER.forEach { position ->
            val list = chartFor(position).at(position)
            if (list.isEmpty()) return@forEach
            val pinned = pins.order[position].orEmpty()
            val key = "pos-${position.name}"
            item(key = key) {
                ChartBlock(
                    title = position.label,
                    meta = if (pinned.isNotEmpty()) "${pinned.size} pinned" else "Sorts itself",
                    pinned = pinned.isNotEmpty(),
                    summary = list.first().name,
                    isOpen = open == key,
                    onOpen = { open = key },
                    onClose = { open = null },
                    onAuto = if (pinned.isEmpty()) null
                    else ({ save(pins.copy(order = pins.order - position)) }),
                ) {
                    list.indices.forEach { i ->
                        PlayerRow(
                            i, list[i], overall(list[i], schemeFor(position)), list[i].id.v in pinned,
                            canUp = i > 0, canDown = i < list.size - 1,
                            fit = SchemeFitGrade.letter(schemeFit(list[i], schemeFor(position))),
                            onUp = { save(pins.copy(order = pins.order + (position to moved(list, i, i - 1)))) },
                            onDown = { save(pins.copy(order = pins.order + (position to moved(list, i, i + 1)))) },
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Packages",
                style = NdTheme.type.headline, color = NdTheme.colors.chalk,
            )
        }
        SPOTS.forEach { spot ->
            val chart = chartFor(spot.position)
            val list = chart.forPackage(spot.keys.first(), spot.position)
            if (list.size <= 1) return@forEach
            val pinned = spot.keys.firstNotNullOfOrNull { pins.packages[it]?.get(spot.position) }.orEmpty()
            val key = "pkg-${spot.label}"
            fun write(ids: List<Int>?): DepthPins = pins.copy(packages = spot.keys.fold(pins.packages) { acc, k ->
                val positions = (acc[k] ?: emptyMap()).let { if (ids == null) it - spot.position else it + (spot.position to ids) }
                if (positions.isEmpty()) acc - k else acc + (k to positions)
            })
            item(key = key) {
                val shown = list.take(spot.count + 2)
                ChartBlock(
                    title = spot.label,
                    meta = if (pinned.isNotEmpty()) "Pinned" else "Sorts itself",
                    pinned = pinned.isNotEmpty(),
                    summary = list.take(spot.count).joinToString(", ") { it.lastName },
                    isOpen = open == key,
                    onOpen = { open = key },
                    onClose = { open = null },
                    onAuto = if (pinned.isEmpty()) null else ({ save(write(null)) }),
                ) {
                    shown.indices.forEach { i ->
                        PlayerRow(
                            i, shown[i], overall(shown[i], schemeFor(spot.position)), shown[i].id.v in pinned,
                            canUp = i > 0, canDown = i < shown.size - 1, inPackage = i < spot.count,
                            fit = SchemeFitGrade.letter(schemeFit(shown[i], schemeFor(spot.position))),
                            onUp = { save(write(moved(list, i, i - 1))) },
                            onDown = { save(write(moved(list, i, i + 1))) },
                        )
                    }
                }
            }
        }

        item(key = "returners") {
            val kick = SpecialTeams.returnerFor(offChart, offense, st = dynasty.league.tuning.specialTeams)
            val punt = SpecialTeams.returnerFor(offChart, offense, punt = true, st = dynasty.league.tuning.specialTeams)
            val candidates = (offChart.at(Position.WR) + offChart.at(Position.RB) + offChart.at(Position.CB))
                .sortedByDescending { it.ratings[RatingId.SPEED] }.take(6)
            SituationBlock(
                "Returners",
                meta = if (pins.kickReturner != null || pins.puntReturner != null) "Pinned" else "Sorts itself",
            ) {
                Text(
                    "Kicks: ${kick?.name ?: "nobody"}. Punts: ${punt?.name ?: "nobody"}.",
                    style = NdTheme.type.data, color = NdTheme.colors.chalk,
                )
                Text(
                    "The fastest men, backups first: a starter returns only when clearly the better man.",
                    style = NdTheme.type.caption, color = NdTheme.colors.chalkDim,
                    modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
                )
                candidates.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${c.position.label} ${c.name}",
                            style = NdTheme.type.data, color = NdTheme.colors.chalk,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { save(pins.copy(kickReturner = c.id.v)) }) {
                            Text("Kicks", style = NdTheme.type.caption, color = NdTheme.colors.pylonText)
                        }
                        TextButton(onClick = { save(pins.copy(puntReturner = c.id.v)) }) {
                            Text("Punts", style = NdTheme.type.caption, color = NdTheme.colors.pylonText)
                        }
                    }
                }
                if (pins.kickReturner != null || pins.puntReturner != null) {
                    SecondaryButton(
                        "Let the chart pick the returners",
                        { save(pins.copy(kickReturner = null, puntReturner = null)) },
                        Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }
        item(key = "special-teams") { SpecialTeamsBlock(dynasty, pins, ::save) }
        item(key = "game-day") { GameDayBlock(dynasty, roster, pins, offense, defense, ::save) }
        item { SecondaryButton("Back to the roster", onBack, Modifier.fillMaxWidth()) }
    }
}

/**
 * Game-day inactives (season.GameDay): who sits this week, hurt or
 * scratched, and the user's say over the scratches. Naming a whole set of
 * scratches fixes them; the front office picks any he leaves unnamed.
 */
@Composable
private fun GameDayBlock(
    dynasty: Dynasty,
    roster: List<Player>,
    pins: DepthPins,
    offense: Scheme,
    defense: Scheme,
    save: (DepthPins) -> Unit,
) {
    val c = NdTheme.colors
    val eligible = WeekRunner.eligible(roster)
    val squad = dynasty.team.practiceSquad.mapNotNull { dynasty.league.playersById[it] }
    val st = dynasty.league.tuning.specialTeams
    val up = GameDay.callUps(eligible, squad, offense, defense, pins.callUp, st)
    val actives = GameDay.actives(eligible, offense, defense, pins.inactive, squad, pins.callUp, st)
    val scratched = eligible - actives.toSet()
    val hurt = roster.filter { it !in eligible && !com.nflsim.engine.season.RosterMoves.onReserve(it) }
    var swapping by remember { mutableStateOf<Player?>(null) }
    fun depth(p: Player): String {
        val scheme = if (p.position.isOffense || p.position in SPECIALISTS) offense else defense
        val at = roster.filter { it.position == p.position }.sortedByDescending { overall(it, scheme) }
        return depthLabel(at.indexOf(p) + 1, at.size, p.position.label)
    }

    SituationBlock(
        "Game day",
        meta = if (pins.inactive.isNotEmpty() || pins.callUp.isNotEmpty()) "Your picks" else "Front office picks",
    ) {
        Text(
            gameDayNote(actives.size, actives.count { it.position.group == com.nflsim.engine.model.PositionGroup.OL }),
            style = NdTheme.type.caption, color = c.chalkDim,
            modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
        )
        if (hurt.isNotEmpty()) {
            Text(
                "Hurt, and inactive: " + hurt.joinToString(", ") { "${it.position.label} ${it.name}" } + ".",
                style = NdTheme.type.data, color = c.chalk,
                modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
            )
        }
        if (up.isNotEmpty()) {
            Text(
                "Called up from the practice squad: " +
                    // This game counts: the league adds it when the week is played.
                    up.joinToString(", ") { "${it.position.label} ${it.name} (${callUpCount(it.elevations + 1)})" } + ".",
                style = NdTheme.type.data, color = c.chalk,
                modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
            )
        }
        if (scratched.isEmpty()) {
            Text("Nobody healthy sits this week.", style = NdTheme.type.data, color = c.chalk)
        } else {
            Text("Inactive this week:", style = NdTheme.type.caption, color = c.chalkDim)
        }
        scratched.forEach { man ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${man.position.label} ${man.name}, ${depth(man)}",
                    style = NdTheme.type.data, color = c.chalk,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { swapping = if (swapping == man) null else man }) {
                    Text(if (swapping == man) "Cancel" else "Dress him", style = NdTheme.type.caption, color = c.pylonText)
                }
            }
        }
        swapping?.let { out ->
            // He dresses; one of the men dressed sits in his place.
            val candidates = GameDay.sitOrder(actives + out, offense, defense, keep = up.toSet(), st = st)
                .filter { it != out }.take(SWAP_SHOWN)
            Text(
                "Who sits instead of ${out.name}?",
                style = NdTheme.type.caption, color = c.chalkDim,
                modifier = Modifier.padding(top = NdTheme.spacing.xs),
            )
            candidates.forEach { sit ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${sit.position.label} ${sit.name}, ${depth(sit)}",
                        style = NdTheme.type.data, color = c.chalk,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        swapping = null
                        save(pins.copy(inactive = (scratched - out + sit).map { it.id.v }))
                    }) {
                        Text("Sit him", style = NdTheme.type.caption, color = c.pylonText)
                    }
                }
            }
        }
        // Call-ups: two a game, three a season each; naming one sends the deepest man out.
        val callable = squad.filter { it.injuryWeeks == 0 && it.elevations < GameDay.CALL_UP_LIMIT }
            .sortedByDescending { overall(it, if (it.position.isOffense) offense else defense) }
            .take(CALL_UP_SHOWN)
        if (callable.isNotEmpty()) {
            Text(
                "Call up from your squad (two a game, each three times a season):",
                style = NdTheme.type.caption, color = c.chalkDim,
                modifier = Modifier.padding(top = NdTheme.spacing.s),
            )
            callable.forEach { man ->
                val named = man.id.v in pins.callUp
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${man.position.label} ${man.name}, ${callUpCount(man.elevations)}",
                        style = NdTheme.type.data, color = c.chalk,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            save(pins.copy(callUp = if (named) pins.callUp - man.id.v else (pins.callUp + man.id.v).takeLast(GameDay.CALL_UPS)))
                        },
                    ) {
                        Text(if (named) "Don't call up" else "Call him up", style = NdTheme.type.caption, color = c.pylonText)
                    }
                }
            }
        }
        if (pins.inactive.isNotEmpty() || pins.callUp.isNotEmpty()) {
            SecondaryButton(
                "Let the front office pick",
                { swapping = null; save(pins.copy(inactive = emptyList(), callUp = emptyList())) },
                Modifier.padding(top = NdTheme.spacing.s),
            )
        }
    }
}

/** Squad men the call-up list offers, best first. */
private const val CALL_UP_SHOWN = 6

/** A squad man's call-ups this season, out of the three allowed. */
internal fun callUpCount(times: Int): String = "$times of ${GameDay.CALL_UP_LIMIT} call-ups used"

/**
 * The units this week's 48 would put out (sim.SpecialTeamsUnits), by name -
 * no grades, since what the club knows of its men's ratings goes through
 * its scouts - and the user's core special teamers, who play on every
 * coverage and return unit.
 */
@Composable
private fun SpecialTeamsBlock(dynasty: Dynasty, pins: DepthPins, save: (DepthPins) -> Unit) {
    val c = NdTheme.colors
    val st = dynasty.league.tuning.specialTeams
    // The club as it would take the field, with the pins as they stand on screen.
    val league = dynasty.league.copy(teams = dynasty.league.teams.map { if (it.id == dynasty.userTeamId) it.copy(depthPins = pins) else it })
    val team = WeekRunner.teams(league, league.tuning).getValue(dynasty.userTeamId)
    val units = SpecialTeamsUnits.of(team, st)
    var open by remember { mutableStateOf(false) }
    fun names(men: List<Player>) = men.joinToString(", ") { "${it.position.label} ${it.lastName}" }

    SituationBlock(
        "Special teams",
        meta = if (pins.specialTeams.isNotEmpty()) "${pins.specialTeams.size} core" else "Picks itself",
    ) {
        Text(
            "Each unit takes its men from the 48 by what the job needs: coverage runs and tackles, " +
                "return blockers block in space, gunners get downfield, jammers slow them. Starters " +
                "play coverage and returns only when clearly the better man.",
            style = NdTheme.type.caption, color = c.chalkDim,
            modifier = Modifier.padding(bottom = NdTheme.spacing.xs),
        )
        fun returns(p: Player?) = p?.let { "Returner ${it.position.label} ${it.lastName}; " } ?: ""
        listOf(
            "Kick coverage" to names(units.kickCoverage),
            "Kick return" to returns(SpecialTeams.returnerFor(team.offDepth, team.offScheme, st = st)) + names(units.kickReturn),
            "Punt coverage" to "Gunners ${names(units.gunners)}; ${names(units.puntCoverage)}",
            "Punt return" to returns(SpecialTeams.returnerFor(team.offDepth, team.offScheme, punt = true, st = st)) +
                "Jammers ${names(units.jammers)}; ${names(units.puntReturn)}",
            "Field goal" to listOfNotNull(
                units.snapper?.let { "snaps ${it.position.label} ${it.lastName}" },
                units.holder?.let { "holds ${it.position.label} ${it.lastName}" },
            ).joinToString(", ").ifEmpty { "No snapper or holder dressed" },
        ).forEach { (unit, men) ->
            Text(unit, style = NdTheme.type.data.copy(fontWeight = FontWeight.W600), color = c.chalk)
            Text(men, style = NdTheme.type.caption, color = c.chalkDim, modifier = Modifier.padding(bottom = NdTheme.spacing.xs))
        }
        TextButton(onClick = { open = !open }) {
            Text(if (open) "Done" else "Pick core special teamers", style = NdTheme.type.caption, color = c.pylonText)
        }
        if (open) {
            // The men a coach would look at first: the best in coverage and in
            // return blocking, starters among them, and anyone already pinned.
            val pinned = pins.specialTeams.toSet()
            fun best(role: com.nflsim.engine.sim.StRole) = team.roster
                .filter { it.position !in setOf(Position.QB, Position.K, Position.P, Position.LS) }
                .sortedByDescending { SpecialTeamsUnits.value(it, role, if (it.position.isOffense) team.offScheme else team.defScheme, st) }
                .take(CORE_SHOWN)
            val candidates = (team.roster.filter { it.id.v in pinned } +
                best(com.nflsim.engine.sim.StRole.COVERAGE) + best(com.nflsim.engine.sim.StRole.BLOCKER)).distinctBy { it.id }
            val onUnits = (units.kickCoverage + units.kickReturn + units.gunners + units.puntCoverage +
                units.jammers + units.puntReturn).groupingBy { it.id }.eachCount()
            candidates.forEach { man ->
                val core = man.id.v in pinned
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${man.position.label} ${man.name}, ${unitCount(onUnits[man.id] ?: 0)}",
                        style = NdTheme.type.data, color = c.chalk, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        save(pins.copy(specialTeams = if (core) pins.specialTeams - man.id.v else pins.specialTeams + man.id.v))
                    }) {
                        Text(if (core) "Remove" else "Make core", style = NdTheme.type.caption, color = c.pylonText)
                    }
                }
            }
            if (pinned.isNotEmpty()) {
                SecondaryButton(
                    "Let the units pick themselves",
                    { save(pins.copy(specialTeams = emptyList())) },
                    Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }
    }
}

/** How many of the best men in each role the core picker offers. */
private const val CORE_SHOWN = 8

/** How many coverage and return units a man plays on. */
internal fun unitCount(n: Int): String = when (n) {
    0 -> "on no unit"
    1 -> "on 1 unit"
    else -> "on $n units"
}

/** How many of the deepest men a swap offers to sit. */
private const val SWAP_SHOWN = 8

/** What game day allows, in words. */
internal fun gameDayNote(dressing: Int, linemen: Int): String =
    "$dressing dress this week. A club dresses 48 when eight are offensive linemen, 47 otherwise. " +
        "Hurt men sit first; the club scratches the deepest of the rest." +
        if (linemen < GameDay.LINEMEN) " Only $linemen linemen are fit, so no more than 47 can dress." else ""

/** Where a man sits at his position: "4th of 5 at LB". */
internal fun depthLabel(rank: Int, of: Int, position: String): String {
    val suffix = if (rank % 100 in 11..13) "th" else when (rank % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$rank$suffix of $of at $position"
}

/**
 * A position or a package spot: closed it names its starters, open it is the
 * order itself. The edge marks a spot a club has pinned by hand.
 */
@Composable
private fun ChartBlock(
    title: String,
    meta: String,
    pinned: Boolean,
    summary: String,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onAuto: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    SituationBlock(
        title,
        // Closed, the block is one line: the position and who starts there.
        meta = if (isOpen) meta else summary,
        situation = if (pinned) Situation.THIRD_DOWN else Situation.NORMAL,
        onClick = if (isOpen) null else onOpen,
        divider = isOpen || pinned,
    ) {
        if (!isOpen) {
            if (pinned) StatusTag(meta, TagTone.INFO)
        } else {
            content()
            Row(
                Modifier.padding(top = NdTheme.spacing.s),
                horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
            ) {
                SecondaryButton("Close", onClose)
                if (onAuto != null) SecondaryButton("Clear the pins", onAuto)
            }
        }
    }
}

private val SPECIALISTS = setOf(Position.K, Position.P, Position.LS)

@Composable
private fun PlayerRow(
    index: Int,
    player: Player,
    ovr: Int,
    pinned: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    inPackage: Boolean = true,
    fit: String = "",
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val c = NdTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${index + 1}",
            style = NdTheme.type.label, color = c.chalkDim,
            modifier = Modifier.width(20.dp),
        )
        Text(
            player.name + if (fit.isNotEmpty()) "  $fit" else "",
            style = if (index == 0) NdTheme.type.data.copy(fontWeight = FontWeight.W600)
            else NdTheme.type.data,
            color = if (inPackage) c.chalk else c.chalkDim,
            modifier = Modifier.weight(1f),
        )
        if (pinned) StatusTag("Pinned", TagTone.INFO, Modifier.padding(end = NdTheme.spacing.xs))
        RatingValue(ovr, Modifier.width(30.dp))
        TextButton(
            onClick = onUp, enabled = canUp, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(44.dp).semantics { contentDescription = "Move ${player.name} up" },
        ) { Text("↑", style = NdTheme.type.title, color = if (canUp) c.pylonText else c.turfLine) }
        TextButton(
            onClick = onDown, enabled = canDown, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(44.dp).semantics { contentDescription = "Move ${player.name} down" },
        ) { Text("↓", style = NdTheme.type.title, color = if (canDown) c.pylonText else c.turfLine) }
    }
}
