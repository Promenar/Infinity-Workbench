package com.promenar.infinity.ui.linux

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.linux.terminal.TerminalThemeColors
import com.promenar.infinity.ui.theme.InfinityDarkColorScheme
import com.promenar.infinity.ui.theme.InfinityLightColorScheme
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import org.junit.Test

class TerminalThemeColorsTest {
    private val light = TerminalThemeColors.from(InfinityLightColorScheme)
    private val dark = TerminalThemeColors.from(InfinityDarkColorScheme)
    private val defaults = TerminalColors.COLOR_SCHEME.mDefaultColors
    private val fg = TextStyle.COLOR_INDEX_FOREGROUND
    private val bg = TextStyle.COLOR_INDEX_BACKGROUND

    private fun contrast(a: Int, b: Int): Float {
        val (high, low) = listOf(Color(a).luminance(), Color(b).luminance()).sortedDescending()
        return (high + 0.05f) / (low + 0.05f)
    }

    @Test
    fun `浅色主题前景是深色且与背景对比足够`() {
        assertThat(Color(light.background).luminance()).isGreaterThan(0.5f)
        assertThat(contrast(light.foreground, light.background)).isAtLeast(7f)
        // ANSI 白色在浅色底映射为深色，ls 等默认着色仍可读。
        assertThat(contrast(light.ansi[7], light.background)).isAtLeast(4.5f)
        assertThat(contrast(dark.foreground, dark.background)).isAtLeast(7f)
    }

    @Test
    fun `新会话的默认槽位换成主题色，扩展色保持xterm默认`() {
        val colors = TerminalColors()
        assertThat(light.applyTo(colors.mCurrentColors, defaults, null)).isTrue()
        assertThat(colors.mCurrentColors[fg]).isEqualTo(light.foreground)
        assertThat(colors.mCurrentColors[bg]).isEqualTo(light.background)
        assertThat(colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR]).isEqualTo(light.cursor)
        assertThat(colors.mCurrentColors.copyOfRange(0, 16).toList()).isEqualTo(light.ansi)
        assertThat(colors.mCurrentColors.copyOfRange(16, 256)).isEqualTo(defaults.copyOfRange(16, 256))
        assertThat(light.applyTo(colors.mCurrentColors, defaults, light)).isFalse()
    }

    @Test
    fun `切换深浅色只替换主题管理的槽位，程序自定义颜色保留`() {
        val colors = TerminalColors()
        light.applyTo(colors.mCurrentColors, defaults, null)
        val custom = 0xFF123456.toInt()
        colors.mCurrentColors[1] = custom
        dark.applyTo(colors.mCurrentColors, defaults, light)
        assertThat(colors.mCurrentColors[fg]).isEqualTo(dark.foreground)
        assertThat(colors.mCurrentColors[2]).isEqualTo(dark.ansi[2])
        assertThat(colors.mCurrentColors[1]).isEqualTo(custom)
    }

    @Test
    fun `复位恢复库默认值后再次套用回到主题色`() {
        val colors = TerminalColors()
        light.applyTo(colors.mCurrentColors, defaults, null)
        colors.reset()
        assertThat(colors.mCurrentColors[fg]).isEqualTo(defaults[fg])
        assertThat(light.applyTo(colors.mCurrentColors, defaults, light)).isTrue()
        assertThat(colors.mCurrentColors[fg]).isEqualTo(light.foreground)
    }
}
