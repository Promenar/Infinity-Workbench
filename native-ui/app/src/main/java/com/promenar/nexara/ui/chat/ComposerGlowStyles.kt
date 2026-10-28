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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.promenar.nexara.ui.common.motion.GenerationMotion
import com.promenar.nexara.ui.common.motion.GenerationMotionPhase
import com.promenar.nexara.ui.common.motion.LedBatch
import com.promenar.nexara.ui.common.motion.LocalGenerationMotion
import com.promenar.nexara.ui.common.motion.MotionPalette
import com.promenar.nexara.ui.common.motion.actOn
import com.promenar.nexara.ui.common.motion.breath
import com.promenar.nexara.ui.common.motion.clamp01
import com.promenar.nexara.ui.common.motion.evenBlink
import com.promenar.nexara.ui.common.motion.fract
import com.promenar.nexara.ui.common.motion.heartbeat
import com.promenar.nexara.ui.common.motion.motionHash
import com.promenar.nexara.ui.common.motion.pingPong
import com.promenar.nexara.ui.common.motion.rememberMotionPalette
import com.promenar.nexara.ui.common.motion.slowBlink
import com.promenar.nexara.ui.common.motion.smoothStep
import com.promenar.nexara.ui.theme.ComposerGlowStyle
import com.promenar.nexara.ui.theme.NexaraPureWhite
import kotlin.math.PI
import kotlin.math.abs
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

/** 贴着浮岛边缘的模糊光圈，画在浮岛底色之下；[colorB] 不同时为主色到第三色的斜向渐变。 */
internal fun DrawScope.drawHaloRing(alpha: Float, blur: Float, width: Float, cornerPx: Float, colorA: Color, colorB: Color, paints: ComposerOutlinePaints) {
    if (alpha <= 0.004f) return
    val paint = paints.halo
    paint.shader = if (colorA == colorB) null else LinearGradient(0f, 0f, size.width, size.height, colorA.toArgb(), colorB.toArgb(), Shader.TileMode.CLAMP)
    paint.color = colorA.toArgb()
    paint.alpha = (255 * clamp01(alpha)).roundToInt()
    paint.strokeWidth = width * density
    paint.maskFilter = paints.blur(blur * density)
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx, paint) }
}

/** 贴边描边，内缩半个线宽；[colorB] 不同时为主色到第三色的斜向渐变。 */
internal fun DrawScope.drawEdgeStroke(alpha: Float, width: Float, cornerPx: Float, colorA: Color, colorB: Color, paints: ComposerOutlinePaints) {
    if (alpha <= 0.004f) return
    val paint = paints.stroke
    val inset = width * density / 2
    paint.shader = if (colorA == colorB) null else LinearGradient(0f, 0f, size.width, size.height, colorA.toArgb(), colorB.toArgb(), Shader.TileMode.CLAMP)
    paint.color = colorA.toArgb()
    paint.alpha = (255 * clamp01(alpha)).roundToInt()
    paint.strokeWidth = width * density
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, cornerPx - inset, cornerPx - inset, paint) }
}

/** 潮汐呼吸在 [phase] 下的呼吸量（0..1）与渐变两端颜色。 */
private fun breathePhase(phase: GenerationMotionPhase, state: ComposerGlowState, palette: MotionPalette, colorA: Color, colorB: Color): Triple<Float, Color, Color> {
    val t = state.timeSeconds
    return when (phase) {
        GenerationMotionPhase.WAIT -> Triple(breath(t, 3.6f) * 0.75f, colorA, palette.mid)
        GenerationMotionPhase.STREAM -> Triple((0.5f - 0.5f * cos(2 * PI.toFloat() * state.breathPhase)) * (0.7f + 0.3f * state.act), colorA, colorB)
        GenerationMotionPhase.TOOL -> Triple(min(1f, heartbeat(t)), colorB, colorB)
        GenerationMotionPhase.APPROVAL -> Triple(slowBlink(t) * 0.85f, colorB, colorB)
        GenerationMotionPhase.RETRY -> Triple(0.35f * evenBlink(t), colorA, colorA)
    }
}

