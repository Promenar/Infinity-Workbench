package com.promenar.infinity.ui.common.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 机房灯语动效共用的数学：时间槽哈希、值噪声、∞ 曲线、莫比乌斯带投影与球面取点。
 * 闪烁都按 `hash(id, floor(t * rate))` 取时间槽，频率与屏幕刷新率无关。
 */
internal const val Tau = (2 * PI).toFloat()

internal fun fract(x: Float): Float = x - floor(x)

internal fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)

internal fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = clamp01((x - edge0) / (edge1 - edge0))
    return t * t * (3 - 2 * t)
}

/** 0..1 的伪随机数；用双精度计算，时间槽序号很大时仍然分布均匀。 */
internal fun motionHash(a: Float, b: Float): Float {
    val s = sin(a * 127.1 + b * 311.7) * 43758.5453
    return (s - floor(s)).toFloat()
}

/** 一维值噪声，输出 0..1。 */
internal fun valueNoise(x: Float): Float {
    val i = floor(x)
    val f = x - i
    val u = f * f * (3 - 2 * f)
    return motionHash(i, 0.37f) * (1 - u) + motionHash(i + 1, 0.37f) * u
}

/** 二维值噪声，输出 0..1。 */
internal fun valueNoise2(x: Float, y: Float): Float {
    val i = floor(x)
    val j = floor(y)
    val fx = x - i
    val fy = y - j
    val u = fx * fx * (3 - 2 * fx)
    val v = fy * fy * (3 - 2 * fy)
    val a = motionHash(i, j)
    val b = motionHash(i + 1, j)
    val c = motionHash(i, j + 1)
    val d = motionHash(i + 1, j + 1)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
}

/** ACT 灯：按 [rate] Hz 的时间槽决定亮灭，[burst] 为当前忙碌度（0..1），越忙越常亮。 */
internal fun actLit(id: Int, time: Float, burst: Float, rate: Float): Boolean =
    motionHash(id * 3.3f, floor(time * rate + id * 5.1f)) < 0.15f + 0.75f * burst

/**
 * 灯珠亮度：[base] 常暗打底，数据光带（[band]，0..1）经过时按 24–50Hz 高频闪，另有零星随机亮起。
 * 灯珠字标、灯珠环与灯珠星球共用。
 */
internal fun beadLevel(id: Int, time: Float, band: Float, base: Float = 0.07f): Float {
    var level = base
    if (band > 0.05f && motionHash(id.toFloat(), floor(time * (24 + motionHash(id.toFloat(), 2f) * 26))) > 0.18f) {
        level = maxOf(level, base + (1 - base) * band)
    }
    if (motionHash(id.toFloat(), floor(time * 3 + motionHash(id.toFloat(), 9f) * 3)) < 0.012f) level = maxOf(level, 0.75f)
    return level
}

/** 伯努利双纽线（横躺的 ∞），参数 s 在 π/2 处经过中心；a = 1 时横向半宽为 1。 */
internal fun lemniscateAt(s: Float, out: FloatArray) {
    val sn = sin(s)
    val cs = cos(s)
    val d = 1 + sn * sn
    out[0] = cs / d
    out[1] = sn * cs / d
}

/**
 * 沿双纽线按弧长等距取 [count] 个点（单位坐标，x、y 交替），从中心出发错开半个间距，
 * 两股在中心交叉时不会有两颗灯重叠在同一点。
 */
