package com.promenar.nexara.ui.chat

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.promenar.nexara.ui.common.motion.LedBatch
import com.promenar.nexara.ui.common.motion.MotionPalette
import com.promenar.nexara.ui.common.motion.clamp01
import com.promenar.nexara.ui.common.motion.fract
import com.promenar.nexara.ui.common.motion.motionHash
import com.promenar.nexara.ui.common.motion.rememberMotionPalette
import com.promenar.nexara.ui.common.motion.smoothStep
import com.promenar.nexara.ui.common.motion.valueNoise
import com.promenar.nexara.ui.theme.ComposerGlowStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 呼吸节奏：2.6 秒一个周期，0..1。 */
internal fun composerBreath(timeSeconds: Float): Float = 0.5f - 0.5f * cos(2 * PI.toFloat() * timeSeconds / 2.6f)

/**
 * 浮岛内的停止键位置：工具栏行高 40dp、底部留白 8dp、右侧留白 12dp，停止键 40dp，
 * 因此圆心距右边缘 32dp、距底边 28dp。灯效都避开以它为圆心、半径 21dp 的区域。
 */
internal fun composerStopCenter(width: Float, height: Float, dp: Float): Pair<Float, Float> = (width - 32 * dp) to (height - 28 * dp)

/** 浮岛外圈与描边所需的可复用画笔；每个浮岛持有一份。 */
internal class ComposerOutlinePaints {
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** 模糊半径按 1px 取整缓存，呼吸时不逐帧新建滤镜。 */
    private val blurs = HashMap<Int, BlurMaskFilter>()
    fun blur(radius: Float): BlurMaskFilter {
        val key = radius.roundToInt().coerceAtLeast(1)
        return blurs.getOrPut(key) { BlurMaskFilter(key.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    }
}

/** 潮汐呼吸的外圈光晕：画在浮岛底色之下，随呼吸扩散。 */
internal fun DrawScope.drawBreatheHalo(gen: Float, time: Float, cornerPx: Float, colorA: androidx.compose.ui.graphics.Color, colorB: androidx.compose.ui.graphics.Color, paints: ComposerOutlinePaints) {
    val b = composerBreath(time)
    val paint = paints.halo
    paint.shader = LinearGradient(0f, 0f, size.width, size.height, colorA.toArgb(), colorB.toArgb(), Shader.TileMode.CLAMP)
    paint.alpha = (255 * clamp01(gen * (0.18f + 0.6f * b))).roundToInt()
    paint.strokeWidth = (5 + 8 * b) * density
    paint.maskFilter = paints.blur((8 + 12 * b) * density)
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx, paint) }
}

/** 潮汐呼吸的贴边描边：主色到第三色的斜向渐变，透明度随呼吸涨落。 */
internal fun DrawScope.drawBreatheStroke(gen: Float, time: Float, cornerPx: Float, colorA: androidx.compose.ui.graphics.Color, colorB: androidx.compose.ui.graphics.Color, paints: ComposerOutlinePaints) {
    val b = composerBreath(time)
    val paint = paints.stroke
    val inset = 0.8f * density
    paint.shader = LinearGradient(0f, 0f, size.width, size.height, colorA.toArgb(), colorB.toArgb(), Shader.TileMode.CLAMP)
    paint.alpha = (255 * clamp01(gen * (0.4f + 0.6f * b))).roundToInt()
    paint.strokeWidth = 1.6f * density
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, cornerPx - inset, cornerPx - inset, paint) }
}

/** 灯语样式的描边只留一圈淡淡的呼吸，视线交给浮岛里的灯。 */
internal fun DrawScope.drawCalmBorder(gen: Float, time: Float, cornerPx: Float, color: androidx.compose.ui.graphics.Color, paints: ComposerOutlinePaints) {
    val paint = paints.stroke
    val inset = 0.5f * density
    paint.shader = null
    paint.color = color.copy(alpha = clamp01(gen * (0.15f + 0.2f * composerBreath(time)))).toArgb()
    paint.strokeWidth = density
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, cornerPx - inset, cornerPx - inset, paint) }
}

