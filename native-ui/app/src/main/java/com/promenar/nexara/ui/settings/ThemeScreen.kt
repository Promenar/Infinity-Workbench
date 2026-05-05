package com.promenar.nexara.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

private enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

private val AccentPresets = listOf(
    Color(0xFF6366F1),
    Color(0xFF8B5CF6),
    Color(0xFFEC4899),
    Color(0xFFEF4444),
    Color(0xFFF59E0B),
    Color(0xFF10B981),
    Color(0xFF06B6D4),
    Color(0xFF3B82F6)
)

@Composable
fun ThemeScreen(
    onNavigateBack: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(ThemeMode.DARK) }
    var selectedAccent by remember { mutableStateOf(AccentPresets.first()) }

    NexaraPageLayout(
        title = stringResource(R.string.theme_title),
        onBack = onNavigateBack
    ) {
        Text(
            text = stringResource(R.string.theme_desc),
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.theme_mode),
            style = NexaraTypography.headlineMedium,
            color = NexaraColors.OnSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeMode.entries.forEach { mode ->
                val isSelected = selectedMode == mode
                val borderColor by animateColorAsState(
                    targetValue = if (isSelected) selectedAccent else NexaraColors.GlassBorder,
                    animationSpec = tween(200),
                    label = "modeBorder"
                )
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) selectedAccent.copy(alpha = 0.1f) else NexaraColors.SurfaceContainer.copy(alpha = 0.3f),
                    animationSpec = tween(200),
                    label = "modeBg"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(NexaraShapes.large)
                        .background(bgColor)
                        .border(1.dp, borderColor, NexaraShapes.large)
                        .clickable { selectedMode = mode }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (mode) {
                            ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                            ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                            ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                        },
                        style = NexaraTypography.labelMedium,
                        color = if (isSelected) selectedAccent else NexaraColors.OnSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.theme_accent_color),
            style = NexaraTypography.headlineMedium,
            color = NexaraColors.OnSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AccentPresets.forEach { color ->
                val isSelected = selectedAccent == color
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, NexaraColors.OnSurface, CircleShape)
                            } else {
                                Modifier.border(1.dp, NexaraColors.GlassBorder, CircleShape)
                            }
                        )
                        .clickable { selectedAccent = color }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.theme_preview),
            style = NexaraTypography.headlineMedium,
            color = NexaraColors.OnSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        NexaraGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaraShapes.large as RoundedCornerShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(NexaraShapes.medium)
                        .background(selectedAccent)
                        .padding(vertical = 12.dp, horizontal = 16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.theme_primary_btn),
                        style = NexaraTypography.labelMedium,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(NexaraShapes.medium)
                        .background(NexaraColors.SurfaceHigh)
                        .border(0.5.dp, selectedAccent.copy(alpha = 0.3f), NexaraShapes.medium)
                        .padding(vertical = 12.dp, horizontal = 16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.theme_secondary_btn),
                        style = NexaraTypography.labelMedium,
                        color = selectedAccent
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f).forEach { alpha ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                                .clip(NexaraShapes.small)
                                .background(selectedAccent.copy(alpha = alpha))
                        )
                    }
                }
            }
        }
    }
}
