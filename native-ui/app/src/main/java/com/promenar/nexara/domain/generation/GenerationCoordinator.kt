package com.promenar.nexara.domain.generation

import kotlinx.coroutines.flow.StateFlow

data class GenerationError(
    val message: String,
    val cause: Throwable? = null,
)

data class GenerationTaskSnapshot(
    val taskId: String,
    val sessionId: String,
    val assistantMessageId: String,
    val phase: GenerationPhase,
    val generatedChars: Int,
    val startedAt: Long,
    val error: GenerationError? = null,
)

sealed interface StartGenerationResult {
    data class Started(val taskId: String) : StartGenerationResult
    data class Existing(val snapshot: GenerationTaskSnapshot) : StartGenerationResult
    data class Busy(val activeSessionId: String) : StartGenerationResult
    data class Rejected(val error: GenerationError) : StartGenerationResult
}

enum class CancellationReason { USER, TIMEOUT, REPLACED, SHUTDOWN, BACKGROUND_UNAVAILABLE }

interface GenerationCoordinator {
    val active: StateFlow<GenerationTaskSnapshot?>
    fun observe(sessionId: String): StateFlow<GenerationTaskSnapshot?>
    suspend fun start(request: GenerationRequest): StartGenerationResult
    fun cancel(taskId: String, reason: CancellationReason = CancellationReason.USER): Boolean
    fun acknowledgeTerminal(taskId: String): Boolean
    fun release(sessionId: String, discardTerminal: Boolean = false)
}
