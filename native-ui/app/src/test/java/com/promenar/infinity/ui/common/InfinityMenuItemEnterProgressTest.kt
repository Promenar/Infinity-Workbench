package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.InfinityMotion
import org.junit.Test

class InfinityMenuItemEnterProgressTest {
    private val stagger = InfinityMotion.MenuItemStaggerMillis.toFloat()
    private val enter = InfinityMotion.MenuItemEnterMillis.toFloat()

    @Test
    fun `首项从零开始并在入场时长后到位`() {
        assertThat(infinityMenuItemEnterProgress(0f, 0)).isEqualTo(0f)
        assertThat(infinityMenuItemEnterProgress(enter / 2, 0)).isWithin(1e-4f).of(0.5f)
        assertThat(infinityMenuItemEnterProgress(enter, 0)).isEqualTo(1f)
        assertThat(infinityMenuItemEnterProgress(enter * 3, 0)).isEqualTo(1f)
    }

    @Test
    fun `后续项按错峰间隔延后开始`() {
        assertThat(infinityMenuItemEnterProgress(stagger, 1)).isEqualTo(0f)
        assertThat(infinityMenuItemEnterProgress(stagger + enter / 2, 1)).isWithin(1e-4f).of(0.5f)
        assertThat(infinityMenuItemEnterProgress(stagger * 2 + enter, 2)).isEqualTo(1f)
    }

    @Test
    fun `超过错峰上限的项与最后一档同时出现`() {
        val last = InfinityMotion.MenuItemStaggerMaxItems - 1
        val t = last * stagger + enter / 3
        assertThat(infinityMenuItemEnterProgress(t, last + 5))
            .isEqualTo(infinityMenuItemEnterProgress(t, last))
    }

    @Test
    fun `负序号按首项处理`() {
        assertThat(infinityMenuItemEnterProgress(enter / 2, -3))
            .isEqualTo(infinityMenuItemEnterProgress(enter / 2, 0))
    }
}
