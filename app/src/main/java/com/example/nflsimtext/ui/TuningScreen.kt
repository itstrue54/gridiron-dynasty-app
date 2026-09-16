package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.tuning.TuningFields
import androidx.compose.material3.SliderDefaults
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.NdSlider
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.tuning.TuningTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * SPEC 12's tuning table, behind the Hub's Advanced button: a preset in one
 * tap, then every value in the sim, group by group. Changes are saved with
 * the league; games use them from the next snap, and the AI and player
 * development from the next offseason.
 */
@Composable
fun TuningScreen(dynasty: Dynasty, store: DynastyStore, scope: CoroutineScope, onBack: () -> Unit) {
    var table by remember { mutableStateOf(dynasty.league.tuning) }
    var open by remember { mutableStateOf<String?>(null) }
    fun save(t: TuningTable) {
        table = t
        scope.launch { store.setTuning(t) }
    }
    val presets = listOf(
        "Realistic" to TuningTable.REALISTIC,
        "Arcade" to TuningTable.ARCADE,
        "Grinder" to TuningTable.GRINDER,
    )
    val groups = TuningFields.list(table).groupBy { it.group }

    val preset = presets.firstOrNull { it.second == table }?.first

    ScreenList {
        item {
            Column {
                Text("Tuning", style = NdTheme.type.display, color = NdTheme.colors.chalk)
                Text(
                    "Every coefficient in the sim. Games use a change from the next snap; " +
                        "the AI and player development from the next offseason.",
                    style = NdTheme.type.body, color = NdTheme.colors.chalkDim,
                )
                Spacer(Modifier.height(NdTheme.spacing.m))
                FilterChipRow(
                    options = presets.map { it.first },
                    selected = preset ?: "",
                    onSelect = { label -> presets.first { it.first == label }.let { save(it.second) } },
                )
                if (preset == null) {
                    Text(
                        "Custom values.",
                        style = NdTheme.type.caption, color = NdTheme.colors.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.xs),
                    )
                }
            }
        }
        groups.forEach { (group, fields) ->
            val changed = fields.count { it.value != it.default }
            item(key = "g-$group") {
                SituationBlock(
                    groupLabel(group),
                    meta = if (changed > 0) "$changed changed" else "${fields.size} values",
                    situation = if (changed > 0) Situation.THIRD_DOWN else Situation.NORMAL,
                    onClick = if (open == group) null else ({ open = group }),
                ) {
                    if (open != group) {
                        Text(
                            "Tap to open.",
                            style = NdTheme.type.caption, color = NdTheme.colors.chalkDim,
                        )
                    } else {
                        fields.forEach { f ->
                            FieldSlider(
                                f,
                                onChange = { v -> table = TuningFields.set(table, f.group, f.name, v) },
                                onDone = { save(table) },
                            )
                        }
                        Row(
                            Modifier.padding(top = NdTheme.spacing.s),
                            horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                        ) {
                            SecondaryButton("Close", { open = null })
                            SecondaryButton(
                                "Reset to Realistic",
                                { save(TuningFields.resetGroup(table, group)) },
                            )
                        }
                    }
                }
            }
        }
        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun FieldSlider(f: TuningFields.Field, onChange: (Double) -> Unit, onDone: () -> Unit) {
    val c = NdTheme.colors
    Column(Modifier.padding(vertical = NdTheme.spacing.xs)) {
        Row {
            Text(
                words(f.name),
                style = NdTheme.type.data, color = c.chalk,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (f.isInt) f.value.roundToLong().toString() else "%.4g".format(f.value),
                style = if (f.value != f.default) NdTheme.type.data.copy(fontWeight = FontWeight.W600)
                else NdTheme.type.data,
                color = if (f.value != f.default) c.chalk else c.chalkDim,
            )
        }
        NdSlider(
            value = f.value.toFloat().coerceIn(0f, f.max.toFloat()),
            onValueChange = { onChange(it.toDouble()) },
            onValueChangeFinished = onDone,
            valueRange = 0f..f.max.toFloat(),
            steps = if (f.isInt) (f.max.toInt() - 1).coerceIn(0, 200) else 0,
        )
    }
}

/** "sackGivenPressure" reads as "Sack given pressure". */
private fun words(name: String): String =
    name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

private fun groupLabel(group: String): String = if (group == "ai") "AI" else words(group)
