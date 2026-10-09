package com.example.nflsimtext.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.nflsimtext.ui.components.Direction
import com.example.nflsimtext.ui.components.DriveTracker
import com.example.nflsimtext.ui.components.PlayEvent
import com.example.nflsimtext.ui.components.PlayLogEntry
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.Scoreboard
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.TeamScore
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.reducedMotion
import kotlinx.coroutines.delay
import com.nflsim.engine.model.Team
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side as EngineSide
import com.example.nflsimtext.ui.components.Side as BoardSide

/**
 * The game, one play at a time. The engine has already played it - the whole
 * week runs at once, because a league needs every other result too - so this
 * screen walks its play-by-play rather than simulating anything itself. Next
 * play shows one; sim drive runs to the change of possession; sim to end
 * jumps to the final.
 */
@Composable
fun GameDayScreen(dynasty: Dynasty, onBoxScore: () -> Unit = {}, onBack: () -> Unit = {}) {
    val c = NdTheme.colors
    val game = dynasty.lastGame
    if (game == null || game.playByPlay.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(NdTheme.spacing.xl)) {
            Text("No game to watch yet.", style = NdTheme.type.title, color = c.chalk)
            Text(
                "Play a week from the hub and the game lands here.",
                style = NdTheme.type.body, color = c.chalkDim,
            )
            SecondaryButton("Back to the hub", onBack, Modifier.padding(top = NdTheme.spacing.m))
        }
        return
    }

    val home = dynasty.league.team(game.home)
    val away = dynasty.league.team(game.away)
    val plays = game.playByPlay
    // A game that has been played opens at the final whistle, every play in
    // the log; watching it from the kickoff is a choice, not the only view.
    var shown by remember(plays) { mutableStateOf(plays.size) }
    // A play that was watched animates; a state that was jumped to does not
    // (docs/DESIGN.md 7).
    var animate by remember(plays) { mutableStateOf(false) }
    var scored by remember(plays) { mutableStateOf(false) }
    val motion = NdTheme.motion
    val haptic = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    // The snap that comes next: the board and the field show the game as it
    // stands now, after the last play.
    val now = plays.getOrNull(shown) ?: plays.last()

    LaunchedEffect(shown, animate) {
        // The end zone takes the pylon, holds, and gives it back.
        if (animate && shown > 0 && eventOf(plays, shown - 1) == PlayEvent.SCORE) {
            scored = true
            delay(motion.signature.toLong() + 150L)
            scored = false
        } else {
            scored = false
        }
    }
    val done = shown >= plays.size
    val offenseIsHome = now.offense == EngineSide.HOME
    val offense = if (offenseIsHome) home else away
    val defense = if (offenseIsHome) away else home
    // The ball changing hands says so on the field: the opening kickoff, and
    // after that whatever handed it over.
    val change = if (done) null
        else possessionChange(plays.take(shown), now.offense, now.quarter, now.homeScore, now.awayScore)

    ScreenList {
        item {
            Scoreboard(
                away = TeamScore(away.abbrev, away.name, if (done) game.awayScore else now.awayScore),
                home = TeamScore(home.abbrev, home.name, if (done) game.homeScore else now.homeScore),
                quarter = now.quarter,
                clock = now.clockText,
                animate = animate,
                possession = if (done) null else if (offenseIsHome) BoardSide.HOME else BoardSide.AWAY,
                status = if (done) "Final" else null,
            )
        }

        item { LastPlayOf(plays, shown) }

        if (!done) {
            item {
                SituationBlock(
                    "${downAndDistance(now)} at ${spot(now, defense)}",
                    situation = situationOf(now),
                    meta = "${offense.abbrev} ball",
                ) {
                    DriveTracker(
                        ballOn = now.yardLine,
                        lineToGain = (now.yardLine + now.distance).coerceAtMost(100),
                        direction = Direction.RIGHT,
                        ballLabel = spot(now, defense),
                        gainLabel = spotOf((now.yardLine + now.distance).coerceAtMost(100), defense),
                        scored = scored,
                        animate = animate,
                        banner = change?.let { possessionBanner(it, offense.abbrev) },
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                if (!done) {
                    PrimaryButton(
                        "Next play",
                        {
                            val next = shown
                            animate = true
                            shown = next + 1
                            if (hapticsOn) when (eventOf(plays, next)) {
                                PlayEvent.SCORE, PlayEvent.TURNOVER ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                PlayEvent.FIRST_DOWN ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                null -> Unit
                            }
                        },
                        Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                        // The sim buttons show the end state, and skip the play.
                        SecondaryButton("Sim drive", {
                            animate = false
                            shown = endOfDrive(plays, shown)
                        })
                        SecondaryButton("Sim to end", {
                            animate = false
                            shown = plays.size
                        })
                    }
                } else {
                    PrimaryButton("See the box score", onBoxScore, Modifier.fillMaxWidth())
                    SecondaryButton("Watch it play by play", {
                        animate = false
                        shown = 0
                    }, Modifier.fillMaxWidth())
                    SecondaryButton("Back to the hub", onBack, Modifier.fillMaxWidth())
                }
            }
        }

        if (done) {
            item {
                RecapBlock(dynasty.seed, plays, home, away, game.homeScore, game.awayScore)
            }
        }

        item {
            SituationBlock("Play log", meta = "$shown of ${plays.size}") {
                // Newest first, and everything shown so far - the whole game
                // once it is over.
                (shown - 1 downTo 0).forEach { i ->
                    val entry = @Composable {
                        PlayLogEntry(
                            downDistance = downAndDistance(plays[i]),
                            text = plays[i].text,
                            event = eventOf(plays, i),
                        )
                    }
                    // The newest entry expands in at the top; the rest sit still.
                    if (i == shown - 1 && animate && !reducedMotion()) {
                        val state = remember(i) { MutableTransitionState(false) }
                            .apply { targetState = true }
                        AnimatedVisibility(
                            visibleState = state,
                            enter = expandVertically(motion.standardSpec()) +
                                fadeIn(motion.standardSpec()),
                        ) { entry() }
                    } else {
                        entry()
                    }
                }
            }
        }
    }
}

/** Colour follows the situation, and the down and distance says it in words. */
private fun situationOf(play: PlayLog): Situation = when {
    play.clock <= 120 && (play.quarter == 2 || play.quarter >= 4) -> Situation.TWO_MINUTE
    play.yardLine >= 80 -> Situation.RED_ZONE
    play.down >= 3 -> Situation.THIRD_DOWN
    else -> Situation.NORMAL
}

/** "MEM 34" past midfield, "own 34" before it - the way a broadcast says it. */
private fun spot(play: PlayLog, defense: Team): String = spotOf(play.yardLine, defense)

private fun spotOf(yardLine: Int, defense: Team): String = when {
    yardLine >= 100 -> "the goal line"
    yardLine > 50 -> "${defense.abbrev} ${100 - yardLine}"
    yardLine == 50 -> "midfield"
    else -> "own $yardLine"
}

/**
 * How many plays are shown once the drive under way ends: every snap of the
 * club that has the ball now, so the field turns to the new possession.
 */
internal fun endOfDrive(plays: List<PlayLog>, from: Int): Int {
    if (from >= plays.size) return plays.size
    val side = plays[from].offense
    var i = from
    while (i < plays.size && plays[i].offense == side) i++
    return i
}

/**
 * The last play among the first [shown] of [plays] - a note between plays,
 * the weather or a timeout, is not one - at the top of the game.
 */
@Composable
internal fun LastPlayOf(plays: List<PlayLog>, shown: Int) {
    val i = (shown - 1 downTo 0).firstOrNull { plays[it].kind != com.nflsim.engine.sim.PlayKind.NOTE }
    LastPlay(i?.let { plays[it] }, i?.let { eventOf(plays, it) })
}

/**
 * The last play at the top of the game, the way a broadcast puts the result
 * up: its down and distance, and what happened. Before the first snap, the
 * kickoff.
 */
@Composable
internal fun LastPlay(play: PlayLog?, event: PlayEvent?) {
    SituationBlock("Last play", meta = if (play == null) "Kickoff" else null) {
        if (play == null) {
            Text("The game is about to kick off.", style = NdTheme.type.body, color = NdTheme.colors.chalk)
        } else {
            // The log's own line, so a score or a turnover carries the same edge here as there.
            PlayLogEntry(downDistance = downAndDistance(play), text = play.text, event = event)
        }
    }
}
