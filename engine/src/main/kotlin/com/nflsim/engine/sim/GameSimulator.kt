package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Team
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.stats.BoxScore
import com.nflsim.engine.stats.StatBuilder
import com.nflsim.engine.stats.TeamStats
import com.nflsim.engine.tuning.TuningTable
import kotlin.math.abs

/** A team ready to play: roster, schemes, and depth charts for each side. */
class GameTeam(
    val team: Team,
    val roster: List<Player>,
    val offScheme: Scheme,
    val defScheme: Scheme,
    /** Head coach fourth-down aggression, 0..1. */
    val aggression: Float = 0.5f,
) {
    val offDepth: DepthChart = DepthChart.auto(roster, offScheme)
    val defDepth: DepthChart = DepthChart.auto(roster, defScheme)
    val id: TeamId get() = team.id
}

data class GameResult(
    val home: TeamId,
    val away: TeamId,
    val homeScore: Int,
    val awayScore: Int,
    val boxScore: BoxScore,
    val drives: List<Drive>,
    val playByPlay: List<PlayLog>,
) {
    val winner: TeamId? get() = when {
        homeScore > awayScore -> home
        awayScore > homeScore -> away
        else -> null
    }
    val isTie: Boolean get() = homeScore == awayScore
    val margin: Int get() = abs(homeScore - awayScore)
    val totalPlays: Int get() = boxScore.home.plays + boxScore.away.plays
}

/**
 * A full game, snap to final whistle.
 *
 * Structure: the outer loop runs drives, the inner loop runs plays until the
 * possession ends. Everything that touches the clock lives here rather than
 * being spread through the resolution functions, because clock bugs produce
 * 45 point games and are miserable to chase once drives depend on them
 * (docs/SPEC.md 5.10).
 */
