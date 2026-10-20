package com.promenar.nexara.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GenerationStatsPopupPositionTest {
    // 1140×2616 窗口：状态栏 107px、导航栏 45px，边距 44px，与锚点间隔 11px。
    private val window = IntSize(1140, 2616)
    private val bounds = GenerationStatsPopupBounds(
        topLimitPx = 107 + 44,
        bottomLimitPx = 2616 - 45 - 44,
        marginPx = 44,
        gapPx = 11,
    )
    private val provider = AboveAnchorPositionProvider(bounds)

    private fun position(anchor: IntRect, contentHeight: Int, width: Int = 940, direction: LayoutDirection = LayoutDirection.Ltr): IntOffset =
        provider.calculatePosition(anchor, window, direction, IntSize(width, contentHeight))

    @Test
    fun placesAboveAnchorWhenItFits() {
        val anchor = IntRect(690, 2000, 1085, 2133)
        val offset = position(anchor, contentHeight = 1200)
        assertThat(offset.y).isEqualTo(2000 - 11 - 1200)
        assertThat(offset.x).isEqualTo(1085 - 940)
    }

    @Test
    fun placesBelowWhenAboveWouldEnterStatusBarArea() {
        // 锚点靠近顶部：上方只有 ~860px，放在上方会进入状态栏下沿的边距区。
        val anchor = IntRect(690, 1014, 1085, 1147)
        val offset = position(anchor, contentHeight = 1250)
        assertThat(offset.y).isEqualTo(1147 + 11)
        assertThat(offset.y + 1250).isAtMost(bounds.bottomLimitPx)
    }

    @Test
    fun neverStartsAboveSafeTopEvenWhenNeitherSideFits() {
        val anchor = IntRect(690, 1300, 1085, 1433)
        val tall = 2000
        val offset = position(anchor, contentHeight = tall)
        // 上方空间更大，贴住安全上边界而不是屏幕顶端。
        assertThat(bounds.spaceAbove(anchor)).isGreaterThan(bounds.spaceBelow(anchor))
        assertThat(offset.y).isAtLeast(bounds.topLimitPx)
    }

    @Test
    fun availableHeightKeepsCardOffAnchor() {
        val anchor = IntRect(690, 1014, 1085, 1147)
        val available = bounds.availableHeight(anchor)
        assertThat(available).isEqualTo(bounds.spaceBelow(anchor))
        // 限高后的浮卡放在下方时不越过安全下边界，也不盖住锚点。
        val offset = position(anchor, contentHeight = available)
        assertThat(offset.y).isAtLeast(anchor.bottom)
        assertThat(offset.y + available).isAtMost(bounds.bottomLimitPx)
    }

    @Test
    fun spacesNeverNegative() {
        val anchor = IntRect(0, 50, 100, 120)
        assertThat(bounds.spaceAbove(anchor)).isEqualTo(0)
        assertThat(bounds.spaceBelow(IntRect(0, 2600, 100, 2616))).isEqualTo(0)
    }

    @Test
    fun alignsToAnchorStartInRtlAndKeepsHorizontalMargin() {
        val anchor = IntRect(10, 2000, 400, 2133)
        val offset = position(anchor, contentHeight = 800, direction = LayoutDirection.Rtl)
        assertThat(offset.x).isEqualTo(44)
    }
}
