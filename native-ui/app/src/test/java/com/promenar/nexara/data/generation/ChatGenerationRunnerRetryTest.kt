package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.CompletionReason
import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationFailedException
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRetry
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationTerminalStatus
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationToolDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 流式轮次的自动重试：只重发未执行工具的本轮请求，丢弃半截输出，按退避等待并发出可视化事件。 */
class ChatGenerationRunnerRetryTest {
    private fun failure(code: GenerationFailureCode, retryable: Boolean = false, retryAfter: Int? = null) =
        GenerationFailure(
            code = code,
            formatArgs = GenerationFailure.buildRetryAfterArgs(retryAfter),
            technical = "test",
            retryable = retryable,
        )

    private fun failing(failure: GenerationFailure, partial: String = "半截") =
        flowOf(GenerationChunk.Text(partial, "半截思考"), GenerationChunk.Failure(failure))

    private fun success(text: String) =
        flowOf(GenerationChunk.Text(text), GenerationChunk.Completed(CompletionReason.END_TURN))

    @Test
    fun `网络中断后重试成功且丢弃失败轮次的半截输出`() = runTest {
        val runtime = RetryRuntime(
            maxRetries = 10,
            attempts = listOf(
                failing(failure(GenerationFailureCode.NETWORK)),
                failing(failure(GenerationFailureCode.TIMEOUT)),
                success("完整回答"),
            ),
        )
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
        assertThat(runtime.persisted.last().content).isEqualTo("完整回答")
        assertThat(runtime.persisted.last().reasoning).isEmpty()
        assertThat(runtime.persisted.last().failure).isNull()
        assertThat(events.filterIsInstance<GenerationEvent.RetryScheduled>().map { it.retry }).containsExactly(
            GenerationRetry(1, 10, GenerationFailureCode.NETWORK, 1_000),
            GenerationRetry(2, 10, GenerationFailureCode.TIMEOUT, 2_000),
        ).inOrder()
        assertThat(runtime.waits).containsExactly(1_000L, 2_000L).inOrder()
        assertThat(events.filterIsInstance<GenerationEvent.PhaseChanged>().last().phase)
            .isEqualTo(GenerationPhase.COMPLETED)
    }

