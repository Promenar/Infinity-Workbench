package com.promenar.nexara.data.trigger

import com.promenar.nexara.domain.trigger.SystemTurnDispatcher
import com.promenar.nexara.domain.trigger.SystemTurnOutcome
import com.promenar.nexara.domain.trigger.SystemTurnRequest
import com.promenar.nexara.domain.trigger.SystemTurnSkipReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** 生成任务登记与系统轮撤回共用同一个不可逆准入点。 */
class SystemTurnAdmissionTicket {
    private sealed interface State {
        data object Open : State
        data class Accepted(val taskId: String) : State
        data object Withdrawn : State
    }

    private val state = AtomicReference<State>(State.Open)
    fun tryAccept(taskId: String): Boolean = state.compareAndSet(State.Open, State.Accepted(taskId))
    internal fun tryWithdraw(): Boolean = state.compareAndSet(State.Open, State.Withdrawn)
    internal val isOpen: Boolean get() = state.get() === State.Open
    val acceptedTaskId: String? get() = (state.get() as? State.Accepted)?.taskId
}

/** 平台端口将数据库、生成器与通知留在应用装配层，排队规则可用虚拟时间验证。 */
interface SystemTurnRuntime {
    suspend fun awaitReady()
    suspend fun sessionExists(sessionId: String): Boolean
    suspend fun loadSession(sessionId: String): Boolean
    suspend fun isBusy(sessionId: String): Boolean
    /** 返回 null 表示开始前被其它生成抢占，调用方重新排队。 */
    suspend fun run(request: SystemTurnRequest, ticket: SystemTurnAdmissionTicket): SystemTurnOutcome?
}

