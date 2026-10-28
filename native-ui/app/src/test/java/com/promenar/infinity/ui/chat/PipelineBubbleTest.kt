package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCodec
import org.junit.jupiter.api.Test

class PipelineBubbleTest {
    @Test
    fun `工具轮尾部空助手消息不夺走最后正文的所属消息`() {
        val answer = Message("answer", MessageRole.ASSISTANT, "这是需要保留的正文。")
        val emptyTail = Message("empty-tail", MessageRole.ASSISTANT, "", executionSteps = listOf(
            ExecutionStep("call", "tool_call", "read_file", "{}", "tool", timestamp = 1_000),
            ExecutionStep("result", "tool_result", "read_file", toolCallId = "tool", content = "完成", timestamp = 1_400),
        ))
        val messages = listOf(answer, emptyTail)
        val plan = buildPipelineRenderPlan(messages, false, false)
        val content = plan.steps.filterIsInstance<PipelineStep.Content>().last()
        assertThat(content.messageId).isEqualTo("answer")
        assertThat(pipelineContentOwner(messages, content)).isEqualTo(answer)
    }

    @Test
    fun `多个正文片段各自保留所属消息供长按和编辑使用`() {
        val first = Message("first", MessageRole.ASSISTANT, "前段正文")
        val second = Message("second", MessageRole.ASSISTANT, "最终正文")
        val messages = listOf(first, second)
        val content = buildPipelineRenderPlan(messages, false, false).steps.filterIsInstance<PipelineStep.Content>()
        assertThat(content.map { it.messageId }).containsExactly("first", "second").inOrder()
        assertThat(pipelineContentOwner(messages, content.first())).isEqualTo(first)
        assertThat(pipelineContentOwner(messages, content.last())).isEqualTo(second)
    }

    @Test
    fun `工具信封清理后的正文与流式占位均保留真实消息身份`() {
        val cleaned = Message("cleaned", MessageRole.ASSISTANT, """{"tool_name":"read_file","query":"资料"} 剩余正文""")
        val content = buildPipelineRenderPlan(listOf(cleaned), false, false).steps.filterIsInstance<PipelineStep.Content>().single()
        assertThat(content.content).isEqualTo("剩余正文")
        assertThat(content.messageId).isEqualTo("cleaned")
        val streaming = Message("stream", MessageRole.ASSISTANT, "")
        val placeholder = buildPipelineRenderPlan(listOf(streaming), true, true).steps.filterIsInstance<PipelineStep.Content>().single()
        assertThat(placeholder.messageId).isEqualTo("stream")
        assertThat(pipelineContentOwner(listOf(streaming), placeholder)).isEqualTo(streaming)
    }

    @Test
    fun `正文来源不存在时不会回退到其它消息`() {
        val source = Message("source", MessageRole.ASSISTANT, "正文")
        val step = buildPipelineRenderPlan(listOf(source), false, false).steps.filterIsInstance<PipelineStep.Content>().single()
        assertThat(pipelineContentOwner(listOf(Message("other", MessageRole.ASSISTANT, "其它正文")), step)).isNull()
    }


    @Test
    fun `孤立错误助手消息不并入上一条成功回复`() {
        val success = Message(id = "ok", role = MessageRole.ASSISTANT, content = "成功")
        val error = Message(
            id = "error",
            role = MessageRole.ASSISTANT,
            content = "",
            isError = true,
            errorMessage = "failed",
        )

        val groups = buildPipelineGroups(listOf(success, error))

        assertThat(groups).hasSize(2)
        assertThat(groups[0].messages.map(Message::id)).containsExactly("ok")
        assertThat(groups[1].messages.map(Message::id)).containsExactly("error")
    }

    @Test
    fun `历史错误只按持久化信封映射且旧自然语言安全回落`() {
        val timeoutEnvelope = GenerationFailureCodec.encode(
            GenerationFailure(
                com.promenar.infinity.domain.generation.GenerationFailureCode.TIMEOUT,
                technical = "must-not-display",
            ),
        )

        val decoded = historicalGenerationFailureNotice(timeoutEnvelope)
        val legacy = historicalGenerationFailureNotice("provider 原始错误：secret")

        assertThat(GenerationFailureNotice.template(decoded).resourceId)
            .isEqualTo(com.promenar.infinity.R.string.generation_failure_timeout)
        assertThat(GenerationFailureNotice.template(decoded).args).isEmpty()
        assertThat(GenerationFailureNotice.template(legacy).resourceId)
            .isEqualTo(com.promenar.infinity.R.string.generation_failure_unknown)
        assertThat(GenerationFailureNotice.template(legacy).args).isEmpty()
    }

