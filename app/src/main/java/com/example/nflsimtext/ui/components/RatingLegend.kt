package com.example.nflsimtext.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ratingColor

/**
 * How to read a rating, once, where ratings are read: what the colours mean
 * and what a range is. First-time players met "79-92", grey, white and cyan
 * numbers with nothing to say what any of it was.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingLegend(modifier: Modifier = Modifier) {
    val c = NdTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.xs)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.m)) {
            listOf(95 to "90+ elite", 85 to "80s good", 75 to "70s starter", 60 to "below 70").forEach { (v, label) ->
                Text(label, style = NdTheme.type.caption, color = ratingColor(v, c))
            }
        }
        Text(
            "A range like 79-92 is a man your scouts are not sure of yet. " +
                "It narrows with each season he spends with you.",
            style = NdTheme.type.caption, color = c.chalkDim,
        )
    }
}
