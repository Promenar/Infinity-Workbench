package com.promenar.infinity.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InfinityThemeTokenTest {
    @Test
    fun `间距触控与层级令牌符合批准契约`() {
        assertThat(InfinitySpacing.XSmall.value).isEqualTo(4f)
        assertThat(InfinitySpacing.Small.value).isEqualTo(8f)
        assertThat(InfinitySpacing.Medium.value).isEqualTo(12f)
        assertThat(InfinitySpacing.Large.value).isEqualTo(16f)
        assertThat(InfinitySpacing.XLarge.value).isEqualTo(24f)
        assertThat(InfinitySpacing.XXLarge.value).isEqualTo(32f)
        assertThat(InfinitySpacing.MinimumTouchTarget.value).isEqualTo(48f)
        assertThat(InfinityElevation.Level0.value).isEqualTo(0f)
        assertThat(InfinityElevation.Level1.value).isEqualTo(1f)
        assertThat(InfinityElevation.Level2.value).isEqualTo(3f)
        assertThat(InfinityElevation.Level3.value).isEqualTo(6f)
    }

    @Test
    fun `形状令牌与阶梯符合批准契约`() {
        assertThat(InfinityShapeTokens.XSmall).isEqualTo(RoundedCornerShape(4.dp))
        assertThat(InfinityShapeTokens.Small).isEqualTo(RoundedCornerShape(8.dp))
        assertThat(InfinityShapeTokens.Medium).isEqualTo(RoundedCornerShape(12.dp))
        assertThat(InfinityShapeTokens.Large).isEqualTo(RoundedCornerShape(16.dp))
        assertThat(InfinityShapeTokens.XLarge).isEqualTo(RoundedCornerShape(24.dp))
    }

    @Test
    fun `真实字体与兼容别名符合批准契约`() {
        assertThat(InfinitySans).isEqualTo(FontFamily.SansSerif)
        // 等宽字体打包在应用内，不跟随会被厂商字体主题替换的系统 monospace。
        assertThat(InfinityMonospace).isNotEqualTo(FontFamily.Monospace)
        assertThat(Manrope).isEqualTo(InfinitySans)
        assertThat(Inter).isEqualTo(InfinitySans)
        assertThat(SpaceGrotesk).isEqualTo(InfinityMonospace)
    }

    @Test
    fun `字体使用契约符合辅助文案非全局等宽要求`() {
        assertThat(InfinityTypography.bodySmall.fontFamily).isEqualTo(InfinitySans)
    }

    @Test
    fun `深色主题完整映射 tonal surface 层级`() {
        assertThat(InfinityDarkColorScheme.surfaceDim).isEqualTo(Color(0xFF131315))
        assertThat(InfinityDarkColorScheme.surfaceBright).isEqualTo(Color(0xFF39393B))
        assertThat(InfinityDarkColorScheme.surfaceContainerLowest).isEqualTo(Color(0xFF0E0E10))
        assertThat(InfinityDarkColorScheme.surfaceContainerLow).isEqualTo(Color(0xFF1C1B1D))
        assertThat(InfinityDarkColorScheme.surfaceContainer).isEqualTo(Color(0xFF201F22))
        assertThat(InfinityDarkColorScheme.surfaceContainerHigh).isEqualTo(Color(0xFF2A2A2C))
        assertThat(InfinityDarkColorScheme.surfaceContainerHighest).isEqualTo(Color(0xFF353437))
    }

    @Test
    fun `次级容器为深色消息容器且前景保持高对比`() {
        assertThat(InfinityDarkColorScheme.secondaryContainer).isEqualTo(Color(0xFF2A2A2C))
        assertThat(InfinityDarkColorScheme.secondaryContainer)
            .isEqualTo(InfinityDarkColorScheme.surfaceContainerHigh)
        assertThat(InfinityDarkColorScheme.onSecondaryContainer).isEqualTo(Color(0xFFE5E1E4))
        assertThat(InfinityDarkColorScheme.onSecondaryContainer)
            .isEqualTo(InfinityDarkColorScheme.onSurface)
    }

    @Test
    fun `浅色主题完整映射 tonal surface 层级`() {
        assertThat(InfinityLightColorScheme.background).isNotEqualTo(Color.Unspecified)
        assertThat(InfinityLightColorScheme.surfaceDim).isNotEqualTo(Color.Unspecified)
        assertThat(InfinityLightColorScheme.surfaceBright).isNotEqualTo(Color.Unspecified)
        assertThat(InfinityLightColorScheme.surfaceContainerLowest).isNotEqualTo(Color.Unspecified)
        assertThat(InfinityLightColorScheme.surfaceContainerLow).isNotEqualTo(Color.Unspecified)
        assertThat(InfinityLightColorScheme.surfaceContainer).isNotEqualTo(Color.Unspecified)
        assertThat(InfinityLightColorScheme.surfaceContainerHigh).isNotEqualTo(Color.Unspecified)
        assertThat(InfinityLightColorScheme.surfaceContainerHighest).isNotEqualTo(Color.Unspecified)
        assertThat(InfinityLightColorScheme.background).isNotEqualTo(InfinityDarkColorScheme.background)
    }
}
