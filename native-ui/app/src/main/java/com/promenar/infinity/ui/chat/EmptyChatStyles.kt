package com.promenar.infinity.ui.chat

import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.motion.LedBatch
import com.promenar.infinity.ui.common.motion.MobiusProjector
import com.promenar.infinity.ui.common.motion.MotionPalette
import com.promenar.infinity.ui.common.motion.Tau
import com.promenar.infinity.ui.common.motion.beadLevel
import com.promenar.infinity.ui.common.motion.burstAmount
import com.promenar.infinity.ui.common.motion.fibonacciSphere
import com.promenar.infinity.ui.common.motion.motionHash
import com.promenar.infinity.ui.common.motion.rememberMotionClock
import com.promenar.infinity.ui.common.motion.rememberMotionPalette
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.EmptyChatStyle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 莫比乌斯带的半径（像素）：横向占宽度的 80%，矮屏按高度收小。 */
internal fun mobiusRadius(widthPx: Float, heightPx: Float): Float = min(widthPx * 0.4f, heightPx * 0.55f)

/** 灯珠星球的半径（像素）。 */
internal fun globeRadius(widthPx: Float, heightPx: Float): Float = min(widthPx * 0.27f, heightPx * 0.16f)

/** 图形中心到提示语顶边的距离（dp）：字标留 56dp，莫比乌斯带与球体按各自尺寸往下让。 */
internal fun emptyChatPromptGapDp(style: EmptyChatStyle, widthDp: Float, heightDp: Float): Float = when (style) {
    EmptyChatStyle.FLOW, EmptyChatStyle.LED_WORDMARK -> 56f
    EmptyChatStyle.MOBIUS, EmptyChatStyle.MOBIUS_LED, EmptyChatStyle.MORPH, EmptyChatStyle.LED_MORPH -> mobiusRadius(widthDp, heightDp) * 0.62f + 20f
    EmptyChatStyle.LED_GLOBE -> globeRadius(widthDp, heightDp) + 24f
}

/** 一种空会话场景在两帧之间保留的状态。 */
private class EmptyChatScene(val style: EmptyChatStyle) {
    val gesture = EmptyChatGesture()
    val projector = MobiusProjector()
    val point = FloatArray(3)
    var lastTime = Float.NaN
    var burstTime = -100f
    /** 数据光带的时钟：点按散开时加速。 */
    var bandClock = 0f
    var flow: MobiusFlowParticles? = null
    var dust: FloatArray? = null
    var morph: MorphParticles? = null
    var morphMask: WordmarkMask? = null
    var ledMorph: LedMorphBeads? = null
    /** 灯珠变形的时间轴时钟（秒），见 [LedMorphTimeline]。 */
    var ledMorphClock = 0f
    val ripples = ArrayList<FloatArray>()
    val sphere: FloatArray by lazy { fibonacciSphere(GlobeBeads) }
    val beads = LedBatch(levels = 8, glowFrom = 4)
    val buckets = Array(15) { Path() }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 返回距上一帧的秒数；首帧为 0。 */
    fun advance(time: Float): Float {
        val delta = if (lastTime.isNaN()) 0f else (time - lastTime).coerceIn(0f, 0.1f)
        lastTime = time
        return delta
    }
}

private const val FlowParticles = 2000
private const val MorphParticleCount = 1100
private const val GlobeBeads = 700
private const val DustCount = 70

/**
 * 灯语与莫比乌斯类空会话动画：灯珠字标、粒子流环、灯珠环、字标变形与灯珠星球。颜色都跟随主题色。
 * 手指按住拖动转视角（字标除外），点按触发散开、波纹或跳到下一段变形；开始输入后整体压暗。
 * [particleScale] 缩减粒子数量，设置页的小预览用。纯装饰，不进入无障碍树。
 */
