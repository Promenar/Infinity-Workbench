package com.promenar.nexara.ui.rag.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class InteractiveGraphCanvasContractTest {

    @Test
    fun `系统动画倍率为零时停止装饰脉冲`() {
        assertThat(shouldAnimateDecorativePulse(0f)).isFalse()
    }

    @Test
    fun `系统允许动画时保留装饰脉冲`() {
        assertThat(shouldAnimateDecorativePulse(1f)).isTrue()
    }

    @Test
    fun `页面不可见或布局休眠时停止装饰脉冲`() {
        assertThat(shouldAnimateDecorativePulse(1f, isVisible = false, isSimulating = true)).isFalse()
        assertThat(shouldAnimateDecorativePulse(1f, isVisible = true, isSimulating = false)).isFalse()
        assertThat(shouldAnimateDecorativePulse(1f, isVisible = true, isSimulating = true)).isTrue()
    }
}
