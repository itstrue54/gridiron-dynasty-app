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
    val fatigue: Fatigue = Fatigue(),
    val ratings: Ratings = Ratings(),
) {
    @Serializable
    data class Passing(
        /** Bigger = protection differences matter less. */
        val pressureScale: Float = 30f,
        /** Share of pressures that become sacks before the QB's escape rating. */
        val sackGivenPressure: Float = 0.21f,
        /** Bigger = accuracy and coverage differences matter less. */
        val completionScale: Float = 88f,
        /** Completion probability at a dead-even matchup, before depth. */
        val baseCompletion: Float = 0.82f,
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
        val fumbleBase: Float = 0.0125f,
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

    @Serializable
    data class GameFlow(
        val playClockSeconds: Int = 40,
        val runPlayClockRunoff: Int = 31,
        val completionClockRunoff: Int = 28,
        val incompleteClockRunoff: Int = 6,
        /** Added to every play caller's pass rate, league-wide, before down and distance. */
        val passRateShift: Float = -0.04f,
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
        /** Contender trades: the record that makes a club think it is close, and the win-now bars to buy and to sell. */
        val contenderWinPct: Float = 0.55f,
        val buyerWinNow: Float = 0.6f,
        val sellerWinNow: Float = 0.4f,
        /** Rating points a star must add over the contender's best at his position. */
        val tradeClearUpgrade: Float = 6f,
        /** How much more a seller wants back, and how far past break-even the most aggressive buyer goes. */
        val tradeSellerMargin: Float = 0.05f,
        val tradeAggressionOverpay: Float = 0.25f,
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
