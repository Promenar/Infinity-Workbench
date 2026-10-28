package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SweepGridMotionTest {
    @Test
    fun `速率按 0点6 次方映射，低速也有明显反馈`() {
        assertThat(sweepRateLevel(0f)).isEqualTo(0f)
        assertThat(sweepRateLevel(1f)).isWithin(1e-6f).of(1f)
        assertThat(sweepRateLevel(0.1f)).isGreaterThan(0.2f)
        // 中速与满速拉开差距：线性映射时两者差 0.5，这里仍有明显差距。
        assertThat(sweepRateLevel(1f) - sweepRateLevel(0.5f)).isGreaterThan(0.3f)
    }

    @Test
    fun `光带随强度从 1 道平滑增到 3 道`() {
        assertThat((0 until SweepGridSpec.MaxBands).map { sweepBandWeight(0f, it) }).containsExactly(1f, 0f, 0f).inOrder()
        assertThat((0 until SweepGridSpec.MaxBands).map { sweepBandWeight(1f, it) }).containsExactly(1f, 1f, 1f).inOrder()
        assertThat(sweepBandWeight(0.25f, 1)).isWithin(1e-6f).of(0.5f)
        assertThat(sweepBandWeight(0.25f, 2)).isEqualTo(0f)
    }

    @Test
    fun `余辉随强度从 0点35 秒变长到 0点6 秒`() {
        assertThat(sweepTrailSeconds(0f)).isWithin(1e-6f).of(0.35f)
        assertThat(sweepTrailSeconds(1f)).isWithin(1e-6f).of(0.6f)
    }

    @Test
    fun `写入头走过的灯珠记下点亮时间，一行走完换下一行，走满后回到开头`() {
        val trail = FloatArray(6) { -1f }
        var head = advanceWriteTrail(trail, lastHead = 0f, head = 2.4f, time = 1f)
        assertThat(head).isEqualTo(2.4f)
        assertThat(trail.toList()).containsExactly(-1f, 1f, 1f, -1f, -1f, -1f).inOrder()
        head = advanceWriteTrail(trail, head, head = 7.1f, time = 2f)
        // 第 3、4、5 格接着点亮，第 6、7 格回到开头的 0、1。
        assertThat(trail.toList()).containsExactly(2f, 2f, 1f, 2f, 2f, 2f).inOrder()
        assertThat(head).isEqualTo(7.1f)
    }

    @Test
    fun `积压很多字时一帧最多补记一圈，位置回退时只更新基准`() {
        val trail = FloatArray(4) { -1f }
        advanceWriteTrail(trail, lastHead = 0f, head = 1_000_000f, time = 3f)
        assertThat(trail.toList()).containsExactly(3f, 3f, 3f, 3f)
        val reset = advanceWriteTrail(trail, lastHead = 50f, head = 2f, time = 4f)
        assertThat(reset).isEqualTo(2f)
        assertThat(trail.toList()).containsExactly(3f, 3f, 3f, 3f)
    }
}
