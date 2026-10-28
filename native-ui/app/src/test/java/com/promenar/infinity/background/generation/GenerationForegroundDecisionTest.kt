package com.promenar.infinity.background.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationTaskSnapshot
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
    fun `自动执行工具期间保持前台服务`() {
        val snapshot = snapshot(GenerationPhase.EXECUTING_TOOLS)

        assertThat(reduceGenerationForeground("task", snapshot))
            .isEqualTo(GenerationForegroundDecision.Update(snapshot))
    }

    @Test
    fun `等待审批终态持久化失败与空状态都停止前台服务`() {
        val stoppingPhases = listOf(
            GenerationPhase.WAITING_APPROVAL,
            GenerationPhase.WAITING_INPUT,
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
    fun `部分任务完成后仍更新通知，全部暂停或结束后停止`() {
        val active = snapshot(GenerationPhase.STREAMING)
        val queued = snapshot(GenerationPhase.QUEUED).copy(taskId = "task2", sessionId = "other")
        val done = snapshot(GenerationPhase.COMPLETED).copy(taskId = "done", sessionId = "done")
        assertThat(reduceGenerationForeground(mapOf("session" to active, "other" to queued, "done" to done)))
            .isEqualTo(GenerationForegroundDecision.UpdateMany(listOf(active, queued)))
        assertThat(reduceGenerationForeground(mapOf("other" to queued, "done" to done)))
            .isEqualTo(GenerationForegroundDecision.UpdateMany(listOf(queued)))
        assertThat(reduceGenerationForeground(mapOf("done" to done)))
            .isEqualTo(GenerationForegroundDecision.Stop(""))
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
