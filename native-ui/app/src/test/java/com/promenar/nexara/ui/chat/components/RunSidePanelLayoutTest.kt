package com.promenar.nexara.ui.chat.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 运行侧栏的窗口门槛：折叠屏内屏放得下侧栏，竖屏与横屏手机仍用运行坞。 */
class RunSidePanelLayoutTest {
    private fun fits(width: Dp, height: Dp) = width >= RunSidePanelMinWindowWidth && height >= RunSidePanelMinWindowHeight

    private fun dp(px: Int, density: Int): Dp = (px * 160f / density).dp

    @Test
    fun `折叠屏内屏在默认与放大显示尺寸下都显示侧栏`() {
        // OPPO Find N5 内屏 2248x2480：默认 520dpi 约 692dp 宽，显示放大到 442dpi 约 814dp 宽。
        assertThat(fits(dp(2248, 520), dp(2480, 520))).isTrue()
        assertThat(fits(dp(2248, 442), dp(2480, 442))).isTrue()
    }

    @Test
    fun `手机竖屏与横屏不显示侧栏`() {
        // 同机外屏 1140x2616 @442dpi：竖屏约 413dp 宽，横屏高度约 413dp。
        assertThat(fits(dp(1140, 442), dp(2616, 442))).isFalse()
        assertThat(fits(dp(2616, 442), dp(1140, 442))).isFalse()
    }

    @Test
    fun `侧栏出现时会话列不窄于常见手机宽度`() {
        val chat = RunSidePanelMinWindowWidth - RunSidePanelWidth - 1.dp
        assertThat(chat.value).isAtLeast(RunSidePanelMinChatWidth.value)
        assertThat(RunSidePanelMinChatWidth.value).isAtLeast(360f)
    }
}
