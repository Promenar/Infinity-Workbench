package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.CompletionReason
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

class GenericGatewayTerminalContractTest {
    @Test
    fun `网关completed只有与DONE共同出现才产生成功结束`() = runBlocking {
        val event = """data: {"choices":[{"delta":{"content":"42"},"finish_reason":"completed"}]}"""
        val complete = protocol("$event\n\ndata: [DONE]\n\n").sendPrompt(request()).toList()
        assertThat(complete.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.content }).isEqualTo("42")
        assertThat(complete.filterIsInstance<StreamChunk.Completed>().single().reason).isEqualTo(CompletionReason.END_TURN)
        assertThat(complete.filterIsInstance<StreamChunk.Error>()).isEmpty()

        val truncated = protocol("$event\n\n").sendPrompt(request()).toList()
        assertThat(truncated.filterIsInstance<StreamChunk.Completed>()).isEmpty()
        assertThat(truncated.filterIsInstance<StreamChunk.Error>()).hasSize(1)
    }

    @Test
    fun `网关以completed或stop结束但携带完整工具调用时按TOOL_CALLS处理`() = runBlocking {
        listOf("completed", "stop").forEach { finish ->
            val event = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"$finish"}]}"""
            val result = protocol("data: $event\n\ndata: [DONE]\n\n").sendPrompt(request()).toList()
            val completed = result.filterIsInstance<StreamChunk.Completed>().single()
            assertThat(completed.reason).isEqualTo(CompletionReason.TOOL_CALLS)
            assertThat(completed.completedToolCallIds).containsExactly("call")
            assertThat(result.filterIsInstance<StreamChunk.Error>()).isEmpty()
        }
    }

    @Test
    fun `网关completed不掩盖不完整工具或未知结束状态`() = runBlocking {
        val events = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"read","arguments":"{\"a\":"}}]},"finish_reason":"completed"}]}""",
            """{"choices":[{"delta":{"content":"42"},"finish_reason":"future"}]}""",
            """{"error":{"type":"upstream_error","message":"synthetic failure"}}""",
        )
        events.forEach { event ->
            val result = protocol("data: $event\n\ndata: [DONE]\n\n").sendPrompt(request()).toList()
            assertThat(result.filterIsInstance<StreamChunk.Completed>()).isEmpty()
            assertThat(result.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        }
    }

    @Test
    fun `兼容同步接口接受completed文本且仍拒绝不完整工具`() = runBlocking {
        val body = """{"choices":[{"message":{"content":"42"},"finish_reason":"completed"}]}"""
        assertThat(protocol(body).sendPromptSync(request()).content).isEqualTo("42")
        val incomplete = """{"choices":[{"message":{"content":"","tool_calls":[{"id":"","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"completed"}]}"""
        assertThat(runCatching { protocol(incomplete).sendPromptSync(request()) }.exceptionOrNull()).isNotNull()
    }

    private fun request() = PromptRequest(listOf(ProtocolMessage("user", "synthetic")), "fixture-model")
    private fun protocol(body: String) = GenericOpenAICompatProtocol(
        baseUrl = "http://127.0.0.1:1337/v1/chat/completions",
        apiKey = "",
        model = "fixture-model",
        httpClient = HttpClient(MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }),
    )
}
