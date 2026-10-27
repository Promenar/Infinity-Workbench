package com.promenar.nexara.ui.common.motion

import com.promenar.nexara.ui.theme.NexaraPureWhite
import com.promenar.nexara.ui.theme.NexaraPureBlack
import com.promenar.nexara.ui.theme.NexaraLedFaceplateLight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.promenar.nexara.ui.chat.vividAccent
import com.promenar.nexara.ui.common.hueMidpoint
import com.promenar.nexara.ui.theme.nexaraDomainColors

/**
 * 灯语动效的配色，全部跟随主题色：[a] 为主色灯，[b] 为第三色（扮演交换机面板上的琥珀灯），[mid] 为两者色相中点，
 * [hot] 为高亮灯芯。浅色主题的主色偏深，提亮加饱和后才像“发光”。
 */
@Immutable
internal data class MotionPalette(
    val a: Color,
    val b: Color,
    val mid: Color,
    val hot: Color,
    val outline: Color,
    val muted: Color,
    val dark: Boolean,
) {
    /** 熄灭灯的底色。 */
    val off: Color get() = a.copy(alpha = if (dark) 0.14f else 0.18f)

    /** 面板底板：深色压暗，浅色只留一层淡灰。 */
    val faceplate: Color get() = if (dark) NexaraPureBlack.copy(alpha = 0.5f) else NexaraLedFaceplateLight

    val leds: List<Color> get() = listOf(a, b)
}

@Composable
internal fun rememberMotionPalette(): MotionPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = MaterialTheme.nexaraDomainColors.isDark
    return remember(scheme.primary, scheme.tertiary, scheme.outlineVariant, scheme.onSurfaceVariant, dark) {
        val a = if (dark) scheme.primary else vividAccent(scheme.primary)
        val b = if (dark) scheme.tertiary else vividAccent(scheme.tertiary)
        MotionPalette(
            a = a,
            b = b,
            mid = hueMidpoint(a, b),
            hot = if (dark) lerp(a, NexaraPureWhite, 0.55f) else a,
            outline = scheme.outlineVariant,
            muted = scheme.onSurfaceVariant,
            dark = dark,
        )
    }
}
