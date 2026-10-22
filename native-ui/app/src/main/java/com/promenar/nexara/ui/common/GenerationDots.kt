package com.promenar.nexara.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.network.rememberAnimationsEnabled
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 一轮波浪的时长、相邻圆点的相位差，以及每个圆点在一轮中处于“鼓起”状态的比例。 */
internal const val GenerationDotsPeriodMillis = 1200
internal const val GenerationDotsStagger = 0.125f
private const val GenerationDotsActiveFraction = 0.6f
private const val GenerationDotsRestAlpha = 0.35f

/**
 * 第 [index] 个圆点在相位 [phase]（0..1）时的鼓起程度，0 为静息、1 为最高点。
 * 每个圆点只在一轮的前 60% 内做一次半正弦起落，其余时间静息，三点依次错开形成波浪。
 */
internal fun generationDotLift(phase: Float, index: Int): Float {
    val local = (phase - index * GenerationDotsStagger).mod(1f)
    if (local >= GenerationDotsActiveFraction) return 0f
    return sin(PI.toFloat() * local / GenerationDotsActiveFraction).coerceIn(0f, 1f)
}

/**
 * 两色在色相环上沿较短弧线的中点，饱和度与明度取平均。
 * 主色与第三色常为互补色相，直接混合 RGB 或感知色彩会落到灰色；沿色相环取中点保持鲜明。
 */
internal fun hueMidpoint(a: Color, b: Color): Color {
    val (hueA, satA, valA) = a.toHsv()
    val (hueB, satB, valB) = b.toHsv()
    // 一端是灰色时没有色相可言，直接取另一端的色相。
    val hue = when {
        satA < 0.01f -> hueB
        satB < 0.01f -> hueA
        else -> {
            var delta = hueB - hueA
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            (hueA + delta / 2f).mod(360f)
        }
    }
    return Color.hsv(hue, (satA + satB) / 2f, (valA + valB) / 2f, (a.alpha + b.alpha) / 2f)
}

private fun Color.toHsv(): Triple<Float, Float, Float> {
    val maxC = max(red, max(green, blue))
    val delta = maxC - min(red, min(green, blue))
    val hue = when {
        delta < 1e-4f -> 0f
        maxC == red -> 60f * ((green - blue) / delta).mod(6f)
        maxC == green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return Triple(hue, if (maxC <= 0f) 0f else delta / maxC, maxC)
}

/**
 * 生成中的三点波浪：三个小圆点依次上浮变亮，颜色从主色过渡到第三色。
 * 系统关闭动画或静态预览时显示静止的三点。读屏报“正在生成回复”。
 */
@Composable
fun GenerationDots(modifier: Modifier = Modifier, dotSize: Dp = 6.dp, gap: Dp = 5.dp, lift: Dp = 4.dp) {
    val description = stringResource(R.string.chat_cd_generating_response)
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val colors = remember(primary, tertiary) { listOf(primary, hueMidpoint(primary, tertiary), tertiary) }
    val phaseState = if (animate) {
        rememberInfiniteTransition(label = "generation_dots").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(GenerationDotsPeriodMillis, easing = LinearEasing), RepeatMode.Restart),
            label = "generation_dots_phase",
        )
    } else null
    Canvas(
        modifier
            .size(width = dotSize * 3 + gap * 2, height = dotSize + lift)
            .semantics { contentDescription = description },
    ) {
        val radius = dotSize.toPx() / 2f
        val step = dotSize.toPx() + gap.toPx()
        val restY = size.height - radius
        // 相位只在绘制阶段读取，动画只触发重绘。
        val phase = phaseState?.value
        for (index in 0..2) {
            val raised = if (phase == null) 0f else generationDotLift(phase, index)
            val alpha = if (phase == null) 1f else GenerationDotsRestAlpha + (1f - GenerationDotsRestAlpha) * raised
            drawCircle(
                color = colors[index],
                radius = radius,
                center = Offset(radius + step * index, restY - lift.toPx() * raised),
                alpha = alpha,
            )
        }
    }
}
