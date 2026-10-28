package com.promenar.infinity.ui.common.motion

import com.promenar.infinity.ui.theme.InfinityPureWhite
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

/**
 * 批量绘制方形灯珠：亮度量化为 [levels] 档，每档每色一条 Path，一帧只画几十次路径。
 * 第 [glowFrom] 档以上另画一层模糊光晕；深色主题光晕用叠加混合，灯芯偏白，像真正点亮的 LED。
 * 颜色 0 为主色、1 为琥珀/第三色。
 */
internal class LedBatch(private val levels: Int, private val glowFrom: Int = 2, private val colors: Int = 2) {
    private val core = Array(colors) { Array(levels) { Path() } }
    private val glow = Array(colors) { Array(levels) { Path() } }
    private val off = Path()
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var blurRadius = -1f

    fun reset() {
        for (c in 0 until colors) for (l in 0 until levels) {
            core[c][l].rewind()
            glow[c][l].rewind()
        }
        off.rewind()
    }

    private fun level(intensity: Float) = (intensity * levels).toInt().coerceIn(0, levels - 1)

    /** 以 (x, y) 为中心、边长 [size] 的灯，光晕边长 [glowSize]。 */
    fun dot(color: Int, intensity: Float, x: Float, y: Float, size: Float, glowSize: Float) {
        val l = level(intensity)
        core[color][l].addRect(x - size / 2, y - size / 2, x + size / 2, y + size / 2, Path.Direction.CW)
        if (l >= glowFrom) glow[color][l].addRect(x - glowSize / 2, y - glowSize / 2, x + glowSize / 2, y + glowSize / 2, Path.Direction.CW)
    }

    /** 左上角为 (x, y) 的矩形灯，光晕向四周外扩 [glowPad]。 */
    fun rect(color: Int, intensity: Float, x: Float, y: Float, w: Float, h: Float, glowPad: Float) {
        val l = level(intensity)
        core[color][l].addRect(x, y, x + w, y + h, Path.Direction.CW)
        if (l >= glowFrom) glow[color][l].addRect(x - glowPad, y - glowPad, x + w + glowPad, y + h + glowPad, Path.Direction.CW)
    }

    fun offDot(x: Float, y: Float, size: Float) = off.addRect(x - size / 2, y - size / 2, x + size / 2, y + size / 2, Path.Direction.CW)

    fun offRect(x: Float, y: Float, w: Float, h: Float) = off.addRect(x, y, x + w, y + h, Path.Direction.CW)

    /**
     * [palette] 依次为各颜色；[offColor] 为熄灭灯的底色（透明则不画）；[whitenFrom] 档起深色主题灯芯混入白色；
     * [glowAlpha] 为光晕相对灯芯的透明度。
     */
    fun draw(
        canvas: Canvas,
        palette: List<Color>,
        dark: Boolean,
        blur: Float,
        offColor: Color = Color.Transparent,
        whitenFrom: Int = 0,
        glowAlpha: Float = 0.8f,
        alpha: Float = 1f,
    ) {
        if (offColor.alpha > 0f) {
            corePaint.blendMode = BlendMode.SRC_OVER
            corePaint.color = offColor.copy(alpha = offColor.alpha * alpha).toArgb()
            canvas.drawPath(off, corePaint)
        }
        if (blur > 0f) {
            if (blur != blurRadius) {
                glowPaint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                blurRadius = blur
            }
            glowPaint.blendMode = if (dark) BlendMode.PLUS else BlendMode.SRC_OVER
            for (c in 0 until colors) for (l in glowFrom until levels) {
                if (glow[c][l].isEmpty) continue
                glowPaint.color = palette[c].copy(alpha = clamp01((l + 1f) / levels * glowAlpha * alpha)).toArgb()
                canvas.drawPath(glow[c][l], glowPaint)
            }
        }
        corePaint.blendMode = BlendMode.SRC_OVER
        for (c in 0 until colors) for (l in 0 until levels) {
            if (core[c][l].isEmpty) continue
            val base = palette[c]
            val tint = if (dark && l >= whitenFrom) lerp(base, InfinityPureWhite, 0.3f) else base
            corePaint.color = tint.copy(alpha = clamp01((l + 1f) / levels * alpha)).toArgb()
            canvas.drawPath(core[c][l], corePaint)
        }
    }
}

/** 动效时钟：秒数与上一帧间隔只在绘制阶段读取，推进时只重绘、不重组。 */
@Stable
internal class MotionClock {
    var time by mutableFloatStateOf(0f)
    var delta = 0f
}

/**
 * 逐帧推进的动效时钟；[running] 为 false 时停在当前时刻且不申请帧。
 * [speed] 可整体放慢或加快（设置页预览用 1）。
 */
@Composable
internal fun rememberMotionClock(running: Boolean, speed: Float = 1f): MotionClock {
    val clock = remember { MotionClock() }
    LaunchedEffect(running, speed) {
        if (!running) return@LaunchedEffect
        var last = -1L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last > 0) {
                    // 切到后台再回来时间隔很大，限制单帧推进量，粒子不会一下飞出去。
                    val delta = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f) * speed
                    clock.delta = delta
                    clock.time += delta
                }
                last = now
            }
        }
    }
    return clock
}