@Composable
internal fun LedEmptyChat(
    style: EmptyChatStyle,
    dispersed: Boolean,
    modifier: Modifier = Modifier,
    pointer: WordmarkPointer? = null,
    centerFraction: Float = WordmarkCenterFraction,
    particleScale: Float = 1f,
) {
    val wordmark = stringResource(R.string.brand_wordmark)
    val density = LocalDensity.current.density
    val inspection = LocalInspectionMode.current
    val animate = rememberAnimationsEnabled() && !inspection
    val palette = rememberMotionPalette()
    val clock = rememberMotionClock(running = animate)
    val scene = remember(style, particleScale) { EmptyChatScene(style) }
    val dim by animateFloatAsState(if (dispersed) 0.3f else 1f, tween(500), label = "empty_scene_dim")
    val needsMask = style == EmptyChatStyle.LED_WORDMARK || style == EmptyChatStyle.MORPH || style == EmptyChatStyle.LED_MORPH

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val centerY = heightPx * centerFraction
        // 字标栅格只按宽度重建；高度随键盘变化时绘制阶段整体平移。静态预览同步生成，截图才有确定内容。
        val maskHolder = remember { mutableStateOf<WordmarkMask?>(null) }
        if (needsMask && inspection && maskHolder.value == null) {
            maskHolder.value = rasterizeWordmark(wordmark, widthPx, heightPx, centerY, density)
        }
        LaunchedEffect(needsMask, wordmark, widthPx, density) {
            if (!needsMask || inspection) return@LaunchedEffect
            maskHolder.value = withContext(Dispatchers.Default) { rasterizeWordmark(wordmark, widthPx, heightPx, centerY, density) }
        }
        val origin = remember { floatArrayOf(0f, 0f) }
        Canvas(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInRoot()
                    origin[0] = position.x
                    origin[1] = position.y
                },
        ) {
            // 静止时停在 1.3 秒，画面固定。
            val time = if (animate) clock.time else 1.3f
            val delta = if (animate) scene.advance(time) else 0f
            val px = pointer?.root?.get(0)?.let { (it - origin[0]) / density } ?: Float.NaN
            val py = pointer?.root?.get(1)?.let { (it - origin[1]) / density } ?: Float.NaN
            val tapped = scene.gesture.update(px, py, time, delta)
            val mask = maskHolder.value
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.saveLayerAlpha(0f, 0f, size.width, size.height, (255 * dim).toInt())
                when (style) {
                    EmptyChatStyle.LED_WORDMARK -> if (mask != null) drawLedWordmark(scene, mask, centerY, time, delta, tapped, palette)
                    EmptyChatStyle.MOBIUS -> drawMobiusFlow(scene, centerY, time, delta, tapped, palette, particleScale)
                    EmptyChatStyle.MOBIUS_LED -> drawMobiusLed(scene, centerY, time, delta, tapped, palette, particleScale)
                    EmptyChatStyle.MORPH -> if (mask != null) drawMorph(scene, mask, centerY, time, delta, tapped, palette, particleScale)
                    EmptyChatStyle.LED_GLOBE -> drawLedGlobe(scene, centerY, time, delta, tapped, palette, particleScale)
                    EmptyChatStyle.LED_MORPH -> if (mask != null) drawLedMorph(scene, mask, centerY, time, delta, tapped, palette)
                    EmptyChatStyle.FLOW -> Unit
                }
                native.restore()
            }
        }
    }
}

private fun DrawScope.mobiusView(scene: EmptyChatScene, centerY: Float, time: Float): MobiusProjector =
    scene.projector.configure(
        radius = mobiusRadius(size.width, size.height),
        cx = size.width / 2,
        cy = centerY,
        yaw = 0.22f * sin(time * 0.3f) + scene.gesture.rotY,
        pitch = 0.78f + scene.gesture.rotX,
        burst = burstAmount(time - scene.burstTime),
    )

