package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.domain.generation.GenerationFailureCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 提供商以截断、内容过滤或服务端中断结束输出时，各协议给出可区分的失败，而不是笼统的“提供商不可用”。
 */
class AbnormalFinishReasonTest {
    private data class Expected(val code: GenerationFailureCode, val retryable: Boolean)

    private val outputLimit = Expected(GenerationFailureCode.OUTPUT_LIMIT, retryable = false)
    private val filtered = Expected(GenerationFailureCode.CONTENT_FILTERED, retryable = false)
    private val interrupted = Expected(GenerationFailureCode.SERVER, retryable = true)

    @Test
    fun `OpenAI Chat 与兼容协议按 finish_reason 区分截断、过滤与服务端中断`() = runBlocking {
        val cases = mapOf(
            "length" to outputLimit,
            "content_filter" to filtered,
            "insufficient_system_resource" to interrupted,
            "aborted" to interrupted,
        )
        cases.forEach { (reason, expected) ->
            // 截断常发生在工具参数写到一半时，参数不完整也必须归为同一原因。
            val stream = "data: {\"choices\":[{\"delta\":{\"content\":\"部分\",\"tool_calls\":[{\"index\":0,\"id\":\"call\"," +
                "\"function\":{\"name\":\"canvas_create\",\"arguments\":\"{\\\"html\\\":\\\"<svg\"}}]}," +
                "\"finish_reason\":\"$reason\"}]}\n\ndata: [DONE]\n\n"
            listOf(openAi(stream), compat(stream)).forEach { protocol ->
                assertSingleError(protocol.sendPrompt(request()).toList(), expected, reason)
            }
        }
    }

    @Test
    fun `Anthropic stop_reason 的截断与拒答有独立失败码`() = runBlocking {
        mapOf("max_tokens" to outputLimit, "refusal" to filtered).forEach { (reason, expected) ->
            val stream = "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"$reason\"}}\n\n" +
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            assertSingleError(anthropic(stream).sendPrompt(request()).toList(), expected, reason)
        }
    }

    @Test
    fun `Gemini finishReason 的截断与安全拦截有独立失败码`() = runBlocking {
        mapOf("MAX_TOKENS" to outputLimit, "SAFETY" to filtered, "PROHIBITED_CONTENT" to filtered)
            .forEach { (reason, expected) ->
                val stream = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"a\"}]},\"finishReason\":\"$reason\"}]}\n\n"
                assertSingleError(gemini(stream).sendPrompt(request()).toList(), expected, reason)
            }
    }

    @Test
    fun `Responses incomplete 按 incomplete_details 区分截断与过滤`() = runBlocking {
        mapOf("max_output_tokens" to outputLimit, "content_filter" to filtered).forEach { (reason, expected) ->
            val stream = "data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\"," +
                "\"incomplete_details\":{\"reason\":\"$reason\"}}}\n\n"
            assertSingleError(responses(stream).sendPrompt(request()).toList(), expected, reason)
        }
    }

    @Test
    fun `未知结束原因仍按协议合同错误处理`() = runBlocking {
        val stream = "data: {\"choices\":[{\"delta\":{\"content\":\"42\"},\"finish_reason\":\"future\"}]}\n\ndata: [DONE]\n\n"
        assertSingleError(compat(stream).sendPrompt(request()).toList(), Expected(GenerationFailureCode.SERVER, false), "future")
    }

    private fun assertSingleError(chunks: List<StreamChunk>, expected: Expected, reason: String) {
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
        val error = chunks.filterIsInstance<StreamChunk.Error>().single()
        assertWithMessage(reason).that(error.code).isEqualTo(expected.code)
        assertWithMessage(reason).that(error.retryable).isEqualTo(expected.retryable)
    }

    private fun request() = PromptRequest(listOf(ProtocolMessage("user", "hi")), "test-model")

    private fun client(body: String) = HttpClient(MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    })

    private fun openAi(body: String) = OpenAIProtocol(
        baseUrl = "https://api.openai.com",
        apiKey = "test-key",
        model = "test-model",
        httpClient = client(body),
    )

    private fun compat(body: String) = GenericOpenAICompatProtocol(
        baseUrl = "http://127.0.0.1:1337/v1/chat/completions",
        apiKey = "",
        model = "test-model",
        httpClient = client(body),
    )

    private fun anthropic(body: String) = AnthropicProtocol(
        baseUrl = "https://api.anthropic.com",
        apiKey = "test-key",
        model = "test-model",
        httpClient = client(body),
    )

    private fun gemini(body: String) = GeminiProtocol(
        baseUrl = "https://generativelanguage.googleapis.com",
        apiKey = "test-key",
        model = "gemini-3-flash-preview",
        httpClient = client(body),
    )

    private fun responses(body: String) = OpenAIResponsesProtocol(
        baseUrl = "https://example.test/v1",
        apiKey = "test-key",
        model = "test-model",
        httpClient = client(body),
    )
}
