package com.promenar.nexara.ui.common

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

enum class ExecutionMode(@StringRes val labelRes: Int) {
    AUTO(R.string.common_mode_auto),
    SEMI(R.string.common_mode_semi),
    MANUAL(R.string.common_mode_manual)
}

@Composable
fun ExecutionModeSelector(
    selected: ExecutionMode,
    onSelect: (ExecutionMode) -> Unit
) {
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NexaraColors.GlassSurface)
            .border(0.5.dp, NexaraColors.GlassBorder, shape)
            .padding(3.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            ExecutionMode.entries.forEach { mode ->
                val isSelected = mode == selected
                val bg by animateColorAsState(
                    targetValue = if (isSelected) NexaraColors.Primary else Color.Transparent,
                    animationSpec = tween(200)
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) NexaraColors.OnPrimary else NexaraColors.Secondary,
                    animationSpec = tween(200)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .clickable { onSelect(mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(mode.labelRes),
                        style = NexaraTypography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = textColor
                    )
                }
            }
        }
    }
}
