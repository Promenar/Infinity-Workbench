package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolToolCall
import org.junit.Test

class ToolResultCompactorTest {
    private fun loop(rounds: Int, resultChars: Int): List<ProtocolMessage> = buildList {
        add(ProtocolMessage("system", "system prompt"))
        add(ProtocolMessage("user", "task"))
        repeat(rounds) { round ->
            add(ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("c$round", "read_file", "{}"))))
            add(ProtocolMessage("tool", "x".repeat(resultChars), toolCallId = "c$round"))
        }
    }

    @Test
    fun `预算内不改写消息`() {
        val messages = loop(rounds = 3, resultChars = 40)

        val result = ToolResultCompactor.compact(messages, budgetTokens = 10_000)

        assertThat(result.messages).isSameInstanceAs(messages)
        assertThat(result.elidedCount).isEqualTo(0)
    }

    @Test
    fun `超出预算时由旧到新省略工具结果并保留最近结果与配对结构`() {
        val messages = loop(rounds = 8, resultChars = 4_000)

        val result = ToolResultCompactor.compact(messages, budgetTokens = 5_000, keepRecentToolResults = 4)

        val tools = result.messages.filter { it.role == "tool" }
        assertThat(tools).hasSize(8)
        assertThat(tools.takeLast(4).none { it.content == ToolResultCompactor.ELIDED_TOOL_RESULT }).isTrue()
        assertThat(tools.first().content).isEqualTo(ToolResultCompactor.ELIDED_TOOL_RESULT)
        assertThat(result.messages.map { it.toolCallId }).isEqualTo(messages.map { it.toolCallId })
        assertThat(result.messages.filter { it.role != "tool" }).isEqualTo(messages.filter { it.role != "tool" })
        assertThat(result.estimatedTokens).isAtMost(5_000)
    }

    @Test
    fun `只剩受保护结果时尽力而为不越界`() {
        val messages = loop(rounds = 2, resultChars = 40_000)

        val result = ToolResultCompactor.compact(messages, budgetTokens = 100, keepRecentToolResults = 4)

        assertThat(result.elidedCount).isEqualTo(0)
        assertThat(result.messages).isEqualTo(messages)
    }

    @Test
    fun `近似估算对中文按字计数`() {
        assertThat(ApproximateTokenEstimator.estimate("abcd")).isEqualTo(1)
        assertThat(ApproximateTokenEstimator.estimate("中文")).isEqualTo(2)
    }
}
