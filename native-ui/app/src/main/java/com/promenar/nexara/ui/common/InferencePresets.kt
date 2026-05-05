package com.promenar.nexara.ui.common

import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

data class InferencePreset(
    val id: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val iconTint: Color,
    val temperature: Float,
    val topP: Float
)

private val builtInPresets = listOf(
    InferencePreset(
        id = "precise",
        labelRes = R.string.common_preset_precise,
        icon = Icons.Rounded.Code,
        iconTint = Color(0xFFA78BFA),
        temperature = 0.2f,
        topP = 0.8f
    ),
    InferencePreset(
        id = "balanced",
        labelRes = R.string.common_preset_balanced,
        icon = Icons.Rounded.AutoFixHigh,
        iconTint = Color(0xFF22D3EE),
        temperature = 0.7f,
        topP = 0.9f
    ),
    InferencePreset(
        id = "creative",
        labelRes = R.string.common_preset_creative,
        icon = Icons.AutoMirrored.Rounded.MenuBook,
        iconTint = Color(0xFFFBBF24),
        temperature = 1.0f,
        topP = 0.95f
    )
)

@Composable
fun InferencePresets(
    selected: String,
    onSelect: (InferencePreset) -> Unit
) {
    val presets = remember { builtInPresets }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { preset ->
            val isSelected = preset.id == selected

            NexaraGlassCard(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(preset) }
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                2.dp,
                                NexaraColors.Primary,
                                RoundedCornerShape(12.dp)
                            )
                        } else {
                            Modifier
                        }
                    ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSelected) {
                                Modifier.background(
                                    NexaraColors.Primary.copy(alpha = 0.1f)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = preset.icon,
                        contentDescription = null,
                        tint = preset.iconTint,
                        modifier = Modifier.size(24.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(preset.labelRes),
                        style = NexaraTypography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.common_preset_temp_label, preset.temperature.toString()),
                        style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }
        }
    }
}
