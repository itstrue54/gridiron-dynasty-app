package com.example.nflsimtext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.Team
import com.nflsim.engine.narrative.Recaps
import com.nflsim.engine.sim.PlayLog

/**
 * The game, told (SPEC 10.4): the result, then the plays that swung it.
 * Written from the play log, so a game without one has no recap.
 */
@Composable
fun RecapBlock(
    seed: Long,
    plays: List<PlayLog>,
    home: Team,
    away: Team,
    homeScore: Int,
    awayScore: Int,
) {
    val recap = remember(plays, homeScore, awayScore) {
        Recaps.write(plays, home, away, homeScore, awayScore,
            Recaps.wordsFor(seed, plays, home.id.v, away.id.v, homeScore, awayScore))
    } ?: return
    val c = NdTheme.colors
    SituationBlock("Recap", meta = "The plays that swung it") {
        Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
            Text(recap.lead, style = NdTheme.type.body, color = c.chalk)
            recap.moments.forEach { moment ->
                Text(moment.text, style = NdTheme.type.body, color = c.chalkDim)
            }
        }
    }
}
