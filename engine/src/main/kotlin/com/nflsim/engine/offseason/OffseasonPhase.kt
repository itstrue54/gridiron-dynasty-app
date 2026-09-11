package com.nflsim.engine.offseason

/**
 * The eleven phases of an offseason, declared in the order SPEC §7 fixes.
 *
 * Declaration order is the spec's order. It is deliberately not the order
 * [OffseasonEngine] currently executes them in — see PHASE_ORDER there.
 * Reconciling the two is a separate change with its own calibration pass.
 *
 * [implemented] marks the phases with engine code behind them today. The
 * rest advance without doing anything, which keeps the machine walkable
 * while the missing phases get built.
 */
enum class OffseasonPhase(val label: String, val implemented: Boolean) {
    POST_SEASON_AWARDS("Awards", true),
    COACHING_CARROUSEL("Coaching carousel", false),
    RETIREMENTS("Retirements", true),
    CONTRACT_DECISIONS("Contract decisions", true),
    FRANCHISE_TAG("Franchise tag", true),
    RE_SIGNING("Re-signing", true),
    FREE_AGENCY("Free agency", true),
    PRE_DRAFT("Pre-draft", false),
    DRAFT("Draft", true),
    UDFA("Undrafted free agents", true),
    OTA_CAMP("OTAs and camp", true),
}
