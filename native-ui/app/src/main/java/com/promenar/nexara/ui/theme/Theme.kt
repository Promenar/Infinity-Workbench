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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LocalNexaraDomainColors = staticCompositionLocalOf { NexaraDarkDomainColors }

val MaterialTheme.nexaraDomainColors: NexaraDomainColors
    @Composable
    @ReadOnlyComposable
    get() = LocalNexaraDomainColors.current

private fun dynamicDomainColors(colorScheme: ColorScheme): NexaraDomainColors = NexaraDomainColors(
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
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useDarkTheme -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            androidx.compose.material3.dynamicLightColorScheme(context)
        }
        useDarkTheme -> NexaraDarkColorScheme
        else -> NexaraLightColorScheme
    }
    val domainColors = remember(colorScheme, useDynamicColor, useDarkTheme) {
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                dynamicDomainColors(colorScheme)
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

    CompositionLocalProvider(LocalNexaraDomainColors provides domainColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NexaraTypography,
            shapes = NexaraShapes,
            content = content
        )
    }
}
