package com.promenar.nexara.native.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nexara UI Color System (Stitch MD3 Spec)
 *
 * DO NOT change these colors manually. They are generated from the Stitch
 * design system (projects/10380042700551984895).
 * Base Theme: Dark Mode (Glassmorphism & High-Fidelity Professional Interface)
 */

object NexaraColors {
    // Canvas & Surfaces
    val CanvasBackground = Color(0xFF131315) // Zinc-950, root background
    val SurfaceDim = Color(0xFF131315)
    val SurfaceBright = Color(0xFF39393B)
    val SurfaceLowest = Color(0xFF0E0E10)
    val SurfaceLow = Color(0xFF1C1B1D)
    val SurfaceContainer = Color(0xFF201F22)
    val SurfaceHigh = Color(0xFF2A2A2C)
    val SurfaceHighest = Color(0xFF353437)

    // Primary (Brand Engine)
    val Primary = Color(0xFFC0C1FF)
    val OnPrimary = Color(0xFF1000A9)
    val PrimaryContainer = Color(0xFF8083FF)
    val OnPrimaryContainer = Color(0xFF0D0096)
    val InversePrimary = Color(0xFF494BD6) // The true Indigo-500 anchor (#6366f1 variant)

    // Secondary & Tertiary
    val Secondary = Color(0xFFC8C5CA)
    val OnSecondary = Color(0xFF303033)
    val SecondaryContainer = Color(0xFF47464A)
    val OnSecondaryContainer = Color(0xFFB6B4B8)

    val Tertiary = Color(0xFFFFB783)
    val OnTertiary = Color(0xFF4F2500)
    val TertiaryContainer = Color(0xFFD97721)
    val OnTertiaryContainer = Color(0xFF452000)

    // Semantic / Status
    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFDAD6)

    // Outline & Borders (Crucial for 0.5px hairlines)
    val Outline = Color(0xFF908FA0)
    val OutlineVariant = Color(0xFF464554)

    // Text & Foreground
    val OnBackground = Color(0xFFE5E1E4) // Primary text
    val OnSurface = Color(0xFFE5E1E4)
    val OnSurfaceVariant = Color(0xFFC7C4D7) // Secondary/Muted text

    val SurfaceVariant = Color(0xFF353437)
    val SurfaceTint = Primary
    val InverseSurface = Color(0xFFE5E1E4)
    val InverseOnSurface = Color(0xFF313032)

    // Glassmorphism Accents (As per spec: rgba(255, 255, 255, 0.03))
    val GlassSurface = Color.White.copy(alpha = 0.03f)
    val GlassBorder = Color.White.copy(alpha = 0.1f) // 10% opacity border for glass
}
