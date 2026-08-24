package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ImageGenClientCancellationTest {
    @Test
    fun `error response body cancellation keeps original exception`() = runTest {
        val cancellation = CancellationException("image-error-body-cancelled")
        val body = object : ByteReadChannel by ByteReadChannel(ByteArray(0)) {
            override suspend fun readRemaining(limit: Long): ByteReadPacket {
                throw cancellation
            }
        }
        val client = ImageGenClient(
            baseUrl = "https://images.example.test",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(MockEngine {
                respond(body, HttpStatusCode.BadRequest)
            }),
            responseBodyReader = { throw cancellation },
        )

        val caught = try {
            client.generate("draw a cancellation")
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(caught).isSameInstanceAs(cancellation)
    }
}
