package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.common.motion.Tau
import com.promenar.infinity.ui.common.motion.fract
import com.promenar.infinity.ui.common.motion.smoothStep
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 空会话动画的手势：手指按住拖动转视角（松手后带惯性），短按视为点按。
 * 字标层不拦截触摸，只能逐帧读取 [WordmarkPointer] 记录的按下位置，因此按帧轮询按下与抬起。
 * 坐标与距离单位为 dp。
 */
internal class EmptyChatGesture {
    /** 视角偏移：pitch 与 yaw（弧度）。 */
    var rotX = 0f
        private set
    var rotY = 0f
        private set
    private var spinVelocity = 0f
    private var pressed = false
    private var lastX = 0f
    private var lastY = 0f
    private var moved = 0f
    private var downX = 0f
    private var downY = 0f

    /** 最近一次点按的位置与时刻；没有新点按时 [tapTime] 为 NaN。 */
    var tapX = 0f
        private set
    var tapY = 0f
        private set
    var tapTime = Float.NaN
        private set

    /** 每帧调用一次；[x]、[y] 为 NaN 表示没有按下。返回本帧是否发生点按。 */
    fun update(x: Float, y: Float, time: Float, delta: Float): Boolean {
        val down = !x.isNaN() && !y.isNaN()
        var tapped = false
        when {
            down && !pressed -> {
                pressed = true
                lastX = x
                lastY = y
                downX = x
                downY = y
                moved = 0f
                spinVelocity = 0f
            }
            down -> {
                val dx = x - lastX
                val dy = y - lastY
                moved += hypot(dx, dy)
                if (moved > TapSlopDp) {
                    rotY += dx * 0.012f
                    rotX = (rotX + dy * 0.008f).coerceIn(-0.9f, 0.5f)
                    if (delta > 0f) spinVelocity = dx * 0.012f / delta
                }
                lastX = x
                lastY = y
            }
            pressed -> {
                pressed = false
                if (moved <= TapSlopDp) {
                    tapX = downX
                    tapY = downY
                    tapTime = time
                    tapped = true
                }
            }
        }
        if (!pressed && spinVelocity != 0f) {
            rotY += spinVelocity * delta
            spinVelocity *= exp(-delta * 2.5f)
            if (abs(spinVelocity) < 0.01f) spinVelocity = 0f
        }
        return tapped
    }

    private companion object {
        const val TapSlopDp = 6f
    }
}

/** 粒子流环：粒子铺满带面顺着 ∞ 流动，靠边的流得快、靠里的慢，带出剪切感；约 16% 的粒子贴着带边。 */
internal class MobiusFlowParticles(val count: Int, seed: Int = 13) {
    val u = FloatArray(count)
    val v = FloatArray(count)
    private val speed = FloatArray(count)
    /** 点按散开时各粒子向外推开的比例。 */
    val spread = FloatArray(count)
    val edge = BooleanArray(count)

    init {
        val random = Random(seed)
        for (i in 0 until count) {
            val onEdge = random.nextFloat() < 0.16f
            edge[i] = onEdge
            u[i] = random.nextFloat() * Tau
            v[i] = if (onEdge) (if (random.nextFloat() < 0.5f) -1 else 1) * (0.92f + random.nextFloat() * 0.08f) else random.nextFloat() * 2 - 1
            speed[i] = 0.7f + random.nextFloat() * 0.6f
            spread[i] = 0.3f + random.nextFloat() * 1.4f
        }
    }

    fun step(delta: Float, rate: Float = 0.32f) {
        for (i in 0 until count) {
            u[i] += delta * rate * speed[i] * (1 + 0.35f * v[i])
            wrap(i)
        }
    }

    /** 绕满一圈回到带面的“另一面”：(u + 2π, v) 与 (u, −v) 是同一点。 */
    private fun wrap(i: Int) {
        if (u[i] >= Tau) {
            u[i] -= Tau
            v[i] = -v[i]
        }
    }
}

/**
 * 字标变形：每颗粒子有两套目标——字标笔画上的点与莫比乌斯带上流动的点。
 * 一个周期 11 秒：停在字标 3 秒，2 秒内从左到右依次飞向带面，在带上流动 4 秒，再用 2 秒聚回字标。
 */
internal class MorphParticles(wordTargets: FloatArray, val count: Int, width: Float, seed: Int = 41) {
    val wordX = FloatArray(count)
    val wordY = FloatArray(count)
    val u = FloatArray(count)
    val v = FloatArray(count)
    /** 按横向位置错峰：字标左侧先飞。 */
    val delay = FloatArray(count)
    val hue = IntArray(count)
    val angle = FloatArray(count)
    var clock = 0f
        private set

    init {
        val random = Random(seed)
        val available = (wordTargets.size / 2).coerceAtLeast(1)
        for (i in 0 until count) {
            val j = i % available
            val x = if (wordTargets.isEmpty()) width / 2 else wordTargets[j * 2]
            val y = if (wordTargets.isEmpty()) 0f else wordTargets[j * 2 + 1]
            wordX[i] = x + if (i >= available) (random.nextFloat() - 0.5f) * 2 else 0f
            wordY[i] = y
            delay[i] = x / width.coerceAtLeast(1f) * 0.3f + random.nextFloat() * 0.08f
            u[i] = random.nextFloat() * Tau
            v[i] = random.nextFloat() * 2 - 1
            hue[i] = if (random.nextFloat() < 0.6f) 0 else 1
            angle[i] = random.nextFloat() * Tau
        }
    }

    /** 0 为字标、1 为莫比乌斯带。 */
    val progress: Float
        get() {
            val c = fract(clock / Period) * Period
            return when {
                c < 3 -> 0f
                c < 5 -> (c - 3) / 2
                c < 9 -> 1f
                else -> 1 - (c - 9) / 2
            }
        }

    /** 每颗粒子的变形进度：整体进度按各自的延迟错开，再做平滑。 */
    fun particleProgress(i: Int): Float = smoothStep(0f, 1f, progress * 1.4f - delay[i])

    /** 点按：停在字标或带面时直接跳到下一段变形。 */
    fun skip() {
        val c = fract(clock / Period) * Period
        if (c < 3) clock += 3 - c else if (c in 5f..9f) clock += 9 - c
    }

    fun step(delta: Float) {
        clock += delta
        for (i in 0 until count) {
            u[i] += delta * 0.32f
            if (u[i] >= Tau) {
                u[i] -= Tau
                v[i] = -v[i]
            }
        }
    }

    companion object {
        const val Period = 11f
    }
}
