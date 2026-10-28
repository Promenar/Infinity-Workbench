package com.promenar.infinity.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 滚动停下时尾部离跟随位置多近仍算“回到底部”。 */
internal val ChatAutoFollowResumeTolerance: Dp = 48.dp

/**
 * 会话列表“跟随最新内容”的开关规则。
 *
 * 只有用户往回看（内容下移）才切断跟随；用户把列表推到底，或滚动停下时跟随目标的尾部
 * 已回到输入区上方附近，就恢复跟随，不必再找回底按钮。
 */
internal object ChatAutoFollowPolicy {
    /** 拖动一帧后的跟随状态；[deltaY] 为正表示手指下移、查看更早的内容。 */
    fun afterUserDrag(enabled: Boolean, deltaY: Float): Boolean = enabled && deltaY <= 0f

    /** 拖动到末端后仍有未消费的向前位移，说明用户正把列表推向最新内容。 */
    fun resumesOnOverscroll(unconsumedDeltaY: Float): Boolean = unconsumedDeltaY < 0f

    /**
     * 滚动（含惯性）停下时是否恢复跟随。
     *
     * [tailOverflowPx] 为跟随目标底边超出“输入区上方跟随位置”的像素，目标不可见时为 null。
     */
    fun resumesWhenSettled(tailOverflowPx: Int?, canScrollForward: Boolean, tolerancePx: Int): Boolean =
        !canScrollForward || (tailOverflowPx != null && tailOverflowPx <= tolerancePx)
}
