package com.promenar.nexara.data.remote.integration

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.data.remote.StreamConfig
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolCall
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.GenericOpenAICompatProtocol
import com.promenar.nexara.data.remote.protocol.StreamChunk
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** 显式启用的无密钥回环网关测试；仅发送合成数据，不连接外部配置或读取认证存储。 */
@RunWith(Parameterized::class)
class LocalGatewayIntegrationTest(private val model: String) {
    @Before
    fun requireExplicitExecution() {
        assumeTrue(System.getProperty("nexara.localGatewayIntegration") == "true")
    }

    @Test
    fun `真实流式文本经统一客户端完成`() = runBlocking {
        val result = request(listOf(ProtocolMessage("user", "Reply with exactly the number 42.")))
        assertWithMessage(result.wire.summary()).that(result.errors).isEmpty()
        assertThat(result.text).contains("42")
        assertThat(result.completed).isTrue()
        assertThat(result.errors).isEmpty()
    }

    @Test
    fun `真实工具参数及第二轮工具回传保持关联`() = runBlocking {
        val prompt = ProtocolMessage("user", "Call read_fixture_value with key=answer. Do not guess the result. After receiving the tool result, reply with its value only.")
        val tool = ProtocolTool(function = ProtocolToolFunction(
            name = "read_fixture_value",
            description = "读取测试夹具中的合成值，无网络或文件副作用。",
            parameters = """{"type":"object","properties":{"key":{"type":"string","enum":["answer"]}},"required":["key"],"additionalProperties":false}""",
        ))
        val first = request(listOf(prompt), mapOf("read_fixture_value" to tool))
        assertWithMessage(first.wire.summary()).that(first.errors).isEmpty()
        assertThat(first.completed).isTrue()
        assertThat(first.calls).hasSize(1)
        val call = first.calls.values.single()
        assertThat(call.id).isNotEmpty()
        assertThat(call.name).isEqualTo("read_fixture_value")
        assertThat(Json.parseToJsonElement(call.arguments).jsonObject["key"]?.jsonPrimitive?.content)
            .isEqualTo("answer")
        assertThat(first.completedIds).contains(call.id)
        val second = request(listOf(
            prompt,
            ProtocolMessage("assistant", first.text, toolCalls = listOf(call)),
            ProtocolMessage("tool", "{\"value\":42}", toolCallId = call.id),
        ), mapOf("read_fixture_value" to tool))
        assertWithMessage(second.wire.summary()).that(second.errors).isEmpty()
        assertThat(second.completed).isTrue()
        assertThat(second.text).contains("42")
    }

    private suspend fun request(
        messages: List<ProtocolMessage>,
        tools: Map<String, ProtocolTool> = emptyMap(),
    ): Result = withTimeout(120_000) {
        val result = Result()
        val transport = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
            engine { addInterceptor(result.wire.interceptor) }
            expectSuccess = false
        }
        val client = UnifiedLlmClient(providerConfigResolver = {
            UnifiedProviderConfig(
                protocolType = ProtocolType.Generic_OpenAI_Compat,
                baseUrl = "http://127.0.0.1:1337/v1/chat/completions",
                apiKey = "",
                defaultModel = model,
            )
        }, protocolFactory = { config ->
            GenericOpenAICompatProtocol(config.baseUrl, config.apiKey, config.defaultModel, httpClient = transport)
        })
        try {
            client.sendStream(StreamTextParams(
                model = model,
                messages = messages,
                maxOutputTokens = 1024,
                streamTimeout = 90_000,
                enableGeminiSearch = false,
            ), StreamConfig(tools = tools)).collect { chunk ->
                when (chunk) {
                    is StreamChunk.TextDelta -> result.text += chunk.content
                    is StreamChunk.ToolCallDelta -> {
                        val previous = result.calls[chunk.index]
                        result.calls[chunk.index] = ProtocolToolCall(
                            chunk.id.ifBlank { previous?.id.orEmpty() },
                            chunk.name.ifBlank { previous?.name.orEmpty() },
                            previous?.arguments.orEmpty() + chunk.arguments,
                        )
                    }
                    is StreamChunk.Completed -> {
                        result.completed = true
                        result.completedIds += chunk.completedToolCallIds
                    }
                    is StreamChunk.Error -> {
                        // 仅记录本地固定合同分类，禁止将上游错误正文写入测试报告。
                        val category = when {
                            chunk.technical?.startsWith("Malformed OpenAI-compatible") == true -> "malformed_event"
                            chunk.technical == "TOOL_CALLS terminal contained incomplete calls" -> "incomplete_tools"
                            chunk.technical == "END_TURN contained tool calls" -> "terminal_conflict"
                            chunk.technical?.contains("missing finish_reason") == true -> "missing_finish"
                            chunk.technical?.contains("before [DONE]") == true -> "missing_done"
                            else -> "other"
                        }
                        result.errors += "${chunk.code.name}:$category"
                    }
                    else -> Unit
                }
            }
        } finally { transport.close() }
        result
    }

    private class Result {
        var text = ""
        var completed = false
        val completedIds = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val calls = linkedMapOf<Int, ProtocolToolCall>()
        val wire = WireShape()
    }

    /** 仅输出响应结构计数；上游正文、头、错误详情不进入日志或断言。 */
    private class WireShape {
        private val bytes = Buffer()
        private var status = 0
        val interceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            status = response.code
            val body = response.body
            if (body == null) response else {
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val count = super.read(sink, byteCount)
                        if (count > 0 && bytes.size < 1_048_576) {
                            sink.copyTo(bytes, sink.size - count, minOf(count, 1_048_576 - bytes.size))
                        }
                        return count
                    }
                }
                response.newBuilder().body(source.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
            }
        }
        fun summary(): String {
            var events = 0; var errors = 0; var finishes = 0; var done = false
            bytes.clone().readUtf8().lineSequence().filter { it.startsWith("data:") }.forEach { line ->
                val data = line.substringAfter("data:").trim()
                if (data == "[DONE]") done = true else {
                    events++
                    val chunk = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                    if (chunk?.get("error") is JsonObject) errors++
                    (chunk?.get("choices") as? JsonArray)?.forEach { choice ->
                        val reason = (choice as? JsonObject)?.get("finish_reason")
                        if (reason != null && reason !is kotlinx.serialization.json.JsonNull) finishes++
                    }
                }
            }
            return "wire(http=$status,events=$events,errorRoots=$errors,finishes=$finishes,done=$done)"
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun models(): List<Array<String>> = listOf(
            "newapi/deepseek-v4-flash",
            "newapi/gemini-3.8-flash",
            "newapi/sensenova-6.8-flash-lite",
            "openai-chatgpt/gpt-5.6-luna",
            "newapi/MiniMax-M3",
        ).let { supported ->
            val selected = System.getProperty("nexara.localGatewayModels")
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                ?: supported
            require(selected.isNotEmpty() && selected.all { it in supported }) { "网关测试只接受已授权的模型ID" }
            selected.distinct().map { arrayOf(it) }
        }
    }
}
