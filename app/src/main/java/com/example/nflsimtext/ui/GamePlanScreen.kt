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
    /** The value in plain words: what a coach would call it. */
    val words: (Float) -> String,
    val set: (GamePlan, Float?) -> GamePlan,
)

/** Plain words for a value, by the bands it falls in, lowest first. */
private fun bands(vararg cuts: Pair<Float, String>, top: String): (Float) -> String = { v ->
    cuts.firstOrNull { (limit, _) -> v < limit }?.second ?: top
}

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
        Lever("Pass rate", "How often your coordinator throws, before down, distance and score adjust it.",
            plan.passRate, staffPlan.passRate ?: off.basePassRate, 0.2f..0.85f, true,
            bands(0.40f to "Run-first", 0.52f to "Balanced", 0.62f to "Pass-first", top = "Air it out")) { p, v -> p.copy(passRate = v) },
        Lever("Play action", "How often an early-down pass starts with a fake handoff.",
            plan.playActionRate, staffPlan.playActionRate ?: off.playActionRate, 0f..0.6f, true,
            bands(0.15f to "Rarely", 0.30f to "Sometimes", 0.45f to "Often", top = "Most of the time")) { p, v -> p.copy(playActionRate = v) },
        Lever("Deep shots", "How often a pass goes deep. It follows play action unless you set it.",
            plan.deepShotRate, (plan.playActionRate ?: staffPlan.playActionRate ?: off.playActionRate) * GamePlan.DEEP_SHOT_SHARE,
            0f..0.5f, true, bands(0.10f to "Rarely", 0.20f to "Sometimes", 0.32f to "Often", top = "Every chance")) { p, v -> p.copy(deepShotRate = v) },
        Lever("Pass when trailing", "How much more he throws when you are behind.",
            plan.trailingPassScale, staffPlan.trailingPassScale ?: 1f, 0f..2f, false,
            bands(0.75f to "Stays patient", 1.25f to "Like most coaches", top = "Throws to catch up")) { p, v -> p.copy(trailingPassScale = v) },
        Lever("Two-minute drill", "How much more he throws late in a half when not leading.",
            plan.twoMinutePassBoost, staffPlan.twoMinutePassBoost ?: GamePlan.TWO_MINUTE_BOOST, 0f..0.5f, true,
            bands(0.10f to "Barely changes", 0.25f to "Speeds up", top = "All out")) { p, v -> p.copy(twoMinutePassBoost = v) },
        Lever("Fourth down", "How often he goes for it on fourth down instead of kicking.",
            plan.fourthDownAggression, staffPlan.fourthDownAggression ?: GamePlan.defaultAggression(team.id.v), 0f..1f, false,
            bands(0.30f to "Conservative", 0.60f to "Balanced", 0.80f to "Aggressive", top = "Very aggressive")) { p, v -> p.copy(fourthDownAggression = v) },
    )
    val defense = listOf(
        Lever("Blitz", "How often extra rushers come after the quarterback.",
            plan.blitzRate, staffPlan.blitzRate ?: def.blitzRate, 0f..0.7f, true,
            bands(0.15f to "Rarely", 0.30f to "Sometimes", 0.45f to "Often", top = "All the time")) { p, v -> p.copy(blitzRate = v) },
        Lever("Man or zone", "Man: each defender follows a receiver. Zone: each covers an area.",
            plan.manZoneSplit, staffPlan.manZoneSplit ?: def.manZoneSplit, 0f..1f, true,
            bands(0.35f to "Mostly zone", 0.65f to "Mixed", top = "Mostly man")) { p, v -> p.copy(manZoneSplit = v) },
        Lever("Double their best receiver", "How often two defenders cover their top target.",
            plan.doubleTeamRate, staffPlan.doubleTeamRate ?: GamePlan.DOUBLE_TEAM_RATE, 0f..0.6f, true,
            bands(0.15f to "Rarely", 0.30f to "Sometimes", top = "Often")) { p, v -> p.copy(doubleTeamRate = v) },
    )
    // The exact values, for the players who want them; plain words for everyone else.
    var numbers by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    ScreenList {
        item {
            Column {
                Text("Game plan", style = NdTheme.type.display, color = NdTheme.colors.chalk)
                Text(
                    "When you play a week, your coordinators call the plays from these tendencies. " +
                        "Leave one alone and it follows your staff. To call the plays yourself, " +
                        "use Call the plays yourself on the hub.",
                    style = NdTheme.type.body, color = NdTheme.colors.chalkDim,
                )
                SecondaryButton(
                    if (numbers) "Hide the numbers" else "Show the numbers", { numbers = !numbers },
                    Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }
        item {
            SituationBlock("Offense", meta = off.name) {
                offense.forEach { l ->
                    LeverRow(l, numbers, onChange = { v -> plan = l.set(plan, v) }, onDone = { save(plan) },
                        onReset = { save(l.set(plan, null)) })
                }
            }
        }
        item {
            SituationBlock("Defense", meta = def.name) {
                defense.forEach { l ->
                    LeverRow(l, numbers, onChange = { v -> plan = l.set(plan, v) }, onDone = { save(plan) },
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
private fun LeverRow(l: Lever, numbers: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit, onReset: () -> Unit) {
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
                if (!numbers) l.words(shown)
                else if (l.percent) "${(shown * 100).roundToInt()}%" else "%.2f".format(shown),
                style = NdTheme.type.data,
                color = if (l.value != null) c.chalk else c.chalkDim,
            )
            // An untouched lever says whose call it is showing.
            if (l.value == null) {
                Text(
                    if (numbers) " staff" else "",
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