/**
 * 潮汐呼吸的外圈光晕：画在浮岛底色之下。首字前 3.6 秒一次的慢呼吸；流式时呼吸随速率加快，最快约 1.3 秒；
 * 工具执行时第三色心跳式双拍；等待审批时第三色慢闪；重试时不画光晕。
 */
internal fun DrawScope.drawBreatheHalo(gen: Float, state: ComposerGlowState, motion: GenerationMotion, cornerPx: Float, palette: MotionPalette, colorA: Color, colorB: Color, paints: ComposerOutlinePaints) {
    for (phase in GenerationMotionPhase.entries) {
        val weight = motion.weight(phase)
        if (weight <= 0.004f || phase == GenerationMotionPhase.RETRY) continue
        val (b, c1, c2) = breathePhase(phase, state, palette, colorA, colorB)
        drawHaloRing(gen * weight * (0.15f + 0.6f * b), 8 + 12 * b, 5 + 8 * b, cornerPx, c1, c2, paints)
    }
}

/** 潮汐呼吸的贴边描边，透明度随各阶段的呼吸涨落。 */
internal fun DrawScope.drawBreatheStroke(gen: Float, state: ComposerGlowState, motion: GenerationMotion, cornerPx: Float, palette: MotionPalette, colorA: Color, colorB: Color, paints: ComposerOutlinePaints) {
    for (phase in GenerationMotionPhase.entries) {
        val weight = motion.weight(phase)
        if (weight <= 0.004f) continue
        val (b, c1, c2) = breathePhase(phase, state, palette, colorA, colorB)
        val alpha = if (phase == GenerationMotionPhase.RETRY) 0.1f + 0.5f * b else 0.35f + 0.65f * b
        drawEdgeStroke(gen * weight * alpha, 1.6f, cornerPx, c1, c2, paints)
    }
}

/**
 * 灯语样式外面那圈安静的描边，视线交给浮岛里的灯：首字前与流式为主色呼吸，工具执行为第三色快呼吸，
 * 等待审批为第三色慢闪，重试为压暗的等间隔闪。
 */
internal fun DrawScope.drawPhaseBorder(gen: Float, time: Float, motion: GenerationMotion, cornerPx: Float, palette: MotionPalette, paints: ComposerOutlinePaints) {
    for (phase in GenerationMotionPhase.entries) {
        val weight = motion.weight(phase)
        if (weight <= 0.004f) continue
        val (color, alpha) = when (phase) {
            GenerationMotionPhase.WAIT -> palette.a to 0.12f + 0.18f * breath(time, 3.2f)
            GenerationMotionPhase.STREAM -> palette.a to 0.15f + 0.2f * breath(time, 2.6f)
            GenerationMotionPhase.TOOL -> palette.b to 0.2f + 0.25f * breath(time, 1.2f)
            GenerationMotionPhase.APPROVAL -> palette.b to 0.25f + 0.5f * slowBlink(time)
            GenerationMotionPhase.RETRY -> palette.a to 0.08f + 0.17f * evenBlink(time)
        }
        drawEdgeStroke(gen * weight * alpha, 1f, cornerPx, color, color, paints)
    }
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
    val ledLevel = FloatArray(6)
    val ledWarm = BooleanArray(6)
    val packets = FloatArray(15)
    var screenLit = FloatArray(0)
    var screenAmber = BooleanArray(0)
}

/**
 * 浮岛内的灯效层：放在浮岛底色之上、输入内容之下，只在网孔背光、底沿数据流、点阵波形屏与扫光点阵四种样式下绘制，
 * 按当前生成阶段（[LocalGenerationMotion]）分别绘制并交叉淡入淡出。[state] 的生成进度为 0 时什么都不画。
 */
