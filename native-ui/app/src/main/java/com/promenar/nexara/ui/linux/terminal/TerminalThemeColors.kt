package com.promenar.nexara.ui.linux.terminal

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.promenar.nexara.ui.linux.output.AnsiColor
import com.promenar.nexara.ui.linux.output.AnsiPalette
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import java.util.WeakHashMap

/**
 * 本地终端的主题配色：默认前景、背景、光标与 ANSI 16 色跟随应用深浅色。
 * 终端库的默认值是黑底白字，浅色主题下直接使用会在白色终端区里看不到文字。
 */
@Immutable
data class TerminalThemeColors(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val ansi: List<Int>,
) {
    init {
        require(ansi.size == 16)
    }

    private fun slot(index: Int): Int? = when (index) {
        in 0..15 -> ansi[index]
        TextStyle.COLOR_INDEX_FOREGROUND -> foreground
        TextStyle.COLOR_INDEX_BACKGROUND -> background
        TextStyle.COLOR_INDEX_CURSOR -> cursor
        else -> null
    }

    /**
     * 只替换仍是库默认值或上一次主题值的槽位；程序经 OSC 4/10/11 自定义的颜色保持不变。
     * 复位序列会把槽位恢复为库默认值，再次调用即可换回主题色。返回是否有槽位变化。
     */
    fun applyTo(current: IntArray, defaults: IntArray, previous: TerminalThemeColors?): Boolean {
        var changed = false
        for (index in THEMED_SLOTS) {
            val value = slot(index) ?: continue
            val owned = current[index] == defaults[index] || current[index] == previous?.slot(index)
            if (owned && current[index] != value) {
                current[index] = value
                changed = true
            }
        }
        return changed
    }

    companion object {
        private val THEMED_SLOTS = (0..15) + listOf(
            TextStyle.COLOR_INDEX_FOREGROUND,
            TextStyle.COLOR_INDEX_BACKGROUND,
            TextStyle.COLOR_INDEX_CURSOR,
        )

        /** 背景与终端区 Surface 同色，保证反显和清屏后的底色与容器一致。 */
        fun from(scheme: ColorScheme): TerminalThemeColors {
            val background = scheme.surfaceContainerLowest
            val palette = if (background.luminance() < 0.5f) AnsiPalette.Dark else AnsiPalette.Light
            return TerminalThemeColors(
                foreground = scheme.onSurface.toArgb(),
                background = background.toArgb(),
                cursor = scheme.primary.toArgb(),
                ansi = (0..15).map { palette.color(AnsiColor.Indexed(it)).toArgb() },
            )
        }
    }
}

/** 记录每个终端颜色表最近一次套用的主题，用于切换深浅色时识别哪些槽位仍归主题管理。会话在主线程处理输出与重绘。 */
internal object TerminalThemeApplier {
    private val applied = WeakHashMap<TerminalColors, TerminalThemeColors>()

    fun apply(colors: TerminalColors, theme: TerminalThemeColors): Boolean {
        val changed = theme.applyTo(colors.mCurrentColors, TerminalColors.COLOR_SCHEME.mDefaultColors, applied[colors])
        applied[colors] = theme
        return changed
    }
}
