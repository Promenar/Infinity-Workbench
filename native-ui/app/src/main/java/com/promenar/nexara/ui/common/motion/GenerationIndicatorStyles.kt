package com.promenar.nexara.ui.common.motion

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.GenerationIndicatorStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/** 各灯语指示的设计尺寸（缩放 1 时）；三点波浪的尺寸由调用方的圆点参数决定。 */
internal fun indicatorDesignSize(style: GenerationIndicatorStyle): DpSize = when (style) {
    GenerationIndicatorStyle.WAVE -> DpSize(28.dp, 10.dp)
    GenerationIndicatorStyle.PORTS -> DpSize(48.dp, 8.dp)
    GenerationIndicatorStyle.MATRIX -> DpSize(43.dp, 12.dp)
    GenerationIndicatorStyle.STACKED -> DpSize(47.dp, 12.dp)
    GenerationIndicatorStyle.BAYS -> DpSize(44.dp, 8.dp)
    GenerationIndicatorStyle.INFINITY_LINE -> DpSize(44.dp, 16.dp)
    GenerationIndicatorStyle.INFINITY_LED -> DpSize(46.dp, 16.dp)
}

/** 绘制灯语指示所需的可复用对象；每个指示实例持有一份，避免逐帧分配。 */
internal class IndicatorPainter {
    val leds = LedBatch(levels = 1, glowFrom = 0)
    val beads = LedBatch(levels = 5, glowFrom = 2)
    val infinityBeads: FloatArray by lazy { lemniscatePoints(24) }
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    val glowLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val point = FloatArray(2)
    val point2 = FloatArray(2)
    var glowBlur = -1f
}

/**
 * 以画布中心为原点绘制一种灯语指示。[k] 为每个设计单位（dp）对应的像素数，已含调用方缩放。
 * 交换机面板的灯语：常亮 = 在线，高频随机闪烁 = 正在收发，琥珀 = 另一种速率或告警；“生成中”对应正在收发。
 */
internal fun DrawScope.drawGenerationIndicator(
    style: GenerationIndicatorStyle,
    time: Float,
    k: Float,
    palette: MotionPalette,
    painter: IndicatorPainter,
) {
    val cx = size.width / 2
    val cy = size.height / 2
    when (style) {
        GenerationIndicatorStyle.WAVE -> Unit
        GenerationIndicatorStyle.PORTS -> drawPorts(cx, cy, time, k, palette, painter.leds)
        GenerationIndicatorStyle.MATRIX -> drawMatrix(cx, cy, time, k, palette, painter.leds)
        GenerationIndicatorStyle.STACKED -> drawStacked(cx, cy, time, k, palette, painter.leds)
        GenerationIndicatorStyle.BAYS -> drawBays(cx, cy, time, k, palette, painter.leds)
        GenerationIndicatorStyle.INFINITY_LINE -> drawInfinityLine(cx, cy, time, k, palette, painter)
        GenerationIndicatorStyle.INFINITY_LED -> drawInfinityLed(cx, cy, time, k, palette, painter)
    }
}

private fun DrawScope.faceplate(x: Float, y: Float, w: Float, h: Float, k: Float, palette: MotionPalette) {
    drawRoundRect(palette.faceplate, Offset(x, y), Size(w, h), CornerRadius(1.6f * k))
}

private fun DrawScope.flushLeds(leds: LedBatch, k: Float, palette: MotionPalette) {
    drawIntoCanvas { leds.draw(it.nativeCanvas, palette.leds, palette.dark, blur = 3f * k, offColor = palette.off) }
}

/** 三组网口灯：左灯常亮表示在线，右灯按 24–50Hz 随机闪烁表示正在吐字。 */
private fun DrawScope.drawPorts(cx: Float, cy: Float, t: Float, k: Float, palette: MotionPalette, leds: LedBatch) {
    val sz = 3.8f * k
    val inner = 2f * k
    val pad = 1.8f * k
    val pw = sz * 2 + inner + pad * 2
    val gap = 4f * k
    val total = pw * 3 + gap * 2
    leds.reset()
    var x = cx - total / 2
    for (p in 0 until 3) {
        faceplate(x, cy - sz / 2 - pad, pw, sz + pad * 2, k, palette)
        for (s in 0 until 2) {
            val lx = x + pad + s * (sz + inner)
            val ly = cy - sz / 2
            val id = p * 2 + s
            val on = s == 0 || actLit(id, t, smoothStep(0.2f, 0.8f, valueNoise(t * 2.3f + p * 1.9f)), 24 + motionHash(id.toFloat(), 8.1f) * 26)
            if (on) {
                val amber = s == 1 && motionHash(id.toFloat(), floor(t * 6)) < 0.08f
                leds.rect(if (amber) 1 else 0, 1f, lx, ly, sz, sz, k)
            } else {
                leds.offRect(lx, ly, sz, sz)
            }
        }
        x += pw + gap
    }
    flushLeds(leds, k, palette)
}

