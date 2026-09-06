package com.nflsim.engine.model

import kotlinx.serialization.Serializable

/** Coarse buckets used for scheme fit lookups and coaching assignments. */
@Serializable
enum class PositionGroup { QB, RB, WR, TE, OL, EDGE, DT, LB, CB, S, ST }

@Serializable
enum class Position(val group: PositionGroup, val label: String) {
    QB(PositionGroup.QB, "QB"),
    RB(PositionGroup.RB, "RB"),
    FB(PositionGroup.RB, "FB"),
    WR(PositionGroup.WR, "WR"),
    TE(PositionGroup.TE, "TE"),
    LT(PositionGroup.OL, "LT"),
    LG(PositionGroup.OL, "LG"),
    C(PositionGroup.OL, "C"),
    RG(PositionGroup.OL, "RG"),
    RT(PositionGroup.OL, "RT"),
    EDGE(PositionGroup.EDGE, "EDGE"),
    DT(PositionGroup.DT, "DT"),
    LB(PositionGroup.LB, "LB"),
    CB(PositionGroup.CB, "CB"),
    S(PositionGroup.S, "S"),
    K(PositionGroup.ST, "K"),
    P(PositionGroup.ST, "P"),
    LS(PositionGroup.ST, "LS");

    val isOffense: Boolean
        get() = group in setOf(PositionGroup.QB, PositionGroup.RB, PositionGroup.WR,
                               PositionGroup.TE, PositionGroup.OL)
}

/**
 * Archetypes are how a player is *shaped*, and they drive scheme fit and the
 * progression curve. A 92-overall one-cut zone back is not the same asset in a
 * gap-power scheme as he is in wide zone - see docs/SPEC.md section 4.3.
 */
@Serializable
enum class Archetype(val group: PositionGroup, val label: String) {
    // QB
    POCKET_PASSER(PositionGroup.QB, "Pocket Passer"),
    FIELD_GENERAL(PositionGroup.QB, "Field General"),
    IMPROVISER(PositionGroup.QB, "Improviser"),
    DUAL_THREAT(PositionGroup.QB, "Dual-Threat"),
    GUNSLINGER(PositionGroup.QB, "Gunslinger"),

    // RB / FB
    POWER_BACK(PositionGroup.RB, "Power Back"),
    ONE_CUT_ZONE(PositionGroup.RB, "One-Cut Zone"),
    ELUSIVE(PositionGroup.RB, "Elusive"),
    RECEIVING_BACK(PositionGroup.RB, "Receiving Back"),
    WORKHORSE(PositionGroup.RB, "Workhorse"),

    // WR
    X_CONTESTED(PositionGroup.WR, "X (Contested)"),
    Z_ROUTE_TECH(PositionGroup.WR, "Z (Route Technician)"),
    SLOT(PositionGroup.WR, "Slot"),
    DEEP_THREAT(PositionGroup.WR, "Deep Threat"),
    YAC_RECEIVER(PositionGroup.WR, "YAC"),

    // TE
    INLINE_BLOCKER(PositionGroup.TE, "Inline Blocker"),
    MOVE_TE(PositionGroup.TE, "Move TE"),
    RECEIVING_TE(PositionGroup.TE, "Receiving TE"),
    H_BACK(PositionGroup.TE, "H-Back"),

    // OL
    ZONE_BLOCKER(PositionGroup.OL, "Zone Blocker"),
    POWER_MAULER(PositionGroup.OL, "Power Mauler"),
    PASS_PROTECTOR(PositionGroup.OL, "Pass Protector"),
    ATHLETIC_PULLING(PositionGroup.OL, "Athletic / Pulling"),

    // EDGE
    SPEED_RUSHER(PositionGroup.EDGE, "Speed Rusher"),
    POWER_RUSHER(PositionGroup.EDGE, "Power Rusher"),
    RUN_STOPPING_EDGE(PositionGroup.EDGE, "Run-Stopping"),
    COVERAGE_OLB(PositionGroup.EDGE, "Coverage OLB"),

    // DT
    NOSE_1TECH(PositionGroup.DT, "1-Tech Nose"),
    PENETRATOR_3TECH(PositionGroup.DT, "3-Tech Penetrator"),
    TWO_GAP_5TECH(PositionGroup.DT, "5-Tech Two-Gap"),
    INTERIOR_RUSHER(PositionGroup.DT, "Interior Rusher"),

    // LB
    FIELD_GENERAL_MLB(PositionGroup.LB, "Field General MLB"),
    COVERAGE_LB(PositionGroup.LB, "Coverage LB"),
    BLITZING_LB(PositionGroup.LB, "Blitzing LB"),
    THUMPER(PositionGroup.LB, "Thumper"),

    // CB
    MAN_PRESS(PositionGroup.CB, "Man Press"),
    ZONE_CB(PositionGroup.CB, "Zone"),
    SLOT_CB(PositionGroup.CB, "Slot"),
    BALLHAWK(PositionGroup.CB, "Ballhawk"),

    // S
    FREE_SAFETY(PositionGroup.S, "Free Safety"),
    STRONG_SAFETY(PositionGroup.S, "Strong Safety"),
    BOX_SAFETY(PositionGroup.S, "Box Safety"),
    HYBRID_NICKEL(PositionGroup.S, "Hybrid / Nickel"),

    // Specialists
    SPECIALIST(PositionGroup.ST, "Specialist");

    companion object {
        fun forGroup(group: PositionGroup): List<Archetype> = entries.filter { it.group == group }
        fun forPosition(position: Position): List<Archetype> = forGroup(position.group)
    }
}
