package com.promenar.nexara.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.middleware.LlmMiddleware
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class UnifiedLlmClientCancellationTest {
    @Test
    fun `取消统一客户端收集必须终止仍在途的协议生产者`() = runTest {
        val upstreamEntered = CompletableDeferred<Unit>()
        val upstreamExited = CompletableDeferred<Unit>()
        val client = clientWith(FakeProtocol(flow {
            emit(StreamChunk.TextDelta("first"))
            upstreamEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                upstreamExited.complete(Unit)
            }
        }))
        val job = launch {
            client.sendStream(params(), StreamConfig()).collect()
        }

        withTimeout(5_000) { upstreamEntered.await() }
        withTimeout(5_000) { job.cancelAndJoin() }

        assertThat(job.isCancelled).isTrue()
        withTimeout(5_000) { upstreamExited.await() }
    }

    @Test
    fun `慢收集超过channel容量时普通分片保持完整顺序`() = runTest {
        val expected = (0 until 160).map { "chunk-$it" }
        val client = clientWith(FakeProtocol(flow {
            expected.forEach { emit(StreamChunk.TextDelta(it)) }
            emit(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))
        }))

        val actual = client.sendStream(params(), StreamConfig()).toList()
            .filterIsInstance<StreamChunk.TextDelta>()
            .map { it.content }

        assertThat(actual).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `工具生命周期分片与原始工具分片在背压下均不丢失`() = runTest {
        val count = 96
        val client = clientWith(FakeProtocol(flow {
            repeat(count) { index ->
                emit(StreamChunk.ToolCallDelta("tool-$index", "tool", "{}", index))
            }
            emit(StreamChunk.Completed(
                com.promenar.nexara.domain.generation.CompletionReason.TOOL_CALLS,
                (0 until count).map { "tool-$it" },
            ))
        }))

        val actual = client.sendStream(params(), StreamConfig()).toList()

        assertThat(actual).hasSize(count * 2 + 1)
        repeat(count) { index ->
            assertThat(actual[index * 2]).isInstanceOf(StreamChunk.ToolCallLifecycle::class.java)
            assertThat(actual[index * 2 + 1]).isEqualTo(
                StreamChunk.ToolCallDelta("tool-$index", "tool", "{}", index),
            )
        }
        assertThat(actual.last()).isInstanceOf(StreamChunk.Completed::class.java)
    }

    @Test
    fun `普通协议异常的同一cause必须保留在最终错误分片`() = runTest {
        val cause = IllegalStateException("provider-private")
        val client = clientWith(FakeProtocol(flow { throw cause }))

        val error = client.sendStream(params(), StreamConfig()).toList().single() as StreamChunk.Error

        assertThat(error.cause).isSameInstanceAs(cause)
        assertThat(error.toString()).doesNotContain("provider-private")
    }

    @Test
    fun `onRequestEnd只在正常或错误分片终态执行而取消时不执行`() = runTest {
        var normalEndCount = 0
        val normal = clientWith(
            FakeProtocol(flow { emit(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN)) }),
            listOf(object : LlmMiddleware {
                override val name = "normal"
                override suspend fun onRequestEnd(params: StreamTextParams) { normalEndCount++ }
            }),
        )
        normal.sendStream(params(), StreamConfig()).collect()

        var errorEndCount = 0
        val failed = clientWith(
            FakeProtocol(flow { error("ordinary") }),
            listOf(object : LlmMiddleware {
                override val name = "failed"
                override suspend fun onRequestEnd(params: StreamTextParams) { errorEndCount++ }
            }),
        )
        failed.sendStream(params(), StreamConfig()).collect()

        var cancelledEndCount = 0
        val cancelled = clientWith(
            FakeProtocol(flow { throw CancellationException("cancel") }),
            listOf(object : LlmMiddleware {
                override val name = "cancelled"
                override suspend fun onRequestEnd(params: StreamTextParams) { cancelledEndCount++ }
            }),
        )
        runCatching { cancelled.sendStream(params(), StreamConfig()).collect() }

        assertThat(normalEndCount).isEqualTo(1)
        assertThat(errorEndCount).isEqualTo(1)
        assertThat(cancelledEndCount).isEqualTo(0)
    }
    @Test
    fun `协议取消异常必须从统一客户端原样传播`() = runTest {
        val cancellation = CancellationException("provider-cancel")
        val client = clientWith(FakeProtocol(flow { throw cancellation }))

        val result = runCatching {
            client.sendStream(params(), StreamConfig()).collect()
        }.exceptionOrNull()

        assertThat(result).isInstanceOf(CancellationException::class.java)
        assertThat(result?.message).isEqualTo(cancellation.message)
    }

    @Test
    fun `协议普通异常仍转换为结构化错误分片`() = runTest {
        val client = clientWith(FakeProtocol(flow { error("provider-private") }))
        val chunks = mutableListOf<StreamChunk>()

        client.sendStream(params(), StreamConfig()).collect(chunks::add)

        val error = chunks.single() as StreamChunk.Error
        assertThat(error.technical).contains("provider-private")
    }

    private fun clientWith(
        protocol: LlmProtocol,
        middlewares: List<LlmMiddleware> = emptyList(),
    ) = UnifiedLlmClient(
        providerConfigResolver = {
            UnifiedProviderConfig(
                protocolType = ProtocolType.Generic_OpenAI_Compat,
                baseUrl = "https://example.invalid",
                apiKey = "test-only",
                defaultModel = "model",
            )
        },
        middlewares = middlewares,
        protocolFactory = { protocol },
    )

    private fun params() = StreamTextParams(
        model = "model",
        messages = listOf(ProtocolMessage(role = "user", content = "hello")),
    )

    private class FakeProtocol(
        private val stream: Flow<StreamChunk>,
    ) : LlmProtocol {
        override val protocolType = ProtocolType.Generic_OpenAI_Compat
        override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = stream
        override suspend fun sendPromptSync(request: PromptRequest): PromptResponse = error("unused")
        override fun cancel() = Unit
    }
}