class GameSimulator(
    private val home: GameTeam,
    private val away: GameTeam,
    private val tuning: TuningTable = TuningTable.REALISTIC,
) {

    private val stats = StatBuilder()
    private val drives = mutableListOf<Drive>()
    private val playByPlay = mutableListOf<PlayLog>()
    private var homeStats = TeamStats()
    private var awayStats = TeamStats()

    fun simulate(rng: Rng): GameResult {
        // Coin toss. The team that defers gets the ball out of the half.
        val awayReceivesFirst = rng.nextBoolean()
        val firstReceiver = if (awayReceivesFirst) Side.AWAY else Side.HOME

        var state = GameState(home.id, away.id, possession = firstReceiver)
        state = openWithKickoff(state, firstReceiver, rng)

        var secondHalfStarted = false

        while (!state.isOver) {
            val before = state
            state = simulateDrive(state, rng)

            // Halftime: the other team gets the ball.
            if (state.quarter == 3 && !secondHalfStarted) {
                secondHalfStarted = true
                val receiver = firstReceiver.other()
                state = openWithKickoff(
                    state.copy(possession = receiver, down = 1, distance = 10), receiver, rng)
            }

            // Safety valve. A drive that consumes nothing means a bug, and an
            // infinite game is worse than a wrong one.
            if (state == before) break
        }

        return GameResult(
            home = home.id, away = away.id,
            homeScore = state.homeScore, awayScore = state.awayScore,
            boxScore = BoxScore(homeStats.copy(points = state.homeScore),
                                awayStats.copy(points = state.awayScore),
                                stats.snapshot()),
            drives = drives.toList(),
            playByPlay = playByPlay.toList(),
        )
    }

    // ---------------------------------------------------------------

    private fun openWithKickoff(state: GameState, receiver: Side, rng: Rng): GameState {
        val receiving = teamFor(receiver)
        val (spot, _) = SpecialTeams.kickoff(
            SpecialTeams.returnerFor(receiving.offDepth, receiving.offScheme),
            receiving.offScheme, rng)
        return state.copy(possession = receiver, yardLine = spot, down = 1, distance = 10)
    }

    private fun simulateDrive(start: GameState, rng: Rng): GameState {
        var state = start
        val offense = state.possession
        val driveStartQuarter = state.quarter
        val driveStartClock = state.secondsLeft
        val driveStartYard = state.yardLine
        var plays = 0
        var yards = 0
        var seconds = 0
        var points = 0
        var ending = DriveEnding.END_OF_HALF
        var reachedRedZone = false

        while (true) {
            if (state.quarter > 4) { ending = DriveEnding.END_OF_GAME; break }

            if (!reachedRedZone && state.yardLine >= 80) {
                reachedRedZone = true
                addTeam(offense) { it.copy(redZoneTrips = it.redZoneTrips + 1) }
            }

            // Fourth down is a decision, not a play.
            if (state.down == 4) {
                val decision = fourthDown(state, offense, rng)
                when (decision) {
                    FourthDownChoice.PUNT -> {
                        val punting = teamFor(offense)
                        val receivingTeam = teamFor(offense.other())
                        val punt = SpecialTeams.punt(
                            SpecialTeams.punterFor(punting.offDepth),
                            SpecialTeams.returnerFor(receivingTeam.offDepth, receivingTeam.offScheme),
                            state.yardLine, punting.offScheme, receivingTeam.offScheme, rng)
                        log(state, punt.narrative)
                        val landing = (state.yardLine + punt.netYards).coerceIn(1, 99)
                        state = advanceClock(state, 12)
                        state = state.copy(
                            possession = offense.other(),
                            yardLine = (100 - landing).coerceIn(1, 99),
                            down = 1, distance = 10)
                        ending = DriveEnding.PUNT
                        break
                    }
                    FourthDownChoice.FIELD_GOAL -> {
                        val kicking = teamFor(offense)
                        val kick = SpecialTeams.fieldGoal(
                            SpecialTeams.kickerFor(kicking.offDepth),
                            100 - state.yardLine, kicking.offScheme,
                            home.team.stadium.altitudeFt, rng,
                            clutch = state.quarter >= 4 && abs(state.scoreDiff) <= 3)
                        log(state, kick.narrative)
                        state = advanceClock(state, 6)
                        if (kick.good) {
                            points += 3
                            state = addPoints(state, offense, 3)
                            ending = DriveEnding.FIELD_GOAL
                            state = openWithKickoff(state, offense.other(), rng)
                        } else {
                            ending = DriveEnding.MISSED_FIELD_GOAL
                            state = state.copy(
                                possession = offense.other(),
                                yardLine = (100 - state.yardLine).coerceIn(1, 99),
                                down = 1, distance = 10)
                        }
                        break
                    }
                    FourthDownChoice.GO_FOR_IT -> {
                        addTeam(offense) { it.copy(fourthDownAttempts = it.fourthDownAttempts + 1) }
                    }
                }
            }

            // ---- run a play ----
            val outcome = runPlay(state, offense, rng)
            val result = outcome.result
            plays++
            state = advanceClock(state, result.clockRunoff)
            seconds += result.clockRunoff

            val applied = applyResult(state, offense, result, outcome.wasPass)
            yards += applied.yardsGained
            state = applied.state

            if (applied.touchdown) {
                points += 6
                state = addPoints(state, offense, 6)
                val kicking = teamFor(offense)
                if (SpecialTeams.extraPoint(SpecialTeams.kickerFor(kicking.offDepth),
                        kicking.offScheme, rng)) {
                    points += 1
                    state = addPoints(state, offense, 1)
                }
                if (reachedRedZone) {
                    addTeam(offense) { it.copy(redZoneTouchdowns = it.redZoneTouchdowns + 1) }
                }
                ending = DriveEnding.TOUCHDOWN
                state = advanceClock(state, 8)
                state = openWithKickoff(state, offense.other(), rng)
                break
            }

            if (applied.turnover) {
                ending = if (result.outcome == PlayOutcome.INTERCEPTION)
                    DriveEnding.INTERCEPTION else DriveEnding.FUMBLE
                break
            }
            if (applied.safety) {
                state = addPoints(state, offense.other(), 2)
                ending = DriveEnding.SAFETY
                state = state.copy(possession = offense.other(), yardLine = 40, down = 1, distance = 10)
                break
            }
            if (applied.turnoverOnDowns) {
                ending = DriveEnding.DOWNS
                break
            }
            if (state.quarter > 4) { ending = DriveEnding.END_OF_GAME; break }
            if (state.quarter == 3 && driveStartQuarter <= 2) { ending = DriveEnding.END_OF_HALF; break }
        }

        drives += Drive(
            offense = offense,
            startQuarter = driveStartQuarter,
            startClock = driveStartClock,
            startYardLine = driveStartYard,
            ending = ending,
            plays = plays, yards = yards, seconds = seconds, points = points,
            endYardLine = state.yardLine,
        )
        addTeam(offense) {
            it.copy(drives = it.drives + 1, possessionSeconds = it.possessionSeconds + seconds)
        }
        return state
    }

    // ---------------------------------------------------------------

    private data class PlayOutcomeBundle(val result: PlayResult, val wasPass: Boolean)

    private fun runPlay(state: GameState, offense: Side, rng: Rng): PlayOutcomeBundle {
        val offTeam = teamFor(offense)
        val defTeam = teamFor(offense.other())
        val playState = state.toPlayState()

        // Build a neutral context to get the calls, then rebuild with the
        // personnel and front those calls actually asked for.
        val probe = PlayContext(
            offense = OffenseUnit.from(offTeam.offDepth, Personnel.P_11, offTeam.offScheme),
            defense = DefenseUnit.from(defTeam.defDepth, DefensiveFront.FOUR_THREE_OVER, defTeam.defScheme),
            state = playState,
            tuning = tuning,
            crowdNoise = if (offense == Side.AWAY) home.team.stadium.crowdNoise else 0,
        )
        val offCall = PlayCaller.offense(probe, rng)
        val defCall = PlayCaller.defense(probe, rng)

        val ctx = probe.copy(
            offense = OffenseUnit.from(offTeam.offDepth, offCall.personnel, offTeam.offScheme),
            defense = DefenseUnit.from(defTeam.defDepth, defCall.front, defTeam.defScheme),
        )
        val result = PlaySimulator.simPlay(ctx, offCall, defCall, rng)
        log(state, result.log.narrative + (result.penalty?.let { " (${it.description})" } ?: ""))
        return PlayOutcomeBundle(result, offCall is OffensivePlayCall.Pass)
    }

    private data class Applied(
        val state: GameState,
        val yardsGained: Int,
        val touchdown: Boolean = false,
        val turnover: Boolean = false,
        val turnoverOnDowns: Boolean = false,
        val safety: Boolean = false,
    )

    private fun applyResult(
        state: GameState,
        offense: Side,
        result: PlayResult,
        wasPass: Boolean,
    ): Applied {
        recordStats(state, offense, result, wasPass)

        val penalty = result.penalty
        if (penalty != null && penalty.type.negatesPlay) {
            val newLine = (state.yardLine + penalty.yards).coerceIn(1, 99)
            val automaticFirst = !penalty.type.onOffense &&
                (penalty.type == PenaltyType.DEFENSIVE_HOLDING ||
                 penalty.type == PenaltyType.PASS_INTERFERENCE)
            val newDown = if (automaticFirst) 1 else state.down
            val newDistance = if (automaticFirst) minOf(10, 100 - newLine)
                              else (state.distance - penalty.yards).coerceIn(1, 99)
            if (automaticFirst) addTeam(offense) { it.copy(firstDowns = it.firstDowns + 1) }
            return Applied(
                state.copy(yardLine = newLine, down = newDown, distance = newDistance),
                yardsGained = 0,
            )
        }

        val gained = result.yards
        val newLine = state.yardLine + gained

        if (newLine >= 100) return Applied(state.copy(yardLine = 99), gained, touchdown = true)
        if (newLine <= 0) return Applied(state.copy(yardLine = 1), gained, safety = true)

        if (result.turnover) {
            return Applied(
                state.copy(
                    possession = offense.other(),
                    yardLine = (100 - newLine).coerceIn(1, 99),
                    down = 1, distance = 10),
                gained, turnover = true,
            )
        }

        val gotFirstDown = gained >= state.distance
        if (gotFirstDown) {
            addTeam(offense) { it.copy(firstDowns = it.firstDowns + 1) }
            if (state.down == 3) addTeam(offense) {
                it.copy(thirdDownConversions = it.thirdDownConversions + 1)
            }
            if (state.down == 4) addTeam(offense) {
                it.copy(fourthDownConversions = it.fourthDownConversions + 1)
            }
            return Applied(
                state.copy(yardLine = newLine, down = 1,
                    distance = minOf(10, 100 - newLine)),
                gained,
            )
        }

        if (state.down >= 4) {
            return Applied(
                state.copy(
                    possession = offense.other(),
                    yardLine = (100 - newLine).coerceIn(1, 99),
                    down = 1, distance = 10),
                gained, turnoverOnDowns = true,
            )
        }

        return Applied(
            state.copy(yardLine = newLine, down = state.down + 1, distance = state.distance - gained),
            gained,
        )
    }

    // ---------------------------------------------------------------

    private fun recordStats(state: GameState, offense: Side, result: PlayResult, wasPass: Boolean) {
        addTeam(offense) { it.copy(plays = it.plays + 1) }
        if (state.down == 3) addTeam(offense) {
            it.copy(thirdDownAttempts = it.thirdDownAttempts + 1)
        }
        result.penalty?.let { p ->
            val side = if (p.type.onOffense) offense else offense.other()
            addTeam(side) {
                it.copy(penalties = it.penalties + 1, penaltyYards = it.penaltyYards + abs(p.yards))
            }
            if (p.type.negatesPlay) return
        }

        val td = state.yardLine + result.yards >= 100

        when (result.outcome) {
            PlayOutcome.RUN, PlayOutcome.SCRAMBLE -> {
                addTeam(offense) {
                    it.copy(rushAttempts = it.rushAttempts + 1,
                        rushYards = it.rushYards + result.yards,
                        totalYards = it.totalYards + result.yards)
                }
                stats.update(result.ballCarrier) {
                    it.copy(carries = it.carries + 1, rushYards = it.rushYards + result.yards,
                        rushTouchdowns = it.rushTouchdowns + if (td) 1 else 0)
                }
            }
            PlayOutcome.COMPLETION -> {
                addTeam(offense) {
                    it.copy(passAttempts = it.passAttempts + 1, completions = it.completions + 1,
                        passYards = it.passYards + result.yards,
                        totalYards = it.totalYards + result.yards)
                }
                stats.update(result.passer) {
                    it.copy(passAttempts = it.passAttempts + 1, completions = it.completions + 1,
                        passYards = it.passYards + result.yards,
                        passTouchdowns = it.passTouchdowns + if (td) 1 else 0)
                }
                stats.update(result.target) {
                    it.copy(targets = it.targets + 1, receptions = it.receptions + 1,
                        receivingYards = it.receivingYards + result.yards,
                        receivingTouchdowns = it.receivingTouchdowns + if (td) 1 else 0)
                }
            }
            PlayOutcome.INCOMPLETE, PlayOutcome.THROWAWAY -> {
                addTeam(offense) { it.copy(passAttempts = it.passAttempts + 1) }
                stats.update(result.passer) { it.copy(passAttempts = it.passAttempts + 1) }
                stats.update(result.target) { it.copy(targets = it.targets + 1) }
            }
            PlayOutcome.INTERCEPTION -> {
                addTeam(offense) {
                    it.copy(passAttempts = it.passAttempts + 1, turnovers = it.turnovers + 1)
                }
                stats.update(result.passer) {
                    it.copy(passAttempts = it.passAttempts + 1,
                        interceptionsThrown = it.interceptionsThrown + 1)
                }
                stats.update(result.tackler) { it.copy(interceptions = it.interceptions + 1) }
            }
            PlayOutcome.SACK -> {
                addTeam(offense) {
                    it.copy(sacksAllowed = it.sacksAllowed + 1,
                        sackYards = it.sackYards + result.yards,
                        totalYards = it.totalYards + result.yards)
                }
                stats.update(result.passer) { it.copy(timesSacked = it.timesSacked + 1) }
                stats.update(result.tackler) { it.copy(sacks = it.sacks + 1) }
            }
            PlayOutcome.FUMBLE_LOST -> {
                addTeam(offense) {
                    it.copy(rushAttempts = it.rushAttempts + 1, turnovers = it.turnovers + 1)
                }
                stats.update(result.ballCarrier) {
                    it.copy(carries = it.carries + 1, fumblesLost = it.fumblesLost + 1)
                }
            }
            else -> {}
        }
        if (result.outcome != PlayOutcome.INCOMPLETE) {
            stats.update(result.tackler) { it.copy(tackles = it.tackles + 1) }
        }
    }

    private fun advanceClock(state: GameState, seconds: Int): GameState {
        var left = state.secondsLeft - seconds
        var quarter = state.quarter
        while (left <= 0 && quarter <= 4) {
            quarter++
            if (quarter <= 4) left += GameState.QUARTER_SECONDS else left = 0
        }
        return state.copy(quarter = quarter, secondsLeft = left.coerceAtLeast(0))
    }

    private fun addPoints(state: GameState, side: Side, points: Int): GameState =
        if (side == Side.HOME) state.copy(homeScore = state.homeScore + points)
        else state.copy(awayScore = state.awayScore + points)

    private fun fourthDown(state: GameState, offense: Side, rng: Rng): FourthDownChoice {
        val t = teamFor(offense)
        return FourthDown.decide(
            state, SpecialTeams.kickerFor(t.offDepth), t.offScheme,
            home.team.stadium.altitudeFt, t.aggression, rng)
    }

    private fun teamFor(side: Side): GameTeam = if (side == Side.HOME) home else away

    private fun addTeam(side: Side, block: (TeamStats) -> TeamStats) {
        if (side == Side.HOME) homeStats = block(homeStats) else awayStats = block(awayStats)
    }

    private fun log(state: GameState, text: String) {
        if (text.isBlank()) return
        playByPlay += PlayLog(
            quarter = state.quarter, clock = state.secondsLeft, offense = state.possession,
            down = state.down, distance = state.distance, yardLine = state.yardLine,
            homeScore = state.homeScore, awayScore = state.awayScore, text = text,
        )
    }
}
