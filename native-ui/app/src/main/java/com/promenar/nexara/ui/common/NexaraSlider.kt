package com.promenar.nexara.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraColors

/**
 * Nexara 风格的优雅 Slider 组件
 * 移除了 MD3 默认臃肿的轨道和刻度点，采用更轻量化的设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexaraSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    trackHeight: Dp = 4.dp,
    thumbSize: Dp = 20.dp,
    activeColor: Color = NexaraColors.Primary,
    inactiveColor: Color = NexaraColors.GlassSurface,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isActive = isPressed || isDragged

    val thumbScale by animateFloatAsState(
        targetValue = if (isActive) 1.2f else 1f,
        label = "thumbScale"
    )

    val currentThumbSize by animateDpAsState(
        targetValue = if (isActive) thumbSize * 1.1f else thumbSize,
        label = "thumbSize"
    )

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            Box(
                modifier = Modifier
                    .size(currentThumbSize)
                    .shadow(if (isActive) 6.dp else 2.dp, CircleShape)
                    .background(Color.White, CircleShape)
                    .padding(2.dp)
                    .background(activeColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // 中间的白色小圆点，增加精致感
                Box(
                    modifier = Modifier
                        .size(thumbSize / 3)
                        .background(Color.White, CircleShape)
                )
            }
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(trackHeight),
                colors = SliderDefaults.colors(
                    activeTrackColor = activeColor,
                    inactiveTrackColor = inactiveColor,
                    activeTickColor = Color.Transparent, // 隐藏刻度点
                    inactiveTickColor = Color.Transparent
                ),
                enabled = enabled,
                drawStopIndicator = null
            )
        }
    )
}

/**
 * 整数版本的 NexaraSlider
 */
@Composable
fun NexaraSliderInt(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    NexaraSlider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.toInt()) },
        modifier = modifier,
        valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        steps = steps,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished
    )
}
