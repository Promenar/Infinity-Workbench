package com.promenar.infinity.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

private val LocalInfinityDomainColors = staticCompositionLocalOf { InfinityDarkDomainColors }

val MaterialTheme.infinityDomainColors: InfinityDomainColors
    @Composable
    @ReadOnlyComposable
    get() = LocalInfinityDomainColors.current

private fun dynamicDomainColors(colorScheme: ColorScheme, dark: Boolean): InfinityDomainColors = InfinityDomainColors(
    success = colorScheme.tertiary,
    onSuccess = colorScheme.onTertiary,
    successContainer = colorScheme.tertiaryContainer,
    onSuccessContainer = colorScheme.onTertiaryContainer,
    warning = colorScheme.secondary,
    onWarning = colorScheme.onSecondary,
    warningContainer = colorScheme.secondaryContainer,
    onWarningContainer = colorScheme.onSecondaryContainer,
    info = colorScheme.primary,
    onInfo = colorScheme.onPrimary,
    infoContainer = colorScheme.primaryContainer,
    onInfoContainer = colorScheme.onPrimaryContainer,
    overlayContent = InfinityDarkDomainColors.overlayContent,
    ragReady = colorScheme.tertiary,
    ragIndexing = colorScheme.primary,
    ragError = colorScheme.error,
    ragPending = colorScheme.outline,
    codeKeyword = colorScheme.tertiary,
    codeDeclaration = colorScheme.primary,
    codeLiteral = colorScheme.secondary,
    codeFunction = colorScheme.tertiary,
    codeString = colorScheme.primary,
    codeComment = colorScheme.onSurfaceVariant,
    signalOn = if (dark) InfinityDarkSignalOn else InfinityLightSignalOn,
    signalPending = if (dark) InfinityDarkSignalPending else InfinityLightSignalPending,
    signalDown = if (dark) InfinityDarkSignalDown else InfinityLightSignalDown,
    isDark = dark,
)

@Composable
fun InfinityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    preferences: InfinityThemePreferences? = null,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (preferences?.mode) {
        InfinityThemeMode.SYSTEM -> darkTheme
        InfinityThemeMode.LIGHT -> false
        InfinityThemeMode.DARK -> true
        null -> true
    }
    val useDynamicColor = preferences?.colorSource == InfinityColorSource.DYNAMIC ||
        (preferences == null && dynamicColor)
    val seedColor = preferences?.primaryColor?.let { Color(it.toULong()) }
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useDarkTheme -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            androidx.compose.material3.dynamicLightColorScheme(context)
        }
        seedColor != null -> seededColorScheme(seedColor, useDarkTheme, preferences?.schemeStyle ?: InfinitySchemeStyle.TONAL_SPOT)
        // 默认主色选了非柔和的配色风格时，以首个预设色为种子按该风格生成。
        preferences != null && preferences.schemeStyle != InfinitySchemeStyle.TONAL_SPOT ->
            seededColorScheme(InfinityPresetColors.first(), useDarkTheme, preferences.schemeStyle)
        useDarkTheme -> InfinityDarkColorScheme
        else -> InfinityLightColorScheme
    }.withPureBlackSurface(useDarkTheme && preferences?.pureBlack == true)
    val domainColors = remember(colorScheme, useDynamicColor, useDarkTheme) {
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                dynamicDomainColors(colorScheme, useDarkTheme)
            }
            useDarkTheme -> InfinityDarkDomainColors
            else -> InfinityLightDomainColors
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDarkTheme
            insetsController.isAppearanceLightNavigationBars = !useDarkTheme
            
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    val currentDensity = LocalDensity.current
    val textScale = if (preferences?.textScaleEnabled == true) {
        preferences.textScale.coerceIn(0.8f, 1.4f)
    } else {
        1f
    }
    val scaledDensity = remember(currentDensity, textScale) {
        Density(currentDensity.density, currentDensity.fontScale * textScale)
    }

    val hapticsEnabled = preferences?.hapticsEnabled ?: false
    val statusHapticsEnabled = preferences?.statusHapticsEnabled ?: false
    val haptics = remember(view, hapticsEnabled, statusHapticsEnabled) {
        if (view.isInEditMode) com.promenar.infinity.ui.common.InfinityHaptics.None
        else com.promenar.infinity.ui.common.InfinityHaptics(view, hapticsEnabled, statusHapticsEnabled)
    }

    CompositionLocalProvider(
        LocalInfinityDomainColors provides domainColors,
        LocalInfinityMotionStyles provides (preferences?.motion ?: InfinityMotionStyles()),
        LocalDensity provides scaledDensity,
        com.promenar.infinity.ui.common.LocalInfinityHaptics provides haptics,
        // 长按菜单、文本选择等组件内置的触感同样服从应用开关。
        androidx.compose.ui.platform.LocalHapticFeedback provides
            if (preferences != null && !hapticsEnabled) SilentHapticFeedback else androidx.compose.ui.platform.LocalHapticFeedback.current,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = InfinityTypography,
            shapes = InfinityShapes,
        ) {
            // 默认点按反馈裁在圆角内，覆盖 MaterialTheme 提供的矩形涟漪。
            CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides InfinityIndication, content = content)
        }
    }
}

private object SilentHapticFeedback : androidx.compose.ui.hapticfeedback.HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) = Unit
}
