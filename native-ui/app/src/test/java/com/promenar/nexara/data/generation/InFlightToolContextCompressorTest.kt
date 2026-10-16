package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InFlightToolContextCompressorTest {
    private fun history() = buildList {
        add(ProtocolMessage("user", "task"))
        repeat(6) { round ->
            add(ProtocolMessage("assistant", "", toolCalls = listOf(
                ProtocolToolCall("a$round", "read", "{}"), ProtocolToolCall("b$round", "read", "{}"),
            )))
            add(ProtocolMessage("tool", "HEAD$round" + "x".repeat(4000) + "TAIL$round", toolCallId = "a$round"))
            add(ProtocolMessage("tool", "second".repeat(800), toolCallId = "b$round"))
        }
    }

    @Test
    fun `摘要失败保留头尾与长度且保护最近完整轮次和调用配对`() = runTest {
        val messages = history()
        val original = messages.toList()
        val compacted = InFlightToolContextCompressor().compact(messages, 2500, keepRecentRounds = 2) {
            throw IllegalStateException("summary unavailable")
        }
        assertThat(compacted.first { it.toolCallId == "a0" }.content).contains("HEAD0")
        assertThat(compacted.first { it.toolCallId == "a0" }.content).contains("TAIL0")
        assertThat(compacted.first { it.toolCallId == "a0" }.content).contains("4010")
        assertThat(compacted.takeLast(6)).isEqualTo(messages.takeLast(6))
        assertThat(compacted.map { it.toolCallId to it.toolCalls }).isEqualTo(messages.map { it.toolCallId to it.toolCalls })
        assertThat(messages).isEqualTo(original)
    }

    @Test
    fun `摘要模型成功时使用短摘要且同内容缓存避免重复调用`() = runTest {
        val compressor = InFlightToolContextCompressor()
        var calls = 0
        val summarize: suspend (String) -> String? = { calls++; "retained fact" }
        val first = compressor.compact(history(), 2500, keepRecentRounds = 2, summarize = summarize)
        val before = calls
        val second = compressor.compact(history(), 2500, keepRecentRounds = 2, summarize = summarize)
        assertThat(first.first { it.role == "tool" }.content).contains("retained fact")
        assertThat(second).isEqualTo(first)
        assertThat(calls).isEqualTo(before)
    }

    @Test(expected = CancellationException::class)
    fun `摘要取消透传`() = runTest {
        InFlightToolContextCompressor().compact(history(), 1) { throw CancellationException("stop") }
    }
}
