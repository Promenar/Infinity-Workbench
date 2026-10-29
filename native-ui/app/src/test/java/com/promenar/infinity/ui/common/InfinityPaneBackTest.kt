package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.navigation.PageLayer
import com.promenar.infinity.ui.common.InfinityPaneBackPhase.Cancelling
import com.promenar.infinity.ui.common.InfinityPaneBackPhase.Committed
import com.promenar.infinity.ui.common.InfinityPaneBackPhase.Dragging
import com.promenar.infinity.ui.common.InfinityPaneBackPhase.Idle
import com.promenar.infinity.ui.common.InfinityPaneBackPhase.Settling
import com.promenar.infinity.ui.theme.InfinityMotion
import org.junit.Test

/** 整页二级页面跟手返回的纯逻辑：进度到位移与压暗的映射、拖动谁、各层所处的位置。 */
class InfinityPaneBackTest {
    @Test
    fun `位移与压暗随进度线性变化，与导航页的跟手返回一致`() {
        val width = 400f
        assertThat(InfinityMotion.pagePredictiveTopOffset(0f, width)).isEqualTo(0f)
        assertThat(InfinityMotion.pagePredictiveTopOffset(0.5f, width)).isEqualTo(200f)
        assertThat(InfinityMotion.pagePredictiveTopOffset(1f, width)).isEqualTo(400f)
        // 下层从 -25% 回到原位。
        assertThat(InfinityMotion.pagePredictiveUnderOffset(0f, width)).isEqualTo(-100f)
        assertThat(InfinityMotion.pagePredictiveUnderOffset(0.5f, width)).isEqualTo(-50f)
        assertThat(InfinityMotion.pagePredictiveUnderOffset(1f, width)).isEqualTo(0f)
        assertThat(InfinityMotion.pagePredictiveUnderDim(0f)).isEqualTo(1f)
        assertThat(InfinityMotion.pagePredictiveUnderDim(0.25f)).isEqualTo(0.75f)
        assertThat(InfinityMotion.pagePredictiveUnderDim(1f)).isEqualTo(0f)
        // 越界进度按边界处理，不会移过整页或反向压暗。
        assertThat(InfinityMotion.pagePredictiveTopOffset(1.2f, width)).isEqualTo(400f)
        assertThat(InfinityMotion.pagePredictiveUnderDim(-0.1f)).isEqualTo(1f)
    }

    @Test
    fun `只有根层时拖动整个二级页面，多层时只拖最上层`() {
        assertThat(paneBackTarget(emptyMap())).isEqualTo(PANE_BACK_ROOT)
        assertThat(paneBackTarget(mapOf("explorer" to true))).isEqualTo(PANE_BACK_ROOT)
        assertThat(paneBackTarget(mapOf("explorer" to false, "git" to true))).isEqualTo("git")
        assertThat(paneBackTarget(mapOf("explorer" to false, "git" to false, "browser" to true))).isEqualTo("browser")
    }

    @Test
    fun `拖动整个二级页面时页面在上、主内容在下，各层原地不动`() {
        for (phase in listOf(Dragging, Settling, Cancelling)) {
            assertThat(paneBackLayer(phase, PANE_BACK_ROOT, PANE_BACK_ROOT, visible = true, top = true)).isEqualTo(PageLayer.TOP)
            assertThat(paneBackContentLayer(phase, PANE_BACK_ROOT)).isEqualTo(PageLayer.UNDER)
            assertThat(paneBackLayer(phase, PANE_BACK_ROOT, "explorer", visible = true, top = true)).isEqualTo(PageLayer.NONE)
        }
    }

    @Test
    fun `多层时最上层在上，其余可见层在下，主内容与整页不动`() {
        assertThat(paneBackLayer(Dragging, "git", "git", visible = true, top = true)).isEqualTo(PageLayer.TOP)
        assertThat(paneBackLayer(Dragging, "git", "explorer", visible = true, top = false)).isEqualTo(PageLayer.UNDER)
        // 正在退场、已不可见的层不参与视差。
        assertThat(paneBackLayer(Dragging, "git", "browser", visible = false, top = false)).isEqualTo(PageLayer.NONE)
        assertThat(paneBackLayer(Dragging, "git", PANE_BACK_ROOT, visible = true, top = true)).isEqualTo(PageLayer.NONE)
        assertThat(paneBackContentLayer(Dragging, "git")).isEqualTo(PageLayer.NONE)
    }

    @Test
    fun `松手弹出后被拖动的层退场期间保持移出，重新可见时回到原位，下层与主内容立即静止`() {
        assertThat(paneBackLayer(Committed, "git", "git", visible = false, top = false)).isEqualTo(PageLayer.TOP)
        assertThat(paneBackLayer(Committed, "git", "git", visible = true, top = true)).isEqualTo(PageLayer.NONE)
        assertThat(paneBackLayer(Committed, "git", "explorer", visible = true, top = true)).isEqualTo(PageLayer.NONE)
        assertThat(paneBackLayer(Committed, PANE_BACK_ROOT, PANE_BACK_ROOT, visible = false, top = true)).isEqualTo(PageLayer.TOP)
        assertThat(paneBackContentLayer(Committed, PANE_BACK_ROOT)).isEqualTo(PageLayer.NONE)
    }

    @Test
    fun `静止时不画任何层次`() {
        assertThat(paneBackLayer(Idle, null, "git", visible = true, top = true)).isEqualTo(PageLayer.NONE)
        assertThat(paneBackLayer(Idle, null, PANE_BACK_ROOT, visible = true, top = true)).isEqualTo(PageLayer.NONE)
        assertThat(paneBackContentLayer(Idle, null)).isEqualTo(PageLayer.NONE)
    }
}
