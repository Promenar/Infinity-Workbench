package com.promenar.infinity.ui.chat

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 空会话字标的粒子模拟：一部分粒子被弹簧拉向字标笔画采样点并轻微呼吸，其余粒子沿噪声流场漂移；
 * 散开后所有粒子都改为随流场漂移，复原时重新聚回字标。坐标单位为像素，速度按 60fps 一帧的位移计。
 */
internal class WordmarkParticles(
    targets: FloatArray,
    ambientCount: Int,
    private val width: Float,
    height: Float,
    private val density: Float,
    seed: Int = 7,
) {
    val logoCount = targets.size / 2
    val count = logoCount + ambientCount
    val x = FloatArray(count)
    val y = FloatArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    val targetX = FloatArray(logoCount) { targets[it * 2] }
    val targetY = FloatArray(logoCount) { targets[it * 2 + 1] }
    /** 0 远、1 中、2 近：越近越大、越亮、越快；3 为字标粒子，比环境粒子更亮，字形清晰。 */
    val layer = IntArray(count)
    /** 0 主色、1 第三色、2 辅色。 */
    val hue = IntArray(count)
    /** 闪烁相位分桶，绘制时同桶粒子共用一次批量绘制。 */
    val twinkle = IntArray(count)
    private val phase = FloatArray(count)
    private var height = height
    private var anchorY = Float.NaN

    var dispersed = false
        private set

    init {
        val random = Random(seed)
        for (i in 0 until count) {
            x[i] = random.nextFloat() * width
            y[i] = random.nextFloat() * height
            layer[i] = if (i < logoCount) LogoLayer else when {
                random.nextFloat() < 0.18f -> 2
                random.nextFloat() < 0.5f -> 1
                else -> 0
            }
            hue[i] = when {
                random.nextFloat() < 0.62f -> 0
                random.nextFloat() < 0.6f -> 1
                else -> 2
            }
            phase[i] = random.nextFloat() * 6.2832f
            twinkle[i] = random.nextInt(TwinkleBuckets)
        }
    }

    /** 字标粒子沿离心方向带一点向上的初速度炸开，之后交给流场。 */
    fun disperse(centerX: Float, centerY: Float, random: Random = Random(11)) {
        if (dispersed) return
        dispersed = true
        for (i in 0 until logoCount) {
            val dx = x[i] - centerX
            val dy = y[i] - centerY
            val distance = hypot(dx, dy).coerceAtLeast(1f)
            val kick = (1.5f + random.nextFloat() * 2.5f) * density
            vx[i] += dx / distance * kick
            vy[i] += dy / distance * kick - 0.6f * density
        }
    }

    fun reform() {
        dispersed = false
    }

    /**
     * 可用高度变化（键盘弹出、输入框长高）时把字标整体平移到新的中心，粒子随弹簧飞过去，不重新栅格化。
     * 第一次调用只记录中心。
     */
    fun recenter(centerY: Float, height: Float) {
        this.height = height
        if (!anchorY.isNaN()) {
            val dy = centerY - anchorY
            for (i in 0 until logoCount) targetY[i] += dy
        }
        anchorY = centerY
    }

    /** 静止显示：字标粒子直接落在笔画上，速度清零；用于系统关闭动画与静态预览。 */
    fun settle() {
        for (i in 0 until logoCount) {
            x[i] = targetX[i]
            y[i] = targetY[i]
        }
        vx.fill(0f)
        vy.fill(0f)
    }

    fun step(deltaSeconds: Float, timeSeconds: Float, pointerX: Float = Float.NaN, pointerY: Float = Float.NaN) {
        val frames = (deltaSeconds * 60f).coerceIn(0f, 3f)
        if (frames == 0f) return
        val logoDamping = 0.86f.pow(frames)
        val noiseScale = 0.0065f / density
        val drift = timeSeconds * 0.05f
        val repelRadius = 70f * density
        for (i in 0 until count) {
            val nx = x[i] * noiseScale + drift * 6f
            val ny = y[i] * noiseScale - drift * 4f
            val e = 0.006f
            var fx = (flow(nx, ny + e) - flow(nx, ny - e)) / (2 * e)
            var fy = -(flow(nx + e, ny) - flow(nx - e, ny)) / (2 * e)
            val magnitude = hypot(fx, fy).coerceAtLeast(1e-4f)
            fx /= magnitude
            fy /= magnitude
            if (!dispersed && i < logoCount) {
                val breath = sin(timeSeconds * 1.2f + phase[i]) * 1.2f * density
                val pull = 0.022f * frames
                vx[i] = vx[i] * logoDamping + (targetX[i] + fx * breath - x[i]) * pull + fx * LogoFlowPush * density * frames
                vy[i] = vy[i] * logoDamping + (targetY[i] + fy * breath - y[i]) * pull + fy * LogoFlowPush * density * frames
            } else {
                val speed = LayerSpeed[layer[i]] * 1.4f * density
                val relax = 1f - (1f - 0.035f).pow(frames)
                vx[i] += (fx * speed - vx[i]) * relax
                vy[i] += (fy * speed - vy[i]) * relax
            }
            if (!pointerX.isNaN()) {
                val dx = x[i] - pointerX
                val dy = y[i] - pointerY
                val distance = hypot(dx, dy)
                if (distance in 0.1f..repelRadius) {
                    val push = (1f - distance / repelRadius) * 1.6f * density * frames
                    vx[i] += dx / distance * push
                    vy[i] += dy / distance * push
                }
            }
            x[i] += vx[i] * frames
            y[i] += vy[i] * frames
            if (dispersed || i >= logoCount) wrap(i)
        }
    }

    private fun wrap(i: Int) {
        val margin = 5f * density
        if (x[i] < -margin) x[i] = width + margin
        if (x[i] > width + margin) x[i] = -margin
        if (y[i] < -margin) y[i] = height + margin
        if (y[i] > height + margin) y[i] = -margin
    }

    companion object {
        const val TwinkleBuckets = 4
        /** 流场对字标粒子的推力；与弹簧拉力平衡后笔画偏移约 2dp，字形保持清晰又有流动感。 */
        private const val LogoFlowPush = 0.04f
        const val LogoLayer = 3
        val LayerSpeed = floatArrayOf(0.35f, 0.6f, 0.95f, 0.6f)

        private fun hash(i: Float, j: Float): Float {
            val s = sin(i * 127.1f + j * 311.7f) * 43758.547f
            return s - floor(s)
        }

        private fun valueNoise(px: Float, py: Float): Float {
            val i = floor(px)
            val j = floor(py)
            val fx = px - i
            val fy = py - j
            val u = fx * fx * (3 - 2 * fx)
            val v = fy * fy * (3 - 2 * fy)
            val a = hash(i, j)
            val b = hash(i + 1, j)
            val c = hash(i, j + 1)
            val d = hash(i + 1, j + 1)
            return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
        }

        internal fun flow(px: Float, py: Float): Float = valueNoise(px, py) + 0.5f * valueNoise(px * 2.1f + 5.2f, py * 2.1f + 1.3f)
    }
}

