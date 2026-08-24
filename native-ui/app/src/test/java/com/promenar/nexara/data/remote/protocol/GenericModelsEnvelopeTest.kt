package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GenericModelsEnvelopeTest {
    @Test
    fun `Generic模型列表同时接受裸数组与data对象envelope`() = runTest {
        val bare = listModels("""[{"id":"bare-a"},{"id":"bare-b"}]""")
        val wrapped = listModels("""{"data":[{"id":"wrapped-a"},{"id":"wrapped-b"}]}""")

        assertThat(bare).containsExactly("bare-a", "bare-b").inOrder()
        assertThat(wrapped).containsExactly("wrapped-a", "wrapped-b").inOrder()
    }

    @Test
    fun `Generic模型列表任一元素畸形时整包失败关闭`() = runTest {
        listOf(
            """[{"id":"valid"},{}]""",
            """{"data":[{"id":"valid"},{"id":7}]}""",
            """{"data":{}}""",
        ).forEach { body ->
            assertThat(listModels(body)).isEmpty()
        }
    }

    private suspend fun listModels(body: String): List<String> {
        val client = HttpClient(MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        return GenericOpenAICompatProtocol(
            baseUrl = "https://generic.example.test/v1/chat/completions",
            apiKey = "fake-key",
            model = "",
            httpClient = client,
        ).listModels()
    }
}