/** 10×3 点阵按负载场亮灭，偶有一列扫过，像 CPU 负载灯。 */
private fun DrawScope.drawMatrix(cx: Float, cy: Float, t: Float, k: Float, palette: MotionPalette, leds: LedBatch) {
    val cols = 10
    val rows = 3
    val pitch = 4.4f * k
    val sz = 2.8f * k
    val x0 = cx - (cols - 1) * pitch / 2
    val y0 = cy - (rows - 1) * pitch / 2
    val sweep = floor(t * 12).toInt().mod(cols + 5)
    leds.reset()
    for (c in 0 until cols) for (r in 0 until rows) {
        val field = valueNoise2(c * 0.55f + t * 1.9f, r * 1.1f - t * 1.2f)
        val flicker = motionHash(c * 7.1f + r * 3.3f, floor(t * (14 + motionHash(c.toFloat(), r.toFloat()) * 14)))
        val x = x0 + c * pitch - sz / 2
        val y = y0 + r * pitch - sz / 2
        if (flicker < field * 0.95f + (if (sweep == c) 0.6f else 0f) - 0.12f) {
            leds.rect(if (motionHash(c.toFloat(), r + 9f) < 0.14f) 1 else 0, 1f, x, y, sz, sz, 0.6f * k)
        } else {
            leds.offRect(x, y, sz, sz)
        }
    }
    flushLeds(leds, k, palette)
}

/** 上下两排共 16 个口：多数常亮，几个口在高频收发，个别口没插线，偶有琥珀色百兆口。 */
private fun DrawScope.drawStacked(cx: Float, cy: Float, t: Float, k: Float, palette: MotionPalette, leds: LedBatch) {
    val cols = 8
    val w = 3.8f * k
    val h = 2.2f * k
    val gx = 1.8f * k
    val gy = 3.4f * k
    val total = cols * w + (cols - 1) * gx
    val x0 = cx - total / 2
    val y0 = cy - (h * 2 + gy) / 2
    faceplate(x0 - 2 * k, y0 - 2 * k, total + 4 * k, h * 2 + gy + 4 * k, k, palette)
    leds.reset()
    for (c in 0 until cols) for (r in 0 until 2) {
        val id = c * 2 + r
        val x = x0 + c * (w + gx)
        val y = y0 + r * (h + gy)
        val linked = motionHash(id.toFloat(), 3.3f) > 0.13f
        val busy = motionHash(id.toFloat(), 4.4f) < 0.55f
        val amber = motionHash(id.toFloat(), 5.5f) < 0.16f
        val on = linked && (!busy || actLit(id, t, smoothStep(0.15f, 0.8f, valueNoise(t * 1.7f + id * 0.9f)), 22 + motionHash(id.toFloat(), 6f) * 28))
        if (on) leds.rect(if (amber) 1 else 0, 1f, x, y, w, h, 0.8f * k) else leds.offRect(x, y, w, h)
    }
    flushLeds(leds, k, palette)
}

/** 四个盘位托架：每个一颗常亮的电源灯和一条随读写乱闪的活动灯，像 NAS 前面板。 */
private fun DrawScope.drawBays(cx: Float, cy: Float, t: Float, k: Float, palette: MotionPalette, leds: LedBatch) {
    val n = 4
    val tw = 9.5f * k
    val th = 8f * k
    val gap = 2f * k
    val total = n * tw + (n - 1) * gap
    val x0 = cx - total / 2
    leds.reset()
    for (i in 0 until n) {
        val x = x0 + i * (tw + gap)
        val y = cy - th / 2
        drawRoundRect(palette.outline.copy(alpha = 0.9f), Offset(x, y), Size(tw, th), CornerRadius(1.4f * k), style = Stroke(0.8f * k))
        drawRect(palette.outline.copy(alpha = 0.35f), Offset(x + 1.6f * k, y + th - 2.4f * k), Size(tw - 3.2f * k, 0.8f * k))
        leds.rect(0, 1f, x + 1.6f * k, y + 1.8f * k, 1.8f * k, 1.8f * k, 0.6f * k)
        val on = actLit(i + 10, t, smoothStep(0.2f, 0.85f, valueNoise(t * 1.5f + i * 2.6f)), 20 + motionHash(i.toFloat(), 7f) * 30)
        val ax = x + 4.4f * k
        val ay = y + 2.1f * k
        if (on) {
            leds.rect(if (motionHash(i.toFloat(), floor(t * 3)) < 0.06f) 1 else 0, 1f, ax, ay, 3.6f * k, 1.2f * k, 0.6f * k)
        } else {
            leds.offRect(ax, ay, 3.6f * k, 1.2f * k)
        }
    }
    flushLeds(leds, k, palette)
}

/**
 * ∞ 描线：两个光头沿 8 字反向追逐、拖着渐隐的尾巴，每半圈在中心交汇闪一下；
 * 线宽在两环处粗、交叉处细，暗示莫比乌斯带的扭转。
 */
