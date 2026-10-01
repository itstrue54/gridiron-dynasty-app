package com.nflsim.engine.tuning

import kotlinx.serialization.Serializable

/**
 * Every coefficient the simulation uses, in one place.
 *
 * Rule from docs/SPEC.md section 12: no magic numbers inside sim functions. If
 * a play-resolution function needs a constant, it gets a named field here.
 *
 * This is saved with the league, so a dynasty keeps the rules it started under,
 * and it is editable in game behind an Advanced toggle. It is also the thing
 * the M4 calibration pass turns the dials on.
 */
@Serializable
data class TuningTable(
    val passing: Passing = Passing(),
    val rushing: Rushing = Rushing(),
    val blocking: Blocking = Blocking(),
    val coverage: Coverage = Coverage(),
    val penalties: Penalties = Penalties(),
    val tackling: Tackling = Tackling(),
    val injuries: Injuries = Injuries(),
    val gameFlow: GameFlow = GameFlow(),
    val specialTeams: SpecialTeams = SpecialTeams(),
    val progression: Progression = Progression(),
    val ai: Ai = Ai(),
    val form: Form = Form(),
    val fatigue: Fatigue = Fatigue(),
    val ratings: Ratings = Ratings(),
    val calling: Calling = Calling(),
    val fourthDown: FourthDown = FourthDown(),
    val weather: Weather = Weather(),
    val adaptation: Adaptation = Adaptation(),
    val trades: Trades = Trades(),
    val intent: Intent = Intent(),
) {
    @Serializable
    data class Passing(
        /** Bigger = protection differences matter less. */
        val pressureScale: Float = 30f,
        /** Share of pressures that become sacks before the QB's escape rating. */
        val sackGivenPressure: Float = 0.21f,
        /** Bigger = accuracy and coverage differences matter less. */
        val completionScale: Float = 88f,
        /**
         * Completion probability at a dead-even matchup, before depth, in fair
         * weather: 0.82 before weather (SPEC 5.10) took its share.
         */
        val baseCompletion: Float = 0.83f,
        /** Completion penalty per yard of intended air distance. */
        val depthPenaltyPerYard: Float = 0.0138f,
        /** Multiplier on completion when the quarterback is pressured. */
        val pressureCompletionMult: Float = 0.62f,
        val interceptionBase: Float = 0.021f,
        /** How much a badly-lost route matchup raises interception odds. */
        val interceptionCoverageScale: Float = 0.042f,
        val yacScale: Float = 0.35f,
        val throwawayRate: Float = 0.34f,
        /** Pass rush: the better of power and finesse counts this much, the other the rest. */
        val rushBlendStrong: Float = 0.65f,
        val rushBlendWeak: Float = 0.35f,
        /** Protection a play-action fake buys. */
        val playActionProtection: Float = 4.5f,
        /** A pressured quarterback is sacked in proportion to sackEscapeBase - escape / sackEscapeScale. */
        val sackEscapeBase: Float = 1.35f,
        val sackEscapeScale: Float = 145f,
        /** Yards lost on a sack: a base plus an exponential tail. */
        val sackLossBase: Float = 3f,
        val sackLossMean: Float = 4.0f,
        /** How often a quarterback who wants to run does, and what he gains. */
        val scrambleRate: Float = 0.35f,
        val scrambleMean: Float = 4.5f,
        val scrambleSpread: Float = 4f,
        val scrambleSpeed: Float = 0.09f,
        /**
         * Where the ball goes, by slot - WR1, WR2, WR3, tight ends, backs -
         * for a quick throw, an intermediate one and a deep one. A back
         * catches flats and checkdowns; nobody throws a go route to a
         * fullback (SPEC 12: the play caller holds no numbers of its own).
         */
        val targetsQuick: List<Float> = listOf(0.21f, 0.15f, 0.10f, 0.24f, 0.30f),
        val targetsMiddle: List<Float> = listOf(0.29f, 0.23f, 0.16f, 0.23f, 0.09f),
        val targetsDeep: List<Float> = listOf(0.44f, 0.29f, 0.13f, 0.13f, 0.01f),
        /** Completion: what quarterback accuracy and receiver separation count for. */
        val accuracyWeight: Float = 0.35f,
        val separationWeight: Float = 0.45f,
        /** Under pressure a completion keeps poiseBase + throw-under-pressure / poiseScale of itself. */
        val poiseBase: Float = 0.85f,
        val poiseScale: Float = 330f,
        /** Interceptions per point of awareness below 70, and the multiplier under pressure. */
        val interceptionAwareness: Float = 0.00042f,
        val interceptionPressure: Float = 1.7f,
        val airYardsSpread: Float = 2.2f,
        /** Yards after catch: the exponential mean, per point over the tackler, and extras. */
        val yacMean: Float = 2.6f,
        val yacTackling: Float = 0.055f,
        val screenYac: Float = 4.2f,
        val zoneYac: Float = 0.8f,
    )

    @Serializable
    data class Rushing(
        /** Yards on a perfectly neutral carry before any roll. */
        val baseYards: Float = 3.63f,
        /** Yards added per unit of blocking advantage. */
        val advantageYards: Float = 2.00f,
        /** Spread of the ordinary run-to-run roll. */
        val variance: Float = 4.00f,
        /** Chance a carry breaks into the second level at neutral advantage. */
        val breakawayBase: Float = 0.042f,
        val breakawayAdvantageScale: Float = 0.030f,
        /** Mean extra yards once a run breaks. Long tail lives here. */
        val breakawayYards: Float = 11.0f,
        /** A carry's fumble chance before ball security and the hit, in the dry: 0.0125 before weather. */
        val fumbleBase: Float = 0.0115f,
        val tackleForLossFloor: Float = -6f,
        /** Blocking advantage lost per yard inside the twenty. */
        val redZoneCompression: Float = 0.32f,
        /** Yards per point of vision over 70, and per point of break-tackle over the tackler. */
        val visionScale: Float = 0.027f,
        val breakTackleScale: Float = 0.027f,
        /** Breakaway chance per point of elusiveness over 70, and how speed stretches the run. */
        val breakawayElusiveness: Float = 0.0009f,
        val breakawaySpeedBase: Float = 0.75f,
        val breakawaySpeedRange: Float = 0.5f,
        /** Fumbles: ball security, and how hard the hit is. */
        val fumbleSecurityBase: Float = 1.6f,
        val fumbleHitBase: Float = 0.7f,
        val fumbleHitScale: Float = 140f,
        /** Backfield rotation, cumulative: the lead back's share of carries, then the top two's. */
        val rbRotationLead: Float = 0.64f,
        val rbRotationTopTwo: Float = 0.90f,
        /** Carries in a game after which the lead back's handoffs go to the next back. */
        val leadBackCarryCap: Int = 23,
    )

    /**
     * Who makes the tackle on a run (SPEC 12: the play resolution holds no
     * numbers of its own). A run stopped at the line is the front seven's; one
     * that gets past them belongs to the secondary. Crediting it uniformly
     * across the eleven had corners leading the league in tackles ahead of
     * linebackers, which is backwards.
     */
    @Serializable
    data class Tackling(
        /** Yards past which a run has cleared the front seven. */
        val pastTheFront: Int = 7,
        val frontNear: Float = 1.0f,
        val frontPast: Float = 0.35f,
        val linebackerNear: Float = 1.7f,
        val linebackerPast: Float = 1.1f,
        val safetyNear: Float = 0.7f,
        val safetyPast: Float = 1.7f,
        val cornerNear: Float = 0.25f,
        val cornerPast: Float = 0.7f,
        /**
         * Share of tackles after a catch made by the man in coverage. The rest
         * are pursuit: crediting every completion to the defender who covered
         * it left corners near the league's tackle lead, where they should sit
         * well behind the linebackers.
         */
        val coverageShare: Float = 0.45f,
        /**
         * Share of tackles with a second man in, who is credited an assist.
         * The NFL counts those as combined tackles, which is why its leaders
         * read near 170 where one credit a play reads near 140.
         */
        val assistShare: Float = 0.25f,
    ) {
        fun weight(position: com.nflsim.engine.model.Position, yards: Int): Float {
            val past = yards >= pastTheFront
            return when (position) {
                com.nflsim.engine.model.Position.EDGE,
                com.nflsim.engine.model.Position.DT -> if (past) frontPast else frontNear
                com.nflsim.engine.model.Position.LB -> if (past) linebackerPast else linebackerNear
                com.nflsim.engine.model.Position.S -> if (past) safetyPast else safetyNear
                com.nflsim.engine.model.Position.CB -> if (past) cornerPast else cornerNear
                else -> 0.2f
            }
        }
    }

    @Serializable
    data class Blocking(
        /** Points of protection per blocker beyond the rushers sent. */
        val extraBlockerValue: Float = 11f,
        /** Points of run advantage lost per extra defender in the box. */
        val boxCountPenalty: Float = 6.5f,
        /** Scales the offensive-concept vs defensive-front lookup. */
        val gapSchemeScale: Float = 1.0f,
        /**
         * Protection lost by a road offence at maximum crowd noise.
         *
         * Home field is not a mystical bonus - it is linemen who cannot hear
         * the cadence firing a beat late, and a silent count that telegraphs
         * the snap. Modelled where it actually happens rather than as a thumb
         * on the scoreboard.
         */
        val crowdNoiseProtectionCost: Float = 7.0f,
        /** Run blocking advantage lost by a road offence at maximum noise. */
        val crowdNoiseRunCost: Float = 4.0f,
        /** Run blocking help per tight end, and per point of a lead blocker over the baseline. */
        val tightEndHelp: Float = 0.18f,
        val leadBlockScale: Float = 0.12f,
        /** The defensive front: block shedding counts this much, strength the rest. */
        val shedWeight: Float = 0.62f,
        val powerWeight: Float = 0.38f,
    )

    @Serializable
    data class Coverage(
        /** Separation a receiver gets for free against zone. */
        val zoneCushion: Float = 4.5f,
        /** Rating points a double team takes off a receiver. */
        val doubleTeamPenalty: Float = 14f,
        /** Coverage help a blitz gives up per extra rusher. */
        val blitzCoverageCost: Float = 7.5f,
        /**
         * Separation lost per yard inside the twenty. There is no grass behind
         * the defence in the red zone, so coverage tightens the closer you get.
         */
        val redZoneCompression: Float = 1.35f,
        /** Route win: what the receiver's release and the defender's coverage count for. */
        val releaseWeight: Float = 0.22f,
        val coverageWeight: Float = 1.10f,
        /** Separation each deep defender beyond the first takes off a deep route. */
        val deepHelp: Float = 3.2f,
    )

    @Serializable
    data class Penalties(
        val perPlayBase: Float = 0.135f,
        val disciplineScale: Float = 0.55f,
        val crowdNoiseScale: Float = 0.45f,
        /** Each flag's share of the per-play base rate. */
        val falseStartShare: Float = 0.30f,
        val offsideShare: Float = 0.14f,
        val offsideBlitz: Float = 1.4f,
        val passHoldingShare: Float = 0.34f,
        val passInterferenceShare: Float = 0.55f,
        val passInterferenceSeparation: Float = 30f,
        val runHoldingShare: Float = 0.22f,
    )

    /**
     * Injuries that cost games (SPEC 5.5's wear and tear), calibrated to the
     * NFL's rates by position. A snap on the field risks one at its position's
     * rate, raised by fatigue and by the season's wear, and by a player's
     * proneness and low injury resistance. Severity follows the NFL's spread:
     * most cost a game or two, a few end the season.
     */
    @Serializable
    data class Injuries(
        /** On every rate; Grinder plays with more injuries. */
        val scale: Float = 1.08f,
        /** Per-snap chance before fatigue, wear and the player, by position group. */
        val perSnapBack: Float = 0.00113f,
        val perSnapReceiver: Float = 0.00064f,
        val perSnapTightEnd: Float = 0.0011f,
        val perSnapLine: Float = 0.00034f,
        val perSnapQuarterback: Float = 0.00032f,
        val perSnapEdge: Float = 0.00103f,
        val perSnapTackle: Float = 0.00075f,
        val perSnapLinebacker: Float = 0.00095f,
        val perSnapCorner: Float = 0.00065f,
        val perSnapSafety: Float = 0.0006f,
        /** Risk multiplier at full fatigue and at full wear: 1 + k x level / 100. */
        val fatigueRisk: Float = 1.5f,
        val wearRisk: Float = 0.6f,
        /**
         * Load built up through a game: risk x (1 + loadRisk x (snaps / 60 - 0.5)).
         * It averages out over a full game but moves injuries late, where the
         * NFL has about four in ten of them; part-time players carry less.
         */
        val loadRisk: Float = 1.4f,
        /** Wear a snap adds at average durability under load, and the share kept from one week to the next. */
        val wearPerSnap: Float = 0.12f,
        val wearKept: Float = 0.75f,
        /** Share of injuries by games missed: one, two, three or four, five to eight; the rest end the season. */
        val oneGame: Float = 0.4f,
        val twoGames: Float = 0.24f,
        val threeFour: Float = 0.12f,
        val fiveEight: Float = 0.13f,
    ) {
        fun perSnap(position: com.nflsim.engine.model.Position): Float = when (position) {
            com.nflsim.engine.model.Position.RB, com.nflsim.engine.model.Position.FB -> perSnapBack
            com.nflsim.engine.model.Position.WR -> perSnapReceiver
            com.nflsim.engine.model.Position.TE -> perSnapTightEnd
            com.nflsim.engine.model.Position.LT, com.nflsim.engine.model.Position.LG, com.nflsim.engine.model.Position.C, com.nflsim.engine.model.Position.RG, com.nflsim.engine.model.Position.RT -> perSnapLine
            com.nflsim.engine.model.Position.QB -> perSnapQuarterback
            com.nflsim.engine.model.Position.EDGE -> perSnapEdge
            com.nflsim.engine.model.Position.DT -> perSnapTackle
            com.nflsim.engine.model.Position.LB -> perSnapLinebacker
            com.nflsim.engine.model.Position.CB -> perSnapCorner
            com.nflsim.engine.model.Position.S -> perSnapSafety
            else -> 0f
        }
    }

    /**
     * How a coordinator calls a snap (sim.PlayCaller): what down, distance
     * and the score do to his pass rate, and how often he keeps a man in or
     * sends one more. The situations themselves - third and long, the goal
     * line - are football's, and stay in the caller.
     */
    @Serializable
    data class Calling(
        /** Added to the pass rate by down: first, second, third (4+ to go, or fewer), fourth (3+, or fewer). */
        val passFirstDown: Float = -0.06f,
        val passSecondDown: Float = 0.02f,
        val passThirdLong: Float = 0.30f,
        val passThirdShort: Float = 0.05f,
        val passFourthLong: Float = 0.35f,
        val passFourthShort: Float = -0.10f,
        /** Per yard to go past eight, and the most distance can move it either way. */
        val passPerYardToGo: Float = 0.016f,
        val passDistanceMin: Float = -0.14f,
        val passDistanceMax: Float = 0.22f,
        /** Per point behind (a lead takes it off), and the most the score can move it either way. */
        val passPerPointBehind: Float = 0.011f,
        val passScoreMin: Float = -0.16f,
        val passScoreMax: Float = 0.24f,
        /** Taken off at the goal line, two yards or fewer to go. */
        val passGoalLineCut: Float = 0.22f,
        /** No coordinator is ever all run or all pass. */
        val passRateFloor: Float = 0.08f,
        val passRateCeiling: Float = 0.94f,
        /** How deep a second-down throw aims, per yard to go. */
        val secondDownDepthPerYard: Float = 0.9f,
        /** How often a run from the one is a quarterback sneak. */
        val sneakRate: Float = 0.4f,
        /** How often a back stays in to block on a deep shot, and on anything else but a quick throw. */
        val protectDeepRate: Float = 0.55f,
        val protectRate: Float = 0.25f,
        /** Added to the blitz rate on third and six or more, and with goal to go. */
        val blitzThirdLong: Float = 0.10f,
        val blitzGoalToGo: Float = 0.06f,
    )

    /**
     * Fourth down (sim.FourthDown): how often a coach goes for it, by how far
     * he has to go and where, and how far he trusts his kicker.
     */
    @Serializable
    data class FourthDown(
        /** Going for it: in his own end, with a yard or less, two, four or fewer, and more. */
        val goOwnEnd: Float = 0.01f,
        val goInches: Float = 0.42f,
        val goTwo: Float = 0.26f,
        val goShort: Float = 0.12f,
        val goLong: Float = 0.04f,
        /** Added between the opponent's 33 and 48, too far to kick and too close to punt. */
        val goFourDownTerritory: Float = 0.22f,
        /** Added inside the five with three or fewer to go. */
        val goGoalLine: Float = 0.25f,
        /** Added late and behind by more than a kick can make up. */
        val goLateTrailing: Float = 0.35f,
        /** Taken off in the fourth quarter more than a score ahead. */
        val goProtectingLead: Float = 0.10f,
        /** The coach's aggression scales it: base + aggression x scale, then the floor and ceiling. */
        val aggressionBase: Float = 0.6f,
        val aggressionScale: Float = 0.8f,
        val goFloor: Float = 0.005f,
        val goCeiling: Float = 0.95f,
        /** The longest kick he sends a kicker out for: a base, plus kick power and altitude. */
        val rangeBase: Float = 44f,
        val rangePerPower: Float = 20f,
        val rangePerMile: Float = 4f,
        /** With nobody who kicks for a living. */
        val rangeNoKicker: Int = 35,
    )

    /**
     * What the weather does to a game (SPEC 5.10, sim.Weather). Indoors, none
     * of it applies. Wind counts past [windCalm].
     */
    @Serializable
    data class Weather(
        val windCalm: Int = 10,
        /** Completion lost per mph of wind past calm, on a throw of [deepAirYards] or more. */
        val deepAirYards: Int = 15,
        val deepPassPerMph: Float = 0.004f,
        /** Completion lost on every throw in rain, and in snow. */
        val rainCompletion: Float = 0.03f,
        val snowCompletion: Float = 0.05f,
        /** What rain and snow multiply a carry's fumble chance by. */
        val rainFumble: Float = 1.3f,
        val snowFumble: Float = 1.4f,
        /** A field goal's accuracy lost per mph of wind past calm, and in rain and snow. */
        val kickAccuracyPerMph: Float = 0.005f,
        val rainKick: Float = 0.03f,
        val snowKick: Float = 0.05f,
        /** Yards of a kicker's range lost per mph of wind past calm, and per degree below [kickColdBelow]. */
        val kickRangePerMph: Float = 0.3f,
        val kickColdBelow: Int = 40,
        val kickRangePerDegree: Float = 0.1f,
    )

    /**
     * In-game adaptation (SPEC 5.4, sim.GameSimulator.adaptation): how far a
     * coordinator moves off his tendencies against what the other side has
     * shown, at most [window] for a head coach rated 100 in adjustments.
     */
    @Serializable
    data class Adaptation(
        /** Snaps a side has to have seen before it moves at all. */
        val minSnaps: Int = 8,
        val window: Float = 0.12f,
        /** The pass share a defence treats as neither run nor pass heavy, and how hard it reads a lean either way. */
        val neutralPassRate: Float = 0.57f,
        val passGain: Float = 5f,
        /**
         * How hard an offence reads the box it has seen: per man added on
         * average. 1.5 put third-down conversion at 0.417, near its band's top.
         */
        val boxGain: Float = 1.0f,
    )

    @Serializable
    data class GameFlow(
        val playClockSeconds: Int = 40,
        /**
         * What a snap takes off a running clock, play and huddle together.
         * Slower than it was before the clock learned the two-minute
         * warning, the hurry-up, timeouts (31 and 28, then 34 and 31) and
         * out of bounds: they give back the time, and plays per game stay in
         * their band (SPEC 13.2).
         */
        val runPlayClockRunoff: Int = 35,
        val completionClockRunoff: Int = 32,
        val incompleteClockRunoff: Int = 6,
        /** Added to every play caller's pass rate, league-wide, before down and distance. */
        val passRateShift: Float = -0.04f,
        /** A defence this many points ahead in the fourth quarter plays prevent (PlayCaller.defense). */
        val preventLead: Int = 9,
        /**
         * Late-game clock management (SPEC 5.10, sim.ClockManagement): seconds
         * left in the fourth quarter or overtime from which a trailing offense
         * hurries, and what a snap takes off a running clock when it does -
         * as it also does in the two-minute drill before the half.
         */
        val hurryUpSeconds: Int = 300,
        val hurryUpRunoff: Int = 16,
        /** Seconds left from which the club chasing the game spends its timeouts, and how far behind it still bothers. */
        val timeoutSeconds: Int = 180,
        val timeoutMaxDeficit: Int = 16,
        /** What a snap takes off the clock when it is stopped straight after: the play itself. */
        val playSeconds: Int = 6,
        /**
         * How often a play ends out of bounds (SPEC 5.10): a run to the
         * outside or up the middle, a scramble, a catch on a sideline route
         * or anywhere else.
         */
        val outOfBoundsOutsideRun: Float = 0.12f,
        val outOfBoundsInsideRun: Float = 0.02f,
        val outOfBoundsScramble: Float = 0.20f,
        val outOfBoundsSidelineCatch: Float = 0.35f,
        val outOfBoundsCatch: Float = 0.05f,
        /** Late, a club chasing the game heads for the sideline, and one protecting a lead stays in. */
        val outOfBoundsChasing: Float = 2.0f,
        val outOfBoundsProtecting: Float = 0.3f,
        /** Outside the last two minutes of the half and five of the game, the clock restarts on the spot: what that saves. */
        val outOfBoundsRestartSave: Int = 12,
    )

    /** Kicking, punting and returns - SPEC 12's FG distance curve and return rates. */
    @Serializable
    data class SpecialTeams(
        /** Inside this distance a field goal is a formality. */
        val chipShotDistance: Int = 25,
        val chipShotChance: Float = 0.985f,
        /** A kicker's range: the base, up to fgRangePower more for the strongest leg, and altitude. */
        val fgRangeBase: Float = 42f,
        val fgRangePower: Float = 22f,
        val fgAltitudeBonus: Float = 4f,
        /** How quickly makes fall away toward the edge of his range, and the ceiling accuracy sets. */
        val fgCurveWidth: Float = 6.2f,
        val fgBaseAccuracy: Float = 0.72f,
        val fgAccuracyScale: Float = 260f,
        /** Yards past his range over which a make goes from likely to hopeless. */
        val fgBeyondRange: Float = 26f,
        /** With the game on the line a kicker's chance is multiplied by clutchFloor up to clutchFloor + clutchRange. */
        val clutchFloor: Float = 0.94f,
        val clutchRange: Float = 0.09f,
        val extraPointNoKicker: Float = 0.90f,
        val extraPointBase: Float = 0.905f,
        val extraPointAccuracyScale: Float = 1400f,
        val extraPointCeiling: Float = 0.985f,
        /** Gross punt at a 70 leg, yards per point of power, and spread. */
        val puntBase: Float = 38f,
        val puntPowerScale: Float = 0.30f,
        val puntVariance: Float = 5.5f,
        /** Yards short of the goal line a punter aims, per point of placement below 99. */
        val puntPlacementScale: Float = 0.08f,
        /** A punt reaching the end zone goes for a touchback this often at 70 placement, less per point above. */
        val puntTouchbackBase: Float = 0.62f,
        val puntTouchbackPlacement: Float = 0.006f,
        val puntReturnRate: Float = 0.42f,
        val puntReturnMean: Float = 6.5f,
        val puntReturnSkill: Float = 0.05f,
        val kickoffTouchbackRate: Float = 0.63f,
        val kickoffReturnBase: Int = 22,
        val kickoffReturnSpeed: Float = 0.12f,
        val kickoffReturnVariance: Float = 5f,
    )

    /** SPEC 7.1 and 12: how players grow and decline each offseason. */
    @Serializable
    data class Progression(
        /** Work ethic multiplies growth by workBase + workRange x (work ethic / 100). */
        val workBase: Float = 0.75f,
        val workRange: Float = 0.5f,
        /** Coaching: coachBase + coachSlope x coaching x coachability, both over 100. The slope is SPEC 7.1's. */
        val coachBase: Float = 0.2975f,
        val coachSlope: Float = 2.00f,
        /** Decline is softened by work ethic: the age factor x (ageWorkBase - work x ageWorkScale). */
        val ageWorkBase: Float = 1.20f,
        val ageWorkScale: Float = 0.20f,
        /** Year-to-year noise in rating points. */
        val noise: Float = 1.5f,
        /** A breakout adds breakoutBase plus up to breakoutRange; a collapse takes collapseBase plus up to collapseRange. */
        val breakoutBase: Float = 4f,
        val breakoutRange: Float = 5f,
        val collapseBase: Float = 5f,
        val collapseRange: Float = 6f,
        /** How a change splits: physical ratings, mental ones growing and declining, the rest. */
        val physicalShare: Float = 1.15f,
        val mentalGrowth: Float = 0.7f,
        val mentalDecline: Float = 0.35f,
        val mentalFloor: Float = 0.55f,
        val otherShare: Float = 0.9f,
        /** The age curve: growth before the peak, at it, and the decline's exponent and scale after. */
        val youthGrowth: Float = 2.6f,
        val peakGrowth: Float = 0.3f,
        val declineExponent: Float = 1.28f,
        val declineScale: Float = 0.62f,
        /** Playing time: snaps for each band, and what each band multiplies growth by. */
        val snapsHeavy: Int = 800,
        val snapsRegular: Int = 450,
        val snapsSpot: Int = 150,
        val snapHeavy: Float = 1.25f,
        val snapRegular: Float = 1.05f,
        val snapSpot: Float = 0.85f,
        val snapBench: Float = 0.62f,
        /** Breakout chance by development curve, scaled by breakoutWorkBase + work ethic / 100. */
        val breakoutSlow: Float = 0.010f,
        val breakoutNormal: Float = 0.025f,
        val breakoutQuick: Float = 0.055f,
        val breakoutSuperstar: Float = 0.090f,
        val breakoutXFactor: Float = 0.140f,
        val breakoutWorkBase: Float = 0.6f,
        /** Collapse chance per year past the peak, scaled by collapseDurability - durability under load / 100. */
        val collapseRate: Float = 0.012f,
        val collapseDurability: Float = 1.4f,
        /** Retirement chance by age band. */
        val retireBy28: Float = 0.006f,
        val retireBy30: Float = 0.030f,
        val retireBy32: Float = 0.095f,
        val retireBy34: Float = 0.230f,
        val retireBy36: Float = 0.450f,
    )

    /**
     * In-season form (SPEC 10.1 needs somebody to bench). What each position
     * is judged against, how wide a week has to be to read as hot or cold,
     * and how fast it comes and goes.
     */
    @Serializable
    data class Form(
        /** Rating points form is worth on game day at its limit. */
        val swing: Float = 4f,
        /** Of last week's form, what is left this week. */
        val decay: Float = 0.72f,
        /** How much of a week's surprise enters form. */
        val gain: Float = 0.45f,
        /** The week each position is judged against. */
        val passerRating: Float = 85f,
        val yardsPerCarry: Float = 4.15f,
        val yardsPerTarget: Float = 7.5f,
        val defensiveWeek: Float = 5.2f,
        /** How far past that reads as a hot week, or short of it a cold one. */
        val passerSpread: Float = 45f,
        val yardsPerCarrySpread: Float = 2.5f,
        val yardsPerTargetSpread: Float = 6f,
        val defensiveSpread: Float = 6f,
        /** The day's work that counts in full; less counts pro rata. */
        val passAttemptsFull: Float = 20f,
        val carriesFull: Float = 12f,
        val targetsFull: Float = 5f,
    )

    /** SPEC 12's ai group: how hard free agents are chased, how often clubs trade, how the draft weighs need. */
    @Serializable
    data class Ai(
        /** Free agency: a club pays up to 1 + need x faNeedPremium over value to fill a hole. */
        val faNeedPremium: Float = 0.55f,
        /** A losing club pays up to this much more for a key veteran. */
        val faLosingPremium: Float = 0.25f,
        /** An offer below this share of a player's value is not made. */
        val faLowballFloor: Float = 0.72f,
        /**
         * Scales 1 - spend share (0.02-0.38) to the cap a front office keeps free
         * through the market, about 1-15%. Swept over five seeds: 0.3 weakened
         * the effect, 0.5 matched 0.4 and left more of the cap idle.
         */
        val faReserveOfCap: Float = 0.4f,
        /** Swaps a full roster makes in free agency by releasing a worse player. */
        val faMaxUpgrades: Int = 3,
        /** Rating points a free agent has to clear a full roster's weakest player by. */
        val faUpgradeMargin: Float = 4f,
        /** A veteran worth this share of the biggest deal allowed is a key signing a losing club pays extra for. */
        val faKeyVeteranShare: Float = 0.10f,
        /** Opening ask, as a multiple of market, and how much of it is left each day he goes unsigned. */
        val faOpeningPremium: Float = 1.20f,
        val faDailyDecay: Float = 0.955f,
        /** A club only looks at positions it needs past this, and a need is worth this many rating points on its board. */
        val faNeedFloor: Float = 0.20f,
        val faNeedWeight: Float = 14f,
        /** How far a club's board, and its read of an upgrade, varies from the numbers. */
        val faBoardSpread: Float = 4f,
        val faUpgradeSpread: Float = 2f,
        /** Players a club bids on a day. */
        val faTargetsPerDay: Int = 4,
        /** A club with less than this many minimum salaries of room past its reserve bids on nobody new. */
        val faMinSpace: Int = 3,
        /** Players worth this much a year let the market form for this many days, unless an offer beats the ask by [faHoldoutOverride]. */
        val faHoldoutValue: Int = 12_000,
        val faHoldoutDays: Int = 3,
        val faHoldoutOverride: Float = 1.25f,
        /** What scheme fit and a winning club are worth to a free agent against money. */
        val faFitAppeal: Float = 0.30f,
        val faWinningAppeal: Float = 0.35f,
        /** Loyalty to the club he played for: loyalty over this adds to its offer's appeal. */
        val faHomeLoyalty: Float = 400f,
        /** The guaranteed share of a market deal: this, plus this much for each other suitor, up to this many. */
        val faGuaranteeBase: Float = 0.40f,
        val faGuaranteePerSuitor: Float = 0.04f,
        val faGuaranteeSuitors: Int = 4,
        /**
         * Keeping one's own before the market (offseason.Extensions). Room on
         * the roster left for the draft and the market: at 46 rosters filled
         * to 53 before the market opened.
         */
        val extRosterTarget: Int = 40,
        /** What a player asks to stay, as a share of market: 1.06 for the least loyal down to 0.90 for the most. */
        val extPlayerAsk: Float = 1.06f,
        val extPlayerLoyalty: Float = 0.16f,
        /** How far a club goes to keep its own: 0.94 of market up to 1.08 with loyalty. */
        val extClubLimit: Float = 0.94f,
        val extClubLoyalty: Float = 0.14f,
        /** Most that a starter the market cannot replace adds to the limit, and the rating gap that makes him so. */
        val extIrreplaceable: Float = 0.12f,
        val extIrreplaceableGap: Float = 10f,
        /** From this age win now moves the limit, by up to this much either way; and how far aggression stretches it. */
        val extPrimeAge: Int = 27,
        val extWinNowKeep: Float = 0.08f,
        val extAggressionKeep: Float = 0.06f,
        /** Below this multiple of the minimum, let him hit the market. */
        val extKeepThreshold: Float = 1.6f,
        /** How a club ranks its own: need in rating points, loyalty (over this), and how much it varies. */
        val extNeedWeight: Float = 10f,
        val extLoyaltyWeight: Float = 25f,
        val extSpread: Float = 5f,
        /** A club with no more than this many minimum salaries of room extends nobody. */
        val extMinSpace: Int = 4,
        /** The guaranteed share of a re-signing. */
        val extGuarantee: Float = 0.50f,
        /**
         * A club tags a player worth at least this many times the tag. The
         * transition tag needs more: it only buys the right to match. Swept
         * over five seeds: franchise 0.9 gave 13.6 tags a year, 1.15 gave 8.3,
         * 1.3 gave 6.5-7.2 (the NFL's five to eight); transition 1.3 gave 4.2,
         * 1.6 gave 0.5 (the NFL's 0-1).
         */
        val tagWorth: Float = 1.3f,
        val tagTransitionWorth: Float = 1.6f,
        /** Cap compliance: a club cuts to get under the cap no further than this many men. */
        val capMinRoster: Int = 46,
        /** A cap hit worth restructuring or cutting over, in thousands. */
        val capBigDeal: Int = 6_000,
        /** A release worth making for value saves at least this much, and a club makes at most this many. */
        val capMeaningfulSaving: Int = 2_500,
        val capMaxValueCuts: Int = 3,
        /** Of the base salary a club may move into bonus, how much it moves. */
        val restructureShare: Float = 0.6f,
        /** A club takes a fifth-year option when the player is worth at least this share of it. */
        val optionBar: Float = 0.9f,
        /** Contender trades: the record that makes a club think it is close, and the win-now bars to buy and to sell. */
        val contenderWinPct: Float = 0.55f,
        val buyerWinNow: Float = 0.6f,
        val sellerWinNow: Float = 0.4f,
        /**
         * Rating points another club's practice squad man must beat a club's
         * own squad and the street by before it signs him away. A club would
         * rather promote its own, and the man it takes has to go on its 53.
         */
        val poachClearUpgrade: Float = 8f,
        /**
         * Contract disputes (SPEC 10.1). A man with accrued seasons behind
         * him, good enough to have leverage, whose market has moved this far
         * past his deal, asks his club to fix it - and keeps asking.
         */
        val disputeFirstWeek: Int = 3,
        val disputeLastWeek: Int = 15,
        val disputeAccruedSeasons: Int = 3,
        val disputeVoice: Int = 74,
        val disputePayGap: Float = 1.8f,
        /** Ego (0..100) from which a man who wanted paying in the spring, and has the case, holds out of camp. */
        val holdoutEgo: Int = 80,
        /** Form a holdout comes back short of, for missing camp (-100..100). */
        val holdoutForm: Int = 30,
        /** Morale a holdout costs him. */
        val holdoutMorale: Int = 5,
        val disputeWeeklyChance: Float = 0.02f,
        val disputeWaitingMorale: Int = 2,
        /** Waiting wears on him only so far; a refusal can take him lower. */
        val disputeWaitingFloor: Int = 50,
        val disputeRefusedMorale: Int = 18,
        val disputeSettledMorale: Int = 10,
        /**
         * Haggling (SPEC 8.3). The least a man will take, as a share of what
         * the market says he is worth: an ego holds out for all of it, and
         * loyalty will take less to stay. Nobody goes below the floor.
         */
        val disputeReservationBase: Float = 0.90f,
        val disputeEgoWeight: Float = 0.10f,
        val disputeLoyaltyWeight: Float = 0.14f,
        val disputeReservationFloor: Float = 0.74f,
        /** What a rejected offer costs him, against 18 for being told no outright. */
        val disputeSnubMorale: Int = 5,
        /**
         * A league club haggles too (SPEC 8.3): it opens at this share of his
         * market, a bold GM up to this much higher, and pays the floor his
         * agent names if the opening falls short.
         */
        val disputeAiOpenBase: Float = 0.85f,
        val disputeAiOpenAggression: Float = 0.15f,
        /**
         * A deal's length against what he wants: a year shorter costs the
         * club this much more a year, because he gives up security; a year
         * longer buys this much off, because he gains it.
         */
        val termShorterPremium: Float = 0.05f,
        val termLongerDiscount: Float = 0.03f,
        /** The age a club stops wanting to pay a man through. */
        val payThroughAge: Int = 31,
        /**
         * Cap room, as a share of the cap, below which a club is tight: the
         * advice turns to deals that cost less now, and to restructures.
         */
        val tightCapShare: Float = 0.05f,
        /**
         * A man the user's club let walk goes back to it that spring only if
         * his loyalty beats his ego by at least this much (SPEC 7). A proud
         * man does not return to the club that let him go.
         */
        val returnLoyaltyOverEgo: Int = 0,
        /**
         * Of the cap room a club leaves unused at the end of a league year,
         * the share that is added to its cap for the next (SPEC 8.1). The
         * NFL carries all of it.
         */
        val capCarryoverShare: Float = 1.0f,
        /**
         * The advice on an expiring man: an ask within this share of his
         * market is fair; a backup is worth keeping up to this many times
         * the minimum; and past his prime a franchise tag is the answer if
         * it costs no more than this share of what he asks.
         */
        val fairAskShare: Float = 1.08f,
        val cheapDepthMinimums: Float = 3f,
        val veteranTagShare: Float = 1.15f,
        /**
         * Talking to a free agent's agent before the market opens. He starts
         * from about his market, since the auction would pay him that; an ego
         * or a star (worth this share of the cap) wants more to skip the
         * market, and a loyal man coming home or a contender's call less.
         */
        val faTalkBase: Float = 1.02f,
        val faTalkEgoWeight: Float = 0.10f,
        val faTalkLoyaltyWeight: Float = 0.14f,
        val faTalkStarShare: Float = 0.12f,
        val faTalkStarPremium: Float = 0.06f,
        val faTalkContenderWinPct: Float = 0.6f,
        val faTalkContenderDiscount: Float = 0.05f,
        val faTalkFloor: Float = 0.90f,
        val faTalkCeiling: Float = 1.25f,
        /** Offers a man will hear before he stops talking and goes to market. */
        val faTalkAttempts: Int = 2,
        /** Rating points a star must add over the contender's best at his position. */
        val tradeClearUpgrade: Float = 6f,
        /** How much more a seller wants back, and how far past break-even the most aggressive buyer goes. */
        val tradeSellerMargin: Float = 0.05f,
        val tradeAggressionOverpay: Float = 0.25f,
        /** Chance each club calls the user about a trade in a week before the deadline. */
        val tradeOfferCallChance: Float = 0.2f,
        /** Most calls the user takes in a week: the best for the calling clubs. */
        val tradeOffersMax: Int = 2,
        /** How many of its best packages a calling club takes to the trade desk before it gives up on a man. */
        val tradeOfferPool: Int = 6,
        /** Chance a club calls in a week when a man on the user's trade block would help it. */
        val tradeBlockCallChance: Float = 0.5f,
        /** How much better than its best at his position a man on the block must be for a club to call: any upgrade. */
        val tradeBlockUpgrade: Float = 0f,
        /** How often a club grants a player's trade request. */
        val tradeRequestGrantChance: Float = 0.45f,
        /** The draft board: need and scheme fit against talent, as a club reads it. */
        val draftNeedWeight: Float = 9f,
        val draftFitWeight: Float = 6f,
        /**
         * Times a prospect's exposure, setting how well clubs read the board.
         * Above 1 they scout better than the league does and the best players
         * go first; at 0 the board is a lottery.
         */
        val draftScoutingConfidence: Float = 1f,
        /** Draft day: how badly a club must need the best player left, and how many picks back it looks, to move up. */
        val draftTradeUpNeed: Float = 0.6f,
        val draftTradeUpRange: Int = 12,
    )

    /**
     * SPEC 4.9's scheme-fit math: how scheme, familiarity, emphasis, fatigue
     * and morale shape a rating. A league's schemes carry this group, so every
     * rating read from one uses it.
     */
    @Serializable
    data class Ratings(
        /** A rating in a scheme is multiplied by schemeFloor + schemeRange x fit. */
        val schemeFloor: Float = 0.76f,
        val schemeRange: Float = 0.24f,
        /** Familiarity: familiarityFloor + familiarityRange x years in the scheme, full after yearsToLearn. */
        val familiarityFloor: Float = 0.96f,
        val familiarityRange: Float = 0.04f,
        val yearsToLearn: Int = 3,
        /** A rating the scheme leans on especially hard. */
        val emphasisBonus: Float = 1.03f,
        /** A fully fatigued player loses this share. */
        val maxFatiguePenalty: Float = 0.15f,
        /** Morale: moraleFloor + moraleRange x morale / 100. */
        val moraleFloor: Float = 0.98f,
        val moraleRange: Float = 0.04f,
    )

    /**
     * SPEC 5.5: fatigue and rotation within a game. A snap on the field costs
     * its position group's figure, scaled by 1.5 - stamina / 100; a snap on
     * the sideline gives some back, and so do the break between drives and
     * halftime. At a rotating position a player comes out at subOutAt and
     * goes back in once rested to backInAt. Fatigue lowers effective ratings
     * through the ratings group's fatigue penalty.
     */
    @Serializable
    data class Fatigue(
        // Snap shares with quality-aware rotation: lead back 0.85 (a bellcow
        // stays in unless his backup is as good), starting edge 0.72 and
        // tackle 0.77, WR1 and TE1 0.92-0.95, LB1 0.97, corners and safeties
        // 0.97-0.99.
        val perSnapLine: Float = 3.0f,
        val perSnapFront: Float = 15.0f,
        val perSnapBack: Float = 9f,
        val perSnapReceiver: Float = 6.0f,
        val perSnapLinebacker: Float = 5.0f,
        val perSnapSecondary: Float = 4.5f,
        val perSnapQuarterback: Float = 1.5f,
        val sidelineRecovery: Float = 5f,
        val driveRecovery: Float = 10f,
        val halftimeRecovery: Float = 40f,
        val subOutAt: Float = 40f,
        val backInAt: Float = 15f,
    ) {
        fun perSnap(position: com.nflsim.engine.model.Position): Float = when (position) {
            com.nflsim.engine.model.Position.LT, com.nflsim.engine.model.Position.LG,
            com.nflsim.engine.model.Position.C, com.nflsim.engine.model.Position.RG,
            com.nflsim.engine.model.Position.RT -> perSnapLine
            com.nflsim.engine.model.Position.EDGE, com.nflsim.engine.model.Position.DT -> perSnapFront
            com.nflsim.engine.model.Position.RB, com.nflsim.engine.model.Position.FB -> perSnapBack
            com.nflsim.engine.model.Position.WR, com.nflsim.engine.model.Position.TE -> perSnapReceiver
            com.nflsim.engine.model.Position.LB -> perSnapLinebacker
            com.nflsim.engine.model.Position.CB, com.nflsim.engine.model.Position.S -> perSnapSecondary
            com.nflsim.engine.model.Position.QB -> perSnapQuarterback
            else -> 0f
        }

        /** Every per-snap cost multiplied by [k]. */
        fun scaled(k: Float) = copy(
            perSnapLine = perSnapLine * k, perSnapFront = perSnapFront * k, perSnapBack = perSnapBack * k,
            perSnapReceiver = perSnapReceiver * k, perSnapLinebacker = perSnapLinebacker * k,
            perSnapSecondary = perSnapSecondary * k, perSnapQuarterback = perSnapQuarterback * k,
        )
    }

    /**
     * Trades between clubs (SPEC 8.4): what a draft pick is worth, and how a
     * contender buys a star (offseason.ContenderTrades, at the deadline too).
     */
    @Serializable
    data class Trades(
        /**
         * The Johnson chart, points by overall pick 1-224; past the end a pick
         * is worth 1. Clubs trade by it as NFL front offices do.
         */
        val pickChart: List<Float> = listOf(
            3000f, 2600f, 2200f, 1800f, 1700f, 1600f, 1500f, 1400f,
            1350f, 1300f, 1250f, 1200f, 1150f, 1100f, 1050f, 1000f,
            950f, 900f, 875f, 850f, 800f, 780f, 760f, 740f,
            720f, 700f, 680f, 660f, 640f, 620f, 600f, 590f,
            580f, 560f, 550f, 540f, 530f, 520f, 510f, 500f,
            490f, 480f, 470f, 460f, 450f, 440f, 430f, 420f,
            410f, 400f, 390f, 380f, 370f, 360f, 350f, 340f,
            330f, 320f, 310f, 300f, 292f, 284f, 276f, 270f,
            265f, 260f, 255f, 250f, 245f, 240f, 235f, 230f,
            225f, 220f, 215f, 210f, 205f, 200f, 195f, 190f,
            185f, 180f, 175f, 170f, 165f, 160f, 155f, 150f,
            145f, 140f, 136f, 132f, 128f, 124f, 120f, 116f,
            112f, 108f, 104f, 100f, 96f, 92f, 88f, 86f,
            84f, 82f, 80f, 78f, 76f, 74f, 72f, 70f,
            68f, 66f, 64f, 62f, 60f, 58f, 56f, 54f,
            52f, 50f, 49f, 48f, 47f, 46f, 45f, 44f,
            43f, 42f, 41f, 40f, 39.5f, 39f, 38.5f, 38f,
            37.5f, 37f, 36.5f, 36f, 35.5f, 35f, 34.5f, 34f,
            33.5f, 33f, 32.6f, 32.3f, 31.8f, 31.4f, 31f, 30.6f,
            30.2f, 29.8f, 29.4f, 29f, 28.6f, 28.2f, 27.8f, 27.4f,
            27f, 26.6f, 26.2f, 25.8f, 25.4f, 25f, 24.6f, 24.2f,
            23.8f, 23.4f, 23f, 22.6f, 22.2f, 21.8f, 21.4f, 21f,
            20.6f, 20.2f, 19.8f, 19.4f, 19f, 18.6f, 18.2f, 17.8f,
            17.4f, 17f, 16.6f, 16.2f, 15.8f, 15.4f, 15f, 14.6f,
            14.2f, 13.8f, 13.4f, 13f, 12.6f, 12.2f, 11.8f, 11.4f,
            11f, 10.6f, 10.2f, 9.8f, 9.4f, 9f, 8.6f, 8.2f,
            7.8f, 7.4f, 7f, 6.6f, 6.2f, 5.8f, 5.4f, 5f,
            4.6f, 4.2f, 3.8f, 3.4f, 3f, 2.6f, 2.3f, 2f,
        ),
        /** Chart points per point of player value (rating above replacement), measured from this league's drafts. */
        val pointsPerValue: Float = 59f,
        /** How far a club's timeline tilts what a pick is worth: at win now 0 a quarter more, at 1 a quarter less. */
        val pickTimeline: Float = 0.5f,
        /** Need at a position that counts as a real hole, and how many a contender can have and still be close. */
        val holeNeed: Float = 0.35f,
        val maxHoles: Int = 2,
        /** A star: this good, and old enough to be proven. */
        val starOverall: Int = 78,
        val starMinAge: Int = 27,
        /** The oldest star a contender takes: [starMinAge] plus this, plus up to [starAgeRisk] years for risk tolerance. */
        val starAgeSpan: Int = 3,
        val starAgeRisk: Float = 4f,
        /** Young enough to be the future a rebuilding club is buying. */
        val youngAge: Int = 25,
        /** How many of the pieces a seller wants most a contender builds its package from. */
        val packagePool: Int = 6,
        /** Aggression from which a contender goes back for a second player. */
        val secondDealAggression: Float = 0.75f,
        /** Under this win percentage a club sells whatever its GM's timeline. */
        val sellerWinPct: Float = 0.5f,
    )

    /**
     * What players tell their clubs in the spring (offseason.PlayerIntent):
     * who has a voice, what makes him unhappy, when he asks out, and who
     * takes a man who asked.
     */
    @Serializable
    data class Intent(
        /** Below this rating a player has no leverage and knows it. */
        val voice: Int = 68,
        /** Good enough to start somewhere, if not here. */
        val starterQuality: Int = 72,
        /** Unhappy enough to say something, and to ask out. */
        val grumble: Float = 0.30f,
        val demand: Float = 0.62f,
        /** How much less a money grievance pushes a player out the door. */
        val moneyPatience: Float = 0.42f,
        /** What a refused in-season demand adds to his nerve in the spring; it has to clear [moneyPatience]. */
        val refusedNerve: Float = 0.95f,
        /** Losing: the share of games lost past which it grates, scaled, and weighted by age from [losingAgeFrom] over [losingAgeYears]. */
        val losingFrom: Float = 0.45f,
        val losingScale: Float = 2f,
        val losingAgeFrom: Int = 26,
        val losingAgeYears: Float = 6f,
        val losingAgeMin: Float = 0.25f,
        val losingAgeMax: Float = 1.4f,
        /** Buried: a starter-quality man behind somebody, and more for each place further down. */
        val buriedBase: Float = 0.35f,
        val buriedPerRank: Float = 0.22f,
        /** Underpaid: his market past this many times his cap hit, up to [underpaidMax]. */
        val underpaidFrom: Float = 1.35f,
        val underpaidMax: Float = 1.2f,
        /** Loyalty quiets him: loyalty over this comes off his nerve; and how much nerve varies man to man. */
        val loyaltyQuiet: Float = 160f,
        val nerveSpread: Float = 0.18f,
        /** A suitor has to value him above what he costs by this much. */
        val suitorWorth: Float = 1.15f,
        /** Win rate that reads as a contender to a player who wants to win. */
        val contenderWinPct: Float = 0.55f,
        /** How a suitor is chosen: scheme fit, winning, surplus over his cost (per this many thousand), and chance. */
        val suitorFit: Float = 6f,
        val suitorWinning: Float = 10f,
        val suitorSurplus: Float = 1_500f,
        val suitorSpread: Float = 2f,
    )

    companion object {
        val REALISTIC = TuningTable()

        /*
         * The presets are Realistic with a lean, not tables of their own.
         * They were written as absolute values once, and every retune of
         * Realistic left them further behind: by M11 Arcade completed fewer
         * passes than Realistic and Grinder completed 49 per cent. As offsets
         * they keep their character whatever Realistic becomes.
         */

        /** The offence's game: more completions, more after the catch, more long runs. */
        val ARCADE = REALISTIC.let { r ->
            r.copy(
                passing = r.passing.copy(
                    baseCompletion = r.passing.baseCompletion + 0.03f,
                    yacScale = r.passing.yacScale * 1.3f,
                    depthPenaltyPerYard = r.passing.depthPenaltyPerYard * 0.8f,
                ),
                rushing = r.rushing.copy(
                    baseYards = r.rushing.baseYards + 0.3f,
                    breakawayBase = r.rushing.breakawayBase * 1.8f,
                    breakawayYards = r.rushing.breakawayYards * 1.3f,
                ),
            )
        }

        /** The defence's game: tighter windows, more pressure, shorter runs, more bodies hurt. */
        val GRINDER = REALISTIC.let { r ->
            r.copy(
                passing = r.passing.copy(
                    baseCompletion = r.passing.baseCompletion - 0.04f,
                    yacScale = r.passing.yacScale * 0.85f,
                    pressureScale = r.passing.pressureScale * 0.75f,
                ),
                rushing = r.rushing.copy(
                    baseYards = r.rushing.baseYards - 0.35f,
                    variance = r.rushing.variance * 0.6f,
                    breakawayBase = r.rushing.breakawayBase * 0.9f,
                ),
                injuries = r.injuries.copy(scale = r.injuries.scale * 1.25f),
            )
        }
    }
}
