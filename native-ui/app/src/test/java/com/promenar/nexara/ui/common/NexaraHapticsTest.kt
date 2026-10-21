package com.promenar.nexara.ui.common

import android.view.HapticFeedbackConstants
import android.view.View
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class NexaraHapticsTest {
    @Test
    fun `选择与步进在新系统使用分段 tick，旧系统回退时钟 tick`() {
        assertThat(nexaraHapticConstant(NexaraHapticEvent.SELECTION, 34)).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.STEP, 34)).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.SELECTION, 33)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
    }

    @Test
    fun `开关、确认与拒绝在旧系统回退到已有常量`() {
        assertThat(nexaraHapticConstant(NexaraHapticEvent.TOGGLE_ON, 34)).isEqualTo(HapticFeedbackConstants.TOGGLE_ON)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.TOGGLE_OFF, 34)).isEqualTo(HapticFeedbackConstants.TOGGLE_OFF)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.TOGGLE_ON, 33)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.CONFIRM, 30)).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.CONFIRM, 29)).isEqualTo(HapticFeedbackConstants.VIRTUAL_KEY)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.REJECT, 30)).isEqualTo(HapticFeedbackConstants.REJECT)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.REJECT, 29)).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun `状态事件与对应交互共用同一档，等待审批使用长按档`() {
        assertThat(nexaraHapticConstant(NexaraHapticEvent.COMPLETED, 34)).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.FAILED, 34)).isEqualTo(HapticFeedbackConstants.REJECT)
        assertThat(nexaraHapticConstant(NexaraHapticEvent.ATTENTION, 34)).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun `关闭触感反馈后状态提醒也不振动，单独关闭状态提醒不影响交互`() {
        val off = NexaraHaptics(view = null, interaction = false, status = true)
        assertThat(NexaraHapticEvent.entries.none(off::allows)).isTrue()

        val interactionOnly = NexaraHaptics(view = null, interaction = true, status = false)
        assertThat(interactionOnly.allows(NexaraHapticEvent.CONFIRM)).isTrue()
        assertThat(interactionOnly.allows(NexaraHapticEvent.SELECTION)).isTrue()
        assertThat(interactionOnly.allows(NexaraHapticEvent.ATTENTION)).isFalse()
        assertThat(interactionOnly.allows(NexaraHapticEvent.COMPLETED)).isFalse()
        assertThat(interactionOnly.allows(NexaraHapticEvent.FAILED)).isFalse()

        val all = NexaraHaptics(view = null, interaction = true, status = true)
        assertThat(NexaraHapticEvent.entries.all(all::allows)).isTrue()
    }

    @Test
    fun `单选只在选中项变化时振动，打开触感的回应不受旧偏好限制`() {
        val view = mockk<View> { every { performHapticFeedback(any()) } returns true }
        val haptics = NexaraHaptics(view = view, interaction = true, status = true)
        haptics.select(alreadySelected = true)
        verify(exactly = 0) { view.performHapticFeedback(any()) }
        haptics.select(alreadySelected = false)
        verify(exactly = 1) { view.performHapticFeedback(any()) }

        val offView = mockk<View> { every { performHapticFeedback(any()) } returns true }
        val off = NexaraHaptics(view = offView, interaction = false, status = false)
        off.select(alreadySelected = false)
        verify(exactly = 0) { offView.performHapticFeedback(any()) }
        off.confirmEnabled()
        verify(exactly = 1) { offView.performHapticFeedback(any()) }
    }
}
