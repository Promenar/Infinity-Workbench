package com.promenar.nexara.data.remote.protocol

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class ProviderListModelsCancellationTest {
    @Test
    fun `OpenAI chat listModels preserves cancellation`() = runTest {
        assertCancellation(
            OpenAIProtocol(
                baseUrl = "https://api.openai.com",
                apiKey = "fake-key",
                model = "",
                httpClient = cancellingClient(),
            ),
        )
    }

    @Test
    fun `OpenAI responses listModels preserves cancellation`() = runTest {
        assertCancellation(
            OpenAIResponsesProtocol(
                baseUrl = "https://api.openai.com",
                apiKey = "fake-key",
                model = "",
                httpClient = cancellingClient(),
            ),
        )
    }

    @Test
    fun `Anthropic listModels preserves cancellation`() = runTest {
        assertCancellation(
            AnthropicProtocol(
                baseUrl = "https://api.anthropic.com",
                apiKey = "fake-key",
                model = "",
                httpClient = cancellingClient(),
            ),
        )
    }

    @Test
    fun `Anthropic listModels preserves cancellation while reading response body`() = runTest {
        val responseBody = object : ByteReadChannel by ByteReadChannel(ByteArray(0)) {
            override suspend fun readRemaining(limit: Long): ByteReadPacket {
                throw CancellationException("list-models-body-cancelled")
            }
        }
        val protocol = AnthropicProtocol(
            baseUrl = "https://api.anthropic.com",
            apiKey = "fake-key",
            model = "",
            httpClient = HttpClient(MockEngine {
                respond(responseBody, HttpStatusCode.OK)
            }),
        )

        try {
            protocol.listModels()
            fail("读取模型列表响应体时不得吞掉 CancellationException")
        } catch (cancelled: CancellationException) {
            check(cancelled.message == "list-models-body-cancelled") {
                "应原样传播响应体通道的取消异常"
            }
        }
    }

    @Test
    fun `Generic OpenAI compat listModels preserves cancellation`() = runTest {
        assertCancellation(
            GenericOpenAICompatProtocol(
                baseUrl = "https://generic.example.invalid/v1",
                apiKey = "fake-key",
                model = "",
                httpClient = cancellingClient(),
            ),
        )
    }

    private fun cancellingClient(): HttpClient = HttpClient(
        MockEngine { throw CancellationException("list-models-cancelled") },
    )

    private suspend fun assertCancellation(protocol: LlmProtocol) {
        try {
            protocol.listModels()
            fail("listModels 不得吞掉 CancellationException")
        } catch (cancelled: CancellationException) {
            check(cancelled.message == "list-models-cancelled") {
                "应原样传播 MockEngine 的取消异常"
            }
        }
    }
}
