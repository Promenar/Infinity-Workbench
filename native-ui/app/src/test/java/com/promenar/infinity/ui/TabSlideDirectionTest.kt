package com.promenar.infinity.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabSlideDirectionTest {
    @Test
    fun `按会话、资料库、设置的顺序判断滑动方向`() {
        assertThat(tabSlideDirection(AppTab.CHAT, AppTab.LIBRARY)).isEqualTo(1)
        assertThat(tabSlideDirection(AppTab.CHAT, AppTab.SETTINGS)).isEqualTo(1)
        assertThat(tabSlideDirection(AppTab.LIBRARY, AppTab.SETTINGS)).isEqualTo(1)
        assertThat(tabSlideDirection(AppTab.SETTINGS, AppTab.LIBRARY)).isEqualTo(-1)
        // 跨两个标签仍只是一个方向，不放大位移。
        assertThat(tabSlideDirection(AppTab.SETTINGS, AppTab.CHAT)).isEqualTo(-1)
        assertThat(tabSlideDirection(AppTab.LIBRARY, AppTab.LIBRARY)).isEqualTo(0)
    }

    @Test
    fun `导航轨纵向滑动，底栏横向滑动`() {
        assertThat(tabSlideVertical(navigationRail = true)).isTrue()
        assertThat(tabSlideVertical(navigationRail = false)).isFalse()
        // 导航轨的阈值与布局切换一致：600dp 起为导航轨。
        assertThat(tabSlideVertical(shouldUseNavigationRail(600.dp))).isTrue()
        assertThat(tabSlideVertical(shouldUseNavigationRail(599.dp))).isFalse()
    }

    private val Int.dp get() = androidx.compose.ui.unit.Dp(toFloat())
}
