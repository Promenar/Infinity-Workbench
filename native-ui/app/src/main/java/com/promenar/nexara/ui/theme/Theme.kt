package com.promenar.nexara.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NexaraTypography,
        shapes = NexaraShapes,
        content = content
    )
}
