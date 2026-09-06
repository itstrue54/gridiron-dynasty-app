package com.nflsim.engine.sim

import kotlinx.serialization.Serializable

/** Where a run is aimed and how it is blocked. */
@Serializable
enum class RunConcept(val label: String, val outside: Boolean) {
    INSIDE_ZONE("inside zone", false),
    OUTSIDE_ZONE("outside zone", true),
    POWER("power", false),
    COUNTER("counter", false),
    DUO("duo", false),
    TRAP("trap", false),
    DRAW("draw", false),
    TOSS("toss", true),
    QB_SNEAK("sneak", false),
    QB_KEEP("read keeper", true),
}

/** Intended depth of a pass concept, in yards past the line. */
@Serializable
enum class PassConcept(val label: String, val airYards: Int, val quick: Boolean) {
    SCREEN("screen", -2, true),
    FLAT("flat", 2, true),
    SLANT("slant", 5, true),
    STICK("stick", 6, true),
    CROSSER("crosser", 11, false),
    CURL("curl", 12, false),
    DIG("dig", 15, false),
    OUT("out", 14, false),
    CORNER("corner", 20, false),
    POST("post", 22, false),
    GO("go", 28, false),
    SEAM("seam", 18, false),
}

@Serializable
enum class Coverage(val label: String, val man: Boolean, val deepDefenders: Int) {
    COVER_0("Cover 0", true, 0),
    COVER_1("Cover 1", true, 1),
    COVER_2_MAN("Cover 2 man", true, 2),
    COVER_2_ZONE("Cover 2", false, 2),
    COVER_3("Cover 3", false, 3),
    COVER_4("Cover 4", false, 4),
    COVER_6("Cover 6", false, 3),
    TAMPA_2("Tampa 2", false, 3),
}

@Serializable
enum class DefensiveFront(val label: String, val baseBox: Int) {
    FOUR_THREE_OVER("4-3 over", 7),
    FOUR_THREE_UNDER("4-3 under", 7),
    THREE_FOUR_TWO_GAP("3-4 two-gap", 7),
    THREE_FOUR_ONE_GAP("3-4 one-gap", 7),
    NICKEL_FOUR_TWO("4-2-5 nickel", 6),
    DIME_FOUR_ONE("dime", 5),
    THREE_THREE_FIVE("3-3-5", 6),
    GOAL_LINE("goal line", 9),
}

@Serializable
enum class Personnel(val label: String, val backs: Int, val tightEnds: Int, val receivers: Int) {
    P_11("11", 1, 1, 3),
    P_12("12", 1, 2, 2),
    P_21("21", 2, 1, 2),
    P_13("13", 1, 3, 1),
    P_10("10", 1, 0, 4),
    P_22("22", 2, 2, 1),
    GOAL_LINE("goal line", 2, 3, 0),
}

@Serializable
sealed interface OffensivePlayCall {
    val personnel: Personnel
    val playAction: Boolean

    @Serializable
    data class Run(
        val concept: RunConcept,
        override val personnel: Personnel = Personnel.P_11,
        override val playAction: Boolean = false,
    ) : OffensivePlayCall

    @Serializable
    data class Pass(
        val concept: PassConcept,
        override val personnel: Personnel = Personnel.P_11,
        override val playAction: Boolean = false,
        /** Backs and tight ends kept in to block rather than released. */
        val extraProtectors: Int = 0,
        /** Which receiver in the progression is the intended target. */
        val primaryTarget: Int = 0,
    ) : OffensivePlayCall

    @Serializable
    data class Punt(override val personnel: Personnel = Personnel.P_11) : OffensivePlayCall {
        override val playAction: Boolean get() = false
    }

    @Serializable
    data class FieldGoal(override val personnel: Personnel = Personnel.P_11) : OffensivePlayCall {
        override val playAction: Boolean get() = false
    }

    @Serializable
    data class Kneel(override val personnel: Personnel = Personnel.P_21) : OffensivePlayCall {
        override val playAction: Boolean get() = false
    }

    @Serializable
    data class Spike(override val personnel: Personnel = Personnel.P_11) : OffensivePlayCall {
        override val playAction: Boolean get() = false
    }
}

@Serializable
data class DefensivePlayCall(
    val front: DefensiveFront,
    val coverage: Coverage,
    /** Rushers sent beyond the standard four. */
    val extraRushers: Int = 0,
    /** Defenders committed to the run box beyond the front's baseline. */
    val boxAdd: Int = 0,
    /** Index of the receiver being bracketed, if any. */
    val doubledTarget: Int? = null,
) {
    val rushers: Int get() = 4 + extraRushers
    val box: Int get() = front.baseBox + boxAdd
    val isBlitz: Boolean get() = extraRushers > 0
}
