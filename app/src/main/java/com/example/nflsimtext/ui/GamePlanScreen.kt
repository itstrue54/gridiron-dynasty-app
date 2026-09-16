package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.example.nflsimtext.ui.components.NdSlider
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
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

    ScreenList {
        item {
            Column {
                Text("Game plan", style = NdTheme.type.display, color = NdTheme.colors.chalk)
                Text(
                    "Your coordinators call the plays; these set what they call from. " +
                        "A lever you have not touched follows their tendencies in your schemes.",
                    style = NdTheme.type.body, color = NdTheme.colors.chalkDim,
                )
            }
        }
        item {
            SituationBlock("Offense", meta = off.name) {
                offense.forEach { l ->
                    LeverRow(l, onChange = { v -> plan = l.set(plan, v) }, onDone = { save(plan) },
                        onReset = { save(l.set(plan, null)) })
                }
            }
        }
        item {
            SituationBlock("Defense", meta = def.name) {
                defense.forEach { l ->
                    LeverRow(l, onChange = { v -> plan = l.set(plan, v) }, onDone = { save(plan) },
                        onReset = { save(l.set(plan, null)) })
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                if (plan != GamePlan()) {
                    SecondaryButton(
                        "Follow the staff's tendencies again",
                        { save(GamePlan()) },
                        Modifier.fillMaxWidth(),
                    )
                }
                SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LeverRow(l: Lever, onChange: (Float) -> Unit, onDone: () -> Unit, onReset: () -> Unit) {
    val c = NdTheme.colors
    val shown = (l.value ?: l.default).coerceIn(l.range.start, l.range.endInclusive)
    Column(Modifier.padding(vertical = NdTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    l.label,
                    style = if (l.value != null) NdTheme.type.data.copy(fontWeight = FontWeight.W600)
                    else NdTheme.type.data,
                    color = c.chalk,
                )
                Text(l.note, style = NdTheme.type.caption, color = c.chalkDim)
            }
            Text(
                if (l.percent) "${(shown * 100).roundToInt()}%" else "%.2f".format(shown),
                style = NdTheme.type.data,
                color = if (l.value != null) c.chalk else c.chalkDim,
            )
            // An untouched lever says whose number it is showing.
            if (l.value == null) {
                Text(
                    " staff",
                    style = NdTheme.type.caption, color = c.chalkDim,
                )
            } else {
                TextButton(onClick = onReset) {
                    Text("Reset", style = NdTheme.type.caption, color = c.pylonText)
                }
            }
        }
        NdSlider(
            value = shown,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = l.range,
        )
    }
}
