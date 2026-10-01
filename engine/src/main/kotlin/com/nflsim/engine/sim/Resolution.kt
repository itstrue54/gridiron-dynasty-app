package com.nflsim.engine.sim

import com.nflsim.engine.model.Player
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.ratings.RatingContext
import com.nflsim.engine.ratings.Scheme
import com.nflsim.engine.ratings.effectiveRating
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.SplitMixRng
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
    /** The game's weather (SPEC 5.10). */
    val weather: Weather = Weather.INDOORS,
    /**
     * How far each coordinator has adapted to the other this game (SPEC 5.4):
     * added to the offence's pass rate and the defence's blitz rate, and the
     * chance the defence adds a man to the box (or, negative, takes one out).
     */
    val adaptPass: Float = 0f,
    val adaptBlitz: Float = 0f,
    val adaptBox: Float = 0f,
    /** Each side's game plan: the tendencies its coordinator calls from. */
    val offPlan: com.nflsim.engine.model.GamePlan = com.nflsim.engine.model.GamePlan(),
    val defPlan: com.nflsim.engine.model.GamePlan = com.nflsim.engine.model.GamePlan(),
    /** A player's carries so far this game, for the lead back's workload. */
    val carries: (Int) -> Int = { 0 },
    /**
     * Picks how the snap is worded and nothing else (SPEC 10.4). A game hands
     * in its own narration stream; a snap played on its own words itself from
     * a split of its stream, which is pure, so the words never move the play.
     */
    val narration: Rng? = null,
) {
    internal val words: Rng get() = narration ?: SplitMixRng(0L)
}

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
        val teHelp = (ctx.offense.tightEnds.averageRating(RatingId.RUN_BLOCK, offScheme) - t.blocking.blockerBaseline) *
            t.blocking.tightEndHelp * ctx.offense.tightEnds.size
        val backHelp = if (ctx.offense.backs.size > 1)
            (ctx.offense.backs.drop(1).averageRating(RatingId.LEAD_BLOCK, offScheme) - t.blocking.blockerBaseline) * t.blocking.leadBlockScale
        else 0f

        val boxDefenders = (ctx.defense.frontSeven + ctx.defense.safeties)
            .take(def.box.coerceAtLeast(1))
        val shed = boxDefenders.averageRating(RatingId.BLOCK_SHEDDING, defScheme)
        val power = boxDefenders.averageRating(RatingId.STRENGTH, defScheme)
        val frontStrength = shed * t.blocking.shedWeight + power * t.blocking.powerWeight

        val blockers = 5 + ctx.offense.tightEnds.size + (ctx.offense.backs.size - 1).coerceAtLeast(0)
        val numbers = (blockers - def.box) * t.blocking.boxCountPenalty
        val gap = GapSchemeTable.bonus(call.concept, def.front) * t.blocking.gapSchemeScale

        // Nowhere to run to inside the twenty - the safeties are already there.
        val redZone = if (ctx.state.yardsToGoal <= 20)
            (20 - ctx.state.yardsToGoal) * t.rushing.redZoneCompression else 0f

        val noiseCost = (ctx.crowdNoise / 100f) * t.blocking.crowdNoiseRunCost

        val rawAdvantage = (lineBlock + teHelp + backHelp) - frontStrength + gap + numbers -
            redZone - noiseCost
        val advantage = rawAdvantage / t.blocking.advantageDivisor

        // Backs rotate. A lead back takes most of the work but not all of it,
        // which is why a depth chart matters and why RB2 is worth rostering.
        val carrier = when {
            call.concept == RunConcept.QB_SNEAK || call.concept == RunConcept.QB_KEEP ->
                ctx.offense.quarterback
            else -> pickCarrier(ctx.offense.backfield.ifEmpty { ctx.offense.backs }, rng, t.rushing, ctx.carries)
        }

        val vision = rate(carrier, RatingId.VISION, offScheme)
        val elusiveness = rate(carrier, RatingId.ELUSIVENESS, offScheme)
        val breakTackle = rate(carrier, RatingId.BREAK_TACKLE, offScheme)
        val tackling = (ctx.defense.frontSeven + ctx.defense.secondary)
            .averageRating(RatingId.TACKLE, defScheme)

        var yards = t.rushing.baseYards +
            t.rushing.advantageYards * advantage +
            rng.gaussian(0f, t.rushing.variance) +
            (vision - 70) * t.rushing.visionScale +
            (breakTackle - tackling) * t.rushing.breakTackleScale

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
            (elusiveness - 70) * t.rushing.breakawayElusiveness).coerceIn(t.rushing.breakawayMin, t.rushing.breakawayMax)
        val broke = rng.nextFloat() < breakChance
        if (broke) {
            val extra = rng.exponential(t.rushing.breakawayYards) *
                (t.rushing.breakawaySpeedBase + (rate(carrier, RatingId.SPEED, offScheme) / 99f) * t.rushing.breakawaySpeedRange)
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
            (t.rushing.fumbleSecurityBase - rate(carrier, RatingId.BALL_SECURITY, offScheme) / 99f) *
            (t.rushing.fumbleHitBase + hitPower / t.rushing.fumbleHitScale) *
            ctx.weather.fumbleFactor(t.weather)
        if (rng.nextFloat() < fumbleChance) {
            return PlayResult(
                outcome = PlayOutcome.FUMBLE_LOST,
                yards = finalYards.coerceAtMost(3),
                clockRunoff = t.gameFlow.runPlayClockRunoff,
                ballCarrier = carrier.id,
                turnover = true,
                log = SimLog(values + ("fumbleChance" to fumbleChance),
                    PlayLines.write("run.fumble", ctx.words,
                        "carrier" to carrier.name, "concept" to call.concept.label)),
            )
        }

        val tackler = tacklerFor(ctx, finalYards, rng)
        val assister = assisterFor(ctx, finalYards, rng, tackler)
        return PlayResult(
            outcome = PlayOutcome.RUN,
            yards = finalYards,
            clockRunoff = t.gameFlow.runPlayClockRunoff,
            ballCarrier = carrier.id,
            tackler = tackler?.id,
            assister = assister?.id,
            log = SimLog(values, narrate(ctx.words, carrier, call, finalYards, broke, tackler)),
        )
    }

    private fun narrate(
        words: Rng,
        carrier: Player,
        call: OffensivePlayCall.Run,
        yards: Int,
        broke: Boolean,
        tackler: Player?,
    ): String {
        val stop = tackler?.let { PlayLines.write("run.tackle", words, "tackler" to it.lastName) } ?: ""
        val key = when {
            yards < 0 -> "run.loss"
            yards == 0 -> "run.stuffed"
            broke && yards >= 20 -> "run.breakaway"
            yards >= 10 -> "run.big"
            else -> "run.gain"
        }
        return PlayLines.write(key, words,
            "carrier" to carrier.name, "concept" to call.concept.label, "stop" to stop,
            "yards" to yards, "loss" to -yards, "yardage" to PlayLines.yardage(yards))
    }

    /** Weighted toward the players most likely to be near the ball. */
    private fun List<Player>.randomBy(rng: Rng): Player? =
        if (isEmpty()) null else this[rng.nextInt(size)]

    /**
     * Who stopped him. A run held at the line belongs to the front seven; one
     * that got past them belongs to the men behind (docs/SPEC.md 12's weights
     * live in the tuning table).
     */
    internal fun tacklerFor(ctx: PlayContext, yards: Int, rng: Rng): Player? {
        val defenders = ctx.defense.frontSeven + ctx.defense.secondary
        if (defenders.isEmpty()) return null
        val tn = ctx.tuning.tackling
        val weights = defenders.map { tn.weight(it.position, yards) }
        val total = weights.sum()
        if (total <= 0f) return defenders.randomBy(rng)
        var roll = rng.nextFloat() * total
        defenders.forEachIndexed { i, defender ->
            roll -= weights[i]
            if (roll <= 0f) return defender
        }
        return defenders.last()
    }

    /**
     * The second man in, on the quarter of tackles that are not made alone.
     * He is chosen the same way as the tackler, from everyone else.
     */
    internal fun assisterFor(ctx: PlayContext, yards: Int, rng: Rng, tackler: Player?): Player? {
        if (tackler == null || rng.nextFloat() >= ctx.tuning.tackling.assistShare) return null
        val others = (ctx.defense.frontSeven + ctx.defense.secondary).filter { it.id != tackler.id }
        if (others.isEmpty()) return null
        val tn = ctx.tuning.tackling
        val weights = others.map { tn.weight(it.position, yards) }
        val total = weights.sum()
        if (total <= 0f) return others.randomBy(rng)
        var roll = rng.nextFloat() * total
        others.forEachIndexed { i, man ->
            roll -= weights[i]
            if (roll <= 0f) return man
        }
        return others.last()
    }

    /**
     * Roughly 83/12/5 down the rested chart, where a resting back drops to
     * the end: a bellcow's share. A committee comes from rotation instead -
     * a tired lead back sits when his backup, fresh, is as good as he is tired.
     */
    private fun pickCarrier(backs: List<Player>, rng: Rng, rushing: TuningTable.Rushing, carries: (Int) -> Int): Player {
        if (backs.size <= 1) return backs.first()
        val roll = rng.nextFloat()
        val pick = when {
            roll < rushing.rbRotationLead -> backs[0]
            roll < rushing.rbRotationTopTwo -> backs.getOrElse(1) { backs[0] }
            else -> backs.getOrElse(2) { backs[0] }
        }
        // A coach keeps his lead back's workload in reason: past the cap
        // in a game, the next back takes the handoff.
        return if (pick === backs[0] && carries(pick.id.v) >= rushing.leadBackCarryCap) backs[1] else pick
    }

}
