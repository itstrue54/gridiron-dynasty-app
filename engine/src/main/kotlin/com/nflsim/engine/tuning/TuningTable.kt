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
    )

    @Serializable
    data class Penalties(
        val perPlayBase: Float = 0.135f,
        val disciplineScale: Float = 0.55f,
        val crowdNoiseScale: Float = 0.45f,
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
