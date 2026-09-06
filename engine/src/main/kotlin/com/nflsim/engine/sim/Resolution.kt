package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.RatingContext
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.effectiveRating
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.tuning.TuningTable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

internal fun logistic(x: Float): Float = 1f / (1f + exp(-x))

/** Draws from an exponential distribution. This is where the long tail comes from. */
internal fun Rng.exponential(mean: Float): Float {
    val u = nextFloat().coerceIn(1e-6f, 0.999999f)
    return (-ln(1f - u) * mean)
}

internal fun rate(player: Player, id: RatingId, scheme: Scheme): Int =
    effectiveRating(player, id, RatingContext.forPlayer(player, scheme))

internal fun List<Player>.averageRating(id: RatingId, scheme: Scheme): Float =
    if (isEmpty()) 50f else sumOf { rate(it, id, scheme).toDouble() }.toFloat() / size

/**
 * Everything a single snap needs. Assembled once per play by the caller so the
 * resolution functions stay pure and independently testable.
 */
data class PlayContext(
    val offense: OffenseUnit,
    val defense: DefenseUnit,
    val state: PlayState,
    val tuning: TuningTable = TuningTable.REALISTIC,
    /** 0..100, drives false starts and communication problems on the road. */
    val crowdNoise: Int = 0,
)

// ---------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------

internal object RunResolution {

