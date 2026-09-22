package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OpenAIOptionalFieldsContractTest(private val compatible: Boolean) {
    @Test
    fun `流式可选字段为null不终止文本且保留最后usage`() = runBlocking {
        val body = """
            data: {"choices":[{"delta":{"content":"42","tool_calls":null},"finish_reason":null}],"usage":null}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":null}

            data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}

            data: [DONE]

        """.trimIndent() + "\n"
        val chunks = protocol(body).sendPrompt(request()).toList()
        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).hasSize(1)
        assertThat(chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.content }).isEqualTo("42")
        assertThat(chunks.filterIsInstance<StreamChunk.Usage>()).hasSize(1)
        assertThat(chunks.filterIsInstance<StreamChunk.Usage>().single().usage).isEqualTo(ProtocolUsage(3, 1, 4))
    }

    @Test
    fun `工具名和参数分别到达且可选增量为null时仍保持关联`() = runBlocking {
        val events = listOf(
            """{"choices":[{"delta":{"role":"assistant","content":""},"finish_reason":null}],"usage":null}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_fixture","function":{"name":"read_fixture_value","arguments":null}}]},"finish_reason":null}],"usage":null}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":"{\"key\":\"answer\"}"}}]},"finish_reason":null}],"usage":null}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":null}""",
            """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}""",
        )
        val chunks = protocol(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")
            .sendPrompt(request()).toList()
        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>().single().completedToolCallIds).containsExactly("call_fixture")
        val calls = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
        assertThat(calls.map { it.id }.distinct()).containsExactly("call_fixture")
        assertThat(calls.joinToString("") { it.arguments }).isEqualTo("{\"key\":\"answer\"}")
    }

    @Test
    fun `同步可选字段为null仍返回文本`() = runBlocking {
        val body = """{"choices":[{"message":{"content":"42","tool_calls":null},"finish_reason":"stop"}],"usage":null}"""
        assertThat(protocol(body).sendPromptSync(request()).content).isEqualTo("42")
    }

    @Test
    fun `成功结束原因之后的上游错误事件仍否决成功`() = runBlocking {
        val body = """
            data: {"choices":[{"delta":{"content":"42"},"finish_reason":"stop"}]}

            data: {"error":{"type":"upstream_error","message":"synthetic"}}

            data: [DONE]

        """.trimIndent() + "\n"
        val chunks = protocol(body).sendPrompt(request()).toList()
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
    }

    @Test
    fun `可选字段的错误非空类型仍拒绝成功`() = runBlocking {
        for (fields in listOf("\"usage\":[]", "\"usage\":\"bad\"")) {
            val body = "data: {\"choices\":[{\"delta\":{\"content\":\"42\"},\"finish_reason\":\"stop\"}],$fields}\n\ndata: [DONE]\n\n"
            val chunks = protocol(body).sendPrompt(request()).toList()
            assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
            assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
            val trailing = "data: {\"choices\":[{\"delta\":{\"content\":\"42\"},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[],$fields}\n\ndata: [DONE]\n\n"
            val trailingChunks = protocol(trailing).sendPrompt(request()).toList()
            assertThat(trailingChunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
            assertThat(trailingChunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        }
        val invalid = """{"choices":[{"message":{"content":"42","tool_calls":{}},"finish_reason":"stop"}]}"""
        assertThat(runCatching { protocol(invalid).sendPromptSync(request()) }.exceptionOrNull()).isNotNull()
    }

    private fun request() = PromptRequest(listOf(ProtocolMessage("user", "synthetic")), "fixture")
    private fun protocol(body: String): LlmProtocol {
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) })
        return if (compatible) GenericOpenAICompatProtocol("http://localhost/v1/chat/completions", "", "fixture", httpClient = client)
        else OpenAIProtocol("http://localhost/v1/chat/completions", "", "fixture", client)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "compatible={0}")
        fun variants(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }
}