/** 浮岛内灯效的可复用绘制对象。 */
internal class ComposerLightsPainter {
    val leds4 = LedBatch(levels = 4, glowFrom = 1)
    val leds5 = LedBatch(levels = 5, glowFrom = 2)
    val leds6 = LedBatch(levels = 6, glowFrom = 3)
    val holes = Array(7) { Path() }
    val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val ledX = FloatArray(6)
    val ledY = FloatArray(6)
    val ledOn = BooleanArray(6)
}

/**
 * 浮岛内的灯效层：放在浮岛底色之上、输入内容之下，只在网孔背光、底沿数据流、点阵波形屏与扫光点阵四种样式下绘制。
 * [state] 的生成进度为 0 时什么都不画。
 */
@Composable
internal fun ComposerIslandLights(state: ComposerGlowState, style: ComposerGlowStyle, modifier: Modifier = Modifier) {
    if (style == ComposerGlowStyle.AURORA || style == ComposerGlowStyle.BREATHE) return
    val palette = rememberMotionPalette()
    val painter = remember { ComposerLightsPainter() }
    Canvas(modifier.clearAndSetSemantics {}) {
        val gen = state.generating.value
        if (gen <= 0f) return@Canvas
        val time = state.timeSeconds
        drawIntoCanvas { canvas ->
            when (style) {
                ComposerGlowStyle.MESH -> drawMesh(canvas, gen, time, palette, painter)
                ComposerGlowStyle.BOTTOM_STRIP -> drawBottomStrip(canvas, gen, time, palette, painter)
                ComposerGlowStyle.SCOPE_SCREEN -> drawScopeScreen(canvas, gen, time, palette, painter)
                ComposerGlowStyle.SWEEP_GRID -> drawSweepGrid(canvas, gen, time, palette, painter)
                else -> Unit
            }
        }
    }
}

/** 网孔背光：浮岛右半像服务器前面板的冲孔网罩，几颗状态灯藏在网后闪烁，光从网孔里透出来。 */
private fun DrawScope.drawMesh(canvas: ComposeCanvas, gen: Float, t: Float, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val w = size.width
    val h = size.height
    val (sx, sy) = composerStopCenter(w, h, k)
    val cy = h / 2
    val x0 = w * 0.46f
    val x1 = sx - 30 * k
    for (i in 0 until 6) {
        p.ledX[i] = x0 + (x1 - x0) * (i / 5f)
        p.ledY[i] = cy + (if (i % 2 == 1) 7 else -7) * k
        p.ledOn[i] = i == 0 || run {
            val burst = smoothStep(0.25f, 0.8f, valueNoise(t * 1.8f + i * 2.1f))
            motionHash(i * 5.7f, floor(t * (22 + motionHash(i.toFloat(), 4f) * 26))) < 0.15f + 0.75f * burst
        }
    }
    val native = canvas.nativeCanvas
    val radius = 22 * k
    for (i in 0 until 6) {
        if (!p.ledOn[i]) continue
        val color = if (i == 3) palette.b else palette.a
        p.glowPaint.shader = RadialGradient(p.ledX[i], p.ledY[i], radius, color.copy(alpha = (if (palette.dark) 0.22f else 0.16f) * gen).toArgb(), color.copy(alpha = 0f).toArgb(), Shader.TileMode.CLAMP)
        native.drawCircle(p.ledX[i], p.ledY[i], radius, p.glowPaint)
    }
    val pitch = 4.2f * k
    val holeRadius = 0.85f * k
    val sigma2 = 2 * (8 * k).pow(2)
    p.holes.forEach { it.rewind() }
    var row = 0
    var y = pitch / 2
    while (y < h) {
        var x = if (row % 2 == 1) pitch / 2 else 0f
        while (x < w) {
            val fade = smoothStep(0.32f, 0.6f, x / w)
            if (fade > 0f && hypot(x - sx, y - sy) >= 21 * k) {
                var v = 0.12f
                for (i in 0 until 6) if (p.ledOn[i]) {
                    val dx = x - p.ledX[i]
                    val dy = y - p.ledY[i]
                    v += exp(-(dx * dx + dy * dy) / sigma2)
                }
                v = min(1f, v) * fade
                if (v >= 0.03f) p.holes[min(6, (v * 7).toInt())].addCircle(x, y, holeRadius, Path.Direction.CW)
            }
            x += pitch
        }
        row++
        y += pitch * 0.866f
    }
    p.holePaint.blendMode = BlendMode.SRC_OVER
    for (i in 0 until 7) {
        if (p.holes[i].isEmpty) continue
        val color = if (i >= 5) palette.hot else palette.a
        p.holePaint.color = color.copy(alpha = clamp01((i + 0.5f) / 7 * (if (palette.dark) 0.95f else 0.85f) * gen)).toArgb()
        native.drawPath(p.holes[i], p.holePaint)
    }
}

