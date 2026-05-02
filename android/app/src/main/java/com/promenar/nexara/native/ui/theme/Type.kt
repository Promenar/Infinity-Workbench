package com.promenar.nexara.native.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Nexara UI Typography System (Stitch MD3 Spec)
 *
 * Font families defined by the Stitch design system:
 * - Manrope for Headings (h1, h2)
 * - Inter for Body & Labels (body-lg, body-md, label-md)
 * - Space Grotesk for Code (code)
 *
 * NOTE: If custom fonts (.ttf) are not present in res/font, Compose will fall back
 * to the default sans-serif/monospace. The critical part is the exact pixel/line-height metrics.
 */

// If you add TTF files later, define FontFamily(Font(R.font.inter_regular)) here.
val Manrope = FontFamily.SansSerif
val Inter = FontFamily.SansSerif
val SpaceGrotesk = FontFamily.Monospace

val NexaraTypography = Typography(
    // h1
    headlineLarge = TextStyle(
        fontFamily = Manrope,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold // 700
    ),
    // h2
    headlineMedium = TextStyle(
        fontFamily = Manrope,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold // 600
    ),
    // body-lg
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Normal // 400
    ),
    // body-md (The Gold Standard for chat bubbles: 15px with 25px leading)
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontSize = 15.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Normal // 400
    ),
    // label-md
    labelMedium = TextStyle(
        fontFamily = Inter,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.02.sp,
        fontWeight = FontWeight.Medium // 500
    ),
    // code
    bodySmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal // 400
    )
)
