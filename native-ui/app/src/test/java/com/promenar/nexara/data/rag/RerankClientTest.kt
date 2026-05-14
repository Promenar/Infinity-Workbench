package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RerankClientTest {

    private fun makeDoc(index: Int, content: String, sim: Float = 0.5f) = SearchResult(
        id = "doc-$index",
        content = content,
        createdAt = System.currentTimeMillis(),
        similarity = sim
    )

    private fun clientWithMockLlm(protocol: LlmProtocol): RerankClient =
        RerankClient(
            baseUrl = "",
            apiKey = "",
            modelId = "",
            llmProtocol = protocol,
            llmModelId = "test-model"
        )

    @Test
    fun `returns empty for empty candidates`() = runTest {
        val client = RerankClient("", "", "")
        val result = client.rerank("query", emptyList(), topK = 5)

        assertThat(result).isEmpty()
    }

    @Test
    fun `returns input for single candidate`() = runTest {
        val client = RerankClient("", "", "")
        val doc = makeDoc(0, "only one")
        val result = client.rerank("query", listOf(doc), topK = 5)

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo("doc-0")
    }

    @Test
    fun `parses valid rerank JSON response`() = runTest {
        val protocol = mockk<LlmProtocol>()
        val jsonResponse = """[{"index":0,"score":8.0},{"index":1,"score":3.0}]"""
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(
            content = jsonResponse
        )

        val client = clientWithMockLlm(protocol)
        val docs = listOf(
            makeDoc(0, "low content", sim = 0.3f),
            makeDoc(1, "high content", sim = 0.9f)
        )

        val result = client.rerank("query", docs, topK = 2)

        assertThat(result).hasSize(2)
        assertThat(result[0].id).isEqualTo("doc-0")
        assertThat(result[0].similarity).isGreaterThan(result[1].similarity)
    }

    @Test
    fun `returns original order on parse failure`() = runTest {
        val protocol = mockk<LlmProtocol>()
        coEvery { protocol.sendPromptSync(any()) } returns PromptResponse(
            content = "this is not json at all"
        )

        val client = clientWithMockLlm(protocol)
        val docs = listOf(
            makeDoc(0, "first"),
            makeDoc(1, "second")
        )

        val result = client.rerank("query", docs, topK = 2)

        assertThat(result).hasSize(2)
        assertThat(result[0].id).isEqualTo("doc-0")
        assertThat(result[1].id).isEqualTo("doc-1")
    }

    @Test
    fun `handles LLM timeout gracefully`() = runTest {
        val protocol = mockk<LlmProtocol>()
        coEvery { protocol.sendPromptSync(any()) } throws RuntimeException("timeout")

        val client = clientWithMockLlm(protocol)
        val docs = listOf(
            makeDoc(0, "first"),
            makeDoc(1, "second")
        )

        val result = client.rerank("query", docs, topK = 2)

        assertThat(result).hasSize(2)
        assertThat(result[0].id).isEqualTo("doc-0")
    }
}
