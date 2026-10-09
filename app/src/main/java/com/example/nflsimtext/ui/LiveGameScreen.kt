package com.example.nflsimtext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.example.nflsimtext.ui.components.Chip
import com.example.nflsimtext.ui.components.Direction
import com.example.nflsimtext.ui.components.DriveTracker
import com.example.nflsimtext.ui.components.PlayLogEntry
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.Scoreboard
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.TeamScore
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.Team
import com.nflsim.engine.playbook.Formation
import com.nflsim.engine.playbook.Playbook
import com.nflsim.engine.playbook.Playbooks
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.sim.FourthDownChoice
import com.nflsim.engine.sim.GameState
import com.nflsim.engine.sim.OffensivePlayCall
import com.nflsim.engine.sim.PlayLog
import com.nflsim.engine.sim.Side as EngineSide
import com.example.nflsimtext.ui.components.Side as BoardSide

/**
 * Calling the user's game (SPEC 5.4): the scoreboard and the ball, and at
 * each of his snaps the coordinator's call or one of his own - a formation
 * first, then a play from it. Either side goes back to the coordinator with
 * a tap, and the game with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveGameScreen(dynasty: Dynasty, store: DynastyStore, onDone: () -> Unit) {
    val c = NdTheme.colors
    val game = store.live
    // Once the game in hand has been seen, its end is the way out.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(game) { if (game != null) seen = true else if (seen) onDone() }
    // Back hands the rest to the coordinators rather than walking out on it.
    BackHandler(enabled = game != null) { game?.finish() }
    // The depth chart or the roster, opened from the game: it waits at his
    // snap while he looks, and back returns to it.
    var panel by remember { mutableStateOf<GamePanel?>(null) }
    BackHandler(enabled = panel != null) { panel = null }
    val scope = rememberCoroutineScope()
    if (game == null) {
        Column(Modifier.padding(NdTheme.spacing.xl)) {
            Text("Getting the game ready.", style = NdTheme.type.body, color = c.chalkDim)
        }
        return
    }
    val ask = game.ask
    val snap = ask?.snap ?: game.last
    val state = snap?.state
    // A playoff game says who is in it when it kicks off; a regular-season
    // one is the week's. Before the first playoff kickoff - the rest of the
    // bracket playing ahead of his game - there is nothing to show yet.
    val (homeId, awayId) = game.matchup ?: dynasty.schedule.week(dynasty.week)
        .firstOrNull { it.involves(dynasty.userTeamId) }?.let { it.home to it.away }
        ?: run {
            Column(Modifier.padding(NdTheme.spacing.xl)) {
                Text("The rest of the bracket is playing. Your game is next.", style = NdTheme.type.body, color = c.chalkDim)
            }
            return
        }
    val home = dynasty.league.team(homeId)
    val away = dynasty.league.team(awayId)

    if (ask is LiveGame.Ask.Kickoff) {
        Kickoff(ask, game, dynasty)
        return
    }

    // Who has been hurt in this game, for the depth chart and the roster to show.
    val hurtNow = snap?.plays.orEmpty().flatMap { it.injured }.filter { it.team == dynasty.userTeam }.map { it.player }.toSet()
    when (panel) {
        GamePanel.DEPTH -> {
            DepthChartScreen(dynasty, store, scope, inGame = true, hurtNow = hurtNow) { panel = null }
            return
        }
        GamePanel.ROSTER -> {
            val ours = if (homeId == dynasty.userTeamId) EngineSide.HOME else EngineSide.AWAY
            RosterScreen(
                dynasty, onDepthChart = { panel = GamePanel.DEPTH }, onBackToGame = { panel = null }, hurtNow = hurtNow,
                today = defenderTally(snap?.plays.orEmpty(), ours),
            )
            return
        }
        null -> Unit
    }

    ScreenList {
        item {
            Scoreboard(
                away = TeamScore(away.abbrev, away.name, state?.awayScore ?: 0),
                home = TeamScore(home.abbrev, home.name, state?.homeScore ?: 0),
                quarter = state?.quarter ?: 1,
                clock = state?.let { "%d:%02d".format(it.secondsLeft / 60, it.secondsLeft % 60) } ?: "15:00",
                possession = state?.let { if (it.possession == EngineSide.HOME) BoardSide.HOME else BoardSide.AWAY },
            )
        }
        game.title?.let { title ->
            item { Text(title, style = NdTheme.type.label, color = c.chalkDim) }
        }
        // What just happened, at the top; the log of the game stays at the foot.
        val played = snap?.plays.orEmpty()
        item { LastPlayOf(played, played.size, dynasty.league, home, away) }
        // A man hurt or struggling: move his backup up between snaps.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                SecondaryButton("Depth chart", { panel = GamePanel.DEPTH }, Modifier.weight(1f))
                SecondaryButton("Roster", { panel = GamePanel.ROSTER }, Modifier.weight(1f))
            }
        }
        if (state != null) {
            val offense = if (state.possession == EngineSide.HOME) home else away
            val defense = if (state.possession == EngineSide.HOME) away else home
            // The ball changing hands says so on the field.
            val change = possessionChange(played, state.possession, state.quarter, state.homeScore, state.awayScore)
            item {
                SituationBlock(
                    "${downAndDistance(logOf(state))} at ${spotAt(state.yardLine, defense)}",
                    meta = "${offense.abbrev} ball",
                    situation = when {
                        state.yardLine >= 80 -> Situation.RED_ZONE
                        state.down >= 3 -> Situation.THIRD_DOWN
                        else -> Situation.NORMAL
                    },
                ) {
                    DriveTracker(
                        ballOn = state.yardLine,
                        lineToGain = (state.yardLine + state.distance).coerceAtMost(100),
                        direction = Direction.RIGHT,
                        ballLabel = spotAt(state.yardLine, defense),
                        gainLabel = spotAt((state.yardLine + state.distance).coerceAtMost(100), defense),
                        animate = false,
                        banner = change?.let { possessionBanner(it, offense.abbrev) },
                    )
                }
            }
        }

        item {
            when (ask) {
                null -> SituationBlock("On the field", meta = "Coordinators calling") {
                    Text("The play is running.", style = NdTheme.type.body, color = c.chalkDim)
                }
                is LiveGame.Ask.FourthDown -> FourthDownCall(ask, game)
                is LiveGame.Ask.Offense -> OffenseCall(ask, game, game.offense)
                is LiveGame.Ask.Defense -> DefenseCall(ask, game, game.defense)
                is LiveGame.Ask.Kickoff -> Unit
            }
        }

        item {
            SituationBlock("Who calls it") {
                Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                        Chip("Offense: you", game.callOffense, role = Role.RadioButton) { game.callOffense = true }
                        Chip("Offense: coordinator", !game.callOffense, role = Role.RadioButton) { game.handOff(true) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                        Chip("Defense: you", game.callDefense, role = Role.RadioButton) { game.callDefense = true }
                        Chip("Defense: coordinator", !game.callDefense, role = Role.RadioButton) { game.handOff(false) }
                    }
                    // His own timeouts: armed ahead, taken when the next snap
                    // leaves the clock running (SPEC 5.10).
                    val left = snap?.let { it.state.timeoutsFor(it.side) } ?: 0
                    if (left > 0 || game.timeoutArmed) {
                        Chip(
                            if (game.timeoutArmed) "Timeout after this play: called"
                            else "Timeout after this play ($left left)",
                            game.timeoutArmed,
                            role = Role.Checkbox,
                        ) { game.timeoutArmed = !game.timeoutArmed }
                    }
                    SecondaryButton("Let the coordinators finish the game", { game.finish() }, Modifier.fillMaxWidth())
                }
            }
        }

        val plays = snap?.plays.orEmpty()
        if (plays.isNotEmpty()) {
            item {
                SituationBlock("Play log", meta = if (plays.size == 1) "1 play" else "${plays.size} plays") {
                    (plays.size - 1 downTo maxOf(0, plays.size - 12)).forEach { i ->
                        PlayLogEntry(downDistance = downAndDistance(plays[i]), text = plays[i].text, event = eventOf(plays, i))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FourthDownCall(ask: LiveGame.Ask.FourthDown, game: LiveGame) {
    val c = NdTheme.colors
    val label = mapOf(
        FourthDownChoice.GO_FOR_IT to "Go for it",
        FourthDownChoice.PUNT to "Punt",
        FourthDownChoice.FIELD_GOAL to "Kick the field goal",
    )
    // Before fourth down this is the last-second kick: the clock is nearly
    // out and a field goal ties or wins it. Kick now, or play the down.
    val early = ask.snap.state.down < 4
    val choices = if (early) mapOf(FourthDownChoice.FIELD_GOAL to "Kick the field goal now",
        FourthDownChoice.GO_FOR_IT to "Run a play") else label
    SituationBlock(if (early) "Clock running out" else "Fourth down", meta = "Your call", situation = Situation.RED_ZONE) {
        Text("Coordinator: ${choices.getValue(ask.suggested).lowercase()}.", style = NdTheme.type.body, color = c.chalk)
        Column(Modifier.padding(top = NdTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
            choices.forEach { (choice, text) ->
                if (choice == ask.suggested) PrimaryButton(text, { game.answer(choice) }, Modifier.fillMaxWidth())
                else SecondaryButton(text, { game.answer(choice) }, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Before each of his playoff games: the one just finished, if there was one,
 * and the one about to start - his to call, or the coordinators'.
 */
