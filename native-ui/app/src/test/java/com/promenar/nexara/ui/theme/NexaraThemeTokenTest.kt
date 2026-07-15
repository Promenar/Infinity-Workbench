package com.promenar.nexara.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
        assertThat(NexaraElevation.Level1.value).isEqualTo(1f)
        assertThat(NexaraElevation.Level2.value).isEqualTo(3f)
        assertThat(NexaraElevation.Level3.value).isEqualTo(6f)
    }

    @Test
    fun `形状令牌与阶梯符合批准契约`() {
        assertThat(NexaraShapeTokens.XSmall).isEqualTo(RoundedCornerShape(4.dp))
        assertThat(NexaraShapeTokens.Small).isEqualTo(RoundedCornerShape(8.dp))
        assertThat(NexaraShapeTokens.Medium).isEqualTo(RoundedCornerShape(12.dp))
        assertThat(NexaraShapeTokens.Large).isEqualTo(RoundedCornerShape(16.dp))
        assertThat(NexaraShapeTokens.XLarge).isEqualTo(RoundedCornerShape(24.dp))
    }

    @Test
    fun `真实字体与兼容别名符合批准契约`() {
        assertThat(NexaraSans).isEqualTo(FontFamily.SansSerif)
        assertThat(NexaraMonospace).isEqualTo(FontFamily.Monospace)
        assertThat(Manrope).isEqualTo(NexaraSans)
        assertThat(Inter).isEqualTo(NexaraSans)
        assertThat(SpaceGrotesk).isEqualTo(NexaraMonospace)
    }

    @Test
    fun `字体使用契约符合辅助文案非全局等宽要求`() {
        assertThat(NexaraTypography.bodySmall.fontFamily).isEqualTo(NexaraSans)
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

    @Test
    fun `次级容器为深色消息容器且前景保持高对比`() {
        assertThat(NexaraColors.SecondaryContainer).isEqualTo(Color(0xFF2A2A2C))
        assertThat(NexaraColors.SecondaryContainer).isEqualTo(NexaraColors.SurfaceHigh)
        assertThat(NexaraColors.OnSecondaryContainer).isEqualTo(Color(0xFFE5E1E4))
        assertThat(NexaraColors.OnSecondaryContainer).isEqualTo(NexaraColors.OnSurface)
        assertThat(NexaraDarkColorScheme.secondaryContainer).isEqualTo(NexaraColors.SecondaryContainer)
        assertThat(NexaraDarkColorScheme.onSecondaryContainer).isEqualTo(NexaraColors.OnSecondaryContainer)
    }
}
