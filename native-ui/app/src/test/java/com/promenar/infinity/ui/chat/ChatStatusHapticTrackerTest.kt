package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.ui.common.InfinityHapticEvent
import org.junit.Test

class ChatStatusHapticTrackerTest {
    private fun snap(status: GenerationStatus, approval: String? = null, session: String = "s1") =
        ChatHapticSnapshot(session, status, approval)

    private fun outcome(kind: ChatGenerationOutcome.Kind, session: String = "s1") =
        ChatGenerationOutcome(session, kind)

    @Test
    fun `超过阈值的一轮完成振动一次，流式过程中不振动`() {
        val tracker = ChatStatusHapticTracker(minCompletedMs = 10_000)
        assertThat(tracker.next(snap(GenerationStatus.IDLE), 0)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.UPLOADING), 1_000)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.THINKING), 2_000)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.RECEIVING), 5_000)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.COMPLETED), 11_000)).isNull()
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED), 11_100))
            .isEqualTo(InfinityHapticEvent.COMPLETED)
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED), 11_200)).isNull()
    }

    @Test
    fun `终态状态被合并掉时仍按完成事件振动`() {
        // 终态确认后状态立即回到空闲，界面可能从未看到 COMPLETED。
        val tracker = ChatStatusHapticTracker(minCompletedMs = 10_000)
        tracker.next(snap(GenerationStatus.IDLE), 0)
        tracker.next(snap(GenerationStatus.RECEIVING), 1_000)
        assertThat(tracker.next(snap(GenerationStatus.IDLE), 40_000)).isNull()
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED), 40_050))
            .isEqualTo(InfinityHapticEvent.COMPLETED)
    }

    @Test
    fun `短回复完成与主动停止都不振动`() {
        val tracker = ChatStatusHapticTracker(minCompletedMs = 10_000)
        tracker.next(snap(GenerationStatus.IDLE), 0)
        tracker.next(snap(GenerationStatus.RECEIVING), 1_000)
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED), 4_000)).isNull()
        tracker.next(snap(GenerationStatus.IDLE), 5_000)
        tracker.next(snap(GenerationStatus.THINKING), 6_000)
        tracker.next(snap(GenerationStatus.IDLE), 30_000)
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.CANCELLED), 30_000)).isNull()
    }

    @Test
    fun `新一轮重新计时，不沿用上一轮未收到结果的起点`() {
        val tracker = ChatStatusHapticTracker(minCompletedMs = 10_000)
        tracker.next(snap(GenerationStatus.IDLE), 0)
        tracker.next(snap(GenerationStatus.RECEIVING), 1_000)
        tracker.next(snap(GenerationStatus.IDLE), 2_000)
        tracker.next(snap(GenerationStatus.RECEIVING), 50_000)
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED), 53_000)).isNull()
    }

    @Test
    fun `生成失败无论用时都振动一次`() {
        val tracker = ChatStatusHapticTracker()
        tracker.next(snap(GenerationStatus.IDLE), 0)
        tracker.next(snap(GenerationStatus.UPLOADING), 100)
        tracker.next(snap(GenerationStatus.ERROR), 600)
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.FAILED), 600)).isEqualTo(InfinityHapticEvent.FAILED)
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.FAILED), 700)).isNull()
    }

    @Test
    fun `新的审批请求振动，等待时间不计入生成用时`() {
        val tracker = ChatStatusHapticTracker(minCompletedMs = 10_000)
        tracker.next(snap(GenerationStatus.IDLE), 0)
        tracker.next(snap(GenerationStatus.THINKING), 1_000)
        assertThat(tracker.next(snap(GenerationStatus.THINKING, approval = "a"), 3_000)).isEqualTo(InfinityHapticEvent.ATTENTION)
        assertThat(tracker.next(snap(GenerationStatus.THINKING, approval = "a"), 60_000)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.THINKING, approval = "b"), 61_000)).isEqualTo(InfinityHapticEvent.ATTENTION)
        // 批准后重新计时：审批前后合计虽长，批准后只跑了 2 秒。
        assertThat(tracker.next(snap(GenerationStatus.RECEIVING), 62_000)).isNull()
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED), 64_000)).isNull()
    }

    @Test
    fun `进入会话或切换会话时不补发已有的审批与结果`() {
        val tracker = ChatStatusHapticTracker()
        assertThat(tracker.next(snap(GenerationStatus.THINKING, approval = "a"), 0)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.ERROR, session = "s2"), 100)).isNull()
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.FAILED, session = "s2"), 100)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.IDLE, session = "s3"), 200)).isNull()
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED, session = "s3"), 200)).isNull()
        // 进入时已在生成：从进入时刻计时。
        tracker.next(snap(GenerationStatus.RECEIVING, session = "s4"), 1_000)
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED, session = "s4"), 12_000))
            .isEqualTo(InfinityHapticEvent.COMPLETED)
    }

    @Test
    fun `其他会话的结果不振动`() {
        val tracker = ChatStatusHapticTracker()
        tracker.next(snap(GenerationStatus.IDLE), 0)
        tracker.next(snap(GenerationStatus.RECEIVING), 1_000)
        assertThat(tracker.onOutcome(outcome(ChatGenerationOutcome.Kind.FAILED, session = "other"), 2_000)).isNull()
    }

    @Test
    fun `终态阶段映射为结果类别`() {
        assertThat(chatGenerationOutcomeKind(GenerationPhase.COMPLETED, handledFailure = false))
            .isEqualTo(ChatGenerationOutcome.Kind.COMPLETED)
        assertThat(chatGenerationOutcomeKind(GenerationPhase.COMPLETED, handledFailure = true))
            .isEqualTo(ChatGenerationOutcome.Kind.FAILED)
        assertThat(chatGenerationOutcomeKind(GenerationPhase.FAILED, handledFailure = false))
            .isEqualTo(ChatGenerationOutcome.Kind.FAILED)
        assertThat(chatGenerationOutcomeKind(GenerationPhase.PERSISTENCE_FAILED, handledFailure = false))
            .isEqualTo(ChatGenerationOutcome.Kind.FAILED)
        assertThat(chatGenerationOutcomeKind(GenerationPhase.CANCELLED, handledFailure = false))
            .isEqualTo(ChatGenerationOutcome.Kind.CANCELLED)
        assertThat(chatGenerationOutcomeKind(GenerationPhase.STREAMING, handledFailure = false)).isNull()
    }
}