@Composable
private fun Kickoff(ask: LiveGame.Ask.Kickoff, game: LiveGame, dynasty: Dynasty) {
    val c = NdTheme.colors
    val home = dynasty.league.team(ask.home)
    val away = dynasty.league.team(ask.away)
    ScreenList {
        game.previous?.let { f ->
            item {
                val us = dynasty.userTeamId
                val mine = if (f.home == us) f.homeScore else f.awayScore
                val theirs = if (f.home == us) f.awayScore else f.homeScore
                val them = dynasty.league.team(if (f.home == us) f.away else f.home)
                SituationBlock(f.title, meta = "Final") {
                    Text(
                        "${if (mine > theirs) "Beat" else "Lost to"} ${them.name}, $mine–$theirs.",
                        style = NdTheme.type.body, color = c.chalk,
                    )
                }
            }
        }
        item {
            SituationBlock(ask.title, meta = "Up next", situation = Situation.TWO_MINUTE) {
                Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                    Text("${away.name} at ${home.name}", style = NdTheme.type.title, color = c.chalk)
                    PrimaryButton("Kick off", { game.kickOff(true) }, Modifier.fillMaxWidth())
                    SecondaryButton("Let the coordinators play this one", { game.kickOff(false) }, Modifier.fillMaxWidth())
                    SecondaryButton("Let them play the rest of the postseason", { game.finishPostseason() }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OffenseCall(ask: LiveGame.Ask.Offense, game: LiveGame, book: Playbook) {
    val c = NdTheme.colors
    val named = Playbooks.nameOf(book, ask.suggested)
    // A clock call (kneel, spike) has its own row, not a formation of the book's.
    var formation by remember(ask) { mutableStateOf(named?.first?.takeIf { it != Playbooks.CLOCK } ?: book.formations.first()) }
    val late = ask.snap.state.let { it.quarter >= 4 || (it.quarter == 2 && it.secondsLeft <= 120) }
    SituationBlock("Your call", meta = "Offense") {
        Text("Coordinator: " + (named?.let { "${it.first.name} - ${it.second.name}" } ?: "his own call"),
            style = NdTheme.type.body, color = c.chalk)
        PrimaryButton("Run the coordinator's call", { game.takeSuggestion() },
            Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
        Text("Or call it yourself: a formation, then a play.", style = NdTheme.type.caption, color = c.chalkDim,
            modifier = Modifier.padding(top = NdTheme.spacing.m))
        FormationChips(book.formations, formation) { formation = it }
        PlayChips("Runs", formation.plays.filter { it.isRun }.map { it.name }) { n ->
            game.answer(Playbooks.offense(formation, formation.plays.first { it.name == n }))
        }
        PlayChips("Passes", formation.plays.filter { !it.isRun }.map { it.name }) { n ->
            game.answer(Playbooks.offense(formation, formation.plays.first { it.name == n }))
        }
        if (late) {
            PlayChips("Clock", listOf("Kneel", "Spike")) { n ->
                game.answer(if (n == "Kneel") OffensivePlayCall.Kneel() else OffensivePlayCall.Spike())
            }
        }
    }
}

@Composable
private fun DefenseCall(ask: LiveGame.Ask.Defense, game: LiveGame, book: Playbook) {
    val c = NdTheme.colors
    val named = Playbooks.nameOf(book, ask.suggested)
    var formation by remember(ask) { mutableStateOf(named?.first ?: book.formations.first()) }
    SituationBlock("Your call", meta = "Defense") {
        Text("Coordinator: " + (named?.let { "${it.first.name} - ${it.second.name}" } ?: "his own call"),
            style = NdTheme.type.body, color = c.chalk)
        PrimaryButton("Play the coordinator's call", { game.takeSuggestion() },
            Modifier.fillMaxWidth().padding(top = NdTheme.spacing.s))
        Text("Or call it yourself: a front, then a coverage or pressure.", style = NdTheme.type.caption,
            color = c.chalkDim, modifier = Modifier.padding(top = NdTheme.spacing.m))
        FormationChips(book.formations, formation) { formation = it }
        PlayChips("Coverages", formation.plays.filter { it.rushers == 0 }.map { it.name }) { n ->
            game.answer(Playbooks.defense(formation, formation.plays.first { it.name == n }))
        }
        PlayChips("Pressure", formation.plays.filter { it.rushers > 0 }.map { it.name }) { n ->
            game.answer(Playbooks.defense(formation, formation.plays.first { it.name == n }))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormationChips(formations: List<Formation>, chosen: Formation, onPick: (Formation) -> Unit) {
    FlowRow(
        Modifier.padding(top = NdTheme.spacing.s),
        horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
    ) {
        formations.forEach { f -> Chip(f.name, f == chosen, role = Role.RadioButton) { onPick(f) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayChips(title: String, names: List<String>, onPick: (String) -> Unit) {
    if (names.isEmpty()) return
    Text(title.uppercase(), style = NdTheme.type.label, color = NdTheme.colors.chalkDim,
        modifier = Modifier.padding(top = NdTheme.spacing.m, bottom = NdTheme.spacing.xs))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
    ) {
        names.forEach { n -> Chip(n, false, role = Role.Button) { onPick(n) } }
    }
}

/** A game state as a log line, for the down-and-distance reader. */
private fun logOf(s: GameState) = PlayLog(s.quarter, s.secondsLeft, s.possession, s.down, s.distance, s.yardLine,
    s.homeScore, s.awayScore, "")

private fun spotAt(yardLine: Int, defense: Team): String = when {
    yardLine >= 100 -> "the goal line"
    yardLine > 50 -> "${defense.abbrev} ${100 - yardLine}"
    yardLine == 50 -> "midfield"
    else -> "own $yardLine"
}

/** What the game screen has open over the game. */
private enum class GamePanel { DEPTH, ROSTER }