private fun DrawScope.flushBeads(scene: EmptyChatScene, palette: MotionPalette) {
    drawIntoCanvas { scene.beads.draw(it.nativeCanvas, palette.leds, palette.dark, blur = 2.5f * density, whitenFrom = 6, glowAlpha = 0.7f) }
}

/** 灯珠字标：字标排成灯珠点阵，笔画常亮打底；数据光带从左往右缓缓扫过，光带里的灯珠按 ACT 节奏闪。点按从指尖发出一圈波纹。 */
private fun DrawScope.drawLedWordmark(scene: EmptyChatScene, mask: WordmarkMask, centerY: Float, t: Float, delta: Float, tapped: Boolean, palette: MotionPalette) {
    val k = density
    val pitch = 4.2f * k
    val offsetY = centerY - mask.centerY
    if (tapped) scene.ripples += floatArrayOf(t, scene.gesture.tapX * k, scene.gesture.tapY * k)
    scene.ripples.removeAll { t - it[0] >= 1.6f || t < it[0] }
    scene.bandClock += delta
    val cols = (size.width / pitch).toInt()
    val rows = (size.height / pitch).toInt()
    val ox = (size.width - cols * pitch) / 2 + pitch / 2
    val oy = (size.height - rows * pitch) / 2 + pitch / 2
    val bead = pitch * 0.56f
    scene.beads.reset()
    for (r in 0 until rows) for (c in 0 until cols) {
        val x = ox + c * pitch
        val y = oy + r * pitch
        if (mask.at(x / k, (y - offsetY) / k) <= 100) continue
        val id = r * cols + c
        val band = (0.5f + 0.5f * sin(c * 0.2f - scene.bandClock * 2.2f)).pow(14)
        var level = beadLevel(id, t, band, 0.32f)
        for (ripple in scene.ripples) {
            val radius = (t - ripple[0]) * 260 * k
            if (kotlin.math.abs(hypot(x - ripple[1], y - ripple[2]) - radius) < pitch * 1.4f) level = maxOf(level, 1 - (t - ripple[0]) / 1.6f)
        }
        scene.beads.dot(if (level > 0.5f && motionHash(id.toFloat(), 4.4f) < 0.05f) 1 else 0, level, x, y, bead, bead * 1.8f)
    }
    flushBeads(scene, palette)
}

/**
 * 灯珠变形：灯珠字标与灯珠环之间往复变形（时间轴见 [LedMorphTimeline]）。两种形态灯珠数一致、按横向位置一一配对，
 * 变形时按横向位置错开出发，飞行途中带少量旋涡偏移。字标阶段数据光带从左往右扫过，环阶段两段光带沿 ∞ 流过，零星有琥珀灯。
 * 点按跳到下一形态；环阶段拖动转视角。系统关闭动画时时钟不走，停在静态字标。
 */
