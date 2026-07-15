@file:Suppress("DEPRECATION")

package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

class GenericOpenAICompatCancellationTest {
    @Test
    fun `取消仍在读取的SSE流必须关闭响应体并传播取消`() = runBlocking {
        val responseBody = ByteChannel(autoFlush = true)
        responseBody.writeStringUtf8(
            "data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n",
        )
        val completion = CompletableDeferred<Throwable?>()
        val payloadSeen = CompletableDeferred<Unit>()
        val protocol = GenericOpenAICompatProtocol(
            baseUrl = "https://example.test/v1",
            apiKey = "test-only",
            model = "model",
            httpClient = HttpClient(MockEngine {
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        ContentType.Text.EventStream.toString(),
                    ),
                )
            }),
        )
        val job = launch {
            protocol.sendPrompt(request())
                .onCompletion { cause -> completion.complete(cause) }
                .collect { chunk ->
                    if (chunk is StreamChunk.TextDelta && chunk.content == "first") {
                        payloadSeen.complete(Unit)
                    }
                }
        }

        withTimeout(5_000) { payloadSeen.await() }
        withTimeout(5_000) { job.cancelAndJoin() }

        assertThat(job.isCancelled).isTrue()
        assertThat(withTimeout(5_000) { completion.await() })
            .isInstanceOf(CancellationException::class.java)
        withTimeout(5_000) {
            while (!responseBody.isClosedForRead) yield()
        }
    }

    private fun request() = PromptRequest(
        messages = listOf(ProtocolMessage(role = "user", content = "hello")),
        model = "model",
        stream = true,
        streamTimeout = 120_000,
    )
}
