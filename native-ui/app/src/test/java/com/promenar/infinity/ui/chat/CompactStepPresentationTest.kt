package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.ToolCall
import org.junit.jupiter.api.Test

class CompactStepPresentationTest {
    @Test
    fun `结构化工具事件优先于同条消息正文中的工具信封`() {
        val message = toolMessage("mixed", "search", "one").copy(
            content = """{"tool_name":"calculator","query":"1+1"}""",
        )
        val tools = buildPipelineRenderPlan(listOf(message), false, false).steps
            .filterIsInstance<PipelineStep.ToolExec>().flatMap { it.steps }
        assertThat(tools.count { it.type == "tool_call" }).isEqualTo(1)
        assertThat(toolInvocations(tools).single().name).isEqualTo("search")
        assertThat(toolInvocations(tools).single().call?.toolArgs).isEqualTo("{}")
    }

    @Test
    fun `只有审批记录时仍可展示缺少结构化事件的历史工具信封`() {
        val message = Message(
            "legacy", MessageRole.ASSISTANT,
            """{"tool_name":"calculator","query":"1+1"}""",
            executionSteps = listOf(ExecutionStep("approval", "intervention_result")),
        )
        val tools = buildPipelineRenderPlan(listOf(message), false, false).steps
            .filterIsInstance<PipelineStep.ToolExec>().flatMap { it.steps }
        assertThat(toolInvocations(tools).single().name).isEqualTo("calculator")
    }

    @Test
    fun `同条回复含说明正文时未完成工具保持运行态`() {
        val message = toolMessage("current", "search", "one").copy(
            content = "我先查一下资料。",
            executionSteps = listOf(ExecutionStep("start", "tool_call", "search", toolCallId = "one", timestamp = 1000)),
        )
        val tool = buildPipelineRenderPlan(listOf(message), true, false).steps
            .filterIsInstance<PipelineStep.ToolExec>().single()
        assertThat(tool.isExecuting).isTrue()
        assertThat(buildPipelineRenderPlan(listOf(message), false, false).steps
            .filterIsInstance<PipelineStep.ToolExec>().single().isExecuting).isFalse()
    }

    @Test
    fun `运行中工具按执行时登记的步骤 id 读取实时输出`() {
        val message = toolMessage("ai_1", "shell_exec", "call_1").copy(
            executionSteps = listOf(ExecutionStep("step_1000_call_1", "tool_call", "shell_exec", toolCallId = "call_1", timestamp = 1000)),
        )
        val tool = buildPipelineRenderPlan(listOf(message), true, false).steps
            .filterIsInstance<PipelineStep.ToolExec>().single()
        val running = toolInvocations(tool.steps).single { !it.complete }
        // 界面步骤 id 带消息前缀，实时输出存储按 ToolExecutor 的原始步骤 id 登记。
        assertThat(running.call?.id).isEqualTo("ai_1::step_1000_call_1")
        assertThat(unscopedStepId(running.call!!.id)).isEqualTo("step_1000_call_1")
        assertThat(unscopedStepId("step_1000_call_1")).isEqualTo("step_1000_call_1")
    }

    @Test
    fun `历史正文合成的工具步骤没有虚构耗时且结果保留全文`() {
        val result = "资料".repeat(400)
        val message = Message(
            "history", MessageRole.ASSISTANT,
            """{"tool_name":"search","query":"文档"} --- 工具执行结果：$result""",
        )
        val plan = buildPipelineRenderPlan(listOf(message), false, false)
        val step = plan.steps.filterIsInstance<PipelineStep.ToolExec>().single()
        val invocations = toolInvocations(step.steps)
        assertThat(toolGroupDurationMs(invocations)).isNull()
        assertThat(invocations.flatMap { it.results }.single().content).isEqualTo(result)
    }