@Composable
internal fun ComposerIslandLights(state: ComposerGlowState, style: ComposerGlowStyle, modifier: Modifier = Modifier) {
    if (style == ComposerGlowStyle.AURORA || style == ComposerGlowStyle.BREATHE) return
    val palette = rememberMotionPalette()
    val motion = LocalGenerationMotion.current
    val painter = remember { ComposerLightsPainter() }
    Canvas(modifier.clearAndSetSemantics {}) {
        val gen = state.generating.value
        if (gen <= 0f) return@Canvas
        val time = state.timeSeconds
        drawIntoCanvas { canvas ->
            for (phase in GenerationMotionPhase.entries) {
                val weight = motion.weight(phase)
                if (weight <= 0.004f) continue
                val alpha = gen * weight
                when (style) {
                    ComposerGlowStyle.MESH -> drawMesh(canvas, phase, alpha, time, state, palette, painter)
                    ComposerGlowStyle.BOTTOM_STRIP -> drawBottomStrip(canvas, phase, alpha, time, state, motion, palette, painter)
                    ComposerGlowStyle.SCOPE_SCREEN -> drawScopeScreen(canvas, phase, alpha, time, motion, palette, painter)
                    ComposerGlowStyle.SWEEP_GRID -> drawSweepGrid(canvas, phase, alpha, time, state, palette, painter)
                    else -> Unit
                }
            }
        }
    }
}

/**
 * 网孔背光：浮岛右半像服务器前面板的冲孔网罩，几颗状态灯藏在网后，光从网孔里透出来。
 * 首字前只有一颗 LINK 灯在网后呼吸；流式时网后 ACT 灯按速率乱闪；工具执行时一颗第三色灯在网后来回寻道；
 * 等待审批时网后整排第三色灯慢闪；重试时只剩一颗灯等间隔闪。
 */
private fun DrawScope.drawMesh(canvas: ComposeCanvas, phase: GenerationMotionPhase, alpha: Float, t: Float, state: ComposerGlowState, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val w = size.width
    val h = size.height
    val (sx, sy) = composerStopCenter(w, h, k)
    val cy = h / 2
    val x0 = w * 0.46f
    val x1 = sx - 30 * k
    val warm = phase == GenerationMotionPhase.TOOL || phase == GenerationMotionPhase.APPROVAL
    val seek = 1 + (pingPong(t / 1.1f) * 4).roundToInt()
    for (i in 0 until 6) {
        p.ledX[i] = x0 + (x1 - x0) * (i / 5f)
        p.ledY[i] = cy + (if (i % 2 == 1) 7 else -7) * k
        p.ledWarm[i] = false
        p.ledLevel[i] = when (phase) {
            GenerationMotionPhase.WAIT -> if (i == 0) 0.3f + 0.7f * breath(t, 1.6f) else 0f
            GenerationMotionPhase.STREAM -> {
                p.ledWarm[i] = i == 3
                if (i == 0 || actOn(i, t, state.act, i * 2.1f)) 1f else 0f
            }
            GenerationMotionPhase.TOOL -> when (i) {
                seek -> 1f.also { p.ledWarm[i] = true }
                0 -> 0.45f
                else -> 0f
            }
            GenerationMotionPhase.APPROVAL -> (if (slowBlink(t) > 0.5f) 1f else 0.3f).also { p.ledWarm[i] = true }
            GenerationMotionPhase.RETRY -> if (i == 0) 0.6f * evenBlink(t) else 0f
        }
    }
    val native = canvas.nativeCanvas
    val radius = 22 * k
    for (i in 0 until 6) {
        val level = p.ledLevel[i]
        if (level <= 0f) continue
        val color = if (p.ledWarm[i]) palette.b else palette.a
        p.glowPaint.shader = RadialGradient(p.ledX[i], p.ledY[i], radius, color.copy(alpha = clamp01((if (palette.dark) 0.22f else 0.16f) * level * alpha)).toArgb(), color.copy(alpha = 0f).toArgb(), Shader.TileMode.CLAMP)
        native.drawCircle(p.ledX[i], p.ledY[i], radius, p.glowPaint)
    }
    val pitch = 4.2f * k
    val holeRadius = 0.85f * k
    val sigma2 = 2 * (8 * k).pow(2)
    val base = if (phase == GenerationMotionPhase.RETRY) 0.05f else 0.1f
    p.holes.forEach { it.rewind() }
    var row = 0
    var y = pitch / 2
    while (y < h) {
        var x = if (row % 2 == 1) pitch / 2 else 0f
        while (x < w) {
            val fade = smoothStep(0.32f, 0.6f, x / w)
            if (fade > 0f && hypot(x - sx, y - sy) >= 21 * k) {
                var v = base
                for (i in 0 until 6) if (p.ledLevel[i] > 0f) {
                    val dx = x - p.ledX[i]
                    val dy = y - p.ledY[i]
                    v += p.ledLevel[i] * exp(-(dx * dx + dy * dy) / sigma2)
                }
                v = min(1f, v) * fade
                if (v >= 0.03f) p.holes[min(6, (v * 7).toInt())].addCircle(x, y, holeRadius, Path.Direction.CW)
            }
            x += pitch
        }
        row++
        y += pitch * 0.866f
    }
    val holeColor = if (warm) palette.b else palette.a
    val hotColor = if (!warm) palette.hot else if (palette.dark) lerp(palette.b, NexaraPureWhite, 0.45f) else palette.b
    p.holePaint.blendMode = BlendMode.SRC_OVER
    for (i in 0 until 7) {
        if (p.holes[i].isEmpty) continue
        val color = if (i >= 5) hotColor else holeColor
        p.holePaint.color = color.copy(alpha = clamp01((i + 0.5f) / 7 * (if (palette.dark) 0.95f else 0.85f) * alpha)).toArgb()
        native.drawPath(p.holes[i], p.holePaint)
    }
}