private fun DrawScope.drawLedMorph(scene: EmptyChatScene, mask: WordmarkMask, centerY: Float, t: Float, delta: Float, tapped: Boolean, palette: MotionPalette) {
    val k = density
    val pitch = 4.2f * k
    val radius = mobiusRadius(size.width, size.height)
    val beads = scene.ledMorph?.takeIf { it.mask === mask && it.width == size.width }
        ?: buildLedMorphBeads(mask, size.width, size.height, k, pitch, radius).also { scene.ledMorph = it }
    if (tapped) scene.ledMorphClock = LedMorphTimeline.skip(scene.ledMorphClock)
    scene.ledMorphClock += delta
    scene.bandClock += delta
    val clock = scene.ledMorphClock
    val progress = LedMorphTimeline.progress(clock)
    val gathering = LedMorphTimeline.stageAt(clock) == LedMorphTimeline.Stage.GATHER
    val view = mobiusView(scene, centerY, t)
    val offsetY = centerY - mask.centerY
    val wordBead = pitch * 0.56f
    val p = scene.point
    scene.beads.reset()
    for (i in 0 until beads.count) {
        val id = beads.id[i]
        val e = LedMorphTimeline.beadProgress(progress, gathering, beads.rank[i])
        val wx = beads.wordX[i]
        val wy = beads.wordY[i] + offsetY
        var x = wx
        var y = wy
        var level = 0f
        var bead = wordBead
        if (e < 1f) {
            val band = (0.5f + 0.5f * sin(beads.column[i] * 0.2f - scene.bandClock * 2.2f)).pow(14)
            level = beadLevel(id, t, band, 0.32f) * (1 - e)
            bead = wordBead * (1 - e)
        }
        if (e > 0f) {
            val u = beads.ringU[i]
            view.project(u, beads.ringV[i] * view.halfWidth, p)
            val swirl = sin(Math.PI.toFloat() * e) * 14 * k
            val angle = beads.angle[i] + t * 0.8f
            x = wx + (p[0] - wx) * e + cos(angle) * swirl
            y = wy + (p[1] - wy) * e + sin(angle) * swirl
            val band = (0.5f + 0.5f * sin(2 * u - scene.bandClock * 1.6f)).pow(14)
            level += beadLevel(id, t, band, 0.22f) * (0.45f + 0.55f * p[2]) * e
            bead += (1.2f + 1.0f * p[2]) * k * e
        }
        scene.beads.dot(if (level > 0.5f && motionHash(id.toFloat(), 4.4f) < 0.05f) 1 else 0, level, x, y, bead, bead * 1.8f)
    }
    flushBeads(scene, palette)
}

/** 粒子流环：两千颗粒子铺满带面顺着 ∞ 流动，按纵深分桶批量绘制；空中有少量缓缓上升的微尘。 */
private fun DrawScope.drawMobiusFlow(scene: EmptyChatScene, centerY: Float, t: Float, delta: Float, tapped: Boolean, palette: MotionPalette, particleScale: Float) {
    val k = density
    val count = (FlowParticles * particleScale).toInt().coerceAtLeast(200)
    val flow = scene.flow ?: MobiusFlowParticles(count).also { scene.flow = it }
    if (tapped) scene.burstTime = t
    flow.step(delta)
    val view = mobiusView(scene, centerY, t)
    val p = scene.point
    val depthBands = 5
    scene.buckets.forEach { it.rewind() }
    for (i in 0 until flow.count) {
        view.project(flow.u[i], flow.v[i] * view.halfWidth, p, flow.spread[i])
        val di = min(depthBands - 1, (p[2] * depthBands).toInt())
        val ci = min(2, ((cos(flow.u[i]) + 1) / 2 * 3).toInt())
        val s = (0.5f + 0.9f * p[2] + if (flow.edge[i]) 0.35f else 0f) * k
        scene.buckets[ci * depthBands + di].addRect(p[0] - s / 2, p[1] - s / 2, p[0] + s / 2, p[1] + s / 2, Path.Direction.CW)
    }
    val colors = listOf(palette.a, palette.mid, palette.b)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val paint = scene.fill
        paint.blendMode = if (palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER
        for (idx in 0 until 15) {
            if (scene.buckets[idx].isEmpty) continue
            val di = idx % depthBands
            paint.color = colors[idx / depthBands].copy(alpha = 0.22f + 0.78f * (di + 0.5f) / depthBands).toArgb()
            native.drawPath(scene.buckets[idx], paint)
        }
        val dust = scene.dust ?: FloatArray(DustCount * 3).also { array ->
            val random = Random(17)
            for (i in 0 until DustCount) {
                array[i * 3] = random.nextFloat() * size.width
                array[i * 3 + 1] = random.nextFloat() * size.height
                array[i * 3 + 2] = random.nextFloat() * Tau
            }
            scene.dust = array
        }
        paint.blendMode = BlendMode.SRC_OVER
        for (i in 0 until DustCount) {
            dust[i * 3 + 1] -= delta * 4 * k
            if (dust[i * 3 + 1] < -4 * k) dust[i * 3 + 1] = size.height + 4 * k
            val twinkle = 0.4f + 0.6f * sin(t * 1.3f + dust[i * 3 + 2])
            paint.color = palette.muted.copy(alpha = (0.35f * (0.2f + 0.4f * twinkle)).coerceIn(0f, 1f)).toArgb()
            native.drawRect(dust[i * 3], dust[i * 3 + 1], dust[i * 3] + 1.2f * k, dust[i * 3 + 1] + 1.2f * k, paint)
        }
    }
}

