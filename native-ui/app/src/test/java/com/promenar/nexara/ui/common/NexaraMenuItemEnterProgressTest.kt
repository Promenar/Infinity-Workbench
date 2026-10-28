package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.theme.NexaraMotion
import org.junit.Test

class NexaraMenuItemEnterProgressTest {
    private val stagger = NexaraMotion.MenuItemStaggerMillis.toFloat()
    private val enter = NexaraMotion.MenuItemEnterMillis.toFloat()

    @Test
    fun `首项从零开始并在入场时长后到位`() {
        assertThat(nexaraMenuItemEnterProgress(0f, 0)).isEqualTo(0f)
        assertThat(nexaraMenuItemEnterProgress(enter / 2, 0)).isWithin(1e-4f).of(0.5f)
        assertThat(nexaraMenuItemEnterProgress(enter, 0)).isEqualTo(1f)
        assertThat(nexaraMenuItemEnterProgress(enter * 3, 0)).isEqualTo(1f)
    }

    @Test
    fun `后续项按错峰间隔延后开始`() {
        assertThat(nexaraMenuItemEnterProgress(stagger, 1)).isEqualTo(0f)
        assertThat(nexaraMenuItemEnterProgress(stagger + enter / 2, 1)).isWithin(1e-4f).of(0.5f)
        assertThat(nexaraMenuItemEnterProgress(stagger * 2 + enter, 2)).isEqualTo(1f)
    }

    @Test
    fun `超过错峰上限的项与最后一档同时出现`() {
        val last = NexaraMotion.MenuItemStaggerMaxItems - 1
        val t = last * stagger + enter / 3
        assertThat(nexaraMenuItemEnterProgress(t, last + 5))
            .isEqualTo(nexaraMenuItemEnterProgress(t, last))
    }

    @Test
    fun `负序号按首项处理`() {
        assertThat(nexaraMenuItemEnterProgress(enter / 2, -3))
            .isEqualTo(nexaraMenuItemEnterProgress(enter / 2, 0))
    }
}
