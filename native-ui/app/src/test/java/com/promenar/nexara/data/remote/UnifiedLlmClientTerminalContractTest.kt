package com.promenar.nexara.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.domain.generation.CompletionReason
import com.promenar.nexara.domain.generation.GenerationFailureCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UnifiedLlmClientTerminalContractTest {
    @Test
    fun `EOF 无 terminal 转换为单一可重试 NETWORK Error`() = runTest {
        val chunks = client(flowOf(StreamChunk.TextDelta("partial")))
            .sendStream(params(), StreamConfig()).toList()

        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
        val error = chunks.filterIsInstance<StreamChunk.Error>().single()
        assertThat(error.code).isEqualTo(GenerationFailureCode.NETWORK)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `重复 Completed 失败关闭且不泄漏先到的成功 terminal`() = runTest {
        val completed = StreamChunk.Completed(CompletionReason.END_TURN)
        val chunks = client(flowOf(completed, completed)).sendStream(params(), StreamConfig()).toList()

        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
    }

    @Test
    fun `Error 后的 Completed 被丢弃并保持单 terminal`() = runTest {
        val failure = StreamChunk.Error(GenerationFailureCode.SERVER, retryable = true)
        val chunks = client(flowOf(failure, StreamChunk.Completed(CompletionReason.END_TURN)))
            .sendStream(params(), StreamConfig()).toList()

        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).containsExactly(failure)
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
    }

    private fun client(flow: Flow<StreamChunk>) = UnifiedLlmClient(
        providerConfigResolver = {
            UnifiedProviderConfig(ProtocolType.OpenAI_ChatCompletions, "https://api.openai.com", "key", "model")
        },
        protocolFactory = { FakeProtocol(flow) },
    )

    private fun params() = StreamTextParams(
        messages = listOf(ProtocolMessage("user", "hello")),
        model = "model",
    )

    private class FakeProtocol(private val flow: Flow<StreamChunk>) : LlmProtocol {
        override val protocolType = ProtocolType.OpenAI_ChatCompletions
        override suspend fun sendPrompt(request: PromptRequest) = flow
        override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
        override fun cancel() = Unit
    }
}
