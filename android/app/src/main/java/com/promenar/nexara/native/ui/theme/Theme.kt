package com.promenar.nexara.native.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
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
    surfaceVariant = NexaraColors.SurfaceVariant,
    onSurfaceVariant = NexaraColors.OnSurfaceVariant,
    surfaceTint = NexaraColors.SurfaceTint,
    inverseSurface = NexaraColors.InverseSurface,
    inverseOnSurface = NexaraColors.InverseOnSurface,
    error = NexaraColors.Error,
    onError = NexaraColors.OnError,
    errorContainer = NexaraColors.ErrorContainer,
    onErrorContainer = NexaraColors.OnErrorContainer,
    outline = NexaraColors.Outline,
    outlineVariant = NexaraColors.OutlineVariant,
)

@Composable
fun NexaraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Forced to false to maintain strict Stitch branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }
        else -> DarkColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            
            // Set true Edge-to-Edge transparency
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            
            // Handle dark/light icons depending on the theme (Stitch defaults to dark mode)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            
            // Allow drawing under the system bars (Immersive layout)
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
