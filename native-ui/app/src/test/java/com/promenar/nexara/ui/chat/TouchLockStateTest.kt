package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TouchLockStateTest {
    @Test
    fun `生成中显示执行中，有待批准时优先提示批准`() {
        assertThat(touchLockPhase(isGenerating = true, pendingApprovals = 0)).isEqualTo(TouchLockPhase.RUNNING)
        assertThat(touchLockPhase(isGenerating = true, pendingApprovals = 2)).isEqualTo(TouchLockPhase.APPROVAL)
    }

    @Test
    fun `生成结束后显示已完成，锁保持到用户解锁`() {
        assertThat(touchLockPhase(isGenerating = false, pendingApprovals = 0)).isEqualTo(TouchLockPhase.DONE)
    }

    @Test
    fun `滑块拖到轨道九成以上才解锁`() {
        assertThat(slideUnlockReached(offset = 179f, max = 200f)).isFalse()
        assertThat(slideUnlockReached(offset = 180f, max = 200f)).isTrue()
        assertThat(slideUnlockReached(offset = 0f, max = 0f)).isFalse()
    }
}
