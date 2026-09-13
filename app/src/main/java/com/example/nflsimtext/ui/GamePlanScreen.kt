package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import com.nflsim.engine.model.GamePlan
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.season.Dynasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One slider on the game plan: its override, what it falls back to, and how to write it. */
private class Lever(
    val label: String,
    val note: String,
    val value: Float?,
    val default: Float,
    val range: ClosedFloatingPointRange<Float>,
    val percent: Boolean,
    val set: (GamePlan, Float?) -> GamePlan,
)

/**
 * SPEC 5.4's user control: you set the tendencies your coordinators call
 * from, not the plays. Each lever overrides the scheme's value or the
 * league's usual figure; reset one and it follows the scheme again.
 */
@Composable
fun GamePlanScreen(dynasty: Dynasty, store: DynastyStore, scope: CoroutineScope, onBack: () -> Unit) {
    val team = dynasty.team
    val off = SchemeCatalog[team.offenseScheme]
    val def = SchemeCatalog[team.defenseScheme]
    // Untouched levers follow the staff: the coordinators' tendencies, then the scheme.
    val staffPlan = com.nflsim.engine.gen.Tendencies.of(team.staff, dynasty.league.coaches)
    var plan by remember { mutableStateOf(team.gamePlan) }
    fun save(p: GamePlan) {
        plan = p
        scope.launch { store.setGamePlan(p) }
    }

    val offense = listOf(
        Lever("Pass rate", "before down, distance and score", plan.passRate, staffPlan.passRate ?: off.basePassRate,
            0.2f..0.85f, true) { p, v -> p.copy(passRate = v) },
        Lever("Play action", "on early downs", plan.playActionRate, staffPlan.playActionRate ?: off.playActionRate,
            0f..0.6f, true) { p, v -> p.copy(playActionRate = v) },
        Lever("Deep shots", "follows play action unless set", plan.deepShotRate,
            (plan.playActionRate ?: staffPlan.playActionRate ?: off.playActionRate) * GamePlan.DEEP_SHOT_SHARE,
            0f..0.5f, true) { p, v -> p.copy(deepShotRate = v) },
        Lever("Pass when trailing", "times the usual lean", plan.trailingPassScale, staffPlan.trailingPassScale ?: 1f,
            0f..2f, false) { p, v -> p.copy(trailingPassScale = v) },
        Lever("Two-minute pass boost", "when not leading", plan.twoMinutePassBoost, staffPlan.twoMinutePassBoost ?: GamePlan.TWO_MINUTE_BOOST,
            0f..0.5f, true) { p, v -> p.copy(twoMinutePassBoost = v) },
        Lever("Fourth-down aggression", "0 punts always, 1 goes for it", plan.fourthDownAggression,
            staffPlan.fourthDownAggression ?: GamePlan.defaultAggression(team.id.v), 0f..1f, false) { p, v -> p.copy(fourthDownAggression = v) },
    )
    val defense = listOf(
        Lever("Blitz rate", "extra rushers", plan.blitzRate, staffPlan.blitzRate ?: def.blitzRate,
            0f..0.7f, true) { p, v -> p.copy(blitzRate = v) },
        Lever("Man coverage", "share of snaps in man", plan.manZoneSplit, staffPlan.manZoneSplit ?: def.manZoneSplit,
            0f..1f, true) { p, v -> p.copy(manZoneSplit = v) },
        Lever("Double their top receiver", "how often", plan.doubleTeamRate, staffPlan.doubleTeamRate ?: GamePlan.DOUBLE_TEAM_RATE,
            0f..0.6f, true) { p, v -> p.copy(doubleTeamRate = v) },
    )

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(16.dp)) {
                TextButton(onClick = onBack) { Text("< Hub", fontSize = 12.sp) }
                Text("Game plan", fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Your coordinators still call the plays; these set what they call from. " +
                        "A lever you have not touched follows your coordinators' tendencies in your schemes (${off.name}, ${def.name}).",
                    fontFamily = Mono, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionHeader("Offense") }
        items(offense, key = { "o-${it.label}" }) { l ->
            LeverRow(l, onChange = { v -> plan = l.set(plan, v) }, onDone = { save(plan) },
                onReset = { save(l.set(plan, null)) })
        }
        item { SectionHeader("Defense") }
        items(defense, key = { "d-${it.label}" }) { l ->
            LeverRow(l, onChange = { v -> plan = l.set(plan, v) }, onDone = { save(plan) },
                onReset = { save(l.set(plan, null)) })
        }
        if (plan != GamePlan()) item {
            TextButton(onClick = { save(GamePlan()) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Reset the whole plan to your staff's tendencies", fontSize = 12.sp)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LeverRow(l: Lever, onChange: (Float) -> Unit, onDone: () -> Unit, onReset: () -> Unit) {
    val shown = (l.value ?: l.default).coerceIn(l.range.start, l.range.endInclusive)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.label, fontFamily = Mono, fontSize = 13.sp,
                    fontWeight = if (l.value != null) FontWeight.Bold else FontWeight.Normal)
                Text(l.note, fontFamily = Mono, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                (if (l.percent) "${(shown * 100).roundToInt()}%" else "%.2f".format(shown)) +
                    if (l.value == null) " (staff)" else "",
                fontFamily = Mono, fontSize = 12.sp,
            )
            if (l.value != null) TextButton(onClick = onReset) { Text("Reset", fontSize = 11.sp) }
        }
        Slider(
            value = shown,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = l.range,
        )
    }
}
