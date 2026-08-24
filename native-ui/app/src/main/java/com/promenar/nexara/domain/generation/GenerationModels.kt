package com.promenar.nexara.domain.generation

enum class GenerationPhase {
    PREPARING,
    BUILDING_CONTEXT,
    CONNECTING,
    THINKING,
    STREAMING,
    WAITING_APPROVAL,
    POST_PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PERSISTENCE_FAILED,
}

enum class GenerationRuntimePolicy { BACKGROUND_ALLOWED, FOREGROUND_ONLY }

/** Provider 明确声明的成功终止原因；不得由 EOF 或局部内容推断。 */
enum class CompletionReason { END_TURN, TOOL_CALLS }

/** 单次生成允许确认并执行的工具调用总数，与工具循环轮次上限相互独立。 */
const val MAX_TOOL_CALLS_PER_GENERATION = 10

data class GenerationRequest(
    val sessionId: String,
    val assistantMessageId: String,
    val userMessageId: String?,
    val userContent: String,
    val imageDataUrls: List<String>,
    val runtimePolicy: GenerationRuntimePolicy,
    val rollbackUserOnPreparationFailure: Boolean = false,
    val assistantMessageIdToReplace: String? = null,
    val requestId: String = assistantMessageId,
)

data class GenerationToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class GenerationSnapshot(
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<GenerationToolCall> = emptyList(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val citations: List<GenerationCitation> = emptyList(),
    val failure: GenerationFailure? = null,
)

data class GenerationCitation(val title: String, val url: String, val source: String? = null)

sealed interface GenerationChunk {
    data class Text(val content: String, val reasoning: String? = null) : GenerationChunk
    data class Thinking(val content: String) : GenerationChunk
    data class ToolCall(val id: String, val name: String, val arguments: String) : GenerationChunk
    data class Usage(val input: Int, val output: Int, val total: Int) : GenerationChunk
    data class Citations(val citations: List<GenerationCitation>) : GenerationChunk
    data class Failure(val failure: GenerationFailure) : GenerationChunk
    data class Completed(
        val reason: CompletionReason,
        val completedToolCallIds: List<String> = emptyList(),
    ) : GenerationChunk
}

enum class GenerationToolDecision { CONTINUE, WAIT_FOR_APPROVAL, COMPLETE }
sealed interface GenerationPreparationOutcome {
    data object Ready : GenerationPreparationOutcome
    data class Handled(val failure: GenerationFailure) : GenerationPreparationOutcome
}
enum class GenerationTerminalStatus { SUCCESS, ERROR, CANCELLED }

sealed interface GenerationEvent {
    data class PhaseChanged(val phase: GenerationPhase) : GenerationEvent
    data class SnapshotChanged(val snapshot: GenerationSnapshot) : GenerationEvent
    data class TargetChanged(val assistantMessageId: String) : GenerationEvent
    data class Rejected(val failure: GenerationFailure) : GenerationEvent
    data class PersistenceFailed(
        val persistenceCause: Throwable,
        val originalCause: Throwable? = null,
        val failure: GenerationFailure,
    ) : GenerationEvent {
        override fun toString(): String =
            "PersistenceFailed(code=${failure.code}, " +
                "hasPersistenceCause=true, hasOriginalCause=${originalCause != null})"
    }
    data class Failed(val failure: GenerationFailure) : GenerationEvent
}

fun interface GenerationRunner {
    suspend fun run(request: GenerationRequest, emit: suspend (GenerationEvent) -> Unit)
}