internal fun lemniscatePoints(count: Int, samples: Int = 720): FloatArray {
    val xs = FloatArray(samples + 1)
    val ys = FloatArray(samples + 1)
    val cumulative = FloatArray(samples + 1)
    val p = FloatArray(2)
    for (i in 0..samples) {
        lemniscateAt(PI.toFloat() / 2 + i.toFloat() / samples * Tau, p)
        xs[i] = p[0]
        ys[i] = p[1]
        if (i > 0) cumulative[i] = cumulative[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    val out = FloatArray(count * 2)
    var j = 0
    for (i in 0 until count) {
        val target = (i + 0.5f) / count * cumulative[samples]
        while (j < samples - 1 && cumulative[j + 1] < target) j++
        val f = ((target - cumulative[j]) / (cumulative[j + 1] - cumulative[j])).coerceIn(0f, 1f)
        out[i * 2] = xs[j] + (xs[j + 1] - xs[j]) * f
        out[i * 2 + 1] = ys[j] + (ys[j + 1] - ys[j]) * f
    }
    return out
}

/**
 * 横躺的 ∞ 形莫比乌斯带：中线是 x = A·sin u、y = B·sin u·cos u 的 8 字曲线，两次穿过中心时用 z = C·cos u 错开高度，
 * 一股从上方越过另一股。带宽方向在“竖直”与“水平法线”之间随 u/2 旋转，绕一圈恰好转过半圈，
 * 所以 (u + 2π, v) 与 (u, −v) 是同一点，粒子越过 2π 时把 v 取反即可无缝续上。
 * 视图从斜上方俯视（pitch 约 0.78），弱透视。
 */
internal class MobiusProjector {
    private var a = 1f
    private var b = 0.92f
    private var c = 0.16f
    private var cosYaw = 1f
    private var sinYaw = 0f
    private var cosPitch = 1f
    private var sinPitch = 0f
    private var distance = 5f
    private var depthRange = 0.6f
    private var centerX = 0f
    private var centerY = 0f

    /** 带半宽，带面上的 v 取 −halfWidth..halfWidth。 */
    var halfWidth = 0.2f
        private set

    /** 点按后的散开量 0..1；投影时按每颗粒子的 spread 向外推开。 */
    var burst = 0f
        private set

    fun configure(radius: Float, cx: Float, cy: Float, yaw: Float, pitch: Float, burst: Float = 0f, widthScale: Float = 1f): MobiusProjector {
        a = radius
        b = radius * 0.92f
        c = radius * 0.16f
        halfWidth = radius * 0.2f * widthScale
        cosYaw = cos(yaw)
        sinYaw = sin(yaw)
        cosPitch = cos(pitch)
        sinPitch = sin(pitch)
        distance = radius * 5
        depthRange = radius * 0.6f
        centerX = cx
        centerY = cy
        this.burst = burst
        return this
    }

    /** 投影到屏幕：out[0]、out[1] 为坐标，out[2] 为 0（远）..1（近）的纵深。 */
    fun project(u: Float, v: Float, out: FloatArray, spread: Float = 0f) {
        val su = sin(u)
        val cu = cos(u)
        val tx = a * cu
        val ty = b * (cu * cu - su * su)
        val tl = hypot(tx, ty).takeIf { it > 0f } ?: 1f
        val sa = sin(u / 2)
        val ca = cos(u / 2)
        val px = a * su - v * sa * ty / tl
        val py = b * su * cu + v * sa * tx / tl
        val pz = c * cu + v * ca
        val x1 = px * cosYaw - py * sinYaw
        val y1 = px * sinYaw + py * cosYaw
        val up = pz * cosPitch + y1 * sinPitch
        val depth = pz * sinPitch - y1 * cosPitch
        val f = distance / (distance - depth)
        val boost = 1 + burst * spread
        out[0] = centerX + x1 * f * boost
        out[1] = centerY - up * f * boost
        out[2] = clamp01((depth / depthRange + 1) / 2)
    }
}

/** 点按后的散开量：0.08 秒内冲到峰值，再按指数回落。 */
internal fun burstAmount(sinceTap: Float): Float =
    if (sinceTap < 0f) 0f else exp(-sinceTap * 2.2f) * minOf(1f, sinceTap * 12)

/** 斐波那契球面上均匀分布的 [count] 个单位向量（x、y、z 交替）。 */
internal fun fibonacciSphere(count: Int): FloatArray {
    val out = FloatArray(count * 3)
    for (i in 0 until count) {
        val y = 1 - 2 * (i + 0.5f) / count
        val r = sqrt(1 - y * y)
        val phi = i * 2.399963f
        out[i * 3] = cos(phi) * r
        out[i * 3 + 1] = y
        out[i * 3 + 2] = sin(phi) * r
    }
    return out
}
