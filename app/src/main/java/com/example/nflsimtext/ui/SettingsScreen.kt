package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ThemeSetting
import com.example.nflsimtext.ui.theme.next

/**
 * Everything about the app rather than the club: how it looks and feels, the
 * saves, the tuning table and the way back to the title screen. They used to
 * share the hub's one row of links with the GM's work.
 */
@Composable
fun SettingsScreen(
    theme: ThemeSetting,
    onTheme: (ThemeSetting) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    onNavigate: (Tab) -> Unit,
    onTitle: () -> Unit,
    onBack: () -> Unit,
) {
    val c = NdTheme.colors
    val wide = Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s)
    ScreenList {
        item { Text("Settings", style = NdTheme.type.display, color = c.chalk) }
        item {
            SituationBlock("Look and feel") {
                SecondaryButton("Theme: ${theme.label}", { onTheme(theme.next()) }, Modifier.fillMaxWidth())
                SecondaryButton(if (haptics) "Haptics: on" else "Haptics: off", { onHaptics(!haptics) }, wide)
            }
        }
        item {
            SituationBlock("Your dynasty") {
                SecondaryButton("Saves", { onNavigate(Tab.SAVES) }, Modifier.fillMaxWidth())
                SecondaryButton("Back to the title screen", onTitle, wide)
            }
        }
        item {
            SituationBlock("Under the hood") {
                Column {
                    Text(
                        "Every number the simulation plays by, and presets for a different league.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                    )
                    SecondaryButton("Tuning", { onNavigate(Tab.TUNING) }, wide)
                    SecondaryButton("Design gallery", { onNavigate(Tab.GALLERY) }, wide)
                }
            }
        }
        item { SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth()) }
    }
}
