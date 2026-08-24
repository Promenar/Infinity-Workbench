package com.promenar.nexara.data.generation

import com.promenar.nexara.domain.generation.CancellationReason
import com.promenar.nexara.domain.generation.GenerationCoordinator
import com.promenar.nexara.domain.generation.GenerationError
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRunner
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import com.promenar.nexara.domain.generation.StartGenerationResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface GenerationRunnerFactory {
    fun create(request: GenerationRequest, taskId: String): GenerationRunner
}

class DefaultGenerationCoordinator(
    private val applicationScope: CoroutineScope,
    private val runnerFactory: GenerationRunnerFactory,
    private val presentationStore: GenerationPresentationStore = GenerationPresentationStore(),
    private val taskIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val terminalRetentionLimit: Int = 32,
    private val executionGate: com.promenar.nexara.data.session.SessionExecutionGate? = null,
) : GenerationCoordinator {
    private data class RunningTask(
        val taskId: String,
        val request: GenerationRequest,
        val job: Job,
        val terminalAcknowledged: AtomicBoolean = AtomicBoolean(false),
    )

    private val mutex = Mutex()
    private val lifecycleLock = Any()
    private val mutableActive = MutableStateFlow<GenerationTaskSnapshot?>(null)
    private val sessionStates = ConcurrentHashMap<String, MutableStateFlow<GenerationTaskSnapshot?>>()
    private val terminalOrder = ArrayDeque<String>()
    private val terminalOrderLock = Any()

    @Volatile
    private var running: RunningTask? = null

    override val active: StateFlow<GenerationTaskSnapshot?> = mutableActive

    override fun observe(sessionId: String): StateFlow<GenerationTaskSnapshot?> =
        sessionStates.getOrPut(sessionId) { MutableStateFlow(null) }

    override suspend fun start(request: GenerationRequest): StartGenerationResult {
        return try {
            val gate = executionGate
            if (gate == null) startAdmitted(request)
            else gate.withNewSessionAdmission(request.sessionId) { startAdmitted(request) }
        } catch (failure: com.promenar.nexara.data.session.SessionDeletingException) {
            deletionRejected(failure)
        }
    }

    private suspend fun startAdmitted(request: GenerationRequest): StartGenerationResult = mutex.withLock {
        synchronized(lifecycleLock) {
        running?.let { current ->
            val snapshot = mutableActive.value ?: snapshotOf(current.taskId, current.request)
            return@synchronized if (
                current.request.sessionId == request.sessionId &&
                current.request.requestId == request.requestId
            ) {
                StartGenerationResult.Existing(snapshot)
            } else {
                StartGenerationResult.Busy(current.request.sessionId)
            }
        }

        val taskId = taskIdFactory()
        val startedAt = clock()
        val runner = try {
            presentationStore.begin(request.sessionId, taskId)
            runnerFactory.create(request, taskId)
        } catch (failure: Throwable) {
            presentationStore.finish(request.sessionId, taskId)
            return@synchronized StartGenerationResult.Rejected(
                GenerationError(failure.failureOrUnknown()),
            )
        }
        lateinit var task: RunningTask
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                runner.run(request) { event -> handleEvent(taskId, request, startedAt, event) }
            } catch (cancelled: CancellationException) {
                publishTerminalIfCurrent(
                    taskId,
                    request,
                    startedAt,
                    GenerationPhase.CANCELLED,
                    GenerationError(
                        com.promenar.nexara.domain.generation.GenerationFailure.unknown(
                            technical = cancelled.message,
                            cause = cancelled,
                        ),
                    ),
                )
                throw cancelled
            } catch (failure: Throwable) {
                publishTerminalIfCurrent(
                    taskId,
                    request,
                    startedAt,
                    GenerationPhase.FAILED,
                    GenerationError(failure.failureOrUnknown()),
                )
            } finally {
                mutex.withLock {
                    if (running?.taskId == taskId) {
                        if (task.terminalAcknowledged.get()) {
                            presentationStore.finish(request.sessionId, taskId)
                            clearSessionState(request.sessionId, taskId)
                        } else {
                            retainTerminalState(request.sessionId, taskId)
                        }
                        running = null
                        mutableActive.value = null
                    }
                }
            }
        }
        task = RunningTask(taskId, request, job)
        running = task
        val initial = GenerationTaskSnapshot(
            taskId = taskId,
            sessionId = request.sessionId,
            assistantMessageId = request.assistantMessageId,
            phase = GenerationPhase.PREPARING,
            generatedChars = 0,
            startedAt = startedAt,
        )
        mutableActive.value = initial
        publishSessionState(request.sessionId, initial)
        job.start()
        StartGenerationResult.Started(taskId)
        }
    }

    private fun deletionRejected(failure: com.promenar.nexara.data.session.SessionDeletingException) =
        StartGenerationResult.Rejected(
            GenerationError(
                com.promenar.nexara.domain.generation.GenerationFailure.unknown(
                    technical = "session_deleting",
                    cause = failure,
                ),
            ),
        )

    override fun cancel(taskId: String, reason: CancellationReason): Boolean {
        val current = running ?: return false
        if (current.taskId != taskId) return false
        current.job.cancel(CancellationException("Generation cancelled: $reason"))
        return true
    }

    override suspend fun cancelAndJoinSession(
        sessionId: String,
        reason: CancellationReason,
    ): Boolean {
        val task = mutex.withLock { running?.takeIf { it.request.sessionId == sessionId } }
            ?: return false
        task.job.cancel(CancellationException("Generation cancelled: $reason"))
        task.job.join()
        return true
    }

    override fun acknowledgeTerminal(taskId: String): Boolean {
        val current = running
        if (current?.taskId == taskId) {
            val snapshot = sessionStates[current.request.sessionId]?.value
            if (snapshot?.taskId != taskId || !snapshot.phase.isTerminal()) return false
            current.terminalAcknowledged.set(true)
            presentationStore.finish(current.request.sessionId, taskId)
            clearSessionState(current.request.sessionId, taskId)
            if (mutableActive.value?.taskId == taskId) mutableActive.value = null
            return true
        }
        val entry = sessionStates.entries.firstOrNull { (_, flow) ->
            flow.value?.let { it.taskId == taskId && it.phase.isTerminal() } == true
        } ?: return false
        presentationStore.finish(entry.key, taskId)
        clearSessionState(entry.key, taskId)
        return true
    }

    override fun release(sessionId: String, discardTerminal: Boolean) {
        synchronized(lifecycleLock) {
            val activeTaskId = running?.takeIf { it.request.sessionId == sessionId }?.taskId
            val snapshot = sessionStates[sessionId]?.value
            if (snapshot != null && snapshot.taskId == activeTaskId && snapshot.phase !in TERMINAL_PHASES) {
                return@synchronized
            }
            if (snapshot?.phase?.let { it in TERMINAL_PHASES } == true && !discardTerminal) {
                return@synchronized
            }
            snapshot?.taskId?.let { presentationStore.finish(sessionId, it) }
            presentationStore.release(sessionId)
            releaseSessionState(sessionId, snapshot?.taskId)
        }
    }

    private suspend fun handleEvent(
        taskId: String,
        request: GenerationRequest,
        startedAt: Long,
        event: GenerationEvent,
    ) {
        if (running?.taskId != taskId) return
        presentationStore.accept(request.sessionId, taskId, event)
        val previous = sessionState(request.sessionId).value
            ?: GenerationTaskSnapshot(
                taskId,
                request.sessionId,
                request.assistantMessageId,
                GenerationPhase.PREPARING,
                0,
                startedAt,
            )
        val next = when (event) {
            is GenerationEvent.PhaseChanged -> previous.copy(phase = event.phase)
            is GenerationEvent.SnapshotChanged -> previous.copy(generatedChars = event.snapshot.content.length)
            is GenerationEvent.TargetChanged -> previous.copy(assistantMessageId = event.assistantMessageId)
            is GenerationEvent.Rejected -> previous.copy(
                phase = GenerationPhase.FAILED,
                error = GenerationError(event.failure),
            )
            is GenerationEvent.PersistenceFailed -> previous.copy(
                phase = GenerationPhase.PERSISTENCE_FAILED,
                error = GenerationError(event.failure),
            )
            is GenerationEvent.Failed -> previous.copy(
                phase = GenerationPhase.FAILED,
                error = GenerationError(event.failure),
            )
        }
        sessionState(request.sessionId).value = next
        mutableActive.value = next
    }

    private fun publishTerminalIfCurrent(
        taskId: String,
        request: GenerationRequest,
        startedAt: Long,
        phase: GenerationPhase,
        error: GenerationError?,
    ) {
        if (running?.taskId != taskId) return
        val previous = sessionState(request.sessionId).value
            ?: snapshotOf(taskId, request, startedAt)
        if (previous.phase == GenerationPhase.PERSISTENCE_FAILED) return
        val terminal = previous.copy(phase = phase, error = error)
        presentationStore.publishTerminal(
            sessionId = request.sessionId,
            taskId = taskId,
            phase = phase,
            failure = error?.failure,
        )
        sessionState(request.sessionId).value = terminal
        mutableActive.value = terminal
    }

    private fun snapshotOf(
        taskId: String,
        request: GenerationRequest,
        startedAt: Long = clock(),
    ) = GenerationTaskSnapshot(
        taskId,
        request.sessionId,
        request.assistantMessageId,
        GenerationPhase.PREPARING,
        0,
        startedAt,
    )

    private fun sessionState(sessionId: String) =
        sessionStates.getOrPut(sessionId) { MutableStateFlow(null) }

    private fun publishSessionState(sessionId: String, snapshot: GenerationTaskSnapshot) {
        sessionStates.compute(sessionId) { _, existing ->
            (existing ?: MutableStateFlow<GenerationTaskSnapshot?>(null)).also { it.value = snapshot }
        }
    }

    private fun retainTerminalState(sessionId: String, taskId: String) {
        val snapshot = sessionStates[sessionId]?.value
        if (snapshot?.taskId != taskId || !snapshot.phase.isTerminal()) {
            presentationStore.finish(sessionId, taskId)
            clearSessionState(sessionId, taskId)
            return
        }
        synchronized(terminalOrderLock) {
            terminalOrder.remove(sessionId)
            terminalOrder.addLast(sessionId)
            while (terminalOrder.size > terminalRetentionLimit.coerceAtLeast(0)) {
                val evictedSessionId = terminalOrder.removeFirst()
                sessionStates[evictedSessionId]?.let { flow ->
                    flow.value?.taskId?.let { presentationStore.finish(evictedSessionId, it) }
                    presentationStore.release(evictedSessionId)
                    flow.value = null
                }
            }
        }
    }

    private fun clearSessionState(sessionId: String, taskId: String?) {
        val flow = sessionStates[sessionId] ?: return
        val snapshot = flow.value
        if (taskId != null && snapshot != null && snapshot.taskId != taskId) return
        flow.value = null
        synchronized(terminalOrderLock) { terminalOrder.remove(sessionId) }
    }

    private fun releaseSessionState(sessionId: String, taskId: String?) {
        var removed = false
        sessionStates.computeIfPresent(sessionId) { _, flow ->
            val snapshot = flow.value
            val changedSinceRead = if (taskId == null) {
                snapshot != null
            } else {
                snapshot != null && snapshot.taskId != taskId
            }
            if (changedSinceRead) {
                flow
            } else {
                flow.value = null
                removed = true
                flow
            }
        }
        if (removed) synchronized(terminalOrderLock) { terminalOrder.remove(sessionId) }
    }

    internal fun retainedSessionStateCount(): Int = sessionStates.values.count { it.value != null }

    private fun GenerationPhase.isTerminal(): Boolean = this in TERMINAL_PHASES

    private companion object {
        val TERMINAL_PHASES = setOf(
            GenerationPhase.COMPLETED,
            GenerationPhase.FAILED,
            GenerationPhase.CANCELLED,
            GenerationPhase.PERSISTENCE_FAILED,
        )
    }
}
