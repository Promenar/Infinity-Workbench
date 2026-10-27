package com.promenar.nexara.ui.theme

import android.annotation.SuppressLint
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant

/** 使用 Google Material Color Utilities 按 [style] 生成与 Material 3 同源的色板，缺省为 Tonal Spot。 */
// Material Color Utilities 官方 Java 指南直接使用这些 API；Android artifact 的
// library-group 注解并不代表运行时不稳定，因此在这一个适配边界内集中抑制。
@SuppressLint("RestrictedApi")
internal fun seededColorScheme(seed: Color, dark: Boolean, style: NexaraSchemeStyle = NexaraSchemeStyle.TONAL_SPOT): ColorScheme {
    val hct = Hct.fromInt(seed.toArgb())
    val scheme: DynamicScheme = when (style) {
        NexaraSchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, 0.0)
        NexaraSchemeStyle.VIBRANT -> SchemeVibrant(hct, dark, 0.0)
        NexaraSchemeStyle.FIDELITY -> SchemeFidelity(hct, dark, 0.0)
        NexaraSchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, 0.0)
    }
    val base = if (dark) NexaraDarkColorScheme else NexaraLightColorScheme
    fun Int.composeColor() = Color(this)

    return base.copy(
        primary = scheme.primary.composeColor(),
        onPrimary = scheme.onPrimary.composeColor(),
        primaryContainer = scheme.primaryContainer.composeColor(),
        onPrimaryContainer = scheme.onPrimaryContainer.composeColor(),
        inversePrimary = scheme.inversePrimary.composeColor(),
        secondary = scheme.secondary.composeColor(),
        onSecondary = scheme.onSecondary.composeColor(),
        secondaryContainer = scheme.secondaryContainer.composeColor(),
        onSecondaryContainer = scheme.onSecondaryContainer.composeColor(),
        tertiary = scheme.tertiary.composeColor(),
        onTertiary = scheme.onTertiary.composeColor(),
        tertiaryContainer = scheme.tertiaryContainer.composeColor(),
        onTertiaryContainer = scheme.onTertiaryContainer.composeColor(),
        background = scheme.background.composeColor(),
        onBackground = scheme.onBackground.composeColor(),
        surface = scheme.surface.composeColor(),
        onSurface = scheme.onSurface.composeColor(),
        surfaceVariant = scheme.surfaceVariant.composeColor(),
        onSurfaceVariant = scheme.onSurfaceVariant.composeColor(),
        outline = scheme.outline.composeColor(),
        outlineVariant = scheme.outlineVariant.composeColor(),
        error = scheme.error.composeColor(),
        onError = scheme.onError.composeColor(),
        errorContainer = scheme.errorContainer.composeColor(),
        onErrorContainer = scheme.onErrorContainer.composeColor(),
        surfaceTint = scheme.primary.composeColor(),
        inverseSurface = scheme.inverseSurface.composeColor(),
        inverseOnSurface = scheme.inverseOnSurface.composeColor(),
        surfaceDim = scheme.surfaceDim.composeColor(),
        surfaceBright = scheme.surfaceBright.composeColor(),
        surfaceContainerLowest = scheme.surfaceContainerLowest.composeColor(),
        surfaceContainerLow = scheme.surfaceContainerLow.composeColor(),
        surfaceContainer = scheme.surfaceContainer.composeColor(),
        surfaceContainerHigh = scheme.surfaceContainerHigh.composeColor(),
        surfaceContainerHighest = scheme.surfaceContainerHighest.composeColor(),
    )
}

internal fun ColorScheme.withPureBlackSurface(enabled: Boolean): ColorScheme =
    if (!enabled) this else copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
    )
