package com.example.nflsimtext.ui

import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.ratings.SchemeFitGrade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import com.nflsim.engine.model.DepthPins
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.SchemeCatalog
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

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(16.dp)) {
                TextButton(onClick = onBack) { Text("< Roster", fontSize = 12.sp) }
                Text("Depth chart", fontFamily = DataFamily, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Move a player to pin him and everyone above him. Below the pins the chart " +
                        "keeps sorting itself by scheme-adjusted overall. Auto clears a position.",
                    fontFamily = DataFamily, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CHART_ORDER.forEach { position ->
            val list = chartFor(position).at(position)
            if (list.isEmpty()) return@forEach
            val pinned = pins.order[position].orEmpty()
            val key = "pos-${position.name}"
            item(key = key) {
                Header(
                    position.label, list.first().name,
                    if (pinned.isNotEmpty()) "${pinned.size} pinned" else "auto",
                    open == key,
                ) { open = if (open == key) null else key }
            }
            if (open == key) {
                items(list.indices.toList(), key = { "$key-${list[it].id.v}" }) { i ->
                    PlayerRow(
                        i, list[i], overall(list[i], schemeFor(position)), list[i].id.v in pinned,
                        canUp = i > 0, canDown = i < list.size - 1,
                        fit = SchemeFitGrade.letter(schemeFit(list[i], schemeFor(position))),
                        onUp = { save(pins.copy(order = pins.order + (position to moved(list, i, i - 1)))) },
                        onDown = { save(pins.copy(order = pins.order + (position to moved(list, i, i + 1)))) },
                    )
                }
                if (pinned.isNotEmpty()) item(key = "$key-auto") {
                    TextButton(onClick = { save(pins.copy(order = pins.order - position)) },
                        modifier = Modifier.padding(horizontal = 8.dp)) { Text("Auto", fontSize = 12.sp) }
                }
            }
        }

        item { SectionHeader("Packages") }
        SPOTS.forEach { spot ->
            val chart = chartFor(spot.position)
            val list = chart.forPackage(spot.keys.first(), spot.position)
            if (list.size <= 1) return@forEach
            val pinned = spot.keys.firstNotNullOfOrNull { pins.packages[it]?.get(spot.position) }.orEmpty()
            val key = "pkg-${spot.label}"
            item(key = key) {
                Header(
                    spot.label, list.take(spot.count).joinToString(", ") { it.lastName },
                    if (pinned.isNotEmpty()) "pinned" else "auto",
                    open == key,
                ) { open = if (open == key) null else key }
            }
            if (open == key) {
                fun write(ids: List<Int>?): DepthPins = pins.copy(packages = spot.keys.fold(pins.packages) { acc, k ->
                    val positions = (acc[k] ?: emptyMap()).let { if (ids == null) it - spot.position else it + (spot.position to ids) }
                    if (positions.isEmpty()) acc - k else acc + (k to positions)
                })
                val shown = list.take(spot.count + 2)
                items(shown.indices.toList(), key = { "$key-${shown[it].id.v}" }) { i ->
                    PlayerRow(
                        i, shown[i], overall(shown[i], schemeFor(spot.position)), shown[i].id.v in pinned,
                        canUp = i > 0, canDown = i < shown.size - 1, inPackage = i < spot.count,
                        fit = SchemeFitGrade.letter(schemeFit(shown[i], schemeFor(spot.position))),
                        onUp = { save(write(moved(list, i, i - 1))) },
                        onDown = { save(write(moved(list, i, i + 1))) },
                    )
                }
                if (pinned.isNotEmpty()) item(key = "$key-auto") {
                    TextButton(onClick = { save(write(null)) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Auto", fontSize = 12.sp)
                    }
                }
            }
        }

        item { SectionHeader("Returners") }
        item(key = "returners") {
            val kick = SpecialTeams.returnerFor(offChart, offense)
            val punt = SpecialTeams.returnerFor(offChart, offense, punt = true)
            val candidates = (offChart.at(Position.WR) + offChart.at(Position.RB) + offChart.at(Position.CB))
                .sortedByDescending { it.ratings[RatingId.SPEED] }.take(6)
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Kicks: ${kick?.name ?: "-"}${if (pins.kickReturner != null) " (pinned)" else ""}",
                    fontFamily = DataFamily, fontSize = 12.sp)
                Text("Punts: ${punt?.name ?: "-"}${if (pins.puntReturner != null) " (pinned)" else ""}",
                    fontFamily = DataFamily, fontSize = 12.sp)
                candidates.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.position.label} ${c.name}", fontFamily = DataFamily, fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { save(pins.copy(kickReturner = c.id.v)) }) { Text("KR", fontSize = 11.sp) }
                        TextButton(onClick = { save(pins.copy(puntReturner = c.id.v)) }) { Text("PR", fontSize = 11.sp) }
                    }
                }
                if (pins.kickReturner != null || pins.puntReturner != null) {
                    TextButton(onClick = { save(pins.copy(kickReturner = null, puntReturner = null)) }) {
                        Text("Auto returners", fontSize = 12.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private val SPECIALISTS = setOf(Position.K, Position.P, Position.LS)

@Composable
private fun Header(title: String, summary: String, state: String, isOpen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text((if (isOpen) "- " else "+ ") + title, fontFamily = DataFamily, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.width(150.dp))
        Text(summary, fontFamily = DataFamily, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(state, fontFamily = DataFamily, fontSize = 11.sp,
            color = if (state == "auto") MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary)
    }
}

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
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", fontFamily = DataFamily, fontSize = 12.sp, modifier = Modifier.width(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            player.name + (if (fit.isNotEmpty()) "  $fit" else "") + if (pinned) "  *" else "",
            fontFamily = DataFamily, fontSize = 12.sp, modifier = Modifier.weight(1f),
            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
            color = if (inPackage) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("$ovr", fontFamily = DataFamily, fontSize = 12.sp, modifier = Modifier.width(32.dp))
        TextButton(onClick = onUp, enabled = canUp, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(40.dp)) { Text("^", fontSize = 13.sp) }
        TextButton(onClick = onDown, enabled = canDown, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(40.dp)) { Text("v", fontSize = 13.sp) }
    }
}
