package com.promenar.nexara.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Task 4.1 流体导航动画契约 RED 测试。
 */
class FluidNavigationMotionTest {
    @Test
    fun `fluidNavigationIndicatorScale should clamp and return baseline identity at edges`() {
        val edgeStart = fluidNavigationIndicatorScale(0f)
        val edgeEnd = fluidNavigationIndicatorScale(1f)
        val clampStart = fluidNavigationIndicatorScale(-0.6f)
        val clampEnd = fluidNavigationIndicatorScale(1.4f)

        assertThat(edgeStart.x).isWithin(0.0001f).of(1f)
        assertThat(edgeStart.y).isWithin(0.0001f).of(1f)
        assertThat(edgeEnd.x).isWithin(0.0001f).of(1f)
        assertThat(edgeEnd.y).isWithin(0.0001f).of(1f)

        assertThat(clampStart.x).isWithin(0.0001f).of(edgeStart.x)
        assertThat(clampStart.y).isWithin(0.0001f).of(edgeStart.y)
        assertThat(clampEnd.x).isWithin(0.0001f).of(edgeEnd.x)
        assertThat(clampEnd.y).isWithin(0.0001f).of(edgeEnd.y)
    }

    @Test
    fun `fluidNavigationIndicatorScale should stretch horizontally and squeeze vertically at midpoint`() {
        val mid = fluidNavigationIndicatorScale(0.5f)

        assertThat(mid.x).isGreaterThan(1f)
        assertThat(mid.y).isLessThan(1f)
    }

    @Test
    fun `fluidNavigationTargetIndex should map app tabs in order`() {
        assertThat(fluidNavigationTargetIndex(AppTab.CHAT)).isEqualTo(0)
        assertThat(fluidNavigationTargetIndex(AppTab.LIBRARY)).isEqualTo(1)
        assertThat(fluidNavigationTargetIndex(AppTab.SETTINGS)).isEqualTo(2)
    }

    @Test
    fun `水滴导航仅在启用触感且实际切换标签时触发tick`() {
        assertThat(shouldPerformNavigationHaptic(AppTab.CHAT, AppTab.LIBRARY, enabled = true)).isTrue()
        assertThat(shouldPerformNavigationHaptic(AppTab.CHAT, AppTab.CHAT, enabled = true)).isFalse()
        assertThat(shouldPerformNavigationHaptic(AppTab.CHAT, AppTab.LIBRARY, enabled = false)).isFalse()
    }

    @Test
    fun `导航触感在新系统使用分段tick旧系统使用时钟tick`() {
        assertThat(navigationHapticConstant(34)).isEqualTo(android.view.HapticFeedbackConstants.SEGMENT_TICK)
        assertThat(navigationHapticConstant(33)).isEqualTo(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
}