/**
 * 底沿数据流：底沿内侧两排细灯，像 1U 服务器前面板下方的灯带；长短不一的数据包从左往右流过，
 * 包里的位随机闪烁，每排最左一颗是常亮的 LINK 灯。
 */
private fun DrawScope.drawBottomStrip(canvas: ComposeCanvas, gen: Float, t: Float, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val (sx, _) = composerStopCenter(size.width, size.height, k)
    val x0 = ComposerIslandCorner.toPx() * 0.75f
    val x1 = sx - 26 * k
    val pitch = 3.6f * k
    val sz = 1.9f * k
    val gz = 3.8f * k
    val cols = max(4, ((x1 - x0) / pitch).toInt())
    val span = cols * pitch
    val leds = p.leds4
    leds.reset()
    val packets = FloatArray(15)
    for (r in 0 until 2) {
        val y = size.height - (if (r == 0) 11 else 7) * k
        for (j in 0 until 5) {
            val len = (2 + floor(motionHash(r.toFloat(), j.toFloat()) * 5)) * pitch
            val speed = (28 + motionHash(j.toFloat(), r + 3f) * 46) * k
            val head = fract(motionHash(j.toFloat(), r + 7f) + t * speed / (span + len)) * (span + len)
            packets[j * 3] = head - len
            packets[j * 3 + 1] = head
            packets[j * 3 + 2] = if (motionHash(j.toFloat(), r + 11f) < 0.2f) 1f else 0f
        }
        for (c in 0 until cols) {
            val px = c * pitch + pitch / 2
            val x = x0 + px
            var intensity = 0f
            var amber = false
            if (c == 0) {
                intensity = 0.85f
            } else {
                for (j in 0 until 5) if (px > packets[j * 3] && px <= packets[j * 3 + 1]) {
                    intensity = 1f
                    amber = packets[j * 3 + 2] > 0f
                    break
                }
            }
            if (intensity == 1f && motionHash(c * 7f + r, floor(t * 30)) < 0.2f) intensity = 0.2f
            if (intensity < 0.1f) leds.offDot(x, y, sz) else leds.dot(if (amber) 1 else 0, intensity, x, y, sz, gz)
        }
    }
    leds.draw(canvas.nativeCanvas, palette.leds, palette.dark, blur = 3 * k, offColor = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.16f), alpha = gen)
}

/**
 * 点阵波形屏：输入行右侧嵌一块小点阵屏，像交换机前面板的流量显示；5 行灯珠画出不断左移的输出速率柱状图，
 * 最新两列在闪，越旧的列越暗。柱高按时间槽取噪声，不保存历史。
 */
