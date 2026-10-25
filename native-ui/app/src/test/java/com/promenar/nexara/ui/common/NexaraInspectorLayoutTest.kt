package com.promenar.nexara.ui.common

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NexaraInspectorLayoutTest {
    @Test fun `折叠屏内屏与平板启用检视器，竖屏与横屏手机仍按窄窗口`() {
        assertThat(nexaraInspectorAvailable(814.dp, 898.dp)).isTrue()
        assertThat(nexaraInspectorAvailable(1280.dp, 800.dp)).isTrue()
        assertThat(nexaraInspectorAvailable(600.dp, 480.dp)).isTrue()
        assertThat(nexaraInspectorAvailable(411.dp, 892.dp)).isFalse()
        assertThat(nexaraInspectorAvailable(892.dp, 411.dp)).isFalse()
        assertThat(nexaraInspectorAvailable(599.dp, 900.dp)).isFalse()
    }

    @Test fun `加宽至多 560dp，并给会话留出至少 48dp`() {
        assertThat(nexaraInspectorWidth(814.dp, widened = false)).isEqualTo(360.dp)
        assertThat(nexaraInspectorWidth(814.dp, widened = true)).isEqualTo(560.dp)
        assertThat(nexaraInspectorWidth(600.dp, widened = true)).isEqualTo(552.dp)
        // 再窄也不比常规宽度更窄。
        assertThat(nexaraInspectorWidth(380.dp, widened = true)).isEqualTo(360.dp)
    }

    @Test fun `会话让位后不足 480dp 时检视器浮在会话之上`() {
        assertThat(nexaraInspectorPlacement(814.dp, 360.dp)).isEqualTo(NexaraInspectorPlacement.Float)
        assertThat(nexaraInspectorPlacement(1280.dp, 360.dp)).isEqualTo(NexaraInspectorPlacement.Shift)
        assertThat(nexaraInspectorPlacement(1280.dp, 560.dp)).isEqualTo(NexaraInspectorPlacement.Shift)
        assertThat(nexaraInspectorPlacement(864.dp, 360.dp)).isEqualTo(NexaraInspectorPlacement.Shift)
        assertThat(nexaraInspectorPlacement(863.dp, 360.dp)).isEqualTo(NexaraInspectorPlacement.Float)
    }
}
