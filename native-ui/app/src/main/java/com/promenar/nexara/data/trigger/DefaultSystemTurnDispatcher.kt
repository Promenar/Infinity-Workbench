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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** 平台端口将数据库、生成器与通知留在应用装配层，排队规则可用虚拟时间验证。 */
interface SystemTurnRuntime {
    suspend fun awaitReady()
    suspend fun sessionExists(sessionId: String): Boolean
    suspend fun loadSession(sessionId: String): Boolean
    suspend fun isBusy(): Boolean
    /** 返回 null 表示开始前被其它生成抢占，调用方重新排队。 */
    suspend fun run(request: SystemTurnRequest, onStarted: () -> Unit): SystemTurnOutcome?
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
        var started = false
        var expiry: Job? = null
        var execution: Deferred<SystemTurnOutcome?>? = null
    }
    private val lock = Any()
    private val pending = linkedMapOf<String, Entry>()
    private val running = mutableMapOf<String, Entry>()
    private var worker: Job? = null

    override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
        val entry = Entry(request)
        synchronized(lock) {
            pending.put(request.sourceId, entry)?.outcome?.complete(
                SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED),
            )
            running[request.sourceId]?.takeIf { !it.started }?.let { older ->
                older.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
                older.execution?.cancel()
            }
            entry.expiry = applicationScope.launch {
                runtime.awaitReady()
                delay(busyTimeoutMillis)
                synchronized(lock) {
                    if (!entry.started && (pending[request.sourceId] === entry || running[request.sourceId] === entry)) {
                        if (pending[request.sourceId] === entry) pending.remove(request.sourceId)
                        entry.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT))
                        entry.execution?.cancel()
                    }
                }
            }
            entry.outcome.invokeOnCompletion { entry.expiry?.cancel() }
            if (worker?.isActive != true) {
                worker = applicationScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { drain() }
                worker?.start()
            }
        }
        try { return entry.outcome.await() }
        finally {
            synchronized(lock) {
                if (!entry.started && pending[request.sourceId] === entry) pending.remove(request.sourceId)
                if (!entry.started) {
                    entry.expiry?.cancel()
                    entry.outcome.cancel()
                    entry.execution?.cancel()
                }
            }
        }
    }

    /** 暂停或删除只撤回尚未开始的轮次，已发出的生成仍交付真实结果。 */
    fun withdrawPendingSource(sourceId: String) {
        synchronized(lock) {
            pending.remove(sourceId)?.outcome?.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
            running[sourceId]?.takeIf { !it.started }?.let {
                it.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
                it.execution?.cancel()
            }
        }
    }

    /** 系统执行窗口被撤回时，仅停止该来源已经开始的生成。普通调用方取消不走此入口。 */
    suspend fun stopSource(sourceId: String) {
        val entry = synchronized(lock) {
            pending.remove(sourceId)?.also {
                it.outcome.complete(SystemTurnOutcome.Failed(interruptedMessage))
            }
            running[sourceId]
        }
        entry?.execution?.cancelAndJoin()
    }

    private suspend fun drain() {
        try {
            runtime.awaitReady()
            while (true) {
                val entry = synchronized(lock) {
                    pending.values.firstOrNull().also { if (it == null) worker = null }
                } ?: return
                val request = entry.request
                val outcome = when {
                    !runtime.sessionExists(request.sessionId) -> SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING)
                    runtime.isBusy() -> { delay(pollMillis); continue }
                    else -> null
                }
                if (outcome != null) {
                    synchronized(lock) {
                        if (pending[request.sourceId] === entry) {
                            pending.remove(request.sourceId)
                            entry.outcome.complete(outcome)
                        }
                    }
                    continue
                }
                val admitted = synchronized(lock) {
                    if (pending[request.sourceId] !== entry) false else {
                        pending.remove(request.sourceId)
                        entry.execution = applicationScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                            if (!runtime.loadSession(request.sessionId)) {
                                SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING)
                            } else runtime.run(request) {
                                synchronized(lock) { entry.started = true; entry.expiry?.cancel() }
                            }
                        }
                        running[request.sourceId] = entry
                        true
                    }
                }
                if (!admitted) continue
                try {
                    run {
                        val result = checkNotNull(entry.execution).await()
                        if (result != null) entry.outcome.complete(result)
                        else synchronized(lock) {
                            if (entry.outcome.isCompleted) return@synchronized
                            if (pending.containsKey(request.sourceId)) {
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
                    synchronized(lock) { if (running[request.sourceId] === entry) running.remove(request.sourceId) }
                }
            }
        } finally {
            val thisJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            synchronized(lock) {
                // 正常退出时 worker 已置空；不能清除刚入队请求新建的 worker。
                if (worker === thisJob) {
                    pending.values.forEach { it.outcome.complete(SystemTurnOutcome.Skipped(SystemTurnSkipReason.NOT_READY)) }
                    pending.clear()
                    worker = null
                }
            }
        }
    }
}
