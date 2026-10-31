package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InspectorStripLiftTest {
    @Test fun `向上拖动按可移动范围换算比例`() {
        assertThat(inspectorStripLift(0f, dragY = -250f, maxLiftPx = 1000f)).isWithin(1e-6f).of(0.25f)
        assertThat(inspectorStripLift(0.5f, dragY = 100f, maxLiftPx = 1000f)).isWithin(1e-6f).of(0.4f)
    }

    @Test fun `拖过上下沿时停在边界`() {
        assertThat(inspectorStripLift(0.9f, dragY = -500f, maxLiftPx = 1000f)).isEqualTo(1f)
        assertThat(inspectorStripLift(0.1f, dragY = 500f, maxLiftPx = 1000f)).isEqualTo(0f)
    }

    @Test fun `没有可移动范围时保持原位`() {
        assertThat(inspectorStripLift(0.3f, dragY = -200f, maxLiftPx = 0f)).isEqualTo(0.3f)
    }
}
