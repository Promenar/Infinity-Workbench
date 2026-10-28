package com.promenar.infinity.domain.media

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyframePlanTest {
    @Test fun `默认均匀八帧使用分段中点`() {
        assertThat(planKeyframes(80.0).timestampsSeconds).containsExactly(5.0,15.0,25.0,35.0,45.0,55.0,65.0,75.0).inOrder()
    }
    @Test fun `指定帧数与裁剪区间`() {
        assertThat(planKeyframes(20.0, frames = 2, startSeconds = -3.0, endSeconds = 99.0).timestampsSeconds)
            .containsExactly(5.0,15.0).inOrder()
        assertThat(planKeyframes(20.0, frames = 2, startSeconds = 4.0, endSeconds = 12.0).timestampsSeconds)
            .containsExactly(6.0,10.0).inOrder()
    }
    @Test fun `间隔从起点取帧且不包含终点`() {
        assertThat(planKeyframes(20.0, intervalSeconds = 2.5, startSeconds = 2.0, endSeconds = 10.0).timestampsSeconds)
            .containsExactly(2.0,4.5,7.0,9.5).inOrder()
    }
    @Test fun `超过三十二帧提示可以继续的时间`() {
        val interval = planKeyframes(100.0, intervalSeconds = 1.0)
        assertThat(interval.timestampsSeconds).hasSize(32)
        assertThat(interval.note).contains("32")
        assertThat(interval.note).contains("32s")
        val uniform = planKeyframes(100.0, frames = 100)
        assertThat(uniform.timestampsSeconds.last()).isEqualTo(31.5)
        assertThat(uniform.note).contains("32s")
    }
    @Test fun `非法密度范围和非有限值明确拒绝`() {
        listOf<() -> Unit>(
            { planKeyframes(10.0, frames = 2, intervalSeconds = 1.0) },
            { planKeyframes(10.0, frames = 0) },
            { planKeyframes(10.0, intervalSeconds = 0.0) },
            { planKeyframes(10.0, startSeconds = 11.0) },
            { planKeyframes(10.0, startSeconds = 3.0, endSeconds = 3.0) },
            { planKeyframes(10.0, endSeconds = Double.NaN) },
            { planKeyframes(10.0, intervalSeconds = Double.POSITIVE_INFINITY) },
        ).forEach { assertThrows(IllegalArgumentException::class.java, it) }
    }
    @Test fun `未知时长只保守读取首帧并解释限制`() {
        val plan = planKeyframes(null)
        assertThat(plan.timestampsSeconds).containsExactly(0.0)
        assertThat(plan.note).contains("时长未知")
        assertThrows(IllegalArgumentException::class.java) { planKeyframes(null, frames = 9) }
        assertThrows(IllegalArgumentException::class.java) { planKeyframes(null, intervalSeconds = 1.0) }
    }
}
