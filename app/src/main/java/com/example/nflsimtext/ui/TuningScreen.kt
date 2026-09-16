package com.example.nflsimtext.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.tuning.TuningFields
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

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(16.dp)) {
                TextButton(onClick = onBack) { Text("< Hub", fontSize = 12.sp) }
                Text("Tuning", fontFamily = DataFamily, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Every coefficient in the sim. Games use a change from the next snap; " +
                        "the AI and player development from the next offseason.",
                    fontFamily = DataFamily, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (label, preset) ->
                        if (table == preset) Button(onClick = {}) { Text(label) }
                        else OutlinedButton(onClick = { save(preset) }) { Text(label) }
                    }
                }
                if (presets.none { it.second == table }) {
                    Text("Custom", fontFamily = DataFamily, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        groups.forEach { (group, fields) ->
            val changed = fields.count { it.value != it.default }
            item(key = "g-$group") {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { open = if (open == group) null else group }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        (if (open == group) "- " else "+ ") + groupLabel(group),
                        fontFamily = DataFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (changed > 0) "$changed changed" else "${fields.size} values",
                        fontFamily = DataFamily, fontSize = 11.sp,
                        color = if (changed > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (open == group) {
                items(fields, key = { "f-$group-${it.name}" }) { f ->
                    FieldSlider(
                        f,
                        onChange = { v -> table = TuningFields.set(table, f.group, f.name, v) },
                        onDone = { save(table) },
                    )
                }
                item(key = "r-$group") {
                    TextButton(
                        onClick = { save(TuningFields.resetGroup(table, group)) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Reset ${groupLabel(group).lowercase()} to Realistic", fontSize = 12.sp) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FieldSlider(f: TuningFields.Field, onChange: (Double) -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row {
            Text(words(f.name), fontFamily = DataFamily, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (f.isInt) f.value.roundToLong().toString() else "%.4g".format(f.value),
                fontFamily = DataFamily, fontSize = 12.sp,
                fontWeight = if (f.value != f.default) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Slider(
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
