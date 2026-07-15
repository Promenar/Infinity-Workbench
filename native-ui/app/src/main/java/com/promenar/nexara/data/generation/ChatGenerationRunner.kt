package com.promenar.nexara.data.generation

import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationFailedException
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
        var primaryFailure: Throwable? = null
        suspend fun safeEmit(event: GenerationEvent, primary: Throwable? = null) {
            try {
                emit(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (observerFailure: Throwable) {
                primary?.addSuppressed(observerFailure)
            }
        }
        suspend fun phase(value: GenerationPhase, primary: Throwable? = null) =
            safeEmit(GenerationEvent.PhaseChanged(value), primary)
        fun Throwable.isRecoveryOf(original: CancellationException): Boolean {
            var current: Throwable? = this
            while (current != null) {
                if (current === original) return true
                current = current.cause
            }
            return false
        }
        suspend fun flushOnce(primary: Throwable? = null) {
            if (!flushed) {
                try {
                    runtime.flush(request)
                    flushed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (flushFailure: Throwable) {
                    flushed = true
                    if (primary != null) primary.addSuppressed(flushFailure) else throw flushFailure
                }
            }
        }
        suspend fun publishPersistenceFailure(
            persistenceFailure: Throwable,
            originalFailure: Throwable? = null,
        ) {
            if (persistenceFailure is CancellationException) throw persistenceFailure
            if (originalFailure != null && originalFailure !== persistenceFailure) {
                persistenceFailure.addSuppressed(originalFailure)
            }
            flushOnce(persistenceFailure)
            val failure = com.promenar.nexara.domain.generation.GenerationFailure.persistence(
                technical = persistenceFailure.message,
                cause = persistenceFailure,
            )
            safeEmit(
                GenerationEvent.PersistenceFailed(persistenceFailure, originalFailure, failure),
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (persistenceFailure: Throwable) {
            publishPersistenceFailure(persistenceFailure, originalFailure)
            false
        }
        suspend fun flushRoundBoundaryWithRetry(): Boolean = try {
            runtime.flush(request)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstFailure: Throwable) {
            try {
                runtime.flush(request)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryFailure: Throwable) {
                retryFailure.addSuppressed(firstFailure)
                flushed = true
                val persistenceFailure = com.promenar.nexara.domain.generation.GenerationFailure.persistence(
                    technical = retryFailure.message,
                    cause = retryFailure,
                )
                safeEmit(
                    GenerationEvent.PersistenceFailed(retryFailure, null, persistenceFailure),
                    retryFailure,
                )
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
                    safeEmit(GenerationEvent.Rejected(initialPreparation.failure))
                    try {
                        flushOnce()
                        phase(GenerationPhase.FAILED)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (persistenceFailure: Throwable) {
                        publishPersistenceFailure(persistenceFailure, initialPreparation.failure.cause)
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
                            snapshot = snapshot.copy(failure = chunk.failure)
                            snapshot = persist(request, snapshot, emit)
                            if (snapshot.toolCalls.isEmpty() && snapshot.content.isBlank()) {
                                throw GenerationFailedException(chunk.failure)
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
                                    GenerationEvent.Rejected(continuationPreparation.failure),
                                )
                                try {
                                    flushOnce()
                                    phase(GenerationPhase.FAILED)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (persistenceFailure: Throwable) {
                                    publishPersistenceFailure(
                                        persistenceFailure,
                                        continuationPreparation.failure.cause,
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
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (persistenceFailure: Throwable) {
                                publishPersistenceFailure(persistenceFailure)
                            }
                        }
                        return
                    }
                    GenerationToolDecision.COMPLETE -> break
                }
            }
            snapshot.failure?.let { throw GenerationFailedException(it) }
            check(snapshot.content.isNotBlank()) { "生成完成但未产生有效内容" }
            phase(GenerationPhase.POST_PROCESSING)
            runtime.postProcess(request, snapshot)
            withContext(NonCancellable) {
                if (persistTerminal(GenerationTerminalStatus.SUCCESS)) {
                    phase(GenerationPhase.COMPLETED)
                }
            }
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            try {
                runtime.cancelProvider()
            } catch (cleanupCancellation: CancellationException) {
                if (!cleanupCancellation.isRecoveryOf(cancelled)) {
                    primaryFailure = cleanupCancellation
                    throw cleanupCancellation
                }
            } catch (cleanupFailure: Throwable) {
                cancelled.addSuppressed(cleanupFailure)
            }
            try {
                withContext(NonCancellable) {
                    if (persistTerminal(GenerationTerminalStatus.CANCELLED, cancelled)) {
                        phase(GenerationPhase.CANCELLED, cancelled)
                    }
                }
            } catch (cleanupCancellation: CancellationException) {
                if (!cleanupCancellation.isRecoveryOf(cancelled)) {
                    primaryFailure = cleanupCancellation
                    throw cleanupCancellation
                }
            } catch (cleanupFailure: Throwable) {
                cancelled.addSuppressed(cleanupFailure)
            }
            throw cancelled
        } catch (failure: Throwable) {
            primaryFailure = failure
            val generationFailure = failure.failureOrUnknown()
            withContext(NonCancellable) {
                if (persistTerminal(GenerationTerminalStatus.ERROR, failure)) {
                    safeEmit(GenerationEvent.Failed(generationFailure), failure)
                    phase(GenerationPhase.FAILED, failure)
                }
            }
            throw failure
        } finally {
            withContext(NonCancellable) { flushOnce(primaryFailure) }
        }
    }

    private suspend fun persist(
        request: GenerationRequest,
        snapshot: GenerationSnapshot,
        emit: suspend (GenerationEvent) -> Unit,
    ): GenerationSnapshot {
        val normalized = runtime.normalize(request, snapshot)
        runtime.persist(request, normalized)
        try {
            emit(GenerationEvent.SnapshotChanged(normalized))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // 展示观察者失败不影响已完成的持久化；取消仍必须向上传播。
        }
        return normalized
    }
}
