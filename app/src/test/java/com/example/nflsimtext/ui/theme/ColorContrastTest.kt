package com.example.nflsimtext.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/DESIGN.md 2: body text at 4.5:1 against its ground, large text and UI
 * glyphs at 3:1. A palette that fails this is not a palette, so it is a test.
 */
class ColorContrastTest {

    private fun channel(c: Float) = if (c <= 0.03928f) c / 12.92
    else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun luminance(c: Color) =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(fg: Color, bg: Color): Double {
        val a = luminance(fg); val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun check(theme: String, name: String, fg: Color, bg: Color, floor: Double = 4.5) {
        val r = ratio(fg, bg)
        assertTrue("$theme $name is %.2f:1, under $floor:1".format(r), r >= floor)
    }

    private fun bodyText(theme: String, c: NdColors) {
        check(theme, "chalk on turf", c.chalk, c.turf)
        check(theme, "chalk on turfRaised", c.chalk, c.turfRaised)
        check(theme, "chalkDim on turf", c.chalkDim, c.turf)
        check(theme, "chalkDim on turfRaised", c.chalkDim, c.turfRaised)
        check(theme, "text on pylon", c.onPylon, c.pylon)
        check(theme, "text on stripe", c.onStripe, c.stripe)
        check(theme, "elite rating", c.tierElite, c.turfRaised)
        check(theme, "good rating", c.tierGood, c.turfRaised)
        check(theme, "average rating", c.tierAverage, c.turfRaised)
        check(theme, "low rating", c.tierLow, c.turfRaised)
    }

    @Test
    fun `night game body text carries 4_5 to 1`() = bodyText("night", NightColors)

    @Test
    fun `day game body text carries 4_5 to 1`() = bodyText("day", DayColors)

    /** Situation edges are 4dp glyphs, so they answer to the 3:1 floor. */
    @Test
    fun `situation edges carry 3 to 1`() {
        for ((theme, c) in listOf("night" to NightColors, "day" to DayColors)) {
            check(theme, "third down edge", c.sitThirdDown, c.turfRaised, 3.0)
            check(theme, "red zone edge", c.sitRedZone, c.turfRaised, 3.0)
            check(theme, "two minute edge", c.sitTwoMinute, c.turfRaised, 3.0)
            check(theme, "pylon as text", c.pylonText, c.turf, 3.0)
        }
    }
}
