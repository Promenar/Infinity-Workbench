package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 最小回归测试：部分 Generic OpenAI 兼容服务在 SSE 中返回 `delta.reasoning`
 * （而非官方 `delta.reasoning_content`）。协议层必须把 `delta.reasoning` 规范化到
 * `StreamChunk.TextDelta.reasoning`，避免该字段被静默丢弃。
 *
 * 约束：
 * - 单测使用 Ktor MockEngine，不发真实网络；
 * - 用例范围被刻意收窄到「仅含 delta.reasoning」的 SSE 数据流。
 */
class GenericOpenAICompatReasoningFieldTest {

    @Test
    @DisplayName("delta.reasoning 单独存在时规范化到 TextDelta.reasoning")
    fun `delta reasoning 单独存在时规范化到 TextDelta reasoning`() = runBlocking {
        val sseBody = """
            data: {"choices":[{"delta":{"reasoning":"hello"}}]}

            data: {"choices":[{"delta":{"reasoning":" world"}}]}

            data: [DONE]

        """.trimIndent()

        val protocol = protocolResponding(
            status = HttpStatusCode.OK,
            body = sseBody,
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )

        val chunks = protocol.sendPrompt(testRequest()).toList()
        val reasoningJoined = chunks
            .filterIsInstance<StreamChunk.TextDelta>()
            .mapNotNull { it.reasoning }
            .joinToString("")

        assertThat(reasoningJoined).isEqualTo("hello world")
        assertThat(chunks.count { it == StreamChunk.Done }).isEqualTo(1)
    }

    @Test
    @DisplayName("reasoning_content 与 reasoning 并存时标准字段优先且不重复")
    fun `双 reasoning 字段并存时标准字段优先且不重复`() = runBlocking {
        val protocol = protocolResponding(
            status = HttpStatusCode.OK,
            body = """
                data: {"choices":[{"delta":{"reasoning_content":"standard","reasoning":"alternate"}}]}

                data: [DONE]

            """.trimIndent(),
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )

        val chunks = protocol.sendPrompt(testRequest()).toList()
        val reasoningJoined = chunks
            .filterIsInstance<StreamChunk.TextDelta>()
            .mapNotNull { it.reasoning }
            .joinToString("")

        assertThat(reasoningJoined).isEqualTo("standard")
        assertThat(chunks.count { it == StreamChunk.Done }).isEqualTo(1)
    }

    private fun protocolResponding(
        status: HttpStatusCode,
        body: String,
        headers: io.ktor.http.Headers = headersOf(HttpHeaders.ContentType, "application/json"),
    ): GenericOpenAICompatProtocol = GenericOpenAICompatProtocol(
        baseUrl = "https://example.test",
        apiKey = "test-key",
        model = "test-model",
        httpClient = HttpClient(MockEngine { respond(body, status, headers) }),
    )

    private fun testRequest() = PromptRequest(
        messages = listOf(ProtocolMessage(role = "user", content = "test")),
        model = "test-model",
        stream = true,
    )
}
