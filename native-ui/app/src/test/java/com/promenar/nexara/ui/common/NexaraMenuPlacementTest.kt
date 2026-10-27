package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Test

class NexaraMenuPlacementTest {
    private val window = IntSize(1000, 2000)
    private val limits = NexaraMenuLimits(marginPx = 16, topLimitPx = 100, bottomLimitPx = 1900, gapPx = 8)
    private val menu = IntSize(300, 400)

    private fun place(anchor: IntRect, touch: IntOffset? = null, ltr: Boolean = true, size: IntSize = menu) =
        nexaraMenuPlacement(anchor, touch, size, window, limits, ltr)

    @Test fun `按钮在左半边时菜单与按钮起点对齐，放在下方`() {
        val p = place(IntRect(100, 300, 160, 360))
        assertThat(p.offset).isEqualTo(IntOffset(100, 368))
        assertThat(p.originX).isEqualTo(0f)
        assertThat(p.originY).isEqualTo(0f)
    }

    @Test fun `按钮在右半边时菜单与按钮终点对齐，朝屏幕中部展开`() {
        val p = place(IntRect(900, 300, 960, 360))
        assertThat(p.offset).isEqualTo(IntOffset(660, 368))
        assertThat(p.originX).isEqualTo(1f)
    }

    @Test fun `下方放不下时翻到按钮上方`() {
        val p = place(IntRect(100, 1700, 160, 1760))
        assertThat(p.offset.y).isEqualTo(1700 - 8 - 400)
        assertThat(p.originY).isEqualTo(1f)
    }

    @Test fun `长按时从按住的位置长出`() {
        val p = place(IntRect(0, 500, 1000, 620), touch = IntOffset(240, 560))
        assertThat(p.offset).isEqualTo(IntOffset(240, 560))
        assertThat(p.originX).isEqualTo(0f)
        assertThat(p.originY).isEqualTo(0f)
    }

    @Test fun `按住点靠右或靠下时菜单向左、向上长出，不出屏`() {
        val p = place(IntRect(0, 1500, 1000, 1700), touch = IntOffset(900, 1650))
        assertThat(p.offset).isEqualTo(IntOffset(600, 1250))
        assertThat(p.originX).isEqualTo(1f)
        assertThat(p.originY).isEqualTo(1f)
    }

    @Test fun `从右到左布局时按住点向左长出`() {
        val p = place(IntRect(0, 500, 1000, 620), touch = IntOffset(700, 560), ltr = false)
        assertThat(p.offset.x).isEqualTo(400)
        assertThat(p.originX).isEqualTo(1f)
    }

    @Test fun `菜单比可用空间还高时贴着安全区并留边`() {
        val p = place(IntRect(100, 900, 160, 960), size = IntSize(300, 1900))
        assertThat(p.offset.y).isEqualTo(100)
        assertThat(p.offset.x).isAtLeast(16)
    }
}
