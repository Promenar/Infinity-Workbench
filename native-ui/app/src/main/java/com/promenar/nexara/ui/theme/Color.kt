package com.promenar.nexara.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme

object NexaraColors {
    val CanvasBackground = Color(0xFF131315)
    val SurfaceDim = Color(0xFF131315)
    val SurfaceBright = Color(0xFF39393B)
    val SurfaceLowest = Color(0xFF0E0E10)
    val SurfaceLow = Color(0xFF1C1B1D)
    val SurfaceContainer = Color(0xFF201F22)
    val SurfaceHigh = Color(0xFF2A2A2C)
    val SurfaceHighest = Color(0xFF353437)

    val Primary = Color(0xFFC0C1FF)
    val OnPrimary = Color(0xFF1000A9)
    val PrimaryContainer = Color(0xFF8083FF)
    val OnPrimaryContainer = Color(0xFF0D0096)
    val InversePrimary = Color(0xFF494BD6)

    val Secondary = Color(0xFFC8C5CA)
    val OnSecondary = Color(0xFF303033)
    val SecondaryContainer = Color(0xFF2A2A2C)
    val OnSecondaryContainer = Color(0xFFE5E1E4)

    val Tertiary = Color(0xFFFFB783)
    val OnTertiary = Color(0xFF4F2500)
    val TertiaryContainer = Color(0xFFD97721)
    val OnTertiaryContainer = Color(0xFF452000)

    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFDAD6)

    val Outline = Color(0xFF908FA0)
    val OutlineVariant = Color(0xFF464554)

    val OnBackground = Color(0xFFE5E1E4)
    val OnSurface = Color(0xFFE5E1E4)
    val OnSurfaceVariant = Color(0xFFC7C4D7)

    val SurfaceVariant = Color(0xFF353437)
    val SurfaceTint = Primary
    val InverseSurface = Color(0xFFE5E1E4)
    val InverseOnSurface = Color(0xFF313032)

    val GlassSurface = Color.White.copy(alpha = 0.03f)
    val GlassBorder = Color.White.copy(alpha = 0.1f)

    val StatusSuccess = Color(0xFF10B981)
    val StatusSuccessOn = Color.White
    val StatusError = Color(0xFFEF4444)
    val StatusErrorOn = Color.White
    val StatusWarning = Color(0xFFF59E0B)
    val StatusInfo = Color(0xFF3B82F6)

    val RagReady = Color(0xFF4ADE80)
    val RagIndexing = Color(0xFF60A5FA)
    val RagError = Color(0xFFF87171)
    val RagPending = Color(0xFF9CA3AF)
}

internal val NexaraDarkColorScheme = darkColorScheme(
    primary = NexaraColors.Primary,
    onPrimary = NexaraColors.OnPrimary,
    primaryContainer = NexaraColors.PrimaryContainer,
    onPrimaryContainer = NexaraColors.OnPrimaryContainer,
    inversePrimary = NexaraColors.InversePrimary,
    secondary = NexaraColors.Secondary,
    onSecondary = NexaraColors.OnSecondary,
    secondaryContainer = NexaraColors.SecondaryContainer,
    onSecondaryContainer = NexaraColors.OnSecondaryContainer,
    tertiary = NexaraColors.Tertiary,
    onTertiary = NexaraColors.OnTertiary,
    tertiaryContainer = NexaraColors.TertiaryContainer,
    onTertiaryContainer = NexaraColors.OnTertiaryContainer,
    background = NexaraColors.CanvasBackground,
    onBackground = NexaraColors.OnBackground,
    surface = NexaraColors.SurfaceDim,
    onSurface = NexaraColors.OnSurface,
    surfaceDim = NexaraColors.SurfaceDim,
    surfaceBright = NexaraColors.SurfaceBright,
    surfaceContainerLowest = NexaraColors.SurfaceLowest,
    surfaceContainerLow = NexaraColors.SurfaceLow,
    surfaceContainer = NexaraColors.SurfaceContainer,
    surfaceContainerHigh = NexaraColors.SurfaceHigh,
    surfaceContainerHighest = NexaraColors.SurfaceHighest,
    surfaceVariant = NexaraColors.SurfaceVariant,
    onSurfaceVariant = NexaraColors.OnSurfaceVariant,
    outline = NexaraColors.Outline,
    outlineVariant = NexaraColors.OutlineVariant,
    error = NexaraColors.Error,
    onError = NexaraColors.OnError,
    errorContainer = NexaraColors.ErrorContainer,
    onErrorContainer = NexaraColors.OnErrorContainer,
    surfaceTint = NexaraColors.SurfaceTint,
    inverseSurface = NexaraColors.InverseSurface,
    inverseOnSurface = NexaraColors.InverseOnSurface,
)
