package com.nflsim.engine.sim

/**
 * The offensive run concept against the defensive front.
 *
 * This small table is the mechanical expression of scheme matchup knowledge -
 * outside zone struggles against a two-gap front that holds its ground, power
 * eats a light nickel box, a trap punishes an over-penetrating three-technique.
 * Values are rating points added to the blocking advantage.
 *
 * Scaled by TuningTable.blocking.gapSchemeScale so the whole effect can be
 * dialled up or down during the M4 calibration pass without touching the table.
 */
object GapSchemeTable {

    private val table: Map<Pair<RunConcept, DefensiveFront>, Float> = buildMap {
        fun put(concept: RunConcept, vararg entries: Pair<DefensiveFront, Float>) {
            entries.forEach { (front, value) -> put(concept to front, value) }
        }

        put(RunConcept.INSIDE_ZONE,
            DefensiveFront.FOUR_THREE_OVER to 1f,
            DefensiveFront.FOUR_THREE_UNDER to -2f,
            DefensiveFront.THREE_FOUR_TWO_GAP to -4f,
            DefensiveFront.THREE_FOUR_ONE_GAP to 3f,
            DefensiveFront.NICKEL_FOUR_TWO to 4f,
            DefensiveFront.DIME_FOUR_ONE to 7f,
            DefensiveFront.THREE_THREE_FIVE to 5f,
            DefensiveFront.GOAL_LINE to -6f)

        put(RunConcept.OUTSIDE_ZONE,
            DefensiveFront.FOUR_THREE_OVER to 2f,
            DefensiveFront.FOUR_THREE_UNDER to 0f,
            DefensiveFront.THREE_FOUR_TWO_GAP to -6f,
            DefensiveFront.THREE_FOUR_ONE_GAP to 4f,
            DefensiveFront.NICKEL_FOUR_TWO to 3f,
            DefensiveFront.DIME_FOUR_ONE to 6f,
            DefensiveFront.THREE_THREE_FIVE to 2f,
            DefensiveFront.GOAL_LINE to -9f)

        put(RunConcept.POWER,
            DefensiveFront.FOUR_THREE_OVER to -1f,
            DefensiveFront.FOUR_THREE_UNDER to 1f,
            DefensiveFront.THREE_FOUR_TWO_GAP to 2f,
            DefensiveFront.THREE_FOUR_ONE_GAP to -2f,
            DefensiveFront.NICKEL_FOUR_TWO to 6f,
            DefensiveFront.DIME_FOUR_ONE to 9f,
            DefensiveFront.THREE_THREE_FIVE to 7f,
            DefensiveFront.GOAL_LINE to -3f)

        put(RunConcept.COUNTER,
            DefensiveFront.FOUR_THREE_OVER to 2f,
            DefensiveFront.FOUR_THREE_UNDER to 3f,
            DefensiveFront.THREE_FOUR_TWO_GAP to 0f,
            DefensiveFront.THREE_FOUR_ONE_GAP to 5f,
            DefensiveFront.NICKEL_FOUR_TWO to 5f,
            DefensiveFront.DIME_FOUR_ONE to 8f,
            DefensiveFront.THREE_THREE_FIVE to 6f,
            DefensiveFront.GOAL_LINE to -4f)

        put(RunConcept.DUO,
            DefensiveFront.FOUR_THREE_OVER to 0f,
            DefensiveFront.FOUR_THREE_UNDER to 2f,
            DefensiveFront.THREE_FOUR_TWO_GAP to 3f,
            DefensiveFront.THREE_FOUR_ONE_GAP to -1f,
            DefensiveFront.NICKEL_FOUR_TWO to 5f,
            DefensiveFront.DIME_FOUR_ONE to 8f,
            DefensiveFront.THREE_THREE_FIVE to 6f,
            DefensiveFront.GOAL_LINE to -2f)

        // A trap punishes penetration and gets swallowed by patient fronts.
        put(RunConcept.TRAP,
            DefensiveFront.FOUR_THREE_OVER to 3f,
            DefensiveFront.FOUR_THREE_UNDER to 2f,
            DefensiveFront.THREE_FOUR_TWO_GAP to -5f,
            DefensiveFront.THREE_FOUR_ONE_GAP to 7f,
            DefensiveFront.NICKEL_FOUR_TWO to 4f,
            DefensiveFront.DIME_FOUR_ONE to 5f,
            DefensiveFront.THREE_THREE_FIVE to 4f,
            DefensiveFront.GOAL_LINE to -7f)

        put(RunConcept.DRAW,
            DefensiveFront.FOUR_THREE_OVER to 2f,
            DefensiveFront.FOUR_THREE_UNDER to 1f,
            DefensiveFront.THREE_FOUR_TWO_GAP to -2f,
            DefensiveFront.THREE_FOUR_ONE_GAP to 6f,
            DefensiveFront.NICKEL_FOUR_TWO to 3f,
            DefensiveFront.DIME_FOUR_ONE to 6f,
            DefensiveFront.THREE_THREE_FIVE to 5f,
            DefensiveFront.GOAL_LINE to -8f)

        put(RunConcept.TOSS,
            DefensiveFront.FOUR_THREE_OVER to 1f,
            DefensiveFront.FOUR_THREE_UNDER to -1f,
            DefensiveFront.THREE_FOUR_TWO_GAP to -5f,
            DefensiveFront.THREE_FOUR_ONE_GAP to 3f,
            DefensiveFront.NICKEL_FOUR_TWO to 2f,
            DefensiveFront.DIME_FOUR_ONE to 5f,
            DefensiveFront.THREE_THREE_FIVE to 1f,
            DefensiveFront.GOAL_LINE to -10f)

        put(RunConcept.QB_SNEAK,
            DefensiveFront.FOUR_THREE_OVER to 4f,
            DefensiveFront.FOUR_THREE_UNDER to 4f,
            DefensiveFront.THREE_FOUR_TWO_GAP to 2f,
            DefensiveFront.THREE_FOUR_ONE_GAP to 5f,
            DefensiveFront.NICKEL_FOUR_TWO to 8f,
            DefensiveFront.DIME_FOUR_ONE to 11f,
            DefensiveFront.THREE_THREE_FIVE to 9f,
            DefensiveFront.GOAL_LINE to 0f)

        put(RunConcept.QB_KEEP,
            DefensiveFront.FOUR_THREE_OVER to 3f,
            DefensiveFront.FOUR_THREE_UNDER to 2f,
            DefensiveFront.THREE_FOUR_TWO_GAP to -1f,
            DefensiveFront.THREE_FOUR_ONE_GAP to 5f,
            DefensiveFront.NICKEL_FOUR_TWO to 4f,
            DefensiveFront.DIME_FOUR_ONE to 6f,
            DefensiveFront.THREE_THREE_FIVE to 3f,
            DefensiveFront.GOAL_LINE to -6f)
    }

    fun bonus(concept: RunConcept, front: DefensiveFront): Float =
        table[concept to front] ?: 0f

    /** Every concept must be scored against every front. Asserted in tests. */
    fun coverage(): Int = table.size
}
