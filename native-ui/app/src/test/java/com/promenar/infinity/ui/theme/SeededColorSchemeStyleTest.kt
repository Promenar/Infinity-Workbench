package com.promenar.infinity.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

class SeededColorSchemeStyleTest {
    private fun saturation(color: Color): Float {
        val maxC = max(color.red, max(color.green, color.blue))
        val minC = min(color.red, min(color.green, color.blue))
        return if (maxC <= 0f) 0f else (maxC - minC) / maxC
    }

    @Test
    fun `高饱和种子色在柔和风格下被压低，在忠实与鲜艳风格下保持鲜明`() {
        val seed = Color(0xFFFF1744)
        val soft = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.TONAL_SPOT).primary
        val faithful = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.FIDELITY).primary
        val vibrant = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.VIBRANT).primary

        assertThat(saturation(faithful)).isGreaterThan(saturation(soft) + 0.2f)
        assertThat(saturation(vibrant)).isGreaterThan(saturation(soft))
    }

    @Test
    fun `表现力风格的第三色与柔和风格不同`() {
        val seed = Color(0xFF6366F1)
        val soft = seededColorScheme(seed, dark = true, style = InfinitySchemeStyle.TONAL_SPOT)
        val expressive = seededColorScheme(seed, dark = true, style = InfinitySchemeStyle.EXPRESSIVE)
        assertThat(expressive.tertiary).isNotEqualTo(soft.tertiary)
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    /**
     * 冰蓝种子在柔和风格下：深色主色接近 #9FD3FF（每个通道相差不超过 24），浅色主色与其上文字、与白底的对比度都不低于 4.5。
     * 种子色是待主控验证的候选值，首次运行后把实测的深浅主色补记到这里。
     */
    @Test
    fun `冰蓝预设深色主色接近目标冰蓝，浅色模式对比度合格`() {
        val target = Color(0xFF9FD3FF)
        val dark = seededColorScheme(InfinityIceBlueSeed, dark = true).primary
        val light = seededColorScheme(InfinityIceBlueSeed, dark = false)

        assertThat(abs(dark.red - target.red) * 255).isAtMost(24f)
        assertThat(abs(dark.green - target.green) * 255).isAtMost(24f)
        assertThat(abs(dark.blue - target.blue) * 255).isAtMost(24f)
        assertThat(contrast(light.primary, light.onPrimary)).isAtLeast(4.5f)
        assertThat(contrast(light.primary, Color.White)).isAtLeast(4.5f)
    }
}
