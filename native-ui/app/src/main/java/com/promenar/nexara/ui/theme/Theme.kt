package com.promenar.nexara.ui.theme

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

private val LocalNexaraDomainColors = staticCompositionLocalOf { NexaraDarkDomainColors }

val MaterialTheme.nexaraDomainColors: NexaraDomainColors
    @Composable
    @ReadOnlyComposable
    get() = LocalNexaraDomainColors.current

private fun dynamicDomainColors(colorScheme: ColorScheme, dark: Boolean): NexaraDomainColors = NexaraDomainColors(
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
    overlayContent = NexaraDarkDomainColors.overlayContent,
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
    signalOn = if (dark) NexaraDarkSignalOn else NexaraLightSignalOn,
    signalPending = if (dark) NexaraDarkSignalPending else NexaraLightSignalPending,
    signalDown = if (dark) NexaraDarkSignalDown else NexaraLightSignalDown,
    isDark = dark,
)

@Composable
fun NexaraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    preferences: NexaraThemePreferences? = null,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (preferences?.mode) {
        NexaraThemeMode.SYSTEM -> darkTheme
        NexaraThemeMode.LIGHT -> false
        NexaraThemeMode.DARK -> true
        null -> true
    }
    val useDynamicColor = preferences?.colorSource == NexaraColorSource.DYNAMIC ||
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
        seedColor != null -> seededColorScheme(seedColor, useDarkTheme)
        useDarkTheme -> NexaraDarkColorScheme
        else -> NexaraLightColorScheme
    }.withPureBlackSurface(useDarkTheme && preferences?.pureBlack == true)
    val domainColors = remember(colorScheme, useDynamicColor, useDarkTheme) {
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                dynamicDomainColors(colorScheme, useDarkTheme)
            }
            useDarkTheme -> NexaraDarkDomainColors
            else -> NexaraLightDomainColors
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
        if (view.isInEditMode) com.promenar.nexara.ui.common.NexaraHaptics.None
        else com.promenar.nexara.ui.common.NexaraHaptics(view, hapticsEnabled, statusHapticsEnabled)
    }

    CompositionLocalProvider(
        LocalNexaraDomainColors provides domainColors,
        LocalDensity provides scaledDensity,
        com.promenar.nexara.ui.common.LocalNexaraHaptics provides haptics,
        // 长按菜单、文本选择等组件内置的触感同样服从应用开关。
        androidx.compose.ui.platform.LocalHapticFeedback provides
            if (preferences != null && !hapticsEnabled) SilentHapticFeedback else androidx.compose.ui.platform.LocalHapticFeedback.current,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NexaraTypography,
            shapes = NexaraShapes,
            content = content
        )
    }
}

private object SilentHapticFeedback : androidx.compose.ui.hapticfeedback.HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) = Unit
}