private fun DrawScope.drawScopeScreen(canvas: ComposeCanvas, gen: Float, t: Float, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val rows = 5
    val pitch = 3.2f * k
    val sz = 2f * k
    val gz = 3.6f * k
    // 停在输入行（工具栏正上方那一行）的右端，与停止键右对齐。
    val x1 = size.width - 14 * k
    val cy = size.height - 72 * k
    val x0 = max(size.width * 0.5f, x1 - 92 * k)
    val cols = max(6, ((x1 - x0 - 4 * k) / pitch).toInt())
    val sw = cols * pitch + 4 * k
    val sh = rows * pitch + 4 * k
    val sx = x1 - sw
    val sy = cy - sh / 2
    if (sy < 0f) return
    val native = canvas.nativeCanvas
    p.panelPaint.style = Paint.Style.FILL
    p.panelPaint.color = palette.faceplate.copy(alpha = palette.faceplate.alpha * gen).toArgb()
    native.drawRoundRect(sx, sy, sx + sw, sy + sh, 3 * k, 3 * k, p.panelPaint)
    p.panelPaint.style = Paint.Style.STROKE
    p.panelPaint.strokeWidth = 0.8f * k
    p.panelPaint.color = palette.outline.copy(alpha = 0.6f * gen).toArgb()
    native.drawRoundRect(sx, sy, sx + sw, sy + sh, 3 * k, 3 * k, p.panelPaint)
    val step = 0.09f
    val now = floor(t / step)
    val leds = p.leds5
    leds.reset()
    for (c in 0 until cols) {
        val slot = now - (cols - 1 - c)
        val age = (cols - 1 - c).toFloat() / cols
        val level = clamp01(0.2f + 0.65f * valueNoise(slot * 0.19f) + (motionHash(slot, 1.3f) - 0.5f) * 0.35f)
        val height = max(1, (level * rows).roundToInt())
        for (r in 0 until rows) {
            val x = sx + 2 * k + c * pitch + pitch / 2
            val y = sy + 2 * k + (rows - 1 - r) * pitch + pitch / 2
            if (r >= height) {
                leds.offDot(x, y, sz)
                continue
            }
            var intensity = (if (r == height - 1) 1f else 0.75f) * (1 - 0.7f * age)
            if (c >= cols - 2 && motionHash(c * 5f + r, floor(t * 30)) < 0.3f) intensity *= 0.3f
            leds.dot(if (r == rows - 1) 1 else 0, intensity, x, y, sz, gz)
        }
    }
    leds.draw(native, palette.leds, palette.dark, blur = 3 * k, offColor = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.14f), alpha = gen)
}

/**
 * 扫光点阵：浮岛里藏着一整面看不见的灯珠，一道斜向光带每 2.4 秒从左扫到右，扫到的灯珠亮起并随机闪烁，
 * 其余位置偶尔零星亮一颗。只画光带附近与零星亮起的灯珠。
 */
private fun DrawScope.drawSweepGrid(canvas: ComposeCanvas, gen: Float, t: Float, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val w = size.width
    val h = size.height
    val (sx, sy) = composerStopCenter(w, h, k)
    val pitch = 4.4f * k
    val sz = 1.5f * k
    val gz = 3.2f * k
    val band = 14 * k
    val cy = h / 2
    val bx = -40 * k + (w + 80 * k) * fract(t / 2.4f)
    val leds = p.leds6
    leds.reset()
    var id = 0
    var y = pitch / 2
    while (y < h) {
        var x = pitch / 2
        while (x < w) {
            val cell = id++
            if (hypot(x - sx, y - sy) < 21 * k) {
                x += pitch
                continue
            }
            val d = (x - bx) + (y - cy) * 0.7f
            var intensity = if (kotlin.math.abs(d) < band * 2.5f) exp(-(d / band).pow(2)) else 0f
            if (intensity > 0.1f && motionHash(cell.toFloat(), floor(t * 30)) < 0.25f) intensity *= 0.3f
            if (intensity < 0.1f && motionHash(cell.toFloat(), floor(t * 4 + motionHash(cell.toFloat(), 3f) * 4)) < 0.008f) intensity = 0.6f
            if (intensity >= 0.06f) leds.dot(if (motionHash(cell.toFloat(), 7.7f) < 0.08f) 1 else 0, intensity * 0.9f, x, y, sz, gz)
            x += pitch
        }
        y += pitch
    }
    leds.draw(canvas.nativeCanvas, palette.leds, palette.dark, blur = 3 * k, alpha = gen)
}