class DefaultSystemTurnDispatcher(
    private val applicationScope: CoroutineScope,
    private val runtime: SystemTurnRuntime,
    private val failureMessage: String,
    private val interruptedMessage: String,
    private val busyTimeoutMillis: Long = 30 * 60 * 1_000L,
    private val pollMillis: Long = 250L,
) : SystemTurnDispatcher {
    private class Entry(val request: SystemTurnRequest) {
        val outcome = CompletableDeferred<SystemTurnOutcome>()
        val ticket = SystemTurnAdmissionTicket()
        var admissionOrder: Long = 0
        var expiry: Job? = null
        var inspection: Job? = null
        var execution: Deferred<SystemTurnOutcome?>? = null
    }
    private val lock = Any()
    private var nextAdmissionOrder = 0L
    private val pending = linkedMapOf<String, Entry>()
    private val running = mutableMapOf<String, Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
        val entry = Entry(request)
        val cancelled = mutableListOf<Job>()
        synchronized(lock) {
            entry.admissionOrder = nextAdmissionOrder++
            pending.put(request.sourceId, entry)?.let { older ->
                older.ticket.tryWithdraw()
                older.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
                older.inspection?.let(cancelled::add)
            }
            running[request.sourceId]?.let { older ->
                if (older.ticket.tryWithdraw()) {
                    older.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
                    older.execution?.let(cancelled::add)
                }
            }
            entry.expiry = applicationScope.launch {
                runtime.awaitReady()
                delay(busyTimeoutMillis)
                val expiredJobs = mutableListOf<Job>()
                synchronized(lock) {
                    if ((pending[request.sourceId] === entry || running[request.sourceId] === entry) && entry.ticket.tryWithdraw()) {
                        if (pending[request.sourceId] === entry) pending.remove(request.sourceId)
                        entry.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT))
                        entry.inspection?.let(expiredJobs::add)
                        entry.execution?.let(expiredJobs::add)
                        wake.trySend(Unit)
                    }
                }
                expiredJobs.forEach { it.cancel() }
            }
            entry.outcome.invokeOnCompletion { entry.expiry?.cancel() }
            ensureWorkerLocked()
            wake.trySend(Unit)
        }
        cancelled.forEach { it.cancel() }
        try { return entry.outcome.await() }
        finally {
            val abandonedJobs = mutableListOf<Job>()
            synchronized(lock) {
                if (entry.ticket.tryWithdraw()) {
                    if (pending[request.sourceId] === entry) pending.remove(request.sourceId)
                    entry.expiry?.let(abandonedJobs::add)
                    entry.outcome.cancel()
                    entry.inspection?.let(abandonedJobs::add)
                    entry.execution?.let(abandonedJobs::add)
                    wake.trySend(Unit)
                }
            }
            abandonedJobs.forEach { it.cancel() }
        }
    }

    /** 暂停或删除只撤回尚未开始的轮次，已发出的生成仍交付真实结果。 */
    fun withdrawPendingSource(sourceId: String) {
        val cancelled = mutableListOf<Job>()
        synchronized(lock) {
            pending.remove(sourceId)?.let {
                it.ticket.tryWithdraw()
                it.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
                it.inspection?.let(cancelled::add)
                wake.trySend(Unit)
            }
            running[sourceId]?.let {
                if (it.ticket.tryWithdraw()) {
                    it.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
                    it.execution?.let(cancelled::add)
                }
            }
        }
        cancelled.forEach { it.cancel() }
    }

    /** 系统执行窗口被撤回时，仅停止该来源已经开始的生成。普通调用方取消不走此入口。 */
    suspend fun stopSource(sourceId: String) {
        var inspection: Job? = null
        val entry = synchronized(lock) {
            pending.remove(sourceId)?.also {
                it.ticket.tryWithdraw()
                it.outcome.complete(SystemTurnOutcome.Failed(interruptedMessage))
                inspection = it.inspection
                wake.trySend(Unit)
            }
            running[sourceId]?.also {
                if (it.ticket.tryWithdraw()) it.outcome.complete(SystemTurnOutcome.Failed(interruptedMessage))
            }
        }
        inspection?.cancel()
        entry?.execution?.cancelAndJoin()
    }

    private fun ensureWorkerLocked() {
        if (worker?.isActive == true) return
        worker = applicationScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { drain() }
        worker?.start()
    }

    private suspend fun drain() {
        try {
            runtime.awaitReady()
            while (true) {
                val entries = synchronized(lock) {
                    pending.values.toList().also { if (it.isEmpty()) worker = null }
                }
                if (entries.isEmpty()) return
                for (entry in entries) {
                    val inspection = synchronized(lock) {
                        val request = entry.request
                        val earlierSameSession = pending.values.any {
                            it !== entry && it.request.sessionId == request.sessionId && it.admissionOrder < entry.admissionOrder
                        }
                        if (pending[request.sourceId] !== entry || entry.inspection != null ||
                            running.containsKey(request.sourceId) || earlierSameSession ||
                            running.values.any { it.request.sessionId == request.sessionId }
                        ) null else applicationScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                            inspectEntry(entry)
                        }.also { entry.inspection = it }
                    }
                    if (inspection != null) {
                        inspection.start()
                    }
                }
                if (synchronized(lock) { pending.isNotEmpty() }) withTimeoutOrNull(pollMillis) { wake.receive() }
            }
        } finally {
            val thisJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            synchronized(lock) {
                // 正常退出时 worker 已置空；不能清除刚入队请求新建的 worker。
                if (worker === thisJob) {
                    pending.values.forEach {
                        it.ticket.tryWithdraw()
                        it.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.NOT_READY))
                        it.inspection?.cancel()
                    }
                    pending.clear()
                    worker = null
                }
            }
        }
    }

    private suspend fun inspectEntry(entry: Entry) {
        val request = entry.request
        var progressed = false
        try {
            if (!runtime.sessionExists(request.sessionId)) {
                synchronized(lock) {
                    if (pending[request.sourceId] === entry) {
                        pending.remove(request.sourceId)
                        entry.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING))
                        progressed = true
                    }
                }
                return
            }
            if (runtime.isBusy(request.sessionId)) return
            val admitted = synchronized(lock) {
                if (pending[request.sourceId] !== entry || running.containsKey(request.sourceId) ||
                    running.values.any { it.request.sessionId == request.sessionId }
                ) false else {
                    pending.remove(request.sourceId)
                    entry.execution = applicationScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        if (!runtime.loadSession(request.sessionId)) {
                            SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING)
                        } else runtime.run(request, entry.ticket)
                    }
                    running[request.sourceId] = entry
                    true
                }
            }
            if (admitted) {
                progressed = true
                applicationScope.launch { completeEntry(entry) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) {
                if (pending[request.sourceId] === entry) {
                    pending.remove(request.sourceId)
                    entry.outcome.complete(SystemTurnOutcome.Failed(failureMessage))
                    progressed = true
                }
            }
        } finally {
            val job = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (entry.inspection === job) entry.inspection = null
                if (progressed && pending.isNotEmpty()) {
                    ensureWorkerLocked()
                    wake.trySend(Unit)
                }
            }
        }
    }

    private suspend fun completeEntry(entry: Entry) {
        val request = entry.request
        try {
            val result = checkNotNull(entry.execution).await()
            if (result != null) entry.outcome.complete(result)
            else synchronized(lock) {
                if (!entry.outcome.isCompleted && entry.ticket.isOpen) {
                    if (pending.containsKey(request.sourceId)) {
                        entry.ticket.tryWithdraw()
                        entry.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
                    } else pending[request.sourceId] = entry
                }
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                entry.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.NOT_READY))
                throw cancelled
            }
            entry.outcome.complete(SystemTurnOutcome.Failed(interruptedMessage))
        } catch (_: Exception) {
            entry.outcome.complete(SystemTurnOutcome.Failed(failureMessage))
        } finally {
            synchronized(lock) {
                if (running[request.sourceId] === entry) running.remove(request.sourceId)
                if (pending.isNotEmpty()) {
                    ensureWorkerLocked()
                    wake.trySend(Unit)
                }
            }
        }
    }
}
