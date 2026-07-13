package com.promenar.nexara.background.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import org.junit.Test

class GenerationForegroundDecisionTest {
    @Test
    fun `活动生成更新通知且只跟踪精确taskId`() {
        val snapshot = snapshot(GenerationPhase.STREAMING)

        assertThat(reduceGenerationForeground("task", snapshot))
            .isEqualTo(GenerationForegroundDecision.Update(snapshot))
        assertThat(reduceGenerationForeground("stale", snapshot))
            .isEqualTo(GenerationForegroundDecision.Stop("stale"))
    }

    @Test
    fun `等待审批终态持久化失败与空状态都停止前台服务`() {
        val stoppingPhases = listOf(
            GenerationPhase.WAITING_APPROVAL,
            GenerationPhase.COMPLETED,
            GenerationPhase.FAILED,
            GenerationPhase.CANCELLED,
            GenerationPhase.PERSISTENCE_FAILED,
        )

        stoppingPhases.forEach { phase ->
            assertThat(reduceGenerationForeground("task", snapshot(phase)))
                .isEqualTo(GenerationForegroundDecision.Stop("task"))
        }
        assertThat(reduceGenerationForeground("task", null))
            .isEqualTo(GenerationForegroundDecision.Stop("task"))
    }

    @Test
    fun `陈旧STOP既不能取消也不能停止当前新task`() {
        assertThat(shouldHandleGenerationStop("new-task", "old-task")).isFalse()
        assertThat(shouldHandleGenerationStop("new-task", "new-task")).isTrue()
    }

    private fun snapshot(phase: GenerationPhase) = GenerationTaskSnapshot(
        taskId = "task",
        sessionId = "session",
        assistantMessageId = "assistant",
        phase = phase,
        generatedChars = 0,
        startedAt = 1L,
    )
}
