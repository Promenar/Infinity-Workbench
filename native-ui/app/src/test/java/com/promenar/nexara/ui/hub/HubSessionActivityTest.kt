package com.promenar.nexara.ui.hub

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import org.junit.Test

class HubSessionActivityTest {
    private fun task(phase: GenerationPhase) =
        GenerationTaskSnapshot(taskId = "t", sessionId = "s1", assistantMessageId = "m", phase = phase, generatedChars = 0, startedAt = 0)

    @Test fun `没有进行中的生成时没有标记`() {
        assertThat(hubSessionActivities(emptyMap())).isEmpty()
    }

    @Test fun `进行中的各阶段都标为生成中`() {
        listOf(
            GenerationPhase.PREPARING, GenerationPhase.CONNECTING, GenerationPhase.THINKING,
            GenerationPhase.STREAMING, GenerationPhase.EXECUTING_TOOLS, GenerationPhase.POST_PROCESSING,
        ).forEach { phase ->
            assertThat(hubSessionActivities(mapOf("s1" to task(phase)))).containsExactly("s1", HubSessionActivity.GENERATING)
        }
    }

    @Test fun `等待批准单独标出`() {
        assertThat(hubSessionActivities(mapOf("s1" to task(GenerationPhase.WAITING_APPROVAL))))
            .containsExactly("s1", HubSessionActivity.AWAITING_APPROVAL)
    }

    @Test fun `等待回答与排队各自标出`() {
        assertThat(hubSessionActivities(mapOf("s1" to task(GenerationPhase.WAITING_INPUT))))
            .containsExactly("s1", HubSessionActivity.AWAITING_ANSWER)
        assertThat(hubSessionActivities(mapOf("s1" to task(GenerationPhase.QUEUED))))
            .containsExactly("s1", HubSessionActivity.QUEUED)
    }

    @Test fun `已结束的任务不标记`() {
        listOf(GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.CANCELLED, GenerationPhase.PERSISTENCE_FAILED).forEach { phase ->
            assertThat(hubSessionActivities(mapOf("s1" to task(phase)))).isEmpty()
        }
    }

    @Test fun `生成任务退出后仍按会话持久状态标出待回答`() {
        val waiting = Session(id = "s2", agentId = "a", title = "问", modelId = "p/m", loopStatus = LoopStatus.WAITING_FOR_INPUT)
        val answered = Session(id = "s3", agentId = "a", title = "答", modelId = "p/m", loopStatus = LoopStatus.INPUT_ANSWERED)
        assertThat(hubSessionActivities(emptyMap(), listOf(waiting, answered)))
            .containsExactly("s2", HubSessionActivity.AWAITING_ANSWER)
        assertThat(hubSessionActivities(mapOf("s1" to task(GenerationPhase.STREAMING)), listOf(waiting)))
            .containsExactly("s2", HubSessionActivity.AWAITING_ANSWER, "s1", HubSessionActivity.GENERATING)
    }

    @Test fun `多个会话各自映射活动`() {
        val first = task(GenerationPhase.QUEUED)
        val second = task(GenerationPhase.WAITING_APPROVAL).copy(sessionId = "s2")
        assertThat(hubSessionActivities(mapOf("s1" to first, "s2" to second)))
            .containsExactly("s1", HubSessionActivity.QUEUED, "s2", HubSessionActivity.AWAITING_APPROVAL)
    }
}
