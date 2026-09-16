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
import com.nflsim.engine.sim.DefensiveFront
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
            val kick = SpecialTeams.returnerFor(offChart, offense)
            val punt = SpecialTeams.returnerFor(offChart, offense, punt = true)
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
                    "The fastest men on the roster.",
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
        item { SecondaryButton("Back to the roster", onBack, Modifier.fillMaxWidth()) }
    }
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
