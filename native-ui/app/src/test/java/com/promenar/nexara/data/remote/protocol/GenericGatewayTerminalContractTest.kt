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
    fun `网关completed不掩盖工具结束冲突或未知结束状态`() = runBlocking {
        val events = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"completed"}]}""",
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
    fun `兼容同步接口接受completed文本且仍拒绝工具冲突`() = runBlocking {
        val body = """{"choices":[{"message":{"content":"42"},"finish_reason":"completed"}]}"""
        assertThat(protocol(body).sendPromptSync(request()).content).isEqualTo("42")
        val conflict = """{"choices":[{"message":{"content":"","tool_calls":[{"id":"call","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"completed"}]}"""
        assertThat(runCatching { protocol(conflict).sendPromptSync(request()) }.exceptionOrNull()).isNotNull()
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
