package com.promenar.nexara.data.generation

import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRunner
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationToolDecision
import com.promenar.nexara.domain.generation.GenerationTerminalStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

interface ChatGenerationRuntime {
    suspend fun prepare(request: GenerationRequest)
    suspend fun buildContext(request: GenerationRequest): GenerationPreparationOutcome
    suspend fun stream(request: GenerationRequest, attempt: Int): Flow<GenerationChunk>
    suspend fun normalize(request: GenerationRequest, snapshot: GenerationSnapshot): GenerationSnapshot = snapshot
    suspend fun finalizeStream(request: GenerationRequest, snapshot: GenerationSnapshot): GenerationSnapshot = snapshot
    suspend fun persist(request: GenerationRequest, snapshot: GenerationSnapshot)
    suspend fun handleTools(
        request: GenerationRequest,
        toolCalls: List<GenerationToolCall>,
    ): GenerationToolDecision
    suspend fun prepareContinuation(request: GenerationRequest): String? = null
    suspend fun postProcess(request: GenerationRequest, snapshot: GenerationSnapshot)
    suspend fun markTerminal(
        request: GenerationRequest,
        status: GenerationTerminalStatus,
        snapshot: GenerationSnapshot,
        cause: Throwable? = null,
    ) = Unit
    suspend fun flush(request: GenerationRequest)
    fun cancelProvider()
}