/** 字号按可用宽度收缩，保证整行字标放得下；不超过上限。 */
internal fun fitWordmarkTextSize(measure: (Float) -> Float, maxWidth: Float, maxSize: Float): Float {
    val natural = measure(maxSize)
    return if (natural <= maxWidth || natural <= 0f) maxSize else maxSize * maxWidth / natural
}

/**
 * 从字标的透明度栅格中按网格步长采样笔画点，返回 x、y 交替排列的坐标，并按种子打乱后截取至多 [limit] 个，
 * 截取后的点仍均匀覆盖全部笔画。
 */
internal fun sampleWordmarkTargets(
    alpha: ByteArray,
    width: Int,
    height: Int,
    step: Int,
    limit: Int,
    threshold: Int = 140,
    seed: Int = 3,
): FloatArray {
    val points = ArrayList<Pair<Float, Float>>()
    val random = Random(seed)
    var row = 0
    while (row < height) {
        var column = 0
        while (column < width) {
            if ((alpha[row * width + column].toInt() and 0xFF) > threshold) {
                points += (column + random.nextFloat() * step * 0.5f) to (row + random.nextFloat() * step * 0.5f)
            }
            column += step
        }
        row += step
    }
    points.shuffle(random)
    val kept = points.take(limit)
    return FloatArray(kept.size * 2).also { out ->
        kept.forEachIndexed { index, (px, py) ->
            out[index * 2] = px
            out[index * 2 + 1] = py
        }
    }
}
