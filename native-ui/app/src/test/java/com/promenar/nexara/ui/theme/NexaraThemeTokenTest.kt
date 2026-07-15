package com.promenar.nexara.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NexaraThemeTokenTest {
    @Test
    fun `间距触控与层级令牌符合批准契约`() {
        assertThat(NexaraSpacing.XSmall.value).isEqualTo(4f)
        assertThat(NexaraSpacing.Small.value).isEqualTo(8f)
        assertThat(NexaraSpacing.Medium.value).isEqualTo(12f)
        assertThat(NexaraSpacing.Large.value).isEqualTo(16f)
        assertThat(NexaraSpacing.XLarge.value).isEqualTo(24f)
        assertThat(NexaraSpacing.XXLarge.value).isEqualTo(32f)
        assertThat(NexaraSpacing.MinimumTouchTarget.value).isEqualTo(48f)
        assertThat(NexaraElevation.Level0.value).isEqualTo(0f)
        assertThat(NexaraElevation.Level3.value).isEqualTo(6f)
    }

    @Test
    fun `深色主题完整映射 tonal surface 层级`() {
        assertThat(NexaraDarkColorScheme.surfaceDim).isEqualTo(NexaraColors.SurfaceDim)
        assertThat(NexaraDarkColorScheme.surfaceBright).isEqualTo(NexaraColors.SurfaceBright)
        assertThat(NexaraDarkColorScheme.surfaceContainerLowest).isEqualTo(NexaraColors.SurfaceLowest)
        assertThat(NexaraDarkColorScheme.surfaceContainerLow).isEqualTo(NexaraColors.SurfaceLow)
        assertThat(NexaraDarkColorScheme.surfaceContainer).isEqualTo(NexaraColors.SurfaceContainer)
        assertThat(NexaraDarkColorScheme.surfaceContainerHigh).isEqualTo(NexaraColors.SurfaceHigh)
        assertThat(NexaraDarkColorScheme.surfaceContainerHighest).isEqualTo(NexaraColors.SurfaceHighest)
    }
}
