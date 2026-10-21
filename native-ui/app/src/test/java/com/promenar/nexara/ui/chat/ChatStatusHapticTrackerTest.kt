package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.common.NexaraHapticEvent
import org.junit.Test

class ChatStatusHapticTrackerTest {
    private fun snap(status: GenerationStatus, approval: String? = null, session: String = "s1") =
        ChatHapticSnapshot(session, status, approval)

    @Test
    fun `超过阈值的一轮完成振动一次，流式过程中不振动`() {
        val tracker = ChatStatusHapticTracker(minCompletedMs = 10_000)
        assertThat(tracker.next(snap(GenerationStatus.IDLE), 0)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.UPLOADING), 1_000)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.THINKING), 2_000)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.RECEIVING), 5_000)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.COMPLETED), 11_000)).isEqualTo(NexaraHapticEvent.COMPLETED)
        assertThat(tracker.next(snap(GenerationStatus.COMPLETED), 11_100)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.IDLE), 12_000)).isNull()
    }

    @Test
    fun `短回复完成与主动停止都不振动`() {
        val tracker = ChatStatusHapticTracker(minCompletedMs = 10_000)
        tracker.next(snap(GenerationStatus.IDLE), 0)
        tracker.next(snap(GenerationStatus.RECEIVING), 1_000)
        assertThat(tracker.next(snap(GenerationStatus.COMPLETED), 4_000)).isNull()
        tracker.next(snap(GenerationStatus.IDLE), 5_000)
        tracker.next(snap(GenerationStatus.THINKING), 6_000)
        assertThat(tracker.next(snap(GenerationStatus.IDLE), 30_000)).isNull()
    }

    @Test
    fun `生成失败无论用时都振动`() {
        val tracker = ChatStatusHapticTracker()
        tracker.next(snap(GenerationStatus.IDLE), 0)
        tracker.next(snap(GenerationStatus.UPLOADING), 100)
        assertThat(tracker.next(snap(GenerationStatus.ERROR), 600)).isEqualTo(NexaraHapticEvent.FAILED)
        assertThat(tracker.next(snap(GenerationStatus.ERROR), 700)).isNull()
    }

    @Test
    fun `新的审批请求振动，等待时间不计入生成用时`() {
        val tracker = ChatStatusHapticTracker(minCompletedMs = 10_000)
        tracker.next(snap(GenerationStatus.IDLE), 0)
        tracker.next(snap(GenerationStatus.THINKING), 1_000)
        assertThat(tracker.next(snap(GenerationStatus.THINKING, approval = "a"), 3_000)).isEqualTo(NexaraHapticEvent.ATTENTION)
        assertThat(tracker.next(snap(GenerationStatus.THINKING, approval = "a"), 60_000)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.THINKING, approval = "b"), 61_000)).isEqualTo(NexaraHapticEvent.ATTENTION)
        // 批准后重新计时：审批前后合计虽长，批准后只跑了 2 秒。
        assertThat(tracker.next(snap(GenerationStatus.RECEIVING), 62_000)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.COMPLETED), 64_000)).isNull()
    }

    @Test
    fun `进入会话或切换会话时不补发已有的审批与结果`() {
        val tracker = ChatStatusHapticTracker()
        assertThat(tracker.next(snap(GenerationStatus.THINKING, approval = "a"), 0)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.ERROR, session = "s2"), 100)).isNull()
        assertThat(tracker.next(snap(GenerationStatus.COMPLETED, session = "s3"), 200)).isNull()
        // 进入时已在生成：从进入时刻计时。
        tracker.next(snap(GenerationStatus.RECEIVING, session = "s4"), 1_000)
        assertThat(tracker.next(snap(GenerationStatus.COMPLETED, session = "s4"), 12_000)).isEqualTo(NexaraHapticEvent.COMPLETED)
    }
}
