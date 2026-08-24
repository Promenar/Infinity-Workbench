package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.CompletionReason
import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationFailedException
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationTerminalStatus
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationToolDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatGenerationRunnerTerminalContractTest {
    @Test
    fun `截断工具流没有 Completed 时不得进入审批或执行`() = runTest {
        val runtime = RuntimeFixture(
            listOf(flowOf(GenerationChunk.ToolCall("call", "search", "{}"))),
            knownTools = setOf("search"),
        )

        val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

        assertThat(failure).isInstanceOf(GenerationFailedException::class.java)
        assertThat(runtime.handled).isEmpty()
        assertThat(runtime.terminals).containsExactly(GenerationTerminalStatus.ERROR)
    }

    @Test
    fun `TOOL_CALLS completed IDs 不匹配与数组参数都失败关闭`() = runTest {
        val mismatch = RuntimeFixture(
            listOf(
                flowOf(
                    GenerationChunk.ToolCall("call", "search", "{}"),
                    GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("other")),
                ),
            ),
            knownTools = setOf("search"),
        )
        val malformed = RuntimeFixture(
            listOf(
                flowOf(
                    GenerationChunk.ToolCall("call", "search", "[]"),
                    GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
                ),
            ),
            knownTools = setOf("search"),
        )

        assertThat(runCatching { ChatGenerationRunner(mismatch).run(request()) {} }.exceptionOrNull())
            .isInstanceOf(GenerationFailedException::class.java)
        assertThat(runCatching { ChatGenerationRunner(malformed).run(request()) {} }.exceptionOrNull())
            .isInstanceOf(GenerationFailedException::class.java)
        assertThat(mismatch.handled).isEmpty()
        assertThat(malformed.handled).isEmpty()
    }

    @Test
    fun `END_TURN 不能携带工具且未知工具不能执行`() = runTest {
        val endTurnWithTool = RuntimeFixture(
            listOf(
                flowOf(
                    GenerationChunk.ToolCall("call", "search", "{}"),
                    GenerationChunk.Completed(CompletionReason.END_TURN),
                ),
            ),
            knownTools = setOf("search"),
        )
        val unknown = RuntimeFixture(
            listOf(
                flowOf(
                    GenerationChunk.ToolCall("call", "unknown", "{}"),
                    GenerationChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call")),
                ),
            ),
            knownTools = setOf("search"),
        )

        assertThat(runCatching { ChatGenerationRunner(endTurnWithTool).run(request()) {} }.exceptionOrNull())
            .isInstanceOf(GenerationFailedException::class.java)
        assertThat(runCatching { ChatGenerationRunner(unknown).run(request()) {} }.exceptionOrNull())
            .isInstanceOf(GenerationFailedException::class.java)
        assertThat(endTurnWithTool.handled).isEmpty()
        assertThat(unknown.handled).isEmpty()
    }

    @Test
    fun `生成全过程超过十个工具调用时第二轮不执行`() = runTest {
        fun round(prefix: String, count: Int): Flow<GenerationChunk> {
            val calls = (1..count).map { GenerationChunk.ToolCall("$prefix-$it", "search", "{}") }
            return flowOf(
                *(calls + GenerationChunk.Completed(
                    CompletionReason.TOOL_CALLS,
                    calls.map { it.id },
                )).toTypedArray(),
            )
        }
        val runtime = RuntimeFixture(
            listOf(round("a", 6), round("b", 5)),
            knownTools = setOf("search"),
        ).apply {
            decisions += GenerationToolDecision.CONTINUE
        }

        val failure = runCatching { ChatGenerationRunner(runtime).run(request()) {} }.exceptionOrNull()

        assertThat(failure).isInstanceOf(GenerationFailedException::class.java)
        assertThat(runtime.handled).hasSize(1)
        assertThat(runtime.handled.single()).hasSize(6)
    }

    private fun request() = GenerationRequest(
        "session", "assistant", "user", "hello", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED,
    )

    private class RuntimeFixture(
        private val streams: List<Flow<GenerationChunk>>,
        private val knownTools: Set<String>,
    ) : ChatGenerationRuntime {
        val handled = mutableListOf<List<GenerationToolCall>>()
        val terminals = mutableListOf<GenerationTerminalStatus>()
        val decisions = ArrayDeque<GenerationToolDecision>()

        override suspend fun prepare(request: GenerationRequest) = Unit
        override suspend fun buildContext(request: GenerationRequest) = GenerationPreparationOutcome.Ready
        override suspend fun stream(request: GenerationRequest, attempt: Int) = streams[attempt]
        override fun knownToolNames(request: GenerationRequest): Set<String> = knownTools
        override suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot) = Unit
        override suspend fun handleTools(
            request: GenerationRequest,
            toolCalls: List<GenerationToolCall>,
        ): GenerationToolDecision {
            handled += toolCalls
            return decisions.removeFirstOrNull() ?: GenerationToolDecision.COMPLETE
        }
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
