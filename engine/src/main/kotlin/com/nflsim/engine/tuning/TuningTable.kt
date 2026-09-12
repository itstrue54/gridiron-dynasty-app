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
    val injuries: Injuries = Injuries(),
    val gameFlow: GameFlow = GameFlow(),
    val specialTeams: SpecialTeams = SpecialTeams(),
) {
    @Serializable
    data class Passing(
        /** Bigger = protection differences matter less. */
        val pressureScale: Float = 30f,
        /** Share of pressures that become sacks before the QB's escape rating. */
        val sackGivenPressure: Float = 0.18f,
        /** Bigger = accuracy and coverage differences matter less. */
        val completionScale: Float = 88f,
        /** Completion probability at a dead-even matchup, before depth. */
        val baseCompletion: Float = 0.80f,
        /** Completion penalty per yard of intended air distance. */
        val depthPenaltyPerYard: Float = 0.0138f,
        /** Multiplier on completion when the quarterback is pressured. */
        val pressureCompletionMult: Float = 0.62f,
        val interceptionBase: Float = 0.025f,
        /** How much a badly-lost route matchup raises interception odds. */
        val interceptionCoverageScale: Float = 0.042f,
        val yacScale: Float = 0.43f,
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
        /** Completion: what quarterback accuracy and receiver separation count for. */
        val accuracyWeight: Float = 0.62f,
        val separationWeight: Float = 0.55f,
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
        val baseYards: Float = 3.60f,
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
        val redZoneCompression: Float = 0.40f,
        /** Yards per point of vision over 70, and per point of break-tackle over the tackler. */
        val visionScale: Float = 0.020f,
        val breakTackleScale: Float = 0.022f,
        /** Breakaway chance per point of elusiveness over 70, and how speed stretches the run. */
        val breakawayElusiveness: Float = 0.0011f,
        val breakawaySpeedBase: Float = 0.75f,
        val breakawaySpeedRange: Float = 0.5f,
        /** Fumbles: ball security, and how hard the hit is. */
        val fumbleSecurityBase: Float = 1.6f,
        val fumbleHitBase: Float = 0.7f,
        val fumbleHitScale: Float = 140f,
        /** Backfield rotation, cumulative: the lead back's share of carries, then the top two's. */
        val rbRotationLead: Float = 0.60f,
        val rbRotationTopTwo: Float = 0.88f,
    )

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
        val redZoneCompression: Float = 1.45f,
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

    @Serializable
    data class Injuries(
        val perPlayBase: Float = 0.0075f,
        val runPlayMultiplier: Float = 1.25f,
        val sackMultiplier: Float = 1.8f,
    )

    @Serializable
    data class GameFlow(
        val playClockSeconds: Int = 40,
        val runPlayClockRunoff: Int = 31,
        val completionClockRunoff: Int = 29,
        val incompleteClockRunoff: Int = 6,
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

    companion object {
        val REALISTIC = TuningTable()

        val ARCADE = TuningTable(
            passing = Passing(baseCompletion = 0.71f, yacScale = 1.35f, depthPenaltyPerYard = 0.010f),
            rushing = Rushing(baseYards = 3.8f, breakawayBase = 0.085f, breakawayYards = 16f),
        )

        val GRINDER = TuningTable(
            passing = Passing(baseCompletion = 0.62f, yacScale = 0.82f, pressureScale = 21f),
            rushing = Rushing(baseYards = 3.0f, variance = 2.2f, breakawayBase = 0.038f),
            injuries = Injuries(perPlayBase = 0.0095f),
        )
    }
}
