package com.promenar.nexara.ui.hub

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import org.junit.Test

class HubSessionActivityTest {
    private fun task(phase: GenerationPhase) =
        GenerationTaskSnapshot(taskId = "t", sessionId = "s1", assistantMessageId = "m", phase = phase, generatedChars = 0, startedAt = 0)

    @Test fun `没有进行中的生成时没有标记`() {
        assertThat(hubSessionActivities(null)).isEmpty()
    }

    @Test fun `进行中的各阶段都标为生成中`() {
        listOf(
            GenerationPhase.PREPARING, GenerationPhase.CONNECTING, GenerationPhase.THINKING,
            GenerationPhase.STREAMING, GenerationPhase.EXECUTING_TOOLS, GenerationPhase.POST_PROCESSING,
        ).forEach { phase ->
            assertThat(hubSessionActivities(task(phase))).containsExactly("s1", HubSessionActivity.GENERATING)
        }
    }

    @Test fun `等待批准单独标出`() {
        assertThat(hubSessionActivities(task(GenerationPhase.WAITING_APPROVAL)))
            .containsExactly("s1", HubSessionActivity.AWAITING_APPROVAL)
    }

    @Test fun `已结束的任务不标记`() {
        listOf(GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.CANCELLED, GenerationPhase.PERSISTENCE_FAILED).forEach { phase ->
            assertThat(hubSessionActivities(task(phase))).isEmpty()
        }
    }
}
