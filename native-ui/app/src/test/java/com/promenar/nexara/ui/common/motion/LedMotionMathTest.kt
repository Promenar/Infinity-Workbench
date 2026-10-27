package com.promenar.nexara.ui.common.motion

import com.google.common.truth.Truth.assertThat
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Test

class LedMotionMathTest {

    @Test
    fun `时间槽哈希确定且落在 0 到 1 之间，大序号仍然分散`() {
        assertThat(motionHash(3f, 7f)).isEqualTo(motionHash(3f, 7f))
        // 一小时后的 50Hz 时间槽序号约为 18 万，相邻槽位仍应取到不同的值。
        val values = (0 until 200).map { motionHash(5f, 180_000f + it) }
        values.forEach { assertThat(it).isIn(com.google.common.collect.Range.closedOpen(0f, 1f)) }
        assertThat(values.toSet().size).isGreaterThan(190)
        assertThat(values.count { it < 0.5f }).isIn(com.google.common.collect.Range.closed(70, 130))
    }

    @Test
    fun `ACT 灯越忙越常亮`() {
        val idle = (0 until 2000).count { actLit(3, it / 50f, burst = 0f, rate = 50f) }
        val busy = (0 until 2000).count { actLit(3, it / 50f, burst = 1f, rate = 50f) }
        assertThat(idle).isLessThan(500)
        assertThat(busy).isGreaterThan(1600)
    }

    @Test
    fun `灯珠亮度不低于底亮且不超过 1，光带经过时明显变亮`() {
        val dim = (0 until 500).map { beadLevel(it, 1.7f, band = 0f, base = 0.16f) }
        val lit = (0 until 500).map { beadLevel(it, 1.7f, band = 1f, base = 0.16f) }
        (dim + lit).forEach { assertThat(it).isIn(com.google.common.collect.Range.closed(0.16f, 1f)) }
        assertThat(lit.average()).isGreaterThan(dim.average() + 0.5)
    }

    @Test
    fun `双纽线灯珠按弧长等距且左右上下对称`() {
        val points = lemniscatePoints(24)
        val n = points.size / 2
        val gaps = (0 until n).map { i ->
            val j = (i + 1) % n
            hypot(points[j * 2] - points[i * 2], points[j * 2 + 1] - points[i * 2 + 1])
        }
        assertThat(gaps.max() - gaps.min()).isLessThan(0.02f)
        assertThat(points.toList().chunked(2).maxOf { it[0] }).isWithin(0.02f).of(1f)
        assertThat(points.toList().chunked(2).minOf { it[0] }).isWithin(0.02f).of(-1f)
        // 交叉点不放灯：离中心最近的灯也错开约半个间距。
        val nearest = points.toList().chunked(2).minOf { hypot(it[0], it[1]) }
        assertThat(nearest).isGreaterThan(gaps.average().toFloat() * 0.35f)
    }

    @Test
    fun `莫比乌斯带绕一圈后翻到另一面：u 加 2π 且 v 取反是同一点`() {
        val projector = MobiusProjector().configure(radius = 100f, cx = 200f, cy = 150f, yaw = 0.3f, pitch = 0.78f)
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (step in 0 until 12) {
            val u = step * 0.5f
            val v = projector.halfWidth * 0.7f
            projector.project(u, v, a)
            projector.project(u + Tau, -v, b)
            assertThat(b[0]).isWithin(0.01f).of(a[0])
            assertThat(b[1]).isWithin(0.01f).of(a[1])
            assertThat(b[2]).isWithin(0.001f).of(a[2])
        }
    }

    @Test
    fun `莫比乌斯带横向占满半径、左右两环对称`() {
        val projector = MobiusProjector().configure(radius = 100f, cx = 0f, cy = 0f, yaw = 0f, pitch = 0.78f)
        val p = FloatArray(3)
        val xs = (0 until 360).map { projector.project(it / 360f * Tau, 0f, p); p[0] }
        assertThat(xs.max()).isWithin(6f).of(100f)
        assertThat(xs.min()).isWithin(6f).of(-100f)
    }

    @Test
    fun `点按散开先冲高再回落`() {
        assertThat(burstAmount(-1f)).isEqualTo(0f)
        assertThat(burstAmount(0f)).isEqualTo(0f)
        assertThat(burstAmount(0.08f)).isGreaterThan(0.7f)
        assertThat(burstAmount(2f)).isLessThan(0.02f)
    }

    @Test
    fun `斐波那契球面点都在单位球上且南北均衡`() {
        val points = fibonacciSphere(700)
        val vectors = points.toList().chunked(3)
        vectors.forEach { assertThat(sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])).isWithin(1e-4f).of(1f) }
        assertThat(vectors.count { it[1] > 0 }).isEqualTo(350)
    }
}
