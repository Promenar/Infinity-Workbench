package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.ComposerGlowStyle
import com.promenar.infinity.ui.theme.InfinityMotionStyles
import com.promenar.infinity.ui.theme.ScopeScreenPlacement
import org.junit.Test

class ComposerSurfaceTest {

    @Test
    fun `环境光柔光从输入行右侧点阵屏处漫开`() {
        val anchor = composerWashAnchor(InfinityMotionStyles(), width = 1000f, height = 400f, lightsTop = 0f, dp = 2f)
        // 距右缘 (10 + 80)dp，距底边 68dp。
        assertThat(anchor.x).isEqualTo(1000f - 90 * 2f)
        assertThat(anchor.y).isEqualTo(400f - 136f)
    }

    @Test
    fun `点阵屏在顶沿时柔光从输入区上沿正中漫开`() {
        val styles = InfinityMotionStyles(composer = ComposerGlowStyle.SCOPE_SCREEN, scopePlacement = ScopeScreenPlacement.TOP_EDGE)
        val anchor = composerWashAnchor(styles, width = 1000f, height = 400f, lightsTop = 120f, dp = 2f)
        assertThat(anchor.x).isEqualTo(500f)
        assertThat(anchor.y).isEqualTo(120f)
    }

    @Test
    fun `窄浮岛上柔光中心不越过左半与上沿三成`() {
        val anchor = composerWashAnchor(InfinityMotionStyles(composer = ComposerGlowStyle.AURORA), width = 200f, height = 100f, lightsTop = 0f, dp = 3f)
        assertThat(anchor.x).isEqualTo(100f)
        assertThat(anchor.y).isWithin(1e-3f).of(30f)
    }
}
