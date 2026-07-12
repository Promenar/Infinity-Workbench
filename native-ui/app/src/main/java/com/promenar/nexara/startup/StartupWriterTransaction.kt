package com.promenar.nexara.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class StartupWriterRegistration(
    val register: () -> Unit,
    val rollback: () -> Unit,
)

internal class StartupWriterTransaction<T>(
    private val preflight: () -> T,
    private val registrations: (T) -> List<StartupWriterRegistration>,
) {
    fun commit(): StartupWriterSession<T> {
        val prepared = preflight()
        val attempted = mutableListOf<StartupWriterRegistration>()
        try {
            registrations(prepared).forEach { registration ->
                attempted += registration
                registration.register()
            }
        } catch (failure: Throwable) {
            attempted.asReversed().forEach { registration ->
                runCatching(registration.rollback).exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
        return StartupWriterSession(prepared, attempted.toList())
    }
}

internal class StartupWriterSession<T>(
    val prepared: T,
    private val registrations: List<StartupWriterRegistration>,
) {
    private var active = true

    fun rollback() {
        if (!active) return
        active = false
        var rollbackFailure: Throwable? = null
        registrations.asReversed().forEach { registration ->
            runCatching(registration.rollback).exceptionOrNull()?.let { failure ->
                if (rollbackFailure == null) rollbackFailure = failure
                else rollbackFailure?.addSuppressed(failure)
            }
        }
        rollbackFailure?.let { throw it }
    }
}

enum class StartupBackgroundTask {
    PROVIDER_CONFIGURATION,
    LOCAL_MODEL_AUTO_LOAD,
    VECTOR_RESUME,
}

enum class StartupBackgroundTaskHealth {
    NotStarted,
    Running,
    Healthy,
    Failed,
}

class StartupBackgroundHealthMonitor(
    private val logFailure: (StartupBackgroundTask, Throwable) -> Unit,
) {
    private val _state = MutableStateFlow(
        StartupBackgroundTask.entries.associateWith { StartupBackgroundTaskHealth.NotStarted }
    )
    val state: StateFlow<Map<StartupBackgroundTask, StartupBackgroundTaskHealth>> = _state.asStateFlow()

    fun launch(
        scope: CoroutineScope,
        task: StartupBackgroundTask,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend () -> Unit,
    ): Job = scope.launch(start = start) {
        runTask(task, block)
    }

    suspend fun <T> collectResilient(
        task: StartupBackgroundTask,
        events: Flow<T>,
        handle: suspend (T) -> Unit,
    ) {
        events.collect { event -> runTask(task) { handle(event) } }
    }

    private suspend fun runTask(
        task: StartupBackgroundTask,
        block: suspend () -> Unit,
    ) {
        setHealth(task, StartupBackgroundTaskHealth.Running)
        try {
            block()
            setHealth(task, StartupBackgroundTaskHealth.Healthy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            setHealth(task, StartupBackgroundTaskHealth.Failed)
            logFailure(task, failure)
        }
    }

    private fun setHealth(task: StartupBackgroundTask, health: StartupBackgroundTaskHealth) {
        _state.update { current -> current + (task to health) }
    }
}
