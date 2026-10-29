package com.promenar.infinity.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.motion.LedBatch
import com.promenar.infinity.ui.common.motion.MobiusProjector
import com.promenar.infinity.ui.common.motion.MotionPalette
import com.promenar.infinity.ui.common.motion.beadLevel
import com.promenar.infinity.ui.common.motion.motionHash
import com.promenar.infinity.ui.common.motion.rememberMotionClock
import com.promenar.infinity.ui.common.motion.rememberMotionPalette
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** 灯珠变形在两帧之间保留的状态。 */
private class LedMorphScene {
    val gesture = EmptyChatGesture()
    val projector = MobiusProjector()
    val point = FloatArray(3)
    val beads = LedBatch(levels = 8, glowFrom = 4)
    var lastTime = Float.NaN
    /** 数据光带的时钟。 */
    var bandClock = 0f
    /** 时间轴时钟（秒），见 [LedMorphTimeline]。 */
    var clock = 0f
    var layout: LedMorphBeads? = null

    fun advance(time: Float): Float {
        val delta = if (lastTime.isNaN()) 0f else (time - lastTime).coerceIn(0f, 0.1f)
        lastTime = time
        return delta
    }
}

/**
 * 灯珠变形字标：品牌字标与灯珠莫比乌斯 ∞ 环之间往复变形，空会话与首次引导共用这一个实现。
 * 时间轴见 [LedMorphTimeline]，灯珠数与排布与灯珠环一致（[mobiusLedColumns] 列 × [MobiusLedLanes] 道）；颜色跟随主题色。
 * [pointer] 非空时点按跳到下一段、环阶段拖动转视角；[dispersed] 时整体压暗。系统关闭动画或静态预览时停在字标。
 * [particleScale] 缩减灯珠数，设置页的小预览用。纯装饰，不进入无障碍树。
 */
@Composable
internal fun LedMorphWordmark(
    modifier: Modifier = Modifier,
    dispersed: Boolean = false,
    pointer: WordmarkPointer? = null,
    centerFraction: Float = WordmarkCenterFraction,
    particleScale: Float = 1f,
) {
    val wordmark = stringResource(R.string.brand_wordmark)
    val density = LocalDensity.current.density
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val palette = rememberMotionPalette()
    val clock = rememberMotionClock(running = animate)
    val scene = remember(particleScale) { LedMorphScene() }
    val dim by animateFloatAsState(if (dispersed) 0.3f else 1f, tween(500), label = "led_morph_dim")

    BoxWithConstraints(modifier) {
        val heightPx = constraints.maxHeight.toFloat()
        val centerY = heightPx * centerFraction
        val mask = rememberWordmarkMask(true, wordmark, constraints.maxWidth.toFloat(), heightPx, centerY, density)
        val origin = remember { floatArrayOf(0f, 0f) }
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}.trackCanvasOrigin(origin)) {
            // 静止时停在 1.3 秒，画面固定；时间轴时钟不走，停在字标。
            val time = if (animate) clock.time else 1.3f
            val delta = if (animate) scene.advance(time) else 0f
            val px = pointer?.root?.get(0)?.let { (it - origin[0]) / density } ?: Float.NaN
            val py = pointer?.root?.get(1)?.let { (it - origin[1]) / density } ?: Float.NaN
            val tapped = scene.gesture.update(px, py, time, delta)
            if (mask == null) return@Canvas
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.saveLayerAlpha(0f, 0f, size.width, size.height, (255 * dim).toInt())
                drawLedMorph(scene, mask, centerY, time, delta, tapped, palette, particleScale)
                native.restore()
            }
        }
    }
}

/**
 * 灯珠变形：字标与灯珠环之间往复变形（时间轴见 [LedMorphTimeline]），两种形态共用同一组细沙粒。
 * 灯珠数、环上排布与亮度规则都和灯珠环一致；字标形态是撒在字形内的近似蓝噪声星尘，数据光带从左往右扫过。
 * 两种形态按横向位置一一配对、错开出发，飞行途中带少量旋涡偏移与零星闪光。
 * 点按跳到下一形态；环阶段拖动转视角。
 */
private fun DrawScope.drawLedMorph(scene: LedMorphScene, mask: WordmarkMask, centerY: Float, t: Float, delta: Float, tapped: Boolean, palette: MotionPalette, particleScale: Float) {
    val k = density
    val columns = mobiusLedColumns(particleScale)
    val beads = scene.layout?.takeIf { it.mask === mask && it.width == size.width && it.count == columns * MobiusLedLanes }
        ?: buildLedMorphBeads(mask, size.width, k, columns, mobiusRadius(size.width, size.height)).also { scene.layout = it }
    if (tapped) scene.clock = LedMorphTimeline.skip(scene.clock)
    scene.clock += delta
    scene.bandClock += delta
    val clock = scene.clock
    val progress = LedMorphTimeline.progress(clock)
    val gathering = LedMorphTimeline.stageAt(clock) == LedMorphTimeline.Stage.GATHER
    val view = mobiusView(scene.projector, scene.gesture, centerY, t)
    val offsetY = centerY - mask.centerY
    val flashSlot = floor(t * 18)
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
        var bead = 0f
        if (e < 1f) {
            val dw = beads.dw[i]
            val band = (0.5f + 0.5f * sin(wx / k * 0.048f - scene.bandClock * 2.2f)).pow(14)
            level = beadLevel(id, t, band, 0.2f) * (0.6f + 0.4f * dw)
            bead = (0.7f + 0.8f * dw) * k
        }
        val fly = sin(Math.PI.toFloat() * e)
        if (e > 0f) {
            val u = beads.ringU[i]
            view.project(u, beads.ringV[i] * view.halfWidth, p, 0.6f)
            val swirl = fly * 14 * k
            val angle = beads.angle[i] + t * 0.8f
            x = wx + (p[0] - wx) * e + cos(angle) * swirl
            y = wy + (p[1] - wy) * e + sin(angle) * swirl
            val band = (0.5f + 0.5f * sin(2 * u - scene.bandClock * 1.6f)).pow(14)
            val ringLevel = beadLevel(id, t, band) * (0.35f + 0.65f * p[2])
            val ringBead = (0.8f + 0.9f * p[2]) * k
            level += (ringLevel - level) * e
            bead += (ringBead - bead) * e
        }
        if (fly > 0.05f && motionHash(id.toFloat(), flashSlot) > 0.82f) level = maxOf(level, 0.55f * fly)
        scene.beads.dot(if (level > 0.5f && motionHash(id.toFloat(), 4.4f) < 0.06f) 1 else 0, level, x, y, bead, bead * 1.8f)
    }
    flushLedBeads(scene.beads, palette)
}
