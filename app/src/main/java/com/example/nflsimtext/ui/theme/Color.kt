package com.example.nflsimtext.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Call Sheet palette (docs/DESIGN.md 2): a coach's laminated sheet, where
 * colour encodes situation and never decorates. Night game is the default.
 *
 * Every colour in the app comes from here. No composable outside this package
 * writes a hex value.
 */
@Immutable
data class NdColors(
    val turf: Color,
    val turfRaised: Color,
    val turfLine: Color,
    val chalk: Color,
    val chalkDim: Color,
    val pylon: Color,
    /** Text and glyphs drawn on a pylon fill. */
    val onPylon: Color,
    /** Pylon used as text on the ground, which needs its own value in daylight. */
    val pylonText: Color,
    val stripe: Color,
    val onStripe: Color,
    val sitNormal: Color,
    val sitThirdDown: Color,
    val sitRedZone: Color,
    val sitTwoMinute: Color,
    val tierElite: Color,
    val tierGood: Color,
    val tierAverage: Color,
    val tierLow: Color,
) {
    /** Touchdowns, field goals and safeties read as an action. */
    val eventScore: Color get() = pylon
    val eventTurnover: Color get() = sitRedZone
    val eventFirstDown: Color get() = stripe
}

val NightColors = NdColors(
    turf = Color(0xFF0F2A20),
    turfRaised = Color(0xFF163527),
    turfLine = Color(0xFF2A4D3C),
    chalk = Color(0xFFEEF1EA),
    chalkDim = Color(0xFF9DB3A7),
    pylon = Color(0xFFFF6B1A),
    onPylon = Color(0xFF0F2A20),
    pylonText = Color(0xFFFF6B1A),
    stripe = Color(0xFFF2D530),
    onStripe = Color(0xFF0F2A20),
    sitNormal = Color(0xFF2A4D3C),
    sitThirdDown = Color(0xFF4C8DF0),
    sitRedZone = Color(0xFFE0464E),
    sitTwoMinute = Color(0xFFB98CF2),
    tierElite = Color(0xFFF2D530),
    tierGood = Color(0xFFEEF1EA),
    tierAverage = Color(0xFF9DB3A7),
    // docs/DESIGN.md lists leather as #B48A6E, which reads 4.32:1 on
    // turfRaised. Lightened the minimum that clears the 4.5:1 floor.
    tierLow = Color(0xFFBC9276),
)

val DayColors = NdColors(
    turf = Color(0xFFF2F4EE),
    turfRaised = Color(0xFFFFFFFF),
    turfLine = Color(0xFFD3DBD2),
    chalk = Color(0xFF0F2A20),
    chalkDim = Color(0xFF4F665A),
    pylon = Color(0xFFC94E0B),
    onPylon = Color(0xFFFFFFFF),
    pylonText = Color(0xFFA8420A),
    stripe = Color(0xFFF6E27A),
    onStripe = Color(0xFF0F2A20),
    sitNormal = Color(0xFFD3DBD2),
    sitThirdDown = Color(0xFF2E6BC9),
    sitRedZone = Color(0xFFC22F38),
    sitTwoMinute = Color(0xFF7A4FC0),
    tierElite = Color(0xFF8A6D00),
    tierGood = Color(0xFF0F2A20),
    tierAverage = Color(0xFF4F665A),
    tierLow = Color(0xFF8A5F44),
)

/** A rating's tier colour. Always shown beside the number, never instead of it. */
fun ratingColor(value: Int, c: NdColors): Color = when {
    value >= 90 -> c.tierElite
    value >= 80 -> c.tierGood
    value >= 70 -> c.tierAverage
    else -> c.tierLow
}
