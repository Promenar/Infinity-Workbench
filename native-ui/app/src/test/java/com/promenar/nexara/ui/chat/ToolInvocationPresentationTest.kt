package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ExecutionStep
import org.junit.jupiter.api.Test

class ToolInvocationPresentationTest {
    @Test
    fun `审批决定不能增加工具数量或阻止工具完成`() {
        val invocations = toolInvocations(listOf(
            ExecutionStep("approval", "intervention_result", content = "已批准"),
            call("a", "one", "write_file", 1000), result("r", "one", 1400),
        ))
        assertThat(invocations).hasSize(1)
        assertThat(invocations.single().complete).isTrue()
        assertThat(toolGroupDurationMs(invocations)).isEqualTo(400)
    }

    @Test
    fun `参数和结果按调用标识配对且同名多次调用不会合并`() {
        val steps = listOf(
            call("a", "one", "search", 1000), call("b", "two", "search", 1500),
            result("b-result", "two", 1700), result("a-result", "one", 2400),
        )
        val invocations = toolInvocations(steps)
        assertThat(invocations).hasSize(2)
        assertThat(invocations[0].durationMs).isEqualTo(1400)
        assertThat(invocations[1].durationMs).isEqualTo(200)
        assertThat(toolGroupDurationMs(invocations)).isEqualTo(1400)
    }

    @Test
    fun `重复事件不增加调用数量`() {
        val start = call("a", "one", "read_file", 1000)
        val end = result("end", "one", 1200)
        val invocations = toolInvocations(listOf(start, start, end, end))
        assertThat(invocations).hasSize(1)
        assertThat(invocations.single().steps).hasSize(2)
    }

    @Test
    fun `旧结果用步骤标识配对且不重复计数`() {
        val start = call("legacy", null, "read_file", 1000)
        val invocations = toolInvocations(listOf(start, result("res_legacy", null, 1400)))
        assertThat(invocations).hasSize(1)
        assertThat(invocations.single().name).isEqualTo("read_file")
        assertThat(invocations.single().durationMs).isEqualTo(400)
    }

    @Test
    fun `名称相同的无标识历史调用按顺序配对`() {
        val invocations = toolInvocations(listOf(
            call("first", null, "search", 1000), call("second", null, "search", 1500),
            result("r1", null, 1800).copy(toolName = "search"),
            result("r2", null, 2000).copy(toolName = "search"),
        ))
        assertThat(invocations.map { it.durationMs }).containsExactly(800L, 500L).inOrder()
    }

    @Test
    fun `缺少调用或结果时间时不猜测完成耗时`() {
        assertThat(toolGroupDurationMs(toolInvocations(listOf(result("r", null, 1800))))).isNull()
        assertThat(toolGroupDurationMs(toolInvocations(listOf(call("s", null, "search", 1000))))).isNull()
        val reversed = listOf(call("s", "one", "search", 2000), result("r", "one", 1000))
        assertThat(toolGroupDurationMs(toolInvocations(reversed))).isNull()
    }

    @Test
    fun `进行中的工具组显示从首个调用起已用时且不接受倒退时钟`() {
        val invocations = toolInvocations(listOf(call("s", "one", "search", 1000)))
        assertThat(toolGroupDurationMs(invocations, nowMs = 2600)).isEqualTo(1600)
        assertThat(toolGroupDurationMs(invocations, nowMs = 900)).isNull()
    }

    @Test
    fun `工具信封缺失名称时从嵌套function与tool_name取名`() {
        val function = ExecutionStep("a", "tool_call", toolArgs = """{"function":{"name":"web_search","arguments":{}}}""")
        val named = ExecutionStep("b", "tool_result", data = """{"tool_name":"read_file"}""")
        assertThat(inferredToolName(function)).isEqualTo("web_search")
        assertThat(inferredToolName(named)).isEqualTo("read_file")
    }

    @Test
    fun `普通参数的name和损坏JSON不会冒充工具名`() {
        val parameter = ExecutionStep("a", "tool_call", toolArgs = """{"name":"笔记.md"}""")
        val invalid = ExecutionStep("b", "tool_call", data = "[invalid")
        assertThat(inferredToolName(parameter)).isNull()
        assertThat(inferredToolName(invalid)).isNull()
    }

    @Test
    fun `调用结果中的真实名称可用于显示且失败保持可观察`() {
        val start = call("a", "one", "", 1000)
        val end = result("r", "one", 1200).copy(type = "error", toolName = "web_fetch")
        val invocation = toolInvocations(listOf(start, end)).single()
        assertThat(invocation.name).isEqualTo("web_fetch")
        assertThat(invocation.failed).isTrue()
        assertThat(invocation.complete).isTrue()
        assertThat(invocation.durationMs).isEqualTo(200)
    }

    private fun call(id: String, callId: String?, name: String, time: Long): ExecutionStep =
        ExecutionStep(id, "tool_call", toolName = name, toolCallId = callId, toolArgs = "{}", timestamp = time)

    private fun result(id: String, callId: String?, time: Long): ExecutionStep =
        ExecutionStep(id, "tool_result", toolCallId = callId, content = "完成", timestamp = time)

    @org.junit.Test
    fun `工具耗时不足一秒显示毫秒否则显示一位小数的秒`() {
        com.google.common.truth.Truth.assertThat(toolDurationText(999)).isEqualTo(
            ToolDurationText(com.promenar.nexara.R.string.chat_tool_duration, listOf(999L)),
        )
        com.google.common.truth.Truth.assertThat(toolDurationText(1_400)).isEqualTo(
            ToolDurationText(com.promenar.nexara.R.string.chat_tool_duration_seconds, listOf("1.4")),
        )
    }
}
