package com.promenar.nexara.data.remote.protocol

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
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
