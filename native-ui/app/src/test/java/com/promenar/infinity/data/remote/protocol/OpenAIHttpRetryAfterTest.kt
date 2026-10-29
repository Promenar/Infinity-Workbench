package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationFailureCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OpenAIHttpRetryAfterTest {
    @Test
    fun `流式限流错误保留重试秒数并限制整数范围`() = runTest {
        for ((header, expected) in listOf(
            "17" to 17,
            " 23 " to 23,
            "2147483648" to Int.MAX_VALUE,
            "0" to 60,
            "-1" to 60,
            "invalid" to 60,
            null to 60,
        )) {
            val error = httpError(HttpStatusCode.TooManyRequests, header)
            assertThat(error.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
            assertThat(error.retryAfterSeconds).isEqualTo(expected)
        }
    }

    @Test
    fun `非限流错误不采用重试响应头`() = runTest {
        val error = httpError(HttpStatusCode.Unauthorized, "17")
        assertThat(error.code).isEqualTo(GenerationFailureCode.AUTH)
        assertThat(error.retryAfterSeconds).isNull()
    }

    private suspend fun httpError(status: HttpStatusCode, retryAfter: String?): StreamChunk.Error {
        val client = HttpClient(MockEngine {
            respond(
                content = "synthetic upstream error",
                status = status,
                headers = headersOf(HttpHeaders.RetryAfter, listOfNotNull(retryAfter)),
            )
        })
        try {
            return OpenAIProtocol("https://provider.invalid/v1", "test-key", "test-model", client)
                .sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "Reply OK.")), model = "test-model", maxTokens = 16))
                .toList().filterIsInstance<StreamChunk.Error>().single()
        } finally { client.close() }
    }
}
