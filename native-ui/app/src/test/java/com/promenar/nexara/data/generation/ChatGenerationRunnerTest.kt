package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationFailureCodec
import com.promenar.nexara.domain.generation.GenerationFailedException
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationToolDecision
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationTerminalStatus
import com.promenar.nexara.domain.generation.CompletionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield
import org.junit.Test

class ChatGenerationRunnerTest {
    @Test
    fun `固定阶段顺序且每个流分片持久化并在完成时flush`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("A"),
            GenerationChunk.Text("B"),
            GenerationChunk.Thinking("R"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )))
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases()).containsExactly(
            GenerationPhase.PREPARING,
            GenerationPhase.BUILDING_CONTEXT,
            GenerationPhase.CONNECTING,
            GenerationPhase.THINKING,
            GenerationPhase.STREAMING,
            GenerationPhase.POST_PROCESSING,
            GenerationPhase.COMPLETED,
        ).inOrder()
        assertThat(runtime.persisted.map { it.content to it.reasoning })
            .containsExactly("A" to "", "AB" to "", "AB" to "R").inOrder()
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
    }

    @Test
    fun `工具等待完成后继续下一轮流`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.ToolCall("tool-1", "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("tool-1")),
            ),
            flowOf(GenerationChunk.Text("continued"), GenerationChunk.Completed(CompletionReason.END_TURN)),
        )).apply { toolDecisions += GenerationToolDecision.CONTINUE }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases()).containsExactly(
            GenerationPhase.PREPARING, GenerationPhase.BUILDING_CONTEXT,
            GenerationPhase.CONNECTING, GenerationPhase.THINKING,
            GenerationPhase.WAITING_APPROVAL,
            GenerationPhase.BUILDING_CONTEXT,
            GenerationPhase.CONNECTING, GenerationPhase.THINKING, GenerationPhase.STREAMING,
            GenerationPhase.POST_PROCESSING, GenerationPhase.COMPLETED,
        ).inOrder()
        assertThat(runtime.streamAttempts).isEqualTo(2)
        assertThat(runtime.persisted.last().toolCalls).isEmpty()
        assertThat(runtime.persisted.last().failure).isNull()
        assertThat(runtime.flushCount).isEqualTo(2)
        assertThat(events.filterIsInstance<GenerationEvent.TargetChanged>().map { it.assistantMessageId })
            .containsExactly("assistant-next")
    }

    @Test
    fun `等待人工工具审批时flush并停在等待阶段`() = runTest {
        val runtime = FakeRuntime(listOf(
            flowOf(
                GenerationChunk.ToolCall("tool-1", "write", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("tool-1")),
            ),
        )).apply { toolDecisions += GenerationToolDecision.WAIT_FOR_APPROVAL }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases().last()).isEqualTo(GenerationPhase.WAITING_APPROVAL)
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.terminals).isEmpty()
    }

    @Test
    fun `已有部分文本后收到Failure仍落ERROR且不进入完成态`() = runTest {
        val streamFailure = GenerationFailure(
            code = GenerationFailureCode.TIMEOUT,
            formatArgs = emptyMap(),
            technical = "stream broken",
        )
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("partial"),
            GenerationChunk.Failure(streamFailure),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )))
        val events = mutableListOf<GenerationEvent>()

        val executionFailure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(executionFailure).isInstanceOf(GenerationFailedException::class.java)
        assertThat((executionFailure as GenerationFailedException).failure).isSameInstanceAs(streamFailure)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.FAILED)
        assertThat(events.phases()).doesNotContain(GenerationPhase.COMPLETED)
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.ERROR)
        assertThat(runtime.persisted.last().content).isEqualTo("partial")
        assertThat(runtime.persisted.last().failure).isEqualTo(streamFailure)
        assertThat(runtime.persisted.last().failure?.code).isEqualTo(GenerationFailureCode.TIMEOUT)
        assertThat(runtime.persisted.last().failure?.technical).isEqualTo("stream broken")
    }

    @Test
    fun `失败时标记FAILED并flush`() = runTest {
        val runtime = FakeRuntime(listOf(flow { throw IllegalStateException("network") }))
        val events = mutableListOf<GenerationEvent>()

        val failure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.FAILED)
        assertThat(runtime.flushCount).isEqualTo(1)
    }

    @Test
    fun `业务异常后终态持久化失败发布PERSISTENCE_FAILED且保留原异常`() = runTest {
        val original = IllegalStateException("network")
        val runtime = FakeRuntime(listOf(flow { throw original })).apply {
            terminalFailure = IllegalStateException("terminal db")
        }
        val events = mutableListOf<GenerationEvent>()

        val failure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(original)
        val persistence = events.filterIsInstance<GenerationEvent.PersistenceFailed>().single()
        assertThat(persistence.failure.code).isEqualTo(GenerationFailureCode.PERSISTENCE)
        assertThat(persistence.failure.technical).isEqualTo("terminal db")
        assertThat(persistence.originalCause).isSameInstanceAs(original)
        assertThat(persistence.persistenceCause.message).isEqualTo("terminal db")
        assertThat(persistence.persistenceCause.suppressed.asList()).contains(original)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(GenerationFailureCodec.encode(persistence.failure))
            .doesNotContain("terminal db")
        assertThat(events.phases()).doesNotContain(GenerationPhase.FAILED)
        assertThat(runtime.flushCount).isEqualTo(1)
    }

    @Test
    fun `成功结果终态持久化失败不得发布COMPLETED`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("done"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        ))).apply {
            terminalFailure = IllegalStateException("missing row")
        }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases().last()).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(events.phases()).doesNotContain(GenerationPhase.COMPLETED)
        assertThat(events.filterIsInstance<GenerationEvent.PersistenceFailed>().single().failure.code)
            .isEqualTo(GenerationFailureCode.PERSISTENCE)
        assertThat(events.filterIsInstance<GenerationEvent.PersistenceFailed>().single().failure.technical)
            .isEqualTo("missing row")
    }

    @Test
    fun `CONTINUE轮次强刷重试仍失败时停止下一轮并发布PERSISTENCE_FAILED`() = runTest {
        val runtime = FakeRuntime(
            listOf(flowOf(
                GenerationChunk.ToolCall("call", "search", "{}"),
                GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
            )),
        ).apply {
            toolDecisions += GenerationToolDecision.CONTINUE
            flushFailuresRemaining = 2
        }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(runtime.streamAttempts).isEqualTo(1)
        assertThat(runtime.flushCount).isEqualTo(2)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(events.phases()).doesNotContain(GenerationPhase.COMPLETED)
    }

    @Test
    fun `失败与取消时observer异常只作为suppressed且不阻断flush`() = runTest {
        val originalFailure = IllegalStateException("network")
        val failedRuntime = FakeRuntime(listOf(flow { throw originalFailure }))
        val failed = runCatching {
            ChatGenerationRunner(failedRuntime).run(request()) { event ->
                if (event is GenerationEvent.PhaseChanged && event.phase == GenerationPhase.FAILED) {
                    throw IllegalStateException("failed observer")
                }
            }
        }.exceptionOrNull()
        assertThat(failed).isSameInstanceAs(originalFailure)
        assertThat(failed!!.suppressed.map { it.message }).contains("failed observer")
        assertThat(failedRuntime.flushCount).isEqualTo(1)

        val cancelledRuntime = FakeRuntime(listOf(flow { throw CancellationException("cancel") }))
        val cancelled = runCatching {
            ChatGenerationRunner(cancelledRuntime).run(request()) { event ->
                if (event is GenerationEvent.PhaseChanged && event.phase == GenerationPhase.CANCELLED) {
                    throw IllegalStateException("cancel observer")
                }
            }
        }.exceptionOrNull()
        assertThat(cancelled).isInstanceOf(CancellationException::class.java)
        assertThat(cancelled!!.suppressed.map { it.message }).contains("cancel observer")
        assertThat(cancelledRuntime.flushCount).isEqualTo(1)
    }

    @Test
    fun `observer 自身抛出 CancellationException 时必须原样传播`() = runTest {
        val observerCancellation = CancellationException("observer cancelled")
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("done"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )))

        val failure = runCatching {
            ChatGenerationRunner(runtime).run(request()) { event ->
                if (event == GenerationEvent.PhaseChanged(GenerationPhase.PREPARING)) {
                    throw observerCancellation
                }
            }
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(observerCancellation)
        assertThat(runtime.providerCancelCount).isEqualTo(1)
        assertThat(runtime.flushCount).isEqualTo(1)
    }

    @Test
    fun `SUCCESS后COMPLETED observer异常不得重标ERROR`() = runTest {
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("done"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )))

        val failure = runCatching {
            ChatGenerationRunner(runtime).run(request()) { event ->
                if (event is GenerationEvent.PhaseChanged && event.phase == GenerationPhase.COMPLETED) {
                    throw IllegalStateException("observer gone")
                }
            }
        }.exceptionOrNull()

        assertThat(failure).isNull()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.SUCCESS)
        assertThat(runtime.flushCount).isEqualTo(1)
    }

    @Test
    fun `成功终态严格按mark再flush再COMPLETED发布`() = runTest {
        val trace = mutableListOf<String>()
        val runtime = FakeRuntime(listOf(flowOf(
            GenerationChunk.Text("done"),
            GenerationChunk.Completed(CompletionReason.END_TURN),
        )), trace)

        ChatGenerationRunner(runtime).run(request()) { event ->
            if (event is GenerationEvent.PhaseChanged) trace += "phase:${event.phase}"
        }

        assertThat(trace.takeLast(3)).containsExactly(
            "terminal:SUCCESS",
            "flush",
            "phase:COMPLETED",
        ).inOrder()
    }

    @Test
    fun `上下文或路由错误已由adapter处理时停止连接并flush`() = runTest {
        val handledFailure = GenerationFailure.unknown(technical = "handled failure")
        val runtime = FakeRuntime(listOf(flowOf(GenerationChunk.Completed(CompletionReason.END_TURN)))).apply {
            preparationOutcome = GenerationPreparationOutcome.Handled(handledFailure)
        }
        val events = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(runtime).run(request(), events::add)

        assertThat(events.phases()).containsExactly(
            GenerationPhase.PREPARING, GenerationPhase.BUILDING_CONTEXT, GenerationPhase.FAILED,
        ).inOrder()
        val rejected = events.filterIsInstance<GenerationEvent.Rejected>().single()
        assertThat(rejected.failure.code).isEqualTo(GenerationFailureCode.UNKNOWN)
        assertThat(rejected.failure.technical).isEqualTo("handled failure")
        assertThat(GenerationFailureCodec.encode(rejected.failure))
            .doesNotContain("handled failure")
        assertThat(runtime.streamAttempts).isEqualTo(0)
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.terminals).isEmpty()
    }

    @Test
    fun `取消传递到provider Flow并标记CANCELLED且flush`() = runTest {
        var providerCancelled = false
        val cancellingFlow = flow<GenerationChunk> {
            try {
                emit(GenerationChunk.Text("partial"))
                throw CancellationException("stop")
            } finally {
                providerCancelled = true
            }
        }
        val runtime = FakeRuntime(listOf(cancellingFlow))
        val events = mutableListOf<GenerationEvent>()

        val failure = runCatching { ChatGenerationRunner(runtime).run(request(), events::add) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(providerCancelled).isTrue()
        assertThat(runtime.providerCancelCount).isEqualTo(1)
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.CANCELLED)
        assertThat(runtime.flushCount).isEqualTo(1)
        assertThat(runtime.persisted.last().content).isEqualTo("partial")
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.CANCELLED)
    }

    @Test
    fun `外部取消时挂起中的终态flush在NonCancellable内完成`() = runTest {
        val runtime = FakeRuntime(listOf(flow { awaitCancellation() })).apply {
            suspendFlush = true
        }
        val events = mutableListOf<GenerationEvent>()
        val job = launch { ChatGenerationRunner(runtime).run(request(), events::add) }
        yield()

        job.cancel()
        job.join()

        assertThat(runtime.flushCompleted).isTrue()
        assertThat(events.phases().last()).isEqualTo(GenerationPhase.CANCELLED)
    }

    private fun request() = GenerationRequest(
        sessionId = "session",
        assistantMessageId = "assistant",
        userMessageId = "user",
        userContent = "hello",
        imageDataUrls = emptyList(),
        runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
    )

    private fun List<GenerationEvent>.phases() = filterIsInstance<GenerationEvent.PhaseChanged>().map { it.phase }

    private class FakeRuntime(
        private val streams: List<Flow<GenerationChunk>>,
        private val trace: MutableList<String> = mutableListOf(),
    ) : ChatGenerationRuntime {
        val persisted = mutableListOf<GenerationSnapshot>()
        val toolDecisions = ArrayDeque<GenerationToolDecision>()
        var streamAttempts = 0
        var flushCount = 0
        var providerCancelCount = 0
        var suspendFlush = false
        var flushCompleted = false
        var preparationOutcome: GenerationPreparationOutcome = GenerationPreparationOutcome.Ready
        val terminals = mutableListOf<GenerationTerminalStatus>()
        var terminalFailure: Throwable? = null
        var flushFailuresRemaining = 0
        var nextAssistantId: String? = "assistant-next"

        override suspend fun prepare(request: GenerationRequest) = Unit
        override suspend fun buildContext(request: GenerationRequest) = preparationOutcome
        override suspend fun stream(request: GenerationRequest, attempt: Int): Flow<GenerationChunk> {
            streamAttempts++
            return streams[attempt]
        }
        override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) {
            persisted += snapshot
        }
        override fun knownToolNames(request: GenerationRequest): Set<String> =
            setOf("search", "write", "read_file")
        override suspend fun handleTools(
            request: GenerationRequest,
            toolCalls: List<GenerationToolCall>,
        ): GenerationToolDecision = toolDecisions.removeFirstOrNull() ?: GenerationToolDecision.COMPLETE
        override suspend fun prepareContinuation(request: GenerationRequest): String? =
            nextAssistantId.also { nextAssistantId = null }
        override suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
        override suspend fun markTerminal(
            request: GenerationRequest,
            status: GenerationTerminalStatus,
            snapshot: GenerationSnapshot,
            cause: Throwable?,
        ) {
            terminals += status
            trace += "terminal:$status"
            terminalFailure?.let { throw it }
        }
        override suspend fun flush(request: GenerationRequest) {
            flushCount++
            trace += "flush"
            if (flushFailuresRemaining > 0) {
                flushFailuresRemaining--
                throw IllegalStateException("flush db")
            }
            if (suspendFlush) yield()
            flushCompleted = true
        }
        override fun cancelProvider() { providerCancelCount++ }
    }
}
