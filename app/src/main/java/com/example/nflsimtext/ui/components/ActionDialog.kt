package com.example.nflsimtext.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import com.example.nflsimtext.ui.theme.NdTheme

/**
 * A decision that has to be made where the user can see it. A confirmation
 * placed in the list scrolls away from the row that asked for it; this sits
 * over the screen until it is answered or dismissed.
 */
@Composable
fun ActionDialog(
    title: String,
    onDismiss: () -> Unit,
    situation: Situation = Situation.NORMAL,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth().background(NdTheme.colors.turf)) {
            SituationBlock(title, situation = situation, content = content)
        }
    }
}
