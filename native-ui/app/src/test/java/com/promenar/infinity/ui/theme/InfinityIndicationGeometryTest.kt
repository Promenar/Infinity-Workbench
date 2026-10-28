package com.promenar.infinity.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InfinityIndicationGeometryTest {
    @Test
    fun `圆角不超过上限也不超过短边一半`() {
        assertThat(indicationCornerRadius(Size(300f, 120f), maxCornerPx = 36f)).isEqualTo(36f)
        // 细长标签呈胶囊形。
        assertThat(indicationCornerRadius(Size(300f, 40f), maxCornerPx = 36f)).isEqualTo(20f)
        assertThat(indicationCornerRadius(Size.Zero, maxCornerPx = 36f)).isEqualTo(0f)
    }

    @Test
    fun `水波半径覆盖到离按点最远的角`() {
        val size = Size(300f, 40f)

        assertThat(indicationWaveRadius(size, Offset(0f, 0f))).isWithin(1e-3f).of(kotlin.math.hypot(300f, 40f))
        assertThat(indicationWaveRadius(size, Offset(150f, 20f))).isWithin(1e-3f).of(kotlin.math.hypot(150f, 20f))
        assertThat(indicationWaveRadius(size, Offset(290f, 5f))).isWithin(1e-3f).of(kotlin.math.hypot(290f, 35f))
    }
}
