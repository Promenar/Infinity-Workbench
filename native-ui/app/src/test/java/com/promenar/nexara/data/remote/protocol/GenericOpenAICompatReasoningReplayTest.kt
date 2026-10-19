package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** DeepSeek 思考模式带 tools 时必须回传 reasoning_content，否则 400；不认识该字段的兼容服务不得收到它。 */
class GenericOpenAICompatReasoningReplayTest {
    private fun assistantMessages(type: ProtocolType): List<JsonObject> = runBlocking {
        var body = ""
        val protocol = GenericOpenAICompatProtocol(
            baseUrl = "https://compat.example/v1",
            apiKey = "test-key",
            model = "test-model",
            protocolType = type,
            httpClient = HttpClient(MockEngine { request ->
                body = request.body.toByteReadPacket().readText()
                respond(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }),
        )
        protocol.sendPrompt(
            PromptRequest(
                messages = listOf(
                    ProtocolMessage("user", "明天杭州天气"),
                    ProtocolMessage(
                        role = "assistant",
                        content = "",
                        reasoning = "需要先知道今天日期。",
                        toolCalls = listOf(ProtocolToolCall("call_1", "get_date", "{}")),
                    ),
                    ProtocolMessage("tool", "2026-10-19", name = "get_date", toolCallId = "call_1"),
                    ProtocolMessage(role = "assistant", content = "明天多云。", reasoning = "  "),
                    ProtocolMessage("user", "后天呢"),
                ),
                model = "test-model",
                tools = listOf(ProtocolTool(function = ProtocolToolFunction("get_date", "date", "{\"type\":\"object\"}"))),
            ),
        ).toList()
        Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["role"]!!.jsonPrimitive.content == "assistant" }
    }

    @Test
    fun `DeepSeek 与 Kimi 回传非空推理且保留工具调用`() {
        listOf(ProtocolType.DeepSeek, ProtocolType.Moonshot_Kimi).forEach { type ->
            val (toolRound, answer) = assistantMessages(type)
            assertThat(toolRound["reasoning_content"]!!.jsonPrimitive.content).isEqualTo("需要先知道今天日期。")
            assertThat(toolRound["tool_calls"]!!.jsonArray).hasSize(1)
            assertThat(answer.containsKey("reasoning_content")).isFalse()
        }
    }

    @Test
    fun `其它兼容服务不发送 reasoning_content`() {
        listOf(ProtocolType.Generic_OpenAI_Compat, ProtocolType.Mistral_Chat, ProtocolType.Qwen_DashScope).forEach { type ->
            assistantMessages(type).forEach { assertThat(it.containsKey("reasoning_content")).isFalse() }
        }
    }
}
