package com.example.nflsimtext.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.reducedMotion

/** A club and what it has scored. */
data class TeamScore(val abbr: String, val name: String, val score: Int)

/** Who has the ball. */
enum class Side { AWAY, HOME }

/**
 * The broadcast line (docs/DESIGN.md 5). One semantics node, because a screen
 * reader should say the score as a sentence, not as six numbers.
 */
@Composable
fun Scoreboard(
    away: TeamScore,
    home: TeamScore,
    quarter: Int,
    clock: String,
    possession: Side? = null,
    /** Set for a game that is over, where a quarter and a clock say nothing. */
    status: String? = null,
    /** Off when the state was jumped to rather than played out. */
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = NdTheme.colors
    val holder = when (possession) {
        Side.AWAY -> away
        Side.HOME -> home
        null -> null
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = NdTheme.spacing.s)
            .clearAndSetSemantics {
                contentDescription = "${away.name} ${away.score}, ${home.name} ${home.score}, " +
                    (status ?: "${quarterWords(quarter)}, ${clockWords(clock)}") +
                    (holder?.let { ", ${it.name} has the ball." } ?: ".")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamColumn(away, possession == Side.AWAY, TextAlign.Start, Modifier.weight(1f), animate)
        Column(Modifier.weight(0.9f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (status != null) {
                Text(status, style = NdTheme.type.headline, color = c.chalkDim)
            } else {
                Text("Q$quarter", style = NdTheme.type.label, color = c.chalkDim)
                Text(clock, style = NdTheme.type.headline, color = c.chalk)
            }
        }
        TeamColumn(home, possession == Side.HOME, TextAlign.End, Modifier.weight(1f), animate)
    }
}

@Composable
private fun TeamColumn(
    team: TeamScore,
    hasBall: Boolean,
    align: TextAlign,
    modifier: Modifier,
    animate: Boolean = true,
) {
    val c = NdTheme.colors
    Column(
        modifier,
        horizontalAlignment = if (align == TextAlign.Start) Alignment.Start else Alignment.End,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.xs)) {
            if (hasBall && align == TextAlign.Start) {
                Text("▸", style = NdTheme.type.label, color = c.pylonText)
            }
            Text(team.abbr, style = NdTheme.type.headline, color = c.chalkDim)
            if (hasBall && align == TextAlign.End) {
                Text("▸", style = NdTheme.type.label, color = c.pylonText)
            }
        }
        val still = !animate || reducedMotion()
        val quick = NdTheme.motion.quick
        AnimatedContent(
            targetState = team.score,
            transitionSpec = {
                val spec: FiniteAnimationSpec<Float> =
                    if (still) snap() else tween(quick, easing = FastOutSlowInEasing)
                fadeIn(spec) togetherWith fadeOut(spec)
            },
            label = "score",
        ) { score ->
            Text(score.toString(), style = NdTheme.type.scoreboard, color = c.chalk)
        }
    }
}

private fun quarterWords(quarter: Int) = when (quarter) {
    1 -> "first quarter"
    2 -> "second quarter"
    3 -> "third quarter"
    4 -> "fourth quarter"
    else -> "overtime"
}

private fun clockWords(clock: String): String {
    val parts = clock.split(":")
    if (parts.size != 2) return clock
    val minutes = parts[0].toIntOrNull() ?: return clock
    val seconds = parts[1].toIntOrNull() ?: return clock
    return "$minutes minutes $seconds seconds"
}

private val demoAway = TeamScore("AUS", "Austin", 24)
private val demoHome = TeamScore("MEM", "Memphis", 17)

@Preview(name = "Night")
@Composable
private fun ScoreNight() = PreviewFrame(dark = true) {
    Scoreboard(demoAway, demoHome, 3, "04:12", Side.AWAY)
}

@Preview(name = "Day")
@Composable
private fun ScoreDay() = PreviewFrame(dark = false) {
    Scoreboard(demoAway, demoHome, 3, "04:12", Side.AWAY)
}

@Preview(name = "Night 1.5x", fontScale = 1.5f)
@Composable
private fun ScoreLarge() = PreviewFrame(dark = true) {
    Scoreboard(demoAway, demoHome, 3, "04:12", Side.AWAY)
}
