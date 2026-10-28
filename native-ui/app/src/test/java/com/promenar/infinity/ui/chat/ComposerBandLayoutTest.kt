package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.ComposerGlowStyle
import com.promenar.infinity.ui.theme.InfinityMotionStyles
import com.promenar.infinity.ui.theme.ScopeScreenPlacement
import org.junit.Test

class ComposerBandLayoutTest {
    private fun scope(placement: ScopeScreenPlacement) =
        InfinityMotionStyles(composer = ComposerGlowStyle.SCOPE_SCREEN, scopePlacement = placement)

    @Test
    fun `输入行右侧模式：点阵屏在输入行，运行坞出现时照常画细灯带`() {
        assertThat(composerBandLayout(scope(ScopeScreenPlacement.INPUT_ROW), dockVisible = false, screenActive = true))
            .isEqualTo(ComposerBandLayout(ScopeSlot.INPUT_ROW, dockStrip = false))
        assertThat(composerBandLayout(scope(ScopeScreenPlacement.INPUT_ROW), dockVisible = true, screenActive = true))
            .isEqualTo(ComposerBandLayout(ScopeSlot.INPUT_ROW, dockStrip = true))
    }

    @Test
    fun `顶沿模式：没有运行坞时在浮岛顶沿，有运行坞时到接缝处顶替细灯带`() {
        assertThat(composerBandLayout(scope(ScopeScreenPlacement.TOP_EDGE), dockVisible = false, screenActive = true))
            .isEqualTo(ComposerBandLayout(ScopeSlot.ISLAND_TOP, dockStrip = false))
        assertThat(composerBandLayout(scope(ScopeScreenPlacement.TOP_EDGE), dockVisible = true, screenActive = true))
            .isEqualTo(ComposerBandLayout(ScopeSlot.DOCK_SEAM, dockStrip = false))
    }

    @Test
    fun `顶沿模式的点阵屏不在显示时收起，运行坞画细灯带`() {
        assertThat(composerBandLayout(scope(ScopeScreenPlacement.TOP_EDGE), dockVisible = true, screenActive = false))
            .isEqualTo(ComposerBandLayout(ScopeSlot.NONE, dockStrip = true))
        assertThat(composerBandLayout(scope(ScopeScreenPlacement.TOP_EDGE), dockVisible = false, screenActive = false))
            .isEqualTo(ComposerBandLayout(ScopeSlot.NONE, dockStrip = false))
    }

    @Test
    fun `其它光效样式不画点阵屏，运行坞出现就画细灯带`() {
        for (style in ComposerGlowStyle.entries - ComposerGlowStyle.SCOPE_SCREEN) {
            val styles = InfinityMotionStyles(composer = style, scopePlacement = ScopeScreenPlacement.TOP_EDGE)
            assertThat(composerBandLayout(styles, dockVisible = true, screenActive = true))
                .isEqualTo(ComposerBandLayout(ScopeSlot.NONE, dockStrip = true))
        }
    }

    @Test
    fun `点阵屏缺省在输入行右侧并显示数字，无法识别的存值回到缺省位置`() {
        assertThat(InfinityMotionStyles().scopePlacement).isEqualTo(ScopeScreenPlacement.INPUT_ROW)
        assertThat(InfinityMotionStyles().scopeDigits).isTrue()
        assertThat(ScopeScreenPlacement.fromPreferenceValue("top_edge")).isEqualTo(ScopeScreenPlacement.TOP_EDGE)
        assertThat(ScopeScreenPlacement.fromPreferenceValue(null)).isEqualTo(ScopeScreenPlacement.INPUT_ROW)
        assertThat(ScopeScreenPlacement.fromPreferenceValue("sideways")).isEqualTo(ScopeScreenPlacement.INPUT_ROW)
    }

    @Test
    fun `点阵屏贴在输入区上沿：运行坞出现或收起时槽位相对输入区不变`() {
        // ISLAND_TOP 与 DOCK_SEAM 都是输入区正上方的同一槽位，只是上面是否多了运行坞。
        val without = composerBandLayout(scope(ScopeScreenPlacement.TOP_EDGE), dockVisible = false, screenActive = true).scope
        val with = composerBandLayout(scope(ScopeScreenPlacement.TOP_EDGE), dockVisible = true, screenActive = true).scope
        assertThat(setOf(without, with)).containsExactly(ScopeSlot.ISLAND_TOP, ScopeSlot.DOCK_SEAM)
    }
}