    @Test
    fun `短推理预览合并换行且保留内容`() {
        val reasoning = "1. 先确认输入\n2. 再组织输出"

        assertThat(streamingReasoningPreview(reasoning)).isEqualTo("1. 先确认输入 2. 再组织输出")
    }

    @Test
    fun `streaming reasoning preview keeps recent tail for long reasoning`() {
        val reasoning = (1..40).joinToString("\n") { index ->
            "$index. 推理步骤 $index"
        }

        val result = streamingReasoningPreview(reasoning, maxChars = 240)

        assertThat(result).doesNotContain("\n")
        assertThat(result).contains("40. 推理步骤 40")
        assertThat(result).doesNotContain("1. 推理步骤 1")
        assertThat(result.length).isAtMost(240)
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
        // 已有正文时生成指示仍跟在末尾，直到本轮结束。
        assertThat(plan.showStandaloneCursor).isTrue()
        assertThat(plan.showContentCursor).isFalse()
    }

    @Test
    fun `finished turn hides generation indicator`() {
        val message = Message(id = "assistant-done", role = MessageRole.ASSISTANT, content = "完成")

        val plan = buildPipelineRenderPlan(messages = listOf(message), isGenerating = false, hasStreamingContent = false)

        assertThat(plan.showStandaloneCursor).isFalse()
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


    @Test
    fun `复制整轮按正文顺序保留工具前后内容且排除思考参数结果`() {
        val messages = listOf(
            Message("first", MessageRole.ASSISTANT, "前段正文", reasoning = "秘密思考"),
            Message("tool", MessageRole.ASSISTANT, "", executionSteps = listOf(
                ExecutionStep("call", "tool_call", "read_file", "秘密参数", "call", timestamp = 100),
                ExecutionStep("result", "tool_result", "read_file", content = "秘密结果", toolCallId = "call", timestamp = 200),
            )),
            Message("last", MessageRole.ASSISTANT, "末段正文"),
        )
        val steps = buildPipelineRenderPlan(messages, false, false).steps
        assertThat(pipelineReplyCopyText(steps)).isEqualTo("前段正文\n\n末段正文")
        assertThat(pipelineReplyCopyText(emptyList())).isEmpty()
    }

    @Test
    fun `command output target maps a scoped call step back to its persisted tool call id`() {
        val call = ExecutionStep(id = "step-1", type = "tool_call", toolName = "shell_exec", toolCallId = "call_abc")
        val messages = listOf(
            Message(id = "msg-1", role = MessageRole.ASSISTANT, content = "", executionSteps = listOf(call)),
        )
        assertThat(commandOutputTarget(messages, "msg-1::step-1")).isEqualTo("msg-1" to "call_abc")
        // 未限定作用域、找不到消息或步骤没有调用 id 时不提供入口。
        assertThat(commandOutputTarget(messages, "step-1")).isNull()
        assertThat(commandOutputTarget(messages, "msg-2::step-1")).isNull()
        val withoutId = listOf(Message(id = "msg-1", role = MessageRole.ASSISTANT, content = "", executionSteps = listOf(call.copy(toolCallId = null))))
        assertThat(commandOutputTarget(withoutId, "msg-1::step-1")).isNull()
    }

    @Test
    fun `shell results are marked linux only when the result says so`() {
        val call = ExecutionStep(id = "c", type = "tool_call", toolName = "shell_exec", toolArgs = "{\"command\":\"uname\"}", toolCallId = "call")
        fun result(content: String) = ExecutionStep(id = "r", type = "tool_result", toolName = "shell_exec", toolCallId = "call", content = content)
        assertThat(ToolInvocationPresentation(listOf(call, result("{\"exit_code\":0,\"stdout\":\"Linux\",\"runtime\":\"linux\"}"))).ranInLinux).isTrue()
        assertThat(ToolInvocationPresentation(listOf(call, result("{\"exit_code\":0,\"stdout\":\"Linux\"}"))).ranInLinux).isFalse()
        assertThat(ToolInvocationPresentation(listOf(call, result("{\"exit_code\":0,\"stdout\":\"Linux\"}"))).isCommand).isTrue()
    }
}