/** 灯珠环：带面上固定排着 7 列灯珠，平时暗着；两段数据光带沿 ∞ 缓缓流过，光带里的灯珠按 ACT 节奏乱闪，零星有琥珀灯。 */
private fun DrawScope.drawMobiusLed(scene: EmptyChatScene, centerY: Float, t: Float, delta: Float, tapped: Boolean, palette: MotionPalette, particleScale: Float) {
    val k = density
    if (tapped) scene.burstTime = t
    val view = mobiusView(scene, centerY, t)
    scene.bandClock += delta * (1 + 3 * view.burst)
    val columns = (170 * particleScale).toInt().coerceAtLeast(60)
    val lanes = 7
    val p = scene.point
    scene.beads.reset()
    for (i in 0 until columns) for (j in 0 until lanes) {
        val u = i.toFloat() / columns * Tau
        val v = (-1 + 2f * j / (lanes - 1)) * view.halfWidth
        val id = i * lanes + j
        view.project(u, v, p, 0.6f)
        val level = beadLevel(id, t, (0.5f + 0.5f * sin(2 * u - scene.bandClock * 1.6f)).pow(14))
        val s = (0.8f + 0.9f * p[2]) * k
        scene.beads.dot(if (level > 0.5f && motionHash(id.toFloat(), 4.4f) < 0.06f) 1 else 0, level * (0.35f + 0.65f * p[2]), p[0], p[1], s, s * 1.8f)
    }
    flushBeads(scene, palette)
}

/**
 * 字标变形：粒子先拼出字标，停留几秒后从左到右依次飞散，在空中扭成 ∞ 形莫比乌斯带流动一阵，再聚回字标，循环往复。
 * 点按立即切到下一形态。
 */
