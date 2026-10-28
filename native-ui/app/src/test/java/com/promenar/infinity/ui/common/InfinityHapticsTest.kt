package com.promenar.infinity.ui.common

import android.view.HapticFeedbackConstants
import android.view.View
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class InfinityHapticsTest {
    @Test
    fun `选择与步进在新系统使用分段 tick，旧系统回退时钟 tick`() {
        assertThat(infinityHapticConstant(InfinityHapticEvent.SELECTION, 34)).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        assertThat(infinityHapticConstant(InfinityHapticEvent.STEP, 34)).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        assertThat(infinityHapticConstant(InfinityHapticEvent.SELECTION, 33)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
    }

    @Test
    fun `开关、确认与拒绝在旧系统回退到已有常量`() {
        assertThat(infinityHapticConstant(InfinityHapticEvent.TOGGLE_ON, 34)).isEqualTo(HapticFeedbackConstants.TOGGLE_ON)
        assertThat(infinityHapticConstant(InfinityHapticEvent.TOGGLE_OFF, 34)).isEqualTo(HapticFeedbackConstants.TOGGLE_OFF)
        assertThat(infinityHapticConstant(InfinityHapticEvent.TOGGLE_ON, 33)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(infinityHapticConstant(InfinityHapticEvent.CONFIRM, 30)).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(infinityHapticConstant(InfinityHapticEvent.CONFIRM, 29)).isEqualTo(HapticFeedbackConstants.VIRTUAL_KEY)
        assertThat(infinityHapticConstant(InfinityHapticEvent.REJECT, 30)).isEqualTo(HapticFeedbackConstants.REJECT)
        assertThat(infinityHapticConstant(InfinityHapticEvent.REJECT, 29)).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun `状态事件与对应交互共用同一档，等待审批使用长按档`() {
        assertThat(infinityHapticConstant(InfinityHapticEvent.COMPLETED, 34)).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(infinityHapticConstant(InfinityHapticEvent.FAILED, 34)).isEqualTo(HapticFeedbackConstants.REJECT)
        assertThat(infinityHapticConstant(InfinityHapticEvent.ATTENTION, 34)).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun `关闭触感反馈后状态提醒也不振动，单独关闭状态提醒不影响交互`() {
        val off = InfinityHaptics(view = null, interaction = false, status = true)
        assertThat(InfinityHapticEvent.entries.none(off::allows)).isTrue()

        val interactionOnly = InfinityHaptics(view = null, interaction = true, status = false)
        assertThat(interactionOnly.allows(InfinityHapticEvent.CONFIRM)).isTrue()
        assertThat(interactionOnly.allows(InfinityHapticEvent.SELECTION)).isTrue()
        assertThat(interactionOnly.allows(InfinityHapticEvent.ATTENTION)).isFalse()
        assertThat(interactionOnly.allows(InfinityHapticEvent.COMPLETED)).isFalse()
        assertThat(interactionOnly.allows(InfinityHapticEvent.FAILED)).isFalse()

        val all = InfinityHaptics(view = null, interaction = true, status = true)
        assertThat(InfinityHapticEvent.entries.all(all::allows)).isTrue()
    }

    @Test
    fun `单选只在选中项变化时振动，打开触感的回应不受旧偏好限制`() {
        val view = mockk<View> { every { performHapticFeedback(any()) } returns true }
        val haptics = InfinityHaptics(view = view, interaction = true, status = true)
        haptics.select(alreadySelected = true)
        verify(exactly = 0) { view.performHapticFeedback(any()) }
        haptics.select(alreadySelected = false)
        verify(exactly = 1) { view.performHapticFeedback(any()) }

        val offView = mockk<View> { every { performHapticFeedback(any()) } returns true }
        val off = InfinityHaptics(view = offView, interaction = false, status = false)
        off.select(alreadySelected = false)
        verify(exactly = 0) { offView.performHapticFeedback(any()) }
        off.confirmEnabled()
        verify(exactly = 1) { offView.performHapticFeedback(any()) }
    }
}