/**
 * 底沿数据流：底沿内侧两排细灯，像 1U 服务器前面板下方的灯带，每排最左一颗是常亮的 LINK 灯。
 * 首字前一个光点在上排来回扫；流式时数据包的数量与流速跟随速率，速率为 0 时只剩 LINK；
 * 工具执行时第三色分段逐段点亮；等待审批时整条灯带第三色慢闪；重试时上排变成退避倒计时进度条。
 */
private fun DrawScope.drawBottomStrip(canvas: ComposeCanvas, phase: GenerationMotionPhase, alpha: Float, t: Float, state: ComposerGlowState, motion: GenerationMotion, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val act = state.act
    val (sx, _) = composerStopCenter(size.width, size.height, k)
    val x0 = ComposerIslandCorner.toPx() * 0.75f
    val x1 = sx - 26 * k
    val pitch = 3.6f * k
    val sz = 1.9f * k
    val gz = 3.8f * k
    val cols = max(8, ((x1 - x0) / pitch).toInt())
    val span = cols * pitch
    val sweep = 1 + pingPong(t / 2.4f) * (cols - 2)
    val flow = state.stripPhase
    val packetCount = if (act < 0.02f) 0 else 1 + (4 * act).roundToInt()
    val segments = 4
    val segmentLength = max(2, (cols - 1) / segments)
    val litSegment = floor(t * 4).toInt() % segments
    val blink = slowBlink(t) > 0.5f
    val retryLeft = motion.retryLeft
    val leds = p.leds4
    leds.reset()
    val packets = p.packets
    for (r in 0 until 2) {
        val y = size.height - (if (r == 0) 11 else 7) * k
        if (phase == GenerationMotionPhase.STREAM) {
            for (j in 0 until packetCount) {
                val len = (2 + floor(motionHash(r.toFloat(), j.toFloat()) * 5)) * pitch
                val speed = (28 + motionHash(j.toFloat(), r + 3f) * 46) * k
                val head = fract(motionHash(j.toFloat(), r + 7f) + flow * speed / (span + len)) * (span + len)
                packets[j * 3] = head - len
                packets[j * 3 + 1] = head
                packets[j * 3 + 2] = if (motionHash(j.toFloat(), r + 11f) < 0.2f) 1f else 0f
            }
        }
        for (c in 0 until cols) {
            val px = c * pitch + pitch / 2
            val x = x0 + px
            var intensity = 0f
            var amber = false
            if (c == 0) {
                intensity = when (phase) {
                    GenerationMotionPhase.APPROVAL -> 0f
                    GenerationMotionPhase.RETRY -> 0.5f * evenBlink(t)
                    else -> 0.85f
                }
            } else when (phase) {
                GenerationMotionPhase.WAIT -> if (r == 0) {
                    val d = abs(c - sweep)
                    intensity = if (d < 0.5f) 1f else if (d < 1.5f) 0.45f else if (d < 2.5f) 0.18f else 0f
                }
                GenerationMotionPhase.STREAM -> {
                    for (j in 0 until packetCount) if (px > packets[j * 3] && px <= packets[j * 3 + 1]) {
                        intensity = 1f
                        amber = packets[j * 3 + 2] > 0f
                        break
                    }
                    if (intensity == 1f && motionHash(c * 7f + r, floor(t * (18 + 24 * act))) < 0.2f) intensity = 0.2f
                }
                GenerationMotionPhase.TOOL -> {
                    val segment = (c - 1) / segmentLength
                    val inSegment = (c - 1) % segmentLength
                    intensity = if (segment == litSegment && inSegment < segmentLength - 1) 1f else 0f
                    amber = true
                }
                GenerationMotionPhase.APPROVAL -> {
                    intensity = if (blink) 0.9f else 0.28f
                    amber = true
                }
                GenerationMotionPhase.RETRY -> if (r == 0) intensity = if ((c - 1f) / (cols - 1) < retryLeft) 0.45f else 0f
            }
            if (intensity < 0.1f) leds.offDot(x, y, sz) else leds.dot(if (amber) 1 else 0, intensity, x, y, sz, gz)
        }
    }
    leds.draw(canvas.nativeCanvas, palette.leds, palette.dark, blur = 3 * k, offColor = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.16f), alpha = alpha)
}

