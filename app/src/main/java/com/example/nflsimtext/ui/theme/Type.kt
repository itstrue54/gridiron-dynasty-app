package com.example.nflsimtext.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.nflsimtext.R

// Google Fonts ships both families as variable fonts only, so one file carries
// every weight and the axis is set per cut. docs/DESIGN.md 10 lists five static
// files; two variable files are the same type at a quarter of the bytes.
@OptIn(ExperimentalTextApi::class)
private fun bigShoulders(weight: Int) = Font(
    R.font.big_shoulders_display,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun plex(weight: Int) = Font(
    R.font.ibm_plex_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Big Shoulders Display: the scoreboard voice. */
val DisplayFamily = FontFamily(bigShoulders(700), bigShoulders(800))

/** IBM Plex Sans: everything read rather than glanced at. */
val TextFamily = FontFamily(plex(400), plex(500), plex(600))

/** Tabular figures. Without these a column of numbers will not line up. */
private const val TNUM = "tnum"

/**
 * The scale from docs/DESIGN.md 3. Numbers are the hero: every style that
 * carries data asks for tabular figures.
 */
@Immutable
data class NdTypography(
    val scoreboard: TextStyle = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.W800,
        fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-0.5).sp,
        fontFeatureSettings = TNUM,
    ),
    val display: TextStyle = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.W700,
        fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TNUM,
    ),
    val headline: TextStyle = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.W700,
        fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = 0.sp,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = TextFamily, fontWeight = FontWeight.W600,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = TextFamily, fontWeight = FontWeight.W400,
        fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    val data: TextStyle = TextStyle(
        fontFamily = TextFamily, fontWeight = FontWeight.W500,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TNUM,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = TextFamily, fontWeight = FontWeight.W500,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TNUM,
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = TextFamily, fontWeight = FontWeight.W400,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
        fontFeatureSettings = TNUM,
    ),
)

/** Material reads our scale through its own names (docs/DESIGN.md 3). */
fun materialTypography(t: NdTypography) = Typography(
    displayLarge = t.scoreboard,
    displayMedium = t.display,
    headlineMedium = t.headline,
    titleMedium = t.title,
    bodyMedium = t.body,
    bodySmall = t.caption,
    labelLarge = t.data,
    labelMedium = t.label,
)