    fun resolve(
        ctx: PlayContext,
        call: OffensivePlayCall.Run,
        def: DefensivePlayCall,
        rng: Rng,
    ): PlayResult {
        val t = ctx.tuning
        val offScheme = ctx.offense.scheme
        val defScheme = ctx.defense.scheme

        // Which blocking skill this concept actually asks for.
        val blockRating = when (call.concept) {
            RunConcept.INSIDE_ZONE, RunConcept.OUTSIDE_ZONE ->
                RatingId.RUN_BLOCK_FINESSE
            RunConcept.POWER, RunConcept.DUO, RunConcept.QB_SNEAK ->
                RatingId.RUN_BLOCK_POWER
            else -> RatingId.RUN_BLOCK
        }

        val lineBlock = ctx.offense.line.averageRating(blockRating, offScheme)
        // Extra blockers help by how much BETTER than replacement they are, not
        // by their raw rating. Multiplying a 78-rated tight end by 0.12 handed
        // the offence nine points of advantage just for having him on the field.
        val teHelp = (ctx.offense.tightEnds.averageRating(RatingId.RUN_BLOCK, offScheme) - BLOCKER_BASELINE) *
            0.18f * ctx.offense.tightEnds.size
        val backHelp = if (ctx.offense.backs.size > 1)
            (ctx.offense.backs.drop(1).averageRating(RatingId.LEAD_BLOCK, offScheme) - BLOCKER_BASELINE) * 0.12f
        else 0f

        val boxDefenders = (ctx.defense.frontSeven + ctx.defense.safeties)
            .take(def.box.coerceAtLeast(1))
        val shed = boxDefenders.averageRating(RatingId.BLOCK_SHEDDING, defScheme)
        val power = boxDefenders.averageRating(RatingId.STRENGTH, defScheme)
        val frontStrength = shed * 0.62f + power * 0.38f

        val blockers = 5 + ctx.offense.tightEnds.size + (ctx.offense.backs.size - 1).coerceAtLeast(0)
        val numbers = (blockers - def.box) * t.blocking.boxCountPenalty
        val gap = GapSchemeTable.bonus(call.concept, def.front) * t.blocking.gapSchemeScale

        // Nowhere to run to inside the twenty - the safeties are already there.
        val redZone = if (ctx.state.yardsToGoal <= 20)
            (20 - ctx.state.yardsToGoal) * t.rushing.redZoneCompression else 0f

        val noiseCost = (ctx.crowdNoise / 100f) * t.blocking.crowdNoiseRunCost

        val rawAdvantage = (lineBlock + teHelp + backHelp) - frontStrength + gap + numbers -
            redZone - noiseCost
        val advantage = rawAdvantage / ADVANTAGE_DIVISOR

        val carrier = if (call.concept == RunConcept.QB_SNEAK || call.concept == RunConcept.QB_KEEP)
            ctx.offense.quarterback else ctx.offense.backs.first()

        val vision = rate(carrier, RatingId.VISION, offScheme)
        val elusiveness = rate(carrier, RatingId.ELUSIVENESS, offScheme)
        val breakTackle = rate(carrier, RatingId.BREAK_TACKLE, offScheme)
        val tackling = (ctx.defense.frontSeven + ctx.defense.secondary)
            .averageRating(RatingId.TACKLE, defScheme)

        var yards = t.rushing.baseYards +
            t.rushing.advantageYards * advantage +
            rng.gaussian(0f, t.rushing.variance) +
            (vision - 70) * 0.020f +
            (breakTackle - tackling) * 0.022f

        val values = mutableMapOf(
            "lineBlock" to lineBlock,
            "frontStrength" to frontStrength,
            "gapSchemeBonus" to gap,
            "numbersAdvantage" to numbers,
            "advantage" to advantage,
        )

        // Second level. Most carries never get here; the ones that do are why
        // yards per carry has the tail it has.
        val breakChance = (t.rushing.breakawayBase +
            t.rushing.breakawayAdvantageScale * advantage +
            (elusiveness - 70) * 0.0011f).coerceIn(0.004f, 0.42f)
        val broke = rng.nextFloat() < breakChance
        if (broke) {
            val extra = rng.exponential(t.rushing.breakawayYards) *
                (0.75f + (rate(carrier, RatingId.SPEED, offScheme) / 99f) * 0.5f)
            yards += extra
            values["breakawayYards"] = extra
        }
        values["breakChance"] = breakChance

        val finalYards = yards.roundToInt()
            .coerceAtLeast(t.rushing.tackleForLossFloor.roundToInt())
            .coerceAtMost(ctx.state.yardsToGoal)

        // Fumble. Ball security and getting hit hard both matter.
        val hitPower = ctx.defense.frontSeven.averageRating(RatingId.HIT_POWER, defScheme)
        val fumbleChance = t.rushing.fumbleBase *
            (1.6f - rate(carrier, RatingId.BALL_SECURITY, offScheme) / 99f) *
            (0.7f + hitPower / 140f)
        if (rng.nextFloat() < fumbleChance) {
            return PlayResult(
                outcome = PlayOutcome.FUMBLE_LOST,
                yards = finalYards.coerceAtMost(3),
                clockRunoff = t.gameFlow.runPlayClockRunoff,
                ballCarrier = carrier.id,
                turnover = true,
                log = SimLog(values + ("fumbleChance" to fumbleChance),
                    "${carrier.name} is stripped on ${call.concept.label}."),
            )
        }

        val tackler = (ctx.defense.frontSeven + ctx.defense.secondary).randomBy(rng)
        return PlayResult(
            outcome = PlayOutcome.RUN,
            yards = finalYards,
            clockRunoff = t.gameFlow.runPlayClockRunoff,
            ballCarrier = carrier.id,
            tackler = tackler?.id,
            log = SimLog(values, narrate(carrier, call, finalYards, broke, tackler)),
        )
    }

    private fun narrate(
        carrier: Player,
        call: OffensivePlayCall.Run,
        yards: Int,
        broke: Boolean,
        tackler: Player?,
    ): String {
        val stop = tackler?.let { ", tackled by ${it.lastName}" } ?: ""
        return when {
            yards < 0 -> "${carrier.name} is dropped for ${-yards} on ${call.concept.label}$stop."
            yards == 0 -> "${carrier.name} is stuffed at the line on ${call.concept.label}$stop."
            broke && yards >= 20 -> "${carrier.name} breaks through on ${call.concept.label} for $yards!"
            yards >= 10 -> "${carrier.name} rips off $yards on ${call.concept.label}$stop."
            else -> "${carrier.name} gains $yards on ${call.concept.label}$stop."
        }
    }

    /** Weighted toward the players most likely to be near the ball. */
    private fun List<Player>.randomBy(rng: Rng): Player? =
        if (isEmpty()) null else this[rng.nextInt(size)]

    private const val ADVANTAGE_DIVISOR = 26f

    /** A replacement-level blocker. Help above this counts; below it hurts. */
    private const val BLOCKER_BASELINE = 68f
}
