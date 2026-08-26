package com.promenar.nexara.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FluidNavigationMotionTest {
    @Test
    fun `Bettbox 式底栏为选中项分配更宽但稳定的布局权重`() {
        assertThat(bottomNavigationTabWeight(selected = true)).isEqualTo(1.45f)
        assertThat(bottomNavigationTabWeight(selected = false)).isEqualTo(1f)
    }

    @Test
    fun `底部导航仅在启用触感且实际切换标签时触发tick`() {
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