private fun DrawScope.drawMorph(scene: EmptyChatScene, mask: WordmarkMask, centerY: Float, t: Float, delta: Float, tapped: Boolean, palette: MotionPalette, particleScale: Float) {
    val k = density
    val count = (MorphParticleCount * particleScale).toInt().coerceAtLeast(200)
    if (scene.morph == null || scene.morphMask !== mask) {
        val targets = sampleWordmarkTargets(mask.alpha, mask.width, mask.height, 2, count, seed = 7)
        for (i in targets.indices) targets[i] *= k
        scene.morph = MorphParticles(targets, count, size.width)
        scene.morphMask = mask
    }
    val morph = scene.morph ?: return
    if (tapped) morph.skip()
    morph.step(delta)
    val offsetY = centerY - mask.centerY
    val view = scene.projector.configure(
        radius = mobiusRadius(size.width, size.height),
        cx = size.width / 2,
        cy = centerY,
        yaw = 0.22f * sin(t * 0.3f) + scene.gesture.rotY,
        pitch = 0.78f + scene.gesture.rotX,
    )
    val p = scene.point
    val levels = 5
    scene.buckets.forEach { it.rewind() }
    for (i in 0 until morph.count) {
        view.project(morph.u[i], morph.v[i] * view.halfWidth, p)
        val e = morph.particleProgress(i)
        val swirl = sin(Math.PI.toFloat() * e) * 16 * k
        val angle = morph.angle[i] + t * 0.8f
        val wx = morph.wordX[i]
        val wy = morph.wordY[i] + offsetY
        val x = wx + (p[0] - wx) * e + cos(angle) * swirl
        val y = wy + (p[1] - wy) * e + sin(angle) * swirl
        val alpha = 0.95f * (1 - e) + (0.25f + 0.75f * p[2]) * e
        val s = (1.1f * (1 - e) + (0.5f + 0.9f * p[2]) * e) * k
        scene.buckets[morph.hue[i] * levels + min(levels - 1, (alpha * levels).toInt())].addRect(x - s / 2, y - s / 2, x + s / 2, y + s / 2, Path.Direction.CW)
    }
    drawIntoCanvas { canvas ->
        val paint = scene.fill
        paint.blendMode = if (palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER
        for (idx in 0 until levels * 2) {
            if (scene.buckets[idx].isEmpty) continue
            paint.color = (if (idx < levels) palette.a else palette.b).copy(alpha = (idx % levels + 0.5f) / levels).toArgb()
            canvas.nativeCanvas.drawPath(scene.buckets[idx], paint)
        }
    }
}

/**
 * 灯珠星球：七百颗灯珠排成一颗缓缓自转的球，像一整面机柜灯墙卷成了球；两条数据环带沿经纬方向扫过，
 * 环带里的灯珠按 ACT 节奏闪，背面的灯珠压暗。点按让环带加速一阵。
 */
private fun DrawScope.drawLedGlobe(scene: EmptyChatScene, centerY: Float, t: Float, delta: Float, tapped: Boolean, palette: MotionPalette, particleScale: Float) {
    val k = density
    if (tapped) scene.burstTime = t
    scene.bandClock += delta * (1 + 3 * burstAmount(t - scene.burstTime))
    val radius = globeRadius(size.width, size.height)
    val cx = size.width / 2
    val yaw = t * 0.18f + scene.gesture.rotY
    val tilt = 0.35f + scene.gesture.rotX
    val cosYaw = cos(yaw)
    val sinYaw = sin(yaw)
    val cosTilt = cos(tilt)
    val sinTilt = sin(tilt)
    val n1y = cos(0.4f)
    val n1z = sin(0.4f)
    val l2 = hypot(hypot(cos(1.1f), 0.3f), sin(1.1f))
    val n2x = cos(1.1f) / l2
    val n2y = 0.3f / l2
    val n2z = sin(1.1f) / l2
    val points = scene.sphere
    val count = (GlobeBeads * particleScale).toInt().coerceIn(120, GlobeBeads)
    val stride = GlobeBeads.toFloat() / count
    scene.beads.reset()
    for (n in 0 until count) {
        val i = (n * stride).toInt()
        val x = points[i * 3]
        val y = points[i * 3 + 1]
        val z = points[i * 3 + 2]
        val d1 = y * n1y + z * n1z
        val d2 = x * n2x + y * n2y + z * n2z
        val b1 = exp(-(d1 / 0.13f).pow(2)) * (0.5f + 0.5f * sin(2 * atan2(z, x) - scene.bandClock * 2)).pow(3)
        val b2 = exp(-(d2 / 0.13f).pow(2)) * (0.5f + 0.5f * sin(2 * atan2(y, x) + scene.bandClock * 1.7f)).pow(3)
        val level = beadLevel(i, t, maxOf(b1, b2), 0.16f)
        val x1 = x * cosYaw + z * sinYaw
        val z1 = -x * sinYaw + z * cosYaw
        val y1 = y * cosTilt - z1 * sinTilt
        val depth = (y * sinTilt + z1 * cosTilt + 1) / 2
        val s = (0.8f + 1.1f * depth) * k
        scene.beads.dot(if (level > 0.5f && motionHash(i.toFloat(), 4.4f) < 0.06f) 1 else 0, level * (0.25f + 0.75f * depth), cx + x1 * radius, centerY - y1 * radius, s, s * 1.8f)
    }
    flushBeads(scene, palette)
}
