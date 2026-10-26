package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ExecutionStep
import org.junit.Test

class LinuxExecLiveInvocationsTest {
    @Test fun `工具返回任务号后面板随实时通道保持并在任务结束后消失`() {
        val calls = toolInvocations(listOf(
            ExecutionStep(id = "message::step", type = "tool_call", toolCallId = "call", toolName = "shell_exec"),
            ExecutionStep(id = "result", type = "tool_result", toolCallId = "call", toolName = "shell_exec", content = """{"state":"running","job_id":"job-a"}"""),
        ))
        assertThat(calls.single().complete).isTrue()
        assertThat(activeToolOutputs(calls, setOf("step"))).hasSize(1)
        assertThat(activeToolOutputs(calls, emptySet())).isEmpty()
    }
}