    @Test
    fun `鉴权等不可重试失败立即结束且不等待`() = runTest {
        val runtime = RetryRuntime(10, listOf(failing(failure(GenerationFailureCode.AUTH)), success("不应到达")))
        val events = mutableListOf<GenerationEvent>()

        val error = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat((error as GenerationFailedException).failure.code).isEqualTo(GenerationFailureCode.AUTH)
        assertThat(runtime.streamCalls).isEqualTo(1)
        assertThat(events.filterIsInstance<GenerationEvent.RetryScheduled>()).isEmpty()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.ERROR)
    }

    @Test
    fun `重试次数用尽后以最后一次失败结束`() = runTest {
        val runtime = RetryRuntime(2, List(3) { failing(failure(GenerationFailureCode.NETWORK)) })

        val error = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

        assertThat(error).isInstanceOf(GenerationFailedException::class.java)
        assertThat(runtime.streamCalls).isEqualTo(3)
        assertThat(runtime.waits).containsExactly(1_000L, 2_000L).inOrder()
        assertThat(runtime.persisted.last().failure?.code).isEqualTo(GenerationFailureCode.NETWORK)
    }

    @Test
    fun `关闭重试时首个失败直接结束`() = runTest {
        val runtime = RetryRuntime(0, listOf(failing(failure(GenerationFailureCode.NETWORK)), success("x")))

        runCatching { ChatGenerationRunner(runtime).run(request()) {} }

        assertThat(runtime.streamCalls).isEqualTo(1)
        assertThat(runtime.waits).isEmpty()
    }

    @Test
    fun `服务端错误只在协议层标记可重试时重试`() = runTest {
        val contract = RetryRuntime(10, listOf(failing(failure(GenerationFailureCode.SERVER)), success("x")))
        runCatching { ChatGenerationRunner(contract).run(request()) {} }
        assertThat(contract.streamCalls).isEqualTo(1)

        val gateway = RetryRuntime(10, listOf(failing(failure(GenerationFailureCode.SERVER, retryable = true)), success("ok")))
        ChatGenerationRunner(gateway).run(request()) {}
        assertThat(gateway.streamCalls).isEqualTo(2)
        assertThat(gateway.persisted.last().content).isEqualTo("ok")
    }

    @Test
    fun `流结束却没有完成信号时按网络失败重试`() = runTest {
        val runtime = RetryRuntime(3, listOf(flowOf(GenerationChunk.Text("断流")), success("补齐")))

        ChatGenerationRunner(runtime).run(request()) {}

        assertThat(runtime.streamCalls).isEqualTo(2)
        assertThat(runtime.persisted.last().content).isEqualTo("补齐")
    }

    @Test
    fun `退避时长按指数增长并尊重限流 Retry-After`() {
        val network = failure(GenerationFailureCode.NETWORK)
        assertThat((1..8).map { streamRetryDelayMs(it, network) })
            .containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L).inOrder()
        assertThat(streamRetryDelayMs(1, failure(GenerationFailureCode.RATE_LIMIT, retryAfter = 5))).isEqualTo(5_000L)
        assertThat(streamRetryDelayMs(1, failure(GenerationFailureCode.RATE_LIMIT, retryAfter = 600))).isEqualTo(60_000L)
        assertThat(streamRetryDelayMs(3, failure(GenerationFailureCode.RATE_LIMIT))).isEqualTo(4_000L)
    }

    @Test
    fun `展示层在重试后首个输出到来时清除重试提示`() = runTest {
        val store = GenerationPresentationStore()
        store.begin("A", "task")
        val retry = GenerationRetry(1, 10, GenerationFailureCode.NETWORK, 1_000)

        store.accept("A", "task", GenerationEvent.RetryScheduled(retry))
        store.accept("A", "task", GenerationEvent.PhaseChanged(GenerationPhase.CONNECTING))
        store.accept("A", "task", GenerationEvent.PhaseChanged(GenerationPhase.THINKING))
        assertThat(store.observe("A").value?.retry).isEqualTo(retry)

        store.accept("A", "task", GenerationEvent.PhaseChanged(GenerationPhase.STREAMING))
        assertThat(store.observe("A").value?.retry).isNull()

        store.accept("A", "task", GenerationEvent.RetryScheduled(retry))
        store.accept("A", "task", GenerationEvent.Failed(failure(GenerationFailureCode.NETWORK)))
        assertThat(store.observe("A").value?.retry).isNull()
    }

    private fun request() = GenerationRequest(
        sessionId = "session",
        assistantMessageId = "assistant",
        userMessageId = "user",
        userContent = "hi",
        imageDataUrls = emptyList(),
        runtimePolicy = GenerationRuntimePolicy.FOREGROUND_ONLY,
    )

    private class RetryRuntime(
        private val maxRetries: Int,
        private val attempts: List<Flow<GenerationChunk>>,
    ) : ChatGenerationRuntime {
        val persisted = mutableListOf<GenerationSnapshot>()
        val terminals = mutableListOf<GenerationTerminalStatus>()
        val waits = mutableListOf<Long>()
        var streamCalls = 0

        override fun maxStreamRetries(request: GenerationRequest): Int = maxRetries
        override suspend fun awaitRetry(delayMs: Long) { waits += delayMs }
        override suspend fun prepare(request: GenerationRequest) = Unit
        override suspend fun buildContext(request: GenerationRequest) = GenerationPreparationOutcome.Ready
        override suspend fun stream(request: GenerationRequest, attempt: Int): Flow<GenerationChunk> =
            attempts[streamCalls++]
        override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) {
            persisted += snapshot
        }
        override suspend fun handleTools(request: GenerationRequest, toolCalls: List<GenerationToolCall>) =
            GenerationToolDecision.COMPLETE
        override suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
        override suspend fun markTerminal(
            request: GenerationRequest,
            status: GenerationTerminalStatus,
            snapshot: GenerationSnapshot,
            cause: Throwable?,
        ) {
            terminals += status
        }
        override suspend fun flush(request: GenerationRequest) = Unit
        override fun cancelProvider() = Unit
    }
}
