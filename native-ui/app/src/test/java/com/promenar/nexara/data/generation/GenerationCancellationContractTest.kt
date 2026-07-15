package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationTerminalStatus
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationToolDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GenerationCancellationContractTest {
    @Test
    fun `成功终态markTerminal取消不得转为持久化失败或完成`() = runTest {
        val cancellation = CancellationException("terminal-cancel")
        val runtime = CancellationRuntime().apply { terminalCancellation = cancellation }
        val events = mutableListOf<GenerationEvent>()

        val result = runCatching {
            ChatGenerationRunner(runtime).run(request(), events::add)
        }.exceptionOrNull()

        assertThat(result.hasCauseInstance(cancellation)).isTrue()
        assertThat(events.filterIsInstance<GenerationEvent.PersistenceFailed>()).isEmpty()
        assertThat(runtime.flushCalls).isEqualTo(1)
        assertThat(events.filterIsInstance<GenerationEvent.PhaseChanged>().map { it.phase })
            .doesNotContain(GenerationPhase.COMPLETED)
    }

    @Test
    fun `轮次首次flush取消不得重试或转为持久化失败`() = runTest {
        val cancellation = CancellationException("round-flush-cancel")
        val runtime = CancellationRuntime(
            chunks = flowOf(GenerationChunk.ToolCall("tool", "search", "{}"), GenerationChunk.Done),
        ).apply {
            toolDecision = GenerationToolDecision.CONTINUE
            flushCancellation = cancellation
        }
        val events = mutableListOf<GenerationEvent>()

        val result = runCatching {
            ChatGenerationRunner(runtime).run(request(), events::add)
        }.exceptionOrNull()

        assertThat(result.hasCauseInstance(cancellation)).isTrue()
        assertThat(runtime.flushCalls).isEqualTo(2) // 首次边界 + 取消终态清理，不得进行边界重试
        assertThat(events.filterIsInstance<GenerationEvent.PersistenceFailed>()).isEmpty()
    }

    @Test
    fun `轮次重试flush取消不得转为持久化失败`() = runTest {
        val cancellation = CancellationException("round-retry-cancel")
        val runtime = CancellationRuntime(
            chunks = flowOf(GenerationChunk.ToolCall("tool", "search", "{}"), GenerationChunk.Done),
        ).apply {
            toolDecision = GenerationToolDecision.CONTINUE
            firstFlushFailure = IllegalStateException("first")
            flushCancellation = cancellation
        }
        val events = mutableListOf<GenerationEvent>()

        val result = runCatching {
            ChatGenerationRunner(runtime).run(request(), events::add)
        }.exceptionOrNull()

        assertThat(result.hasCauseInstance(cancellation)).isTrue()
        assertThat(events.filterIsInstance<GenerationEvent.PersistenceFailed>()).isEmpty()
        assertThat(runtime.flushCalls).isEqualTo(3)
    }

    @Test
    fun `等待审批cleanup取消必须传播且不得误报持久化失败`() = runTest {
        val cancellation = CancellationException("approval-flush-cancel")
        val runtime = CancellationRuntime(
            chunks = flowOf(GenerationChunk.ToolCall("tool", "write", "{}"), GenerationChunk.Done),
        ).apply {
            toolDecision = GenerationToolDecision.WAIT_FOR_APPROVAL
            flushCancellation = cancellation
        }
        val events = mutableListOf<GenerationEvent>()

        val result = runCatching {
            ChatGenerationRunner(runtime).run(request(), events::add)
        }.exceptionOrNull()

        assertThat(result.hasCauseInstance(cancellation)).isTrue()
        assertThat(events.filterIsInstance<GenerationEvent.PersistenceFailed>()).isEmpty()
        assertThat(runtime.flushCalls).isEqualTo(2)
    }

    @Test
    fun `已处理拒绝cleanup取消必须传播且不得误报持久化失败`() = runTest {
        val cancellation = CancellationException("rejection-flush-cancel")
        val runtime = CancellationRuntime().apply {
            preparationOutcome = GenerationPreparationOutcome.Handled(
                com.promenar.nexara.domain.generation.GenerationFailure.unknown("handled"),
            )
            flushCancellation = cancellation
        }
        val events = mutableListOf<GenerationEvent>()

        val result = runCatching {
            ChatGenerationRunner(runtime).run(request(), events::add)
        }.exceptionOrNull()

        assertThat(result.hasCauseInstance(cancellation)).isTrue()
        assertThat(events.filterIsInstance<GenerationEvent.PersistenceFailed>()).isEmpty()
        assertThat(runtime.flushCalls).isEqualTo(2)
    }

    @Test
    fun `finally普通清理失败不得覆盖取消主异常而应加入suppressed`() = runTest {
        val primary = CancellationException("primary")
        val cleanup = IllegalStateException("cleanup")
        val runtime = CancellationRuntime().apply {
            terminalCancellation = primary
            finalFlushFailure = cleanup
        }

        val result = runCatching {
            ChatGenerationRunner(runtime).run(request()) {}
        }.exceptionOrNull()

        assertThat(result.hasCauseInstance(primary)).isTrue()
        assertThat(result.hasSuppressedInstance(cleanup)).isTrue()
    }

    @Test
    fun `phase observer 抛出的取消必须原样传播`() = runTest {
        val cancellation = CancellationException("observer-cancel")
        val runtime = CancellationRuntime()

        val result = runCatching {
            ChatGenerationRunner(runtime).run(request()) { event ->
                if (event == GenerationEvent.PhaseChanged(GenerationPhase.PREPARING)) {
                    throw cancellation
                }
            }
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
        assertThat(runtime.cancelled).isTrue()
    }

    @Test
    fun `snapshot observer 抛出的取消不得被 persist 辅助逻辑吞掉`() = runTest {
        val cancellation = CancellationException("snapshot-observer-cancel")
        val runtime = CancellationRuntime()

        val result = runCatching {
            ChatGenerationRunner(runtime).run(request()) { event ->
                if (event is GenerationEvent.SnapshotChanged) throw cancellation
            }
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
        assertThat(runtime.cancelled).isTrue()
    }

    @Test
    fun `普通 observer 异常仍不阻断成功终态`() = runTest {
        val runtime = CancellationRuntime()

        val result = runCatching {
            ChatGenerationRunner(runtime).run(request()) { event ->
                if (event is GenerationEvent.SnapshotChanged) error("ordinary-observer")
            }
        }.exceptionOrNull()

        assertThat(result).isNull()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
    }

    private fun request() = GenerationRequest(
        sessionId = "session",
        assistantMessageId = "assistant",
        userMessageId = "user",
        userContent = "hello",
        imageDataUrls = emptyList(),
        runtimePolicy = GenerationRuntimePolicy.FOREGROUND_ONLY,
    )

    private fun Throwable?.hasCauseInstance(expected: Throwable): Boolean {
        var current = this
        while (current != null) {
            if (current === expected) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable?.hasSuppressedInstance(expected: Throwable): Boolean {
        var current = this
        while (current != null) {
            if (current.suppressed.any { it === expected }) return true
            current = current.cause
        }
        return false
    }

    private class CancellationRuntime(
        private val chunks: Flow<GenerationChunk> = flowOf(GenerationChunk.Text("ok"), GenerationChunk.Done),
    ) : ChatGenerationRuntime {
        var cancelled = false
        val terminals = mutableListOf<GenerationTerminalStatus>()
        var terminalCancellation: CancellationException? = null
        var flushCancellation: CancellationException? = null
        var firstFlushFailure: Throwable? = null
        var finalFlushFailure: Throwable? = null
        var toolDecision = GenerationToolDecision.COMPLETE
        var preparationOutcome: GenerationPreparationOutcome = GenerationPreparationOutcome.Ready
        var flushCalls = 0

        override suspend fun prepare(request: GenerationRequest) = Unit
        override suspend fun buildContext(request: GenerationRequest) = preparationOutcome
        override suspend fun stream(request: GenerationRequest, attempt: Int): Flow<GenerationChunk> =
            chunks
        override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
        override suspend fun handleTools(
            request: GenerationRequest,
            toolCalls: List<GenerationToolCall>,
        ) = toolDecision
        override suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
        override suspend fun markTerminal(
            request: GenerationRequest,
            status: GenerationTerminalStatus,
            snapshot: GenerationSnapshot,
            cause: Throwable?,
        ) {
            terminals += status
            terminalCancellation?.let { throw it }
        }
        override suspend fun flush(request: GenerationRequest) {
            flushCalls++
            firstFlushFailure?.let {
                firstFlushFailure = null
                throw it
            }
            flushCancellation?.let {
                flushCancellation = null
                throw it
            }
            finalFlushFailure?.let { throw it }
        }
        override fun cancelProvider() {
            cancelled = true
        }
    }
}