/** 3×5 点阵字形，自上而下，每行三位。 */
private val ScreenGlyphs: Map<Char, IntArray> = mapOf(
    '0' to intArrayOf(0b111, 0b101, 0b101, 0b101, 0b111),
    '1' to intArrayOf(0b010, 0b110, 0b010, 0b010, 0b111),
    '2' to intArrayOf(0b111, 0b001, 0b111, 0b100, 0b111),
    '3' to intArrayOf(0b111, 0b001, 0b111, 0b001, 0b111),
    '4' to intArrayOf(0b101, 0b101, 0b111, 0b001, 0b001),
    '5' to intArrayOf(0b111, 0b100, 0b111, 0b001, 0b111),
    '6' to intArrayOf(0b111, 0b100, 0b111, 0b101, 0b111),
    '7' to intArrayOf(0b111, 0b001, 0b010, 0b010, 0b010),
    '8' to intArrayOf(0b111, 0b101, 0b111, 0b101, 0b111),
    '9' to intArrayOf(0b111, 0b101, 0b111, 0b001, 0b111),
    '!' to intArrayOf(0b010, 0b010, 0b010, 0b000, 0b010),
)

/**
 * 点阵波形屏：输入行右侧嵌一块 5 行的小点阵屏，像交换机前面板的流量显示。首字前底行一条带噪声的基线；
 * 流式时柱状图就是实际出字速率，不断左移，最新两列在闪、越旧越暗；工具执行时第三色扫描线来回扫过；
 * 等待审批时屏上显示第三色“!”并慢闪；重试时屏上显示倒计时秒数。
 */
