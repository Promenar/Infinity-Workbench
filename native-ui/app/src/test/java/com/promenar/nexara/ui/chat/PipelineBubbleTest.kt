package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import org.junit.jupiter.api.Test

class PipelineBubbleTest {

    @Test
    fun `streaming reasoning preview keeps short reasoning unchanged`() {
        val reasoning = "1. 先确认输入\n2. 再组织输出"

        assertThat(streamingReasoningPreview(reasoning)).isEqualTo(reasoning)
    }

    @Test
    fun `streaming reasoning preview keeps recent tail for long reasoning`() {
        val reasoning = (1..40).joinToString("\n") { index ->
            "$index. 推理步骤 $index"
        }

        val result = streamingReasoningPreview(reasoning, maxChars = 240, maxLines = 6)

        assertThat(result).startsWith("...\n")
        assertThat(result).contains("40. 推理步骤 40")
        assertThat(result).doesNotContain("1. 推理步骤 1")
        assertThat(result.lines().size).isAtMost(7)
    }

    @Test
    fun `streaming body creates content step after thinking and tools for empty placeholder`() {
        val message = Message(
            id = "assistant-streaming",
            role = MessageRole.ASSISTANT,
            content = "",
            reasoning = "正在分析",
            executionSteps = listOf(
                ExecutionStep(
                    id = "tool-1",
                    type = "tool_result",
                    toolName = "search",
                    content = "完成"
                )
            )
        )

        val plan = buildPipelineRenderPlan(
            messages = listOf(message),
            isGenerating = true,
            hasStreamingContent = true
        )

        assertThat(plan.steps.map { it::class.simpleName })
            .containsExactly("Thinking", "ToolExec", "Content")
            .inOrder()
        assertThat(plan.showStandaloneCursor).isFalse()
        assertThat(plan.showContentCursor).isFalse()
    }

    @Test
    fun `empty TTFT keeps standalone cursor without creating content step`() {
        val message = Message(
            id = "assistant-waiting",
            role = MessageRole.ASSISTANT,
            content = ""
        )

        val plan = buildPipelineRenderPlan(
            messages = listOf(message),
            isGenerating = true,
            hasStreamingContent = false
        )

        assertThat(plan.steps).isEmpty()
        assertThat(plan.showStandaloneCursor).isTrue()
        assertThat(plan.showContentCursor).isFalse()
    }
}
