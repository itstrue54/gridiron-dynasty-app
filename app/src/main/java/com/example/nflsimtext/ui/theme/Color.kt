package com.example.nflsimtext.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Broadcast palette (docs/DESIGN.md 2): a Sunday-night TV package - a
 * near-black navy ground, chalk-white type, a hot-orange call to action and
 * an electric-cyan highlight. Colour still encodes situation and never
 * decorates. Night game is the default. The field names are the Call Sheet's,
 * kept so nothing outside this package had to change.
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
    /** The broadcast accent: the lower-third bar and a highlighted row's edge. Not for text. */
    val accent: Color,
    /** A highlighted table row's ground: a tint, so the row stands out without shouting. */
    val rowHighlight: Color,
) {
    /** Touchdowns, field goals and safeties read as an action. */
    val eventScore: Color get() = pylon
    val eventTurnover: Color get() = sitRedZone
    val eventFirstDown: Color get() = stripe
}

val NightColors = NdColors(
    turf = Color(0xFF0A1224),
    turfRaised = Color(0xFF111C35),
    turfLine = Color(0xFF24365E),
    chalk = Color(0xFFEEF3FA),
    chalkDim = Color(0xFF9AABC8),
    pylon = Color(0xFFFF6B1A),
    onPylon = Color(0xFF0A1224),
    pylonText = Color(0xFFFF7A33),
    stripe = Color(0xFF22D3EE),
    onStripe = Color(0xFF0A1224),
    sitNormal = Color(0xFF24365E),
    sitThirdDown = Color(0xFF5B9CFF),
    sitRedZone = Color(0xFFF0505A),
    sitTwoMinute = Color(0xFFB98CF2),
    tierElite = Color(0xFF22D3EE),
    tierGood = Color(0xFFEEF3FA),
    tierAverage = Color(0xFF9AABC8),
    tierLow = Color(0xFFC9967A),
    accent = Color(0xFF22D3EE),
    rowHighlight = Color(0xFF15304A),
)

/** Every text colour here clears 4.5:1 on turf and turfRaised (lowest: third down, 4.60). */
val DayColors = NdColors(
    turf = Color(0xFFEEF2F8),
    turfRaised = Color(0xFFFFFFFF),
    turfLine = Color(0xFFCFD8E6),
    chalk = Color(0xFF0A1224),
    chalkDim = Color(0xFF4A5A78),
    pylon = Color(0xFFC94A0A),
    onPylon = Color(0xFFFFFFFF),
    pylonText = Color(0xFFB2440A),
    stripe = Color(0xFFA6EAF7),
    onStripe = Color(0xFF0A1224),
    sitNormal = Color(0xFFCFD8E6),
    sitThirdDown = Color(0xFF2E6BC9),
    sitRedZone = Color(0xFFC22F38),
    sitTwoMinute = Color(0xFF7A4FC0),
    tierElite = Color(0xFF0A7487),
    tierGood = Color(0xFF0A1224),
    tierAverage = Color(0xFF4A5A78),
    tierLow = Color(0xFF8A5F44),
    accent = Color(0xFF0B8FA8),
    rowHighlight = Color(0xFFDDF4FA),
)

/** A rating's tier colour. Always shown beside the number, never instead of it. */
fun ratingColor(value: Int, c: NdColors): Color = when {
    value >= 90 -> c.tierElite
    value >= 80 -> c.tierGood
    value >= 70 -> c.tierAverage
    else -> c.tierLow
}
