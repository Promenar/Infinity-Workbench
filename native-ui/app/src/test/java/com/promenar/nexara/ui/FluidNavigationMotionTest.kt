package com.promenar.nexara.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FluidNavigationMotionTest {
    @Test
    fun `Bettbox 式底栏为选中项分配更宽但稳定的布局权重`() {
        assertThat(bottomNavigationTabWeight(selected = true)).isEqualTo(1.45f)
        assertThat(bottomNavigationTabWeight(selected = false)).isEqualTo(1f)
    }
}
