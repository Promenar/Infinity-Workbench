package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import kotlin.math.hypot
import org.junit.Test

class WordmarkParticlesTest {
    private val width = 400f
    private val height = 800f

    @Test
    fun `字号按可用宽度收缩并且不超过上限`() {
        val measure = { size: Float -> size * 5f }

        assertThat(fitWordmarkTextSize(measure, maxWidth = 500f, maxSize = 68f)).isEqualTo(68f)
        assertThat(fitWordmarkTextSize(measure, maxWidth = 170f, maxSize = 68f)).isWithin(1e-3f).of(34f)
    }

    @Test
    fun `采样只取笔画内的点并按上限截取`() {
        // 10x10 的栅格，只有左半边 5 列是笔画。
        val alpha = ByteArray(100) { index -> if (index % 10 < 5) 255.toByte() else 0 }
        val all = sampleWordmarkTargets(alpha, 10, 10, step = 1, limit = 1000)

        assertThat(all.size).isEqualTo(50 * 2)
        for (i in all.indices step 2) assertThat(all[i]).isLessThan(5.5f)

        val limited = sampleWordmarkTargets(alpha, 10, 10, step = 1, limit = 12)
        assertThat(limited.size).isEqualTo(12 * 2)
    }

    @Test
    fun `采样结果对同一输入保持确定`() {
        val alpha = ByteArray(400) { index -> if ((index / 20 + index % 20) % 3 == 0) 200.toByte() else 0 }

        assertThat(sampleWordmarkTargets(alpha, 20, 20, 2, 30)).isEqualTo(sampleWordmarkTargets(alpha, 20, 20, 2, 30))
    }

    @Test
    fun `字标粒子从随机位置收敛到笔画附近`() {
        val particles = particles()
        assertThat(meanDistanceToTargets(particles)).isGreaterThan(50f)

        repeat(240) { frame -> particles.step(1f / 60f, frame / 60f) }

        // 呼吸与流场让粒子在目标附近小幅浮动，不会精确停住。
        assertThat(meanDistanceToTargets(particles)).isLessThan(6f)
    }

    @Test
    fun `散开后离开字标，复原后重新聚拢`() {
        val particles = particles()
        particles.settle()
        particles.disperse(width / 2f, height * 0.42f)
        repeat(180) { frame -> particles.step(1f / 60f, frame / 60f) }
        assertThat(meanDistanceToTargets(particles)).isGreaterThan(40f)

        particles.reform()
        repeat(300) { frame -> particles.step(1f / 60f, 3f + frame / 60f) }
        assertThat(meanDistanceToTargets(particles)).isLessThan(6f)
    }

    @Test
    fun `散开与环境粒子越界后从另一侧回来`() {
        val particles = particles()
        particles.disperse(width / 2f, height * 0.42f)
        repeat(1_200) { frame -> particles.step(1f / 60f, frame / 60f) }

        val margin = 5f * DENSITY + 10f
        for (i in 0 until particles.count) {
            assertThat(particles.x[i]).isIn(com.google.common.collect.Range.closed(-margin, width + margin))
            assertThat(particles.y[i]).isIn(com.google.common.collect.Range.closed(-margin, height + margin))
        }
    }

    @Test
    fun `高度变化时字标整体平移而不重建`() {
        val particles = particles()
        particles.recenter(300f, height)
        val before = particles.targetY.copyOf()

        particles.recenter(200f, 600f)

        for (i in before.indices) assertThat(particles.targetY[i]).isWithin(1e-3f).of(before[i] - 100f)
    }

    @Test
    fun `帧间隔不同时运动距离按时间折算`() {
        val slow = particles().also { it.disperse(width / 2f, height * 0.42f) }
        val fast = particles().also { it.disperse(width / 2f, height * 0.42f) }

        repeat(30) { frame -> slow.step(1f / 30f, frame / 30f) }
        repeat(60) { frame -> fast.step(1f / 60f, frame / 60f) }

        // 30fps 与 60fps 走过一秒后，整体散开程度相近。
        assertThat(meanDistanceToTargets(slow)).isWithin(meanDistanceToTargets(fast) * 0.25f).of(meanDistanceToTargets(fast))
    }

    private fun particles(): WordmarkParticles {
        val targets = FloatArray(120) { index -> if (index % 2 == 0) 100f + index else 330f + (index % 7) * 4f }
        return WordmarkParticles(targets, ambientCount = 40, width = width, height = height, density = DENSITY)
    }

    private fun meanDistanceToTargets(particles: WordmarkParticles): Float =
        (0 until particles.logoCount).map { i ->
            hypot(particles.x[i] - particles.targetX[i], particles.y[i] - particles.targetY[i])
        }.average().toFloat()

    private companion object {
        const val DENSITY = 2f
    }
}
