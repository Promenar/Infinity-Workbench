package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.GenerationFailureCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VertexToolCapabilityGateTest {
    @Test
    fun `缺少 thought signature 夹具时 Vertex 工具能力失败关闭且不请求网络`() = runTest {
        var requests = 0
        val protocol = VertexAIProtocol(
            serviceAccountJson = "",
            projectId = "project",
            model = "gemini-test",
            baseUrl = "https://us-central1-aiplatform.googleapis.com",
            httpClient = HttpClient(MockEngine {
                requests++
                respondOk()
            }),
        )
        val chunks = protocol.sendPrompt(
            PromptRequest(
                messages = listOf(ProtocolMessage("user", "call")),
                model = "gemini-test",
                tools = listOf(
                    ProtocolTool(function = ProtocolToolFunction("write", "write", "{\"type\":\"object\"}")),
                ),
            ),
        ).toList()

        assertThat(requests).isEqualTo(0)
        assertThat(chunks).hasSize(1)
        assertThat((chunks.single() as StreamChunk.Error).code).isEqualTo(GenerationFailureCode.INVALID_REQUEST)
    }

    @Test
    fun `历史工具消息同样被 Vertex 能力门禁拒绝且不请求网络`() = runTest {
        var requests = 0
        val protocol = VertexAIProtocol(
            serviceAccountJson = "",
            projectId = "project",
            model = "gemini-test",
            baseUrl = "https://us-central1-aiplatform.googleapis.com",
            httpClient = HttpClient(MockEngine {
                requests++
                respondOk()
            }),
        )

        val chunks = protocol.sendPrompt(
            PromptRequest(
                messages = listOf(
                    ProtocolMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(ProtocolToolCall("call", "write", "{}")),
                    ),
                    ProtocolMessage("tool", "ok", toolCallId = "call"),
                ),
                model = "gemini-test",
            ),
        ).toList()

        assertThat(requests).isEqualTo(0)
        assertThat(chunks).hasSize(1)
        assertThat((chunks.single() as StreamChunk.Error).code).isEqualTo(GenerationFailureCode.INVALID_REQUEST)
    }
}