private fun DrawScope.drawScopeScreen(canvas: ComposeCanvas, phase: GenerationMotionPhase, alpha: Float, t: Float, motion: GenerationMotion, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val rows = 5
    val pitch = 3.2f * k
    val sz = 2f * k
    val gz = 3.6f * k
    // 停在输入行（工具栏正上方那一行）的右端，与停止键右对齐。
    val x1 = size.width - 14 * k
    val cy = size.height - 72 * k
    val x0 = max(size.width * 0.5f, x1 - 92 * k)
    val cols = max(12, ((x1 - x0 - 4 * k) / pitch).toInt())
    val sw = cols * pitch + 4 * k
    val sh = rows * pitch + 4 * k
    val sx = x1 - sw
    val sy = cy - sh / 2
    if (sy < 0f) return
    val native = canvas.nativeCanvas
    p.panelPaint.style = Paint.Style.FILL
    p.panelPaint.color = palette.faceplate.copy(alpha = palette.faceplate.alpha * alpha).toArgb()
    native.drawRoundRect(sx, sy, sx + sw, sy + sh, 3 * k, 3 * k, p.panelPaint)
    p.panelPaint.style = Paint.Style.STROKE
    p.panelPaint.strokeWidth = 0.8f * k
    p.panelPaint.color = palette.outline.copy(alpha = 0.6f * alpha).toArgb()
    native.drawRoundRect(sx, sy, sx + sw, sy + sh, 3 * k, 3 * k, p.panelPaint)
    if (p.screenLit.size != cols * rows) {
        p.screenLit = FloatArray(cols * rows)
        p.screenAmber = BooleanArray(cols * rows)
    } else {
        p.screenLit.fill(0f)
        p.screenAmber.fill(false)
    }
    val lit = p.screenLit
    val amber = p.screenAmber
    fun set(c: Int, r: Int, intensity: Float, isAmber: Boolean) {
        if (c < 0 || c >= cols || r < 0 || r >= rows) return
        val i = c * rows + r
        if (intensity > lit[i]) {
            lit[i] = intensity
            amber[i] = isAmber
        }
    }
    fun glyphs(text: String, intensity: Float, isAmber: Boolean) {
        val width = text.length * 4 - 1
        val c0 = (cols - width) / 2
        text.forEachIndexed { n, ch ->
            val glyph = ScreenGlyphs[ch] ?: return@forEachIndexed
            for (gy in 0 until 5) for (gx in 0 until 3) {
                if (glyph[gy] shr (2 - gx) and 1 == 1) set(c0 + n * 4 + gx, rows - 1 - gy, intensity, isAmber)
            }
        }
    }
    when (phase) {
        GenerationMotionPhase.WAIT -> for (c in 0 until cols) {
            set(c, 0, 0.35f, false)
            if (motionHash(c.toFloat(), floor(t * 6)) < 0.1f) set(c, 1, 0.3f, false)
        }
        GenerationMotionPhase.STREAM -> {
            val now = floor(t / 0.09f)
            for (c in 0 until cols) {
                val back = cols - 1 - c
                val level = clamp01(motion.historyLevel(back, now - back) + (motionHash(now - back, 1.3f) - 0.5f) * 0.1f)
                val height = if (level < 0.04f) 0 else max(1, (level * rows).roundToInt())
                val age = back.toFloat() / cols
                for (r in 0 until height) {
                    var intensity = (if (r == height - 1) 1f else 0.75f) * (1 - 0.7f * age)
                    if (c >= cols - 2 && motionHash(c * 5f + r, floor(t * 30)) < 0.3f) intensity *= 0.3f
                    set(c, r, intensity, r == rows - 1)
                }
            }
        }
        GenerationMotionPhase.TOOL -> {
            val pos = (pingPong(t / 1.2f) * (cols - 1)).roundToInt()
            for (c in 0 until cols) {
                set(c, 0, 0.25f, false)
                val d = abs(c - pos)
                if (d <= 1) for (r in 0 until rows) set(c, r, if (d == 0) 1f else 0.35f, true)
            }
        }
        GenerationMotionPhase.APPROVAL -> glyphs("!", if (slowBlink(t) > 0.5f) 1f else 0.35f, true)
        GenerationMotionPhase.RETRY -> glyphs(motion.retrySeconds.coerceIn(0, 99).toString(), 0.4f + 0.35f * evenBlink(t), false)
    }
    val leds = p.leds5
    leds.reset()
    for (c in 0 until cols) for (r in 0 until rows) {
        val x = sx + 2 * k + c * pitch + pitch / 2
        val y = sy + 2 * k + (rows - 1 - r) * pitch + pitch / 2
        val i = c * rows + r
        if (lit[i] < 0.1f) leds.offDot(x, y, sz) else leds.dot(if (amber[i]) 1 else 0, lit[i], x, y, sz, gz)
    }
    leds.draw(native, palette.leds, palette.dark, blur = 3 * k, offColor = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.14f), alpha = alpha)
}

