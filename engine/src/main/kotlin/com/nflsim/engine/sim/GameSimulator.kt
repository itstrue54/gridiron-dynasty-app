package com.nflsim.engine.sim

import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Position
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
    /** The staff's tendencies (gen.Tendencies.of). The club's own game plan goes over them. */
    val staffPlan: com.nflsim.engine.model.GamePlan = com.nflsim.engine.model.GamePlan(),
) {
    val offDepth: DepthChart = DepthChart.auto(roster, offScheme, team.depthPins)
    val defDepth: DepthChart = DepthChart.auto(roster, defScheme, team.depthPins)
    val id: TeamId get() = team.id

    /** What this club calls from: its game plan, then its staff's tendencies, then its schemes. */
    val plan: com.nflsim.engine.model.GamePlan = team.gamePlan.over(staffPlan)
}

/** An injury that costs games: who, his club, how many, and the quarter it came in. */
@kotlinx.serialization.Serializable
data class Injury(val player: Int, val team: Int, val gamesOut: Int, val quarter: Int) {
    companion object {
        /** More games than a season has left: out until the offseason heals him. */
        const val SEASON_ENDING = 30
    }
}

@kotlinx.serialization.Serializable
data class GameResult(
    val home: TeamId,
    val away: TeamId,
    val homeScore: Int,
    val awayScore: Int,
    val boxScore: BoxScore,
    val drives: List<Drive>,
    val playByPlay: List<PlayLog>,
    /** Injuries that cost games, and every player's scrimmage snaps - for the week that follows. */
    val injuries: List<Injury> = emptyList(),
    val snaps: Map<Int, Int> = emptyMap(),
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
    /** Who calls [callerSide]'s snaps; null leaves both clubs to their coordinators. */
    private val caller: SnapCaller? = null,
    private val callerSide: Side? = null,
    /**
     * The overtime a game level after four quarters goes to (SPEC 5.10): the
     * regular season's, or the playoffs'. Null, a tie after regulation stands.
     */
    private val overtime: Overtime? = null,
) {

    private val stats = StatBuilder()
    private val drives = mutableListOf<Drive>()
    private val playByPlay = mutableListOf<PlayLog>()
    private var homeStats = TeamStats()
    private var awayStats = TeamStats()

    // ---- fatigue and rotation (SPEC 5.5), this game only ----
    private val fatigue = mutableMapOf<Int, Float>()
    private val resting = mutableSetOf<Int>()
    /** Quarterbacks and linemen play every snap, as they do in the NFL. */
    private val rotates = setOf(
        Position.RB, Position.FB, Position.WR, Position.TE,
        Position.EDGE, Position.DT, Position.LB, Position.CB, Position.S,
    )
    /** Scrimmage snaps each player was on the field for, and each club's snaps on each side. */
    val snaps = mutableMapOf<Int, Int>()
    val offenseSnaps = mutableMapOf<TeamId, Int>()
    val defenseSnaps = mutableMapOf<TeamId, Int>()

    // ---- injuries (SPEC 5.5): once hurt, out for the rest of this game ----
    private val out = mutableSetOf<Int>()
    val injuries = mutableListOf<Injury>()
    private var injuryRng: Rng = com.nflsim.engine.rng.SplitMixRng(0L)
    /** How the plays are worded (SPEC 10.4): its own stream, so words never move a snap. */
    private var words: Rng = com.nflsim.engine.rng.SplitMixRng(0L)

    fun simulate(rng: Rng): GameResult {
        // Its own stream, so a game nobody is hurt in plays exactly as before.
        injuryRng = rng.split("injuries")
        words = rng.split("narration")
        // Coin toss. The team that defers gets the ball out of the half.
        val awayReceivesFirst = rng.nextBoolean()
        val firstReceiver = if (awayReceivesFirst) Side.AWAY else Side.HOME

        var state = GameState(home.id, away.id, possession = firstReceiver)
        state = openWithKickoff(state, firstReceiver, rng)

        var secondHalfStarted = false

        while (!state.isOver) {
            val before = state
            state = simulateDrive(state, rng)
            recover(tuning.fatigue.driveRecovery)

            // Halftime: the other team gets the ball.
            if (state.quarter == 3 && !secondHalfStarted) {
                secondHalfStarted = true
                recover(tuning.fatigue.halftimeRecovery)
                val receiver = firstReceiver.other()
                state = openWithKickoff(
                    // Three timeouts a side each half: the rules.
                    state.copy(possession = receiver, down = 1, distance = 10,
                        homeTimeouts = HALF_TIMEOUTS, awayTimeouts = HALF_TIMEOUTS), receiver, rng)
            }

            // Safety valve. A drive that consumes nothing means a bug, and an
            // infinite game is worse than a wrong one.
            if (state == before) break
        }

        if (overtime != null) state = playOvertime(state, overtime, rng)

        return GameResult(
            home = home.id, away = away.id,
            homeScore = state.homeScore, awayScore = state.awayScore,
            boxScore = BoxScore(homeStats.copy(points = state.homeScore),
                                awayStats.copy(points = state.awayScore),
                                stats.snapshot()),
            drives = drives.toList(),
            playByPlay = playByPlay.toList(),
            injuries = injuries.toList(),
            snaps = snaps.toMap(),
        )
    }

    /**
     * Overtime under [rules] (SPEC 5.10): each period with a toss, a kickoff
     * and two timeouts a side. Both clubs get the ball once; after that the
     * next score wins. A period that runs out with the game decided - one
     * club ahead, the other's answer cut short by the clock - ends it; one
     * that runs out level brings another, up to the rules' limit, and past
     * that the game ends level.
     */
    private fun playOvertime(regulation: GameState, rules: Overtime, rng: Rng): GameState {
        var state = regulation
        val hadTheBall = mutableSetOf<Side>()
        while (state.isOver && state.homeScore == state.awayScore && state.periods < 4 + rules.maxPeriods) {
            val period = state.periods + 1
            val receiver = if (rng.nextBoolean()) Side.AWAY else Side.HOME
            state = state.copy(periods = period, quarter = period, secondsLeft = rules.periodSeconds,
                homeTimeouts = OVERTIME_TIMEOUTS, awayTimeouts = OVERTIME_TIMEOUTS)
            state = openWithKickoff(state, receiver, rng)
            while (!state.isOver) {
                val before = state
                val offense = state.possession
                otherHadTheBall = offense.other() in hadTheBall
                state = simulateDrive(state, rng)
                recover(tuning.fatigue.driveRecovery)
                hadTheBall += offense
                if (hadTheBall.size == 2 && state.homeScore != state.awayScore) {
                    // Decided: the whistle goes now, whatever is left on the clock.
                    state = state.copy(quarter = period + 1, secondsLeft = 0)
                    break
                }
                if (state == before) break
            }
        }
        otherHadTheBall = false
        return state
    }

    /**
     * Overtime, and the other club has had its possession: a score that puts
     * this one ahead ends the game, so a touchdown that does has no try after
     * it. That includes the second club's first possession when the first
     * one came away with nothing. A touchdown that only draws level, or
     * leaves it short, still has its try.
     */
    private var otherHadTheBall = false

    // ---------------------------------------------------------------

    private fun openWithKickoff(state: GameState, receiver: Side, rng: Rng): GameState {
        val receiving = teamFor(receiver)
        val (spot, _) = SpecialTeams.kickoff(
            SpecialTeams.returnerFor(receiving.offDepth, receiving.offScheme),
            receiving.offScheme, rng, st = tuning.specialTeams)
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
            if (state.quarter > state.periods) { ending = DriveEnding.END_OF_GAME; break }

            if (!reachedRedZone && state.yardLine >= 80) {
                reachedRedZone = true
                addTeam(offense) { it.copy(redZoneTrips = it.redZoneTrips + 1) }
            }

            // Fourth down is a decision, not a play - and so is any down with
            // the clock out and a kick that ties or wins (FourthDown.lastKick).
            val kickNow = state.down < 4 && lastKick(state, offense)
            if (state.down == 4 || kickNow) {
                val decision = if (kickNow) lastKickCall(state, offense) else fourthDown(state, offense, rng)
                when (decision) {
                    FourthDownChoice.PUNT -> {
                        val punting = teamFor(offense)
                        val receivingTeam = teamFor(offense.other())
                        val punt = SpecialTeams.punt(
                            SpecialTeams.punterFor(punting.offDepth),
                            SpecialTeams.returnerFor(receivingTeam.offDepth, receivingTeam.offScheme, punt = true),
                            state.yardLine, punting.offScheme, receivingTeam.offScheme, rng, st = tuning.specialTeams,
                            narration = words)
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
                            clutch = state.quarter >= 4 && abs(state.scoreDiff) <= 3, st = tuning.specialTeams,
                            narration = words)
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
                        if (state.down == 4) addTeam(offense) { it.copy(fourthDownAttempts = it.fourthDownAttempts + 1) }
                    }
                }
            }

            // ---- run a play ----
            val outcome = runPlay(state, offense, rng)
            val result = outcome.result
            plays++
            // His timeouts are his to call, when he is calling the game: the
            // coordinators' call is the suggestion (SnapCaller.timeout).
            val choice = if (caller != null && callerSide != null &&
                ClockManagement.canStop(state, callerSide, offense, result, tuning.gameFlow)
            ) {
                val suggested = ClockManagement.after(state, offense, result, tuning.gameFlow).timeout == callerSide
                callerSide to caller.timeout(Snap(state, callerSide, playByPlay.toList()), suggested)
            } else null
            val clock = ClockManagement.after(state, offense, result, tuning.gameFlow, choice)
            state = advanceClock(state, clock.runoff)
            seconds += clock.runoff
            clock.timeout?.let { side ->
                state = if (side == Side.HOME) state.copy(homeTimeouts = state.homeTimeouts - 1)
                    else state.copy(awayTimeouts = state.awayTimeouts - 1)
                log(state, PlayLines.write("timeout", words, "team" to teamFor(side).team.name,
                    "left" to timeoutsLeft(state.timeoutsFor(side))))
            }

            val applied = applyResult(state, offense, result, outcome.wasPass)
            yards += applied.yardsGained
            state = applied.state

            if (applied.touchdown) {
                points += 6
                state = addPoints(state, offense, 6)
                val kicking = teamFor(offense)
                val walkOff = otherHadTheBall && state.scoreFor(offense) > state.scoreFor(offense.other())
                if (!walkOff && SpecialTeams.extraPoint(SpecialTeams.kickerFor(kicking.offDepth),
                        kicking.offScheme, rng, st = tuning.specialTeams)) {
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
            if (state.quarter > state.periods) { ending = DriveEnding.END_OF_GAME; break }
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
        val offDepth = offTeam.offDepth.rested(resting, fatigue, out)
        val defDepth = defTeam.defDepth.rested(resting, fatigue, out)

        // Build a neutral context to get the calls, then rebuild with the
        // personnel and front those calls actually asked for.
        val probe = PlayContext(
            offense = OffenseUnit.from(offDepth, Personnel.P_11, offTeam.offScheme),
            defense = DefenseUnit.from(defDepth, DefensiveFront.FOUR_THREE_OVER, defTeam.defScheme),
            state = playState,
            tuning = tuning,
            crowdNoise = if (offense == Side.AWAY) home.team.stadium.crowdNoise else 0,
            offPlan = offTeam.plan,
            defPlan = defTeam.plan,
            carries = stats::carries,
            narration = words,
        )
        // The coordinators call both sides first, from the game's stream, so
        // the suggestion a caller sees is exactly what would have been played.
        val suggestedOff = PlayCaller.offense(probe, rng)
        val suggestedDef = PlayCaller.defense(probe, rng)
        val snap = if (caller != null && callerSide != null) Snap(state, callerSide, playByPlay.toList()) else null
        val offCall = if (snap != null && offense == callerSide) caller!!.offense(snap, suggestedOff) else suggestedOff
        val defCall = if (snap != null && offense != callerSide) caller!!.defense(snap, suggestedDef) else suggestedDef

        val ctx = probe.copy(
            offense = OffenseUnit.from(offDepth, offCall.personnel, offTeam.offScheme),
            defense = DefenseUnit.from(defDepth, defCall.front, defTeam.defScheme),
        )
        val result = PlaySimulator.simPlay(ctx, offCall, defCall, rng)
        snap(ctx, offTeam, defTeam, state.quarter)
        log(state, result.log.narrative + (result.penalty?.let { " (${it.description})" } ?: ""))
        return PlayOutcomeBundle(result, offCall is OffensivePlayCall.Pass)
    }

    /** Everyone on the field tires, everyone else rests, and the tired come out (SPEC 5.5). */
    private fun snap(ctx: PlayContext, offTeam: GameTeam, defTeam: GameTeam, quarter: Int) {
        val f = tuning.fatigue
        val onField = (ctx.offense.onField + ctx.defense.frontSeven + ctx.defense.secondary).map { it.id.v }.toSet()
        offenseSnaps[offTeam.id] = (offenseSnaps[offTeam.id] ?: 0) + 1
        defenseSnaps[defTeam.id] = (defenseSnaps[defTeam.id] ?: 0) + 1
        for (p in offTeam.roster + defTeam.roster) {
            val id = p.id.v
            val now = fatigue[id] ?: 0f
            val next = if (id in onField) {
                snaps[id] = (snaps[id] ?: 0) + 1
                (now + f.perSnap(p.position) * (1.5f - p.ratings[RatingId.STAMINA] / 100f)).coerceIn(0f, 100f)
            } else {
                (now - f.sidelineRecovery).coerceAtLeast(0f)
            }
            fatigue[id] = next
            // Wear and tear: a snap on the field can cost games, more so the
            // more tired and worn the player is.
            if (id in onField && id !in out && injuryRng.nextFloat() < injuryRisk(p, next)) {
                out += id
                resting -= id
                injuries += Injury(id, p.teamId?.v ?: 0, gamesOut(), quarter)
            }
            if (p.position in rotates) {
                if (next >= f.subOutAt && id !in resting && freshBackupAsGood(p, next, offTeam, defTeam)) resting += id
                else if (next <= f.backInAt) resting -= id
            }
        }
    }

    /** A snap's chance of an injury that costs games, for this player at this fatigue. */
    private fun injuryRisk(p: com.nflsim.engine.model.Player, fatigue: Float): Float {
        val i = tuning.injuries
        val proneness = 0.6f + 0.8f * p.traits.injuryProneness / 100f
        val resistance = 1.3f - 0.6f * p.ratings[RatingId.INJURY_RESIST] / 100f
        return i.perSnap(p.position) * i.scale * (1f + i.fatigueRisk * fatigue / 100f) *
            (1f + i.wearRisk * p.wear / 100f) * proneness * resistance *
            (1f + i.loadRisk * ((snaps[p.id.v] ?: 0) / 60f - 0.5f)).coerceAtLeast(0.2f)
    }

    /** Games an injury costs, on the NFL's spread. */
    private fun gamesOut(): Int {
        val i = tuning.injuries
        val u = injuryRng.nextFloat()
        return when {
            u < i.oneGame -> 1
            u < i.oneGame + i.twoGames -> 2
            u < i.oneGame + i.twoGames + i.threeFour -> 3 + injuryRng.nextInt(2)
            u < i.oneGame + i.twoGames + i.threeFour + i.fiveEight -> 5 + injuryRng.nextInt(4)
            else -> Injury.SEASON_ENDING
        }
    }

    /**
     * A coach spells a tired player only for a teammate who, fresh, plays at
     * least as well as he does tired: the next man up at his position, not
     * resting or hurt, by scheme-adjusted overall against the tired player's
     * overall less the fatigue penalty. Good depth rotates; weak depth leaves
     * the starter out there.
     */
    private fun freshBackupAsGood(p: com.nflsim.engine.model.Player, fatigue: Float, offTeam: GameTeam, defTeam: GameTeam): Boolean {
        val team = if (p.teamId == offTeam.id) offTeam else defTeam
        val offense = p.position.isOffense
        val chart = if (offense) team.offDepth else team.defDepth
        val scheme = if (offense) team.offScheme else team.defScheme
        val list = chart.at(p.position)
        val after = list.indexOfFirst { it.id == p.id }
        val next = list.drop(after + 1).firstOrNull { it.id != p.id && it.id.v !in resting && it.id.v !in out }
            ?: return false
        val tired = com.nflsim.engine.ratings.overall(p, scheme) * (1f - scheme.ratings.maxFatiguePenalty * fatigue / 100f)
        return com.nflsim.engine.ratings.overall(next, scheme) >= tired
    }

    /** The break between drives, or halftime. */
    private fun recover(amount: Float) {
        for (id in fatigue.keys.toList()) {
            val next = (fatigue.getValue(id) - amount).coerceAtLeast(0f)
            fatigue[id] = next
            if (next <= tuning.fatigue.backInAt) resting -= id
        }
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
        // The flag first. A snap wiped out by penalty is not an offensive play
        // by NFL convention and must not be counted as one - counting it made
        // plays per game read six higher than the offence actually ran, which
        // in turn made the clock look correctly tuned when it was not.
        result.penalty?.let { p ->
            val side = if (p.type.onOffense) offense else offense.other()
            addTeam(side) {
                it.copy(penalties = it.penalties + 1, penaltyYards = it.penaltyYards + abs(p.yards))
            }
            if (p.type.negatesPlay) return
        }

        addTeam(offense) { it.copy(plays = it.plays + 1) }
        if (state.down == 3) addTeam(offense) {
            it.copy(thirdDownAttempts = it.thirdDownAttempts + 1)
        }

        val td = state.yardLine + result.yards >= 100

        when (result.outcome) {
            PlayOutcome.RUN, PlayOutcome.SCRAMBLE -> {
                addTeam(offense) {
                    it.copy(rushAttempts = it.rushAttempts + 1,
                        rushYards = it.rushYards + result.yards,
                        totalYards = it.totalYards + result.yards,
                        rushesForLoss = it.rushesForLoss + if (result.yards < 0) 1 else 0,
                        rushesOfTwentyPlus = it.rushesOfTwentyPlus + if (result.yards >= 20) 1 else 0)
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
                    it.copy(passAttempts = it.passAttempts + 1, turnovers = it.turnovers + 1,
                        passInterceptions = it.passInterceptions + 1)
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
                    it.copy(rushAttempts = it.rushAttempts + 1, turnovers = it.turnovers + 1,
                        fumblesLost = it.fumblesLost + 1)
                }
                stats.update(result.ballCarrier) {
                    it.copy(carries = it.carries + 1, fumblesLost = it.fumblesLost + 1)
                }
            }
            else -> {}
        }
        if (result.outcome != PlayOutcome.INCOMPLETE) {
            stats.update(result.tackler) { it.copy(tackles = it.tackles + 1) }
            stats.update(result.assister) { it.copy(assists = it.assists + 1) }
        }
    }

    private fun advanceClock(state: GameState, seconds: Int): GameState {
        var left = state.secondsLeft - seconds
        var quarter = state.quarter
        while (left <= 0 && quarter <= state.periods) {
            quarter++
            if (quarter <= state.periods) left += GameState.QUARTER_SECONDS else left = 0
        }
        return state.copy(quarter = quarter, secondsLeft = left.coerceAtLeast(0))
    }

    private fun addPoints(state: GameState, side: Side, points: Int): GameState =
        if (side == Side.HOME) state.copy(homeScore = state.homeScore + points)
        else state.copy(awayScore = state.awayScore + points)

    private fun fourthDown(state: GameState, offense: Side, rng: Rng): FourthDownChoice {
        val t = teamFor(offense)
        val suggested = FourthDown.decide(
            state, SpecialTeams.kickerFor(t.offDepth), t.offScheme,
            home.team.stadium.altitudeFt, t.plan.fourthDownAggression ?: t.aggression, rng, tuning.fourthDown)
        return if (caller != null && offense == callerSide) {
            caller.fourthDown(Snap(state, offense, playByPlay.toList()), suggested)
        } else suggested
    }

    private fun lastKick(state: GameState, offense: Side): Boolean {
        val t = teamFor(offense)
        return FourthDown.lastKick(state, SpecialTeams.kickerFor(t.offDepth), t.offScheme,
            home.team.stadium.altitudeFt, tuning.gameFlow.runPlayClockRunoff, tuning.fourthDown)
    }

    /**
     * The coordinators kick; a user calling his offense is asked, as on
     * fourth down. Before fourth down a punt is not on the table: anything
     * but the kick plays the down.
     */
    private fun lastKickCall(state: GameState, offense: Side): FourthDownChoice {
        if (caller == null || offense != callerSide) return FourthDownChoice.FIELD_GOAL
        return when (caller.fourthDown(Snap(state, offense, playByPlay.toList()), FourthDownChoice.FIELD_GOAL)) {
            FourthDownChoice.FIELD_GOAL -> FourthDownChoice.FIELD_GOAL
            else -> FourthDownChoice.GO_FOR_IT
        }
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

    companion object {
        /** The rules, not a tuning: two timeouts a side in each overtime period, three each half. */
        const val OVERTIME_TIMEOUTS = 2
        const val HALF_TIMEOUTS = 3

        private fun timeoutsLeft(n: Int): String = when (n) {
            0 -> "none left"
            1 -> "one left"
            else -> "$n left"
        }
    }
}
