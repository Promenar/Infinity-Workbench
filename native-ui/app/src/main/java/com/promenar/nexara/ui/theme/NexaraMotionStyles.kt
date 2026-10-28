package com.promenar.nexara.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** 生成中输入框浮岛的光效样式。 */
enum class ComposerGlowStyle {
    /** 光带绕浮岛环行，外圈带噪声光晕。 */
    AURORA,
    /** 整圈描边随呼吸节奏涨落。 */
    BREATHE,
    /** 浮岛右半像冲孔网罩，状态灯在网后闪烁。 */
    MESH,
    /** 底沿内侧两排细灯，数据包从左往右流过。 */
    BOTTOM_STRIP,
    /** 一块点阵信息屏：左侧数字读数，右侧不断左移的速率柱状图；位置与数字见 [ScopeScreenPlacement] 与 [NexaraMotionStyles.scopeDigits]。 */
    SCOPE_SCREEN,
    /** 斜向光带扫过浮岛里隐藏的灯珠墙。 */
    SWEEP_GRID;

    companion object {
        internal fun fromPreferenceValue(value: String?): ComposerGlowStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AURORA
    }
}

/** 点阵波形屏在浮岛里的位置。 */
enum class ScopeScreenPlacement {
    /** 输入行右侧一块 7 行点阵。 */
    INPUT_ROW,
    /** 浮岛顶沿一条 5 行点阵，铺满浮岛内宽，浮岛相应增高；运行坞出现时位于运行坞与输入区的接缝处。 */
    TOP_EDGE;

    companion object {
        internal fun fromPreferenceValue(value: String?): ScopeScreenPlacement =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INPUT_ROW
    }
}

/** 生成状态指示的样式；会话气泡、正文光标与首页会话列表共用。 */
enum class GenerationIndicatorStyle {
    /** 三点依次上浮。 */
    WAVE,
    /** 三组网口灯：左灯常亮、右灯高频闪烁。 */
    PORTS,
    /** 10×3 点阵按负载场亮灭。 */
    MATRIX,
    /** 上下两排共 16 个交换机口。 */
    STACKED,
    /** 四个硬盘位，各一颗电源灯与活动灯。 */
    BAYS,
    /** 两个光头沿 ∞ 反向追逐。 */
    INFINITY_LINE,
    /** 24 颗方灯排成 ∞，数据包沿 8 字跑圈。 */
    INFINITY_LED;

    companion object {
        internal fun fromPreferenceValue(value: String?): GenerationIndicatorStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WAVE
    }
}

/** 空会话的背景动画样式。 */
enum class EmptyChatStyle {
    /** 字标由弹簧粒子拼成，环境粒子沿流场漂移。 */
    FLOW,
    /** 字标排成灯珠点阵，数据光带扫过。 */
    LED_WORDMARK,
    /** 粒子铺满 ∞ 形莫比乌斯带流动。 */
    MOBIUS,
    /** 莫比乌斯带上固定排着灯珠。 */
    MOBIUS_LED,
    /** 粒子在字标与莫比乌斯带之间往复变形。 */
    MORPH,
    /** 灯珠排成缓缓自转的球。 */
    LED_GLOBE;

    companion object {
        internal fun fromPreferenceValue(value: String?): EmptyChatStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FLOW
    }
}

/** 由种子色生成配色的算法，对应 Material Color Utilities 的几种方案。 */
enum class NexaraSchemeStyle {
    /** 柔和：压低种子色饱和度，整体沉稳。 */
    TONAL_SPOT,
    /** 鲜艳：主色保持高饱和。 */
    VIBRANT,
    /** 忠实：主色尽量贴近所选颜色。 */
    FIDELITY,
    /** 表现力：辅色与第三色偏移色相，对比更强。 */
    EXPRESSIVE;

    companion object {
        internal fun fromPreferenceValue(value: String?): NexaraSchemeStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TONAL_SPOT
    }
}

@Immutable
data class NexaraMotionStyles(
    val composer: ComposerGlowStyle = ComposerGlowStyle.AURORA,
    val indicator: GenerationIndicatorStyle = GenerationIndicatorStyle.WAVE,
    val emptyChat: EmptyChatStyle = EmptyChatStyle.FLOW,
    /** 点阵波形屏的位置，只在 [composer] 为 [ComposerGlowStyle.SCOPE_SCREEN] 时生效。 */
    val scopePlacement: ScopeScreenPlacement = ScopeScreenPlacement.INPUT_ROW,
    /** 点阵波形屏是否显示数字读数；关闭时波形铺满整块屏。 */
    val scopeDigits: Boolean = true,
)

/** 当前生效的动效样式；由 [NexaraTheme] 按外观设置提供，设置页预览可以局部覆盖。 */
val LocalNexaraMotionStyles = staticCompositionLocalOf { NexaraMotionStyles() }