/**
 * 扫光点阵：浮岛里藏着一整面看不见的灯珠，光带扫到哪里，哪里的灯珠就亮起来。首字前细光带 4.8 秒慢扫一次；
 * 流式时扫速与闪点密度随速率增加；工具执行时第三色竖条来回寻道；等待审批时停止键周围第三色灯慢闪；
 * 重试时光带停住、压暗等间隔闪。
 */
private fun DrawScope.drawSweepGrid(canvas: ComposeCanvas, phase: GenerationMotionPhase, alpha: Float, t: Float, state: ComposerGlowState, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val w = size.width
    val h = size.height
    val act = state.act
    val (sx, sy) = composerStopCenter(w, h, k)
    val pitch = 4.4f * k
    val sz = 1.5f * k
    val gz = 3.2f * k
    val cy = h / 2
    var bx = 0f
    var band = 14 * k
    var peak = 0.9f
    var slant = 0.7f
    var amberBand = false
    var sparkle = 0f
    var flicker = 0f
    when (phase) {
        GenerationMotionPhase.WAIT -> {
            bx = -40 * k + (w + 80 * k) * fract(t / 4.8f)
            band = 10 * k
            peak = 0.55f
        }
        GenerationMotionPhase.STREAM -> {
            bx = -40 * k + (w + 80 * k) * fract(state.sweepPhase)
            band = (10 + 6 * act) * k
            peak = 0.6f + 0.4f * act
            sparkle = 0.002f + 0.012f * act
            flicker = 0.1f + 0.2f * act
        }
        GenerationMotionPhase.TOOL -> {
            bx = 18 * k + (w - 80 * k) * pingPong(t / 1.4f)
            band = 5 * k
            slant = 0f
            amberBand = true
        }
        GenerationMotionPhase.RETRY -> {
            bx = w * 0.42f
            band = 10 * k
            peak = 0.4f * evenBlink(t)
        }
        GenerationMotionPhase.APPROVAL -> Unit
    }
    val pulse = if (slowBlink(t) > 0.5f) 0.9f else 0.25f
    val leds = p.leds6
    leds.reset()
    var id = 0
    var y = pitch / 2
    while (y < h) {
        var x = pitch / 2
        while (x < w) {
            val cell = id++
            val ds = hypot(x - sx, y - sy)
            if (ds < 21 * k) {
                x += pitch
                continue
            }
            var intensity: Float
            val isAmber: Boolean
            if (phase == GenerationMotionPhase.APPROVAL) {
                intensity = pulse * exp(-(ds - 21 * k) / (40 * k))
                isAmber = true
            } else {
                val d = (x - bx) + (y - cy) * slant
                intensity = if (abs(d) < band * 2.5f) peak * exp(-(d / band).pow(2)) else 0f
                if (intensity > 0.1f && flicker > 0f && motionHash(cell.toFloat(), floor(t * 30)) < flicker) intensity *= 0.3f
                if (intensity < 0.1f && sparkle > 0f && motionHash(cell.toFloat(), floor(t * 4 + motionHash(cell.toFloat(), 3f) * 4)) < sparkle) intensity = 0.6f
                isAmber = amberBand || (phase == GenerationMotionPhase.STREAM && motionHash(cell.toFloat(), 7.7f) < 0.08f)
            }
            if (intensity >= 0.06f) leds.dot(if (isAmber) 1 else 0, intensity * 0.9f, x, y, sz, gz)
            x += pitch
        }
        y += pitch
    }
    leds.draw(canvas.nativeCanvas, palette.leds, palette.dark, blur = 3 * k, alpha = alpha)
}