class ChatGenerationRunner(
    private val runtime: ChatGenerationRuntime,
) : GenerationRunner {
    override suspend fun run(request: GenerationRequest, emit: suspend (GenerationEvent) -> Unit) {
        var snapshot = GenerationSnapshot()
        var flushed = false
        suspend fun safeEmit(event: GenerationEvent, primary: Throwable? = null) {
            runCatching { emit(event) }.exceptionOrNull()?.let { observerFailure ->
                primary?.addSuppressed(observerFailure)
            }
        }
        suspend fun phase(value: GenerationPhase, primary: Throwable? = null) =
            safeEmit(GenerationEvent.PhaseChanged(value), primary)
        suspend fun flushOnce(primary: Throwable? = null) {
            if (!flushed) {
                flushed = true
                try {
                    runtime.flush(request)
                } catch (flushFailure: Throwable) {
                    if (primary != null) primary.addSuppressed(flushFailure) else throw flushFailure
                }
            }
        }
        suspend fun publishPersistenceFailure(
            persistenceFailure: Throwable,
            originalFailure: Throwable? = null,
        ) {
            if (originalFailure != null && originalFailure !== persistenceFailure) {
                persistenceFailure.addSuppressed(originalFailure)
            }
            flushOnce(persistenceFailure)
            safeEmit(
                GenerationEvent.PersistenceFailed(persistenceFailure, originalFailure),
                persistenceFailure,
            )
            phase(GenerationPhase.PERSISTENCE_FAILED, persistenceFailure)
        }
        suspend fun persistTerminal(
            status: GenerationTerminalStatus,
            originalFailure: Throwable? = null,
        ): Boolean = try {
            runtime.markTerminal(request, status, snapshot, originalFailure)
            flushOnce()
            true
        } catch (persistenceFailure: Throwable) {
            publishPersistenceFailure(persistenceFailure, originalFailure)
            false
        }
        suspend fun flushRoundBoundaryWithRetry(): Boolean = try {
            runtime.flush(request)
            true
        } catch (firstFailure: Throwable) {
            try {
                runtime.flush(request)
                true
            } catch (retryFailure: Throwable) {
                retryFailure.addSuppressed(firstFailure)
                flushed = true
                safeEmit(GenerationEvent.PersistenceFailed(retryFailure), retryFailure)
                phase(GenerationPhase.PERSISTENCE_FAILED, retryFailure)
                false
            }
        }

        try {
            phase(GenerationPhase.PREPARING)
            runtime.prepare(request)
            phase(GenerationPhase.BUILDING_CONTEXT)
            val initialPreparation = runtime.buildContext(request)
            if (initialPreparation is GenerationPreparationOutcome.Handled) {
                withContext(NonCancellable) {
                    safeEmit(GenerationEvent.Rejected(initialPreparation.message, initialPreparation.cause))
                    try {
                        flushOnce()
                        phase(GenerationPhase.FAILED)
                    } catch (persistenceFailure: Throwable) {
                        publishPersistenceFailure(persistenceFailure, initialPreparation.cause)
                    }
                }
                return
            }
            var attempt = 0
            while (true) {
                phase(GenerationPhase.CONNECTING)
                val stream = runtime.stream(request, attempt)
                phase(GenerationPhase.THINKING)
                var streamingPhaseEmitted = false
                val roundTools = linkedMapOf<String, GenerationToolCall>()
                stream.collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Text -> {
                            if (!streamingPhaseEmitted) {
                                streamingPhaseEmitted = true
                                phase(GenerationPhase.STREAMING)
                            }
                            snapshot = snapshot.copy(
                                content = snapshot.content + chunk.content,
                                reasoning = snapshot.reasoning + chunk.reasoning.orEmpty(),
                            )
                            snapshot = persist(request, snapshot, emit)
                        }
                        is GenerationChunk.Thinking -> {
                            snapshot = snapshot.copy(reasoning = snapshot.reasoning + chunk.content)
                            snapshot = persist(request, snapshot, emit)
                        }
                        is GenerationChunk.ToolCall -> {
                            val old = roundTools[chunk.id]
                            roundTools[chunk.id] = GenerationToolCall(
                                id = chunk.id,
                                name = chunk.name.ifBlank { old?.name.orEmpty() },
                                arguments = old?.arguments.orEmpty() + chunk.arguments,
                            )
                            snapshot = snapshot.copy(toolCalls = roundTools.values.toList())
                            snapshot = persist(request, snapshot, emit)
                        }
                        is GenerationChunk.Usage -> {
                            snapshot = snapshot.copy(
                                inputTokens = chunk.input,
                                outputTokens = chunk.output,
                                totalTokens = chunk.total,
                            )
                            snapshot = persist(request, snapshot, emit)
                        }
                        is GenerationChunk.Citations -> {
                            snapshot = snapshot.copy(citations = chunk.citations)
                            snapshot = persist(request, snapshot, emit)
                        }
                        is GenerationChunk.Failure -> {
                            snapshot = snapshot.copy(errorMessage = chunk.message)
                            snapshot = persist(request, snapshot, emit)
                            if (snapshot.toolCalls.isEmpty() && snapshot.content.isBlank()) {
                                throw IllegalStateException(chunk.message)
                            }
                        }
                        GenerationChunk.Done -> Unit
                    }
                }
                val finalized = runtime.finalizeStream(request, snapshot)
                if (finalized != snapshot) snapshot = persist(request, finalized, emit)
                val effectiveTools = snapshot.toolCalls
                if (effectiveTools.isEmpty()) break
                phase(GenerationPhase.WAITING_APPROVAL)
                when (runtime.handleTools(request, effectiveTools)) {
                    GenerationToolDecision.CONTINUE -> {
                        attempt++
                        if (!flushRoundBoundaryWithRetry()) return
                        phase(GenerationPhase.BUILDING_CONTEXT)
                        val continuationPreparation = runtime.buildContext(request)
                        if (continuationPreparation is GenerationPreparationOutcome.Handled) {
                            withContext(NonCancellable) {
                                safeEmit(
                                    GenerationEvent.Rejected(
                                        continuationPreparation.message,
                                        continuationPreparation.cause,
                                    ),
                                )
                                try {
                                    flushOnce()
                                    phase(GenerationPhase.FAILED)
                                } catch (persistenceFailure: Throwable) {
                                    publishPersistenceFailure(
                                        persistenceFailure,
                                        continuationPreparation.cause,
                                    )
                                }
                            }
                            return
                        }
                        runtime.prepareContinuation(request)?.let { assistantMessageId ->
                            safeEmit(GenerationEvent.TargetChanged(assistantMessageId))
                        }
                        snapshot = GenerationSnapshot()
                        snapshot = persist(request, snapshot, emit)
                    }
                    GenerationToolDecision.WAIT_FOR_APPROVAL -> {
                        withContext(NonCancellable) {
                            try {
                                flushOnce()
                            } catch (persistenceFailure: Throwable) {
                                publishPersistenceFailure(persistenceFailure)
                            }
                        }
                        return
                    }
                    GenerationToolDecision.COMPLETE -> break
                }
            }
            check(snapshot.errorMessage == null) { snapshot.errorMessage.orEmpty() }
            check(snapshot.content.isNotBlank()) { "生成完成但未产生有效内容" }
            phase(GenerationPhase.POST_PROCESSING)
            runtime.postProcess(request, snapshot)
            withContext(NonCancellable) {
                if (persistTerminal(GenerationTerminalStatus.SUCCESS)) {
                    phase(GenerationPhase.COMPLETED)
                }
            }
        } catch (cancelled: CancellationException) {
            runtime.cancelProvider()
            withContext(NonCancellable) {
                if (persistTerminal(GenerationTerminalStatus.CANCELLED, cancelled)) {
                    phase(GenerationPhase.CANCELLED, cancelled)
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (persistTerminal(GenerationTerminalStatus.ERROR, failure)) {
                    safeEmit(GenerationEvent.Failed(failure), failure)
                    phase(GenerationPhase.FAILED, failure)
                }
            }
            throw failure
        } finally {
            withContext(NonCancellable) { flushOnce() }
        }
    }

    private suspend fun persist(
        request: GenerationRequest,
        snapshot: GenerationSnapshot,
        emit: suspend (GenerationEvent) -> Unit,
    ): GenerationSnapshot {
        val normalized = runtime.normalize(request, snapshot)
        runtime.persist(request, normalized)
        runCatching { emit(GenerationEvent.SnapshotChanged(normalized)) }
        return normalized
    }
}
