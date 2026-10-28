package com.promenar.infinity.ui.common

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InfinityPopoverPositionTest {
    // 1140×2616 窗口：状态栏 107px、导航栏 45px，边距 44px，与锚点间隔 11px。
    private val window = IntSize(1140, 2616)
    private val bounds = InfinityPopoverBounds(
        topLimitPx = 107 + 44,
        bottomLimitPx = 2616 - 45 - 44,
        marginPx = 44,
        gapPx = 11,
    )

    private fun position(
        anchor: IntRect,
        contentHeight: Int,
        width: Int = 940,
        direction: LayoutDirection = LayoutDirection.Ltr,
        side: InfinityPopoverSide = InfinityPopoverSide.Above,
        align: InfinityPopoverAlign = InfinityPopoverAlign.End,
        override: IntRect? = null,
    ): IntOffset = InfinityAnchorPositionProvider(bounds, side, align, override)
        .calculatePosition(anchor, window, direction, IntSize(width, contentHeight))

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
        val offset = position(anchor, contentHeight = 2000)
        // 上方空间更大，贴住安全上边界而不是屏幕顶端。
        assertThat(bounds.spaceAbove(anchor)).isGreaterThan(bounds.spaceBelow(anchor))
        assertThat(offset.y).isAtLeast(bounds.topLimitPx)
    }

    @Test
    fun availableHeightKeepsCardOffAnchor() {
        val anchor = IntRect(690, 1014, 1085, 1147)
        val available = bounds.availableHeight(anchor)
        assertThat(available).isEqualTo(bounds.spaceBelow(anchor))
        // 限高后放在下方时不越过安全下边界，也不盖住锚点。
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

    @Test
    fun belowPreferenceOpensDownwardAndFallsBackAbove() {
        // 设置行、顶栏菜单：下方放得下就向下弹出。
        val row = IntRect(200, 600, 1000, 760)
        assertThat(position(row, contentHeight = 500, side = InfinityPopoverSide.Below).y).isEqualTo(760 + 11)
        // 行贴近底部、下方放不下时改到上方。
        val lowRow = IntRect(200, 2300, 1000, 2460)
        assertThat(position(lowRow, contentHeight = 500, side = InfinityPopoverSide.Below).y).isEqualTo(2300 - 11 - 500)
    }

    @Test
    fun startAlignmentFollowsAnchorLeftEdgeAndMirrorsInRtl() {
        // 输入区左侧的模型按钮：弹出层左缘与按钮左缘对齐。
        val chip = IntRect(150, 2400, 600, 2500)
        assertThat(position(chip, contentHeight = 600, width = 700, align = InfinityPopoverAlign.Start).x).isEqualTo(150)
        assertThat(position(chip, contentHeight = 600, width = 400, align = InfinityPopoverAlign.Start, direction = LayoutDirection.Rtl).x)
            .isEqualTo(600 - 400)
    }

    @Test
    fun explicitAnchorReplacesPopupParentBounds() {
        // 面板由路由统一渲染时，Popup 的父布局不是触发控件，改用记录下来的控件边界。
        val parent = IntRect(0, 0, 1140, 2616)
        val chip = IntRect(150, 2400, 600, 2500)
        val offset = position(parent, contentHeight = 600, width = 700, align = InfinityPopoverAlign.Start, override = chip)
        assertThat(offset).isEqualTo(IntOffset(150, 2400 - 11 - 600))
    }

    @Test
    fun autoAlignmentOpensTowardWindowCenter() {
        // 左半边的控件（顶栏运行模式标识）向右展开，右半边的控件（顶栏菜单）向左展开。
        val leftChip = IntRect(150, 200, 450, 300)
        assertThat(position(leftChip, contentHeight = 500, width = 600, side = InfinityPopoverSide.Below, align = InfinityPopoverAlign.Auto).x).isEqualTo(150)
        val rightMenu = IntRect(950, 200, 1050, 300)
        assertThat(position(rightMenu, contentHeight = 500, width = 600, side = InfinityPopoverSide.Below, align = InfinityPopoverAlign.Auto).x).isEqualTo(1050 - 600)
        // RTL 下左半边是终点一侧。
        assertThat(resolveInfinityPopoverAlign(InfinityPopoverAlign.Auto, leftChip, window.width, LayoutDirection.Rtl)).isEqualTo(InfinityPopoverAlign.End)
        assertThat(resolveInfinityPopoverAlign(InfinityPopoverAlign.Start, rightMenu, window.width, LayoutDirection.Ltr)).isEqualTo(InfinityPopoverAlign.Start)
    }

    @Test
    fun presentationDependsOnWindowWidthAndMeasuredAnchor() {
        assertThat(infinitySheetPresentation(wideWindow = false, anchorMeasured = true)).isEqualTo(InfinitySheetPresentation.BottomSheet)
        assertThat(infinitySheetPresentation(wideWindow = false, anchorMeasured = false)).isEqualTo(InfinitySheetPresentation.BottomSheet)
        assertThat(infinitySheetPresentation(wideWindow = true, anchorMeasured = true)).isEqualTo(InfinitySheetPresentation.Popover)
        assertThat(infinitySheetPresentation(wideWindow = true, anchorMeasured = false)).isEqualTo(InfinitySheetPresentation.CenteredDialog)
    }

    @Test
    fun scaleOriginSitsAtCornerNearestAnchor() {
        assertThat(infinityPopoverTransformOrigin(InfinityPopoverSide.Above, InfinityPopoverAlign.Start, LayoutDirection.Ltr)).isEqualTo(TransformOrigin(0f, 1f))
        assertThat(infinityPopoverTransformOrigin(InfinityPopoverSide.Below, InfinityPopoverAlign.End, LayoutDirection.Ltr)).isEqualTo(TransformOrigin(1f, 0f))
        assertThat(infinityPopoverTransformOrigin(InfinityPopoverSide.Below, InfinityPopoverAlign.End, LayoutDirection.Rtl)).isEqualTo(TransformOrigin(0f, 0f))
    }
}
