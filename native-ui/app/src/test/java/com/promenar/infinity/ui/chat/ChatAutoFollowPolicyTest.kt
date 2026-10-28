package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatAutoFollowPolicyTest {
    private val tolerance = 48

    @Test
    fun `往回看才切断跟随，向尾部拖动不切断`() {
        assertThat(ChatAutoFollowPolicy.afterUserDrag(enabled = true, deltaY = 12f)).isFalse()
        assertThat(ChatAutoFollowPolicy.afterUserDrag(enabled = true, deltaY = -12f)).isTrue()
        assertThat(ChatAutoFollowPolicy.afterUserDrag(enabled = false, deltaY = -12f)).isFalse()
    }

    @Test
    fun `用户把列表推到底时恢复跟随`() {
        assertThat(ChatAutoFollowPolicy.resumesOnOverscroll(unconsumedDeltaY = -8f)).isTrue()
        assertThat(ChatAutoFollowPolicy.resumesOnOverscroll(unconsumedDeltaY = 0f)).isFalse()
        // 顶部回弹不是回到尾部。
        assertThat(ChatAutoFollowPolicy.resumesOnOverscroll(unconsumedDeltaY = 8f)).isFalse()
    }

    @Test
    fun `滚动停下时尾部回到跟随位置附近即恢复`() {
        // 尾部超出跟随位置不足容差：用户只是轻微拨动或已滑回底部。
        assertThat(ChatAutoFollowPolicy.resumesWhenSettled(tailOverflowPx = 30, canScrollForward = true, tolerancePx = tolerance)).isTrue()
        assertThat(ChatAutoFollowPolicy.resumesWhenSettled(tailOverflowPx = -20, canScrollForward = true, tolerancePx = tolerance)).isTrue()
        // 列表已到末端，即便尾部仍被输入区遮住一部分也视为回到底部。
        assertThat(ChatAutoFollowPolicy.resumesWhenSettled(tailOverflowPx = 400, canScrollForward = false, tolerancePx = tolerance)).isTrue()
    }

    @Test
    fun `停在历史内容处不恢复跟随`() {
        assertThat(ChatAutoFollowPolicy.resumesWhenSettled(tailOverflowPx = 300, canScrollForward = true, tolerancePx = tolerance)).isFalse()
        // 跟随目标不在可见范围内。
        assertThat(ChatAutoFollowPolicy.resumesWhenSettled(tailOverflowPx = null, canScrollForward = true, tolerancePx = tolerance)).isFalse()
    }
}