private fun DrawScope.drawInfinityLine(cx: Float, cy: Float, t: Float, k: Float, palette: MotionPalette, painter: IndicatorPainter) {
    val a = 20f * k
    val phase = t / 1.8f * Tau
    val p = painter.point
    val q = painter.point2
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val line = painter.linePaint
        line.blendMode = BlendMode.SRC_OVER
        line.strokeWidth = 0.9f * k
        line.color = palette.mid.copy(alpha = if (palette.dark) 0.16f else 0.22f).toArgb()
        for (i in 0 until 64) {
            lemniscateAt(i / 64f * Tau, p)
            lemniscateAt((i + 1) / 64f * Tau, q)
            native.drawLine(cx + a * p[0], cy + a * p[1], cx + a * q[0], cy + a * q[1], line)
        }
        if (painter.glowBlur != 2.5f * k) {
            painter.glowLinePaint.maskFilter = BlurMaskFilter(2.5f * k, BlurMaskFilter.Blur.NORMAL)
            painter.glowBlur = 2.5f * k
        }
        painter.glowLinePaint.blendMode = if (palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER
        for (pass in 0 until 2) {
            val paint = if (pass == 0) painter.glowLinePaint else line
            paint.blendMode = if (pass == 0 && palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER
            for (tail in 0 until 2) {
                val head = phase + tail * PiF
                val color = if (tail == 0) palette.a else palette.b
                val segments = 34
                for (j in segments - 1 downTo 0) {
                    val s0 = head - j.toFloat() / segments * 2.4f
                    val s1 = head - (j + 1f) / segments * 2.4f
                    val f = 1 - j.toFloat() / segments
                    lemniscateAt(s0, p)
                    lemniscateAt(s1, q)
                    paint.color = (if (j < 2) palette.hot else color).copy(alpha = f * f).toArgb()
                    paint.strokeWidth = (0.6f + 1.6f * f) * k * (0.55f + 0.45f * abs(cos(s0)))
                    native.drawLine(cx + a * q[0], cy + a * q[1], cx + a * p[0], cy + a * p[1], paint)
                }
            }
        }
        val flash = exp(-(abs(cos(phase)) / 0.18f).pow(2))
        if (flash > 0.03f) {
            val r = 7f * k
            painter.flashPaint.shader = RadialGradient(cx, cy, r, palette.hot.copy(alpha = flash).toArgb(), palette.mid.copy(alpha = 0f).toArgb(), Shader.TileMode.CLAMP)
            painter.flashPaint.blendMode = if (palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER
            native.drawCircle(cx, cy, r, painter.flashPaint)
        }
    }
}

/**
 * ∞ 灯珠：24 颗方灯沿 ∞ 等距排开，两个数据包一前一后沿 8 字跑圈，包里的灯按 ACT 节奏闪，经过交叉点时那几颗一起亮；
 * 每圈有一定概率换成琥珀色包。
 */
private fun DrawScope.drawInfinityLed(cx: Float, cy: Float, t: Float, k: Float, palette: MotionPalette, painter: IndicatorPainter) {
    val points = painter.infinityBeads
    val n = points.size / 2
    val a = 21f * k
    val sz = 2.3f * k
    val gz = 4.2f * k
    val period = 1.5f
    val tail = 6f
    val loop = floor(t / period)
    val heads = floatArrayOf(fract(t / period) * n, fract(t / period + 0.5f) * n)
    val amber = booleanArrayOf(motionHash(loop, 2.2f) < 0.25f, motionHash(loop, 4.4f) < 0.25f)
    val beads = painter.beads
    beads.reset()
    for (i in 0 until n) {
        val x = cx + points[i * 2] * a
        val y = cy + points[i * 2 + 1] * a
        var intensity = 0f
        var isAmber = false
        for (h in heads.indices) {
            val d = (heads[h] - i).mod(n.toFloat())
            if (d < tail) {
                val v = (1 - d / tail).pow(1.5f)
                if (v > intensity) {
                    intensity = v
                    isAmber = amber[h]
                }
            }
        }
        if (intensity > 0.15f && intensity < 0.95f && motionHash(i.toFloat(), floor(t * 34)) < 0.25f) intensity *= 0.25f
        if (intensity < 0.1f && motionHash(i * 3.1f, floor(t * 5 + motionHash(i.toFloat(), 6f) * 5)) < 0.03f) intensity = 0.55f
        if (intensity < 0.08f) beads.offDot(x, y, sz) else beads.dot(if (isAmber) 1 else 0, intensity, x, y, sz, gz)
    }
    drawIntoCanvas {
        beads.draw(it.nativeCanvas, palette.leds, palette.dark, blur = 3f * k, offColor = palette.a.copy(alpha = if (palette.dark) 0.13f else 0.18f))
    }
}

private const val PiF = Math.PI.toFloat()
