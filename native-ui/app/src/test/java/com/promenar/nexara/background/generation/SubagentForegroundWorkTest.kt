package com.promenar.nexara.background.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.subagent.SubagentRun
import com.promenar.nexara.data.subagent.SubagentStatus
import com.promenar.nexara.data.subagent.SubagentToolProfile
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import org.junit.Test

class SubagentForegroundWorkTest {
    @Test
    fun `父轮结束后活跃子代理仍保持前台服务`() {
        val completed = GenerationTaskSnapshot("task", "parent", "assistant", GenerationPhase.COMPLETED, 0, 1L)
        for (status in listOf(SubagentStatus.QUEUED, SubagentStatus.RUNNING, SubagentStatus.WAITING_APPROVAL, SubagentStatus.WRAPPING_UP)) {
            val state = reduceForegroundWork(mapOf("parent" to completed), listOf(run(status)))
            assertThat(state.isEmpty).isFalse()
            assertThat(state.generations).isEmpty()
            assertThat(state.subagentCount).isEqualTo(1)
        }
    }

    @Test
    fun `父会话暂停且子代理结束后才撤销前台服务`() {
        val paused = GenerationTaskSnapshot("task", "parent", "assistant", GenerationPhase.WAITING_INPUT, 0, 1L)
        val state = reduceForegroundWork(mapOf("parent" to paused), listOf(run(SubagentStatus.COMPLETED)))
        assertThat(state.isEmpty).isTrue()
    }

    @Test
    fun `六个子代理与会话生成分别计数`() {
        val snapshot = GenerationTaskSnapshot("task", "parent", "assistant", GenerationPhase.STREAMING, 0, 1L)
        val state = reduceForegroundWork(mapOf("parent" to snapshot), List(6) { run(SubagentStatus.RUNNING).copy(index = it) })
        assertThat(state.generations).containsExactly(snapshot)
        assertThat(state.subagentCount).isEqualTo(6)
    }

    private fun run(status: SubagentStatus) = SubagentRun(
        index = 0, description = "任务", prompt = "任务", toolProfile = SubagentToolProfile.READ_ONLY,
        modelId = "model", maxSteps = 0, status = status, startedAt = 1L,
    )
}