    @Test
    fun `生成中的推理预览只保留最新的单行文本`() {
        val reasoning = (1..40).joinToString("\n") { "第${it}步：检查输入并核对结果" }

        val preview = streamingReasoningPreview(reasoning)

        assertThat(preview).doesNotContain("\n")
        assertThat(preview).contains("第40步")
        assertThat(preview).doesNotContain("第1步")
    }

    @Test
    fun `相邻工具轮次合并为一个步骤但保留每次调用`() {
        val messages = listOf(
            toolMessage("first", "search", "call-1"),
            toolMessage("second", "read_file", "call-2"),
        )

        val plan = buildPipelineRenderPlan(messages, false, false)

        assertThat(plan.steps).hasSize(1)
        val toolSteps = (plan.steps.single() as PipelineStep.ToolExec).steps
        assertThat(toolSteps.filter { it.type == "tool_call" }.map { it.toolName })
            .containsExactly("search", "read_file").inOrder()
    }

    @Test
    fun `不同消息复用调用标识时保留两次工具调用和各自参数耗时`() {
        val first = toolMessage("first", "search", "same").copy(executionSteps = listOf(
            ExecutionStep("start", "tool_call", toolArgs = "{\"query\":\"A\"}", toolCallId = "same", timestamp = 1000),
            ExecutionStep("end", "tool_result", toolCallId = "same", content = "A结果", timestamp = 1400),
        ))
        val second = toolMessage("second", "read_file", "same").copy(executionSteps = listOf(
            ExecutionStep("start", "tool_call", toolArgs = "{\"path\":\"B\"}", toolCallId = "same", timestamp = 2000),
            ExecutionStep("end", "tool_result", toolCallId = "same", content = "B结果", timestamp = 2600),
        ))
        val step = buildPipelineRenderPlan(listOf(first, second), false, false).steps.single() as PipelineStep.ToolExec
        val invocations = toolInvocations(step.steps)
        assertThat(invocations).hasSize(2)
        assertThat(invocations.map { it.name }).containsExactly("search", "read_file").inOrder()
        assertThat(invocations.map { it.durationMs }).containsExactly(400L, 600L).inOrder()
        assertThat(invocations.map { it.call?.toolArgs })
            .containsExactly("{\"query\":\"A\"}", "{\"path\":\"B\"}").inOrder()
    }

    @Test
    fun `思考和正文保留工具组之间的因果边界`() {
        val messages = listOf(
            toolMessage("first", "search", "call-1"),
            toolMessage("second", "read_file", "call-2").copy(reasoning = "根据搜索结果读取文档"),
            Message("answer", MessageRole.ASSISTANT, "结论"),
        )

        val plan = buildPipelineRenderPlan(messages, false, false)

        assertThat(plan.steps.map { it::class.simpleName })
            .containsExactly("ToolExec", "Thinking", "ToolExec", "Content").inOrder()
    }

    @Test
    fun `缺少工具名时使用消息中同调用标识的真实工具名`() {
        val message = toolMessage("first", "web_search", "call-1").copy(
            executionSteps = listOf(
                ExecutionStep("start", "tool_call", toolCallId = "call-1", timestamp = 1000),
                ExecutionStep("end", "tool_result", toolCallId = "call-1", timestamp = 2400),
            ),
        )

        val plan = buildPipelineRenderPlan(listOf(message), false, false)

        assertThat((plan.steps.single() as PipelineStep.ToolExec).steps.map { it.toolName })
            .containsExactly("web_search", "web_search")
    }

    private fun toolMessage(id: String, name: String, callId: String) = Message(
        id, MessageRole.ASSISTANT, "",
        toolCalls = listOf(ToolCall(callId, name, "{}")),
        executionSteps = listOf(
            ExecutionStep("$id-start", "tool_call", name, "{}", callId, timestamp = 1000),
            ExecutionStep("$id-end", "tool_result", name, toolCallId = callId, content = "完成", timestamp = 2400),
        ),
    )
}
