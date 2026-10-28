package com.promenar.infinity.data.generation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 同会话回合串行，不同会话并行；旧的无会话入口仍与全部会话排他。 */
class GenerationTurnAdmission {
    private val stateLock = Any()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private var activeSessions = 0
    private var globalActive = false
    private var waitingGlobal = 0
    private var changed = CompletableDeferred<Unit>()

    private class Lease(val owner: GenerationTurnAdmission, val sessionId: String?) : AbstractCoroutineContextElement(Key) {
        var job: Job? = null
        companion object Key : CoroutineContext.Key<Lease>
    }

    private suspend fun ownLease(): Lease? {
        val context = currentCoroutineContext()
        return context[Lease]?.takeIf { it.owner === this && it.job === context[Job] }
    }

    suspend fun <T> withLock(block: suspend () -> T): T {
        val inherited = ownLease()
        if (inherited != null) {
            check(inherited.sessionId == null) { "会话回合不能嵌套全局回合" }
            return block()
        }
        enterGlobal()
        return runWithLease(null, ::leaveGlobal, block)
    }

    suspend fun <T> withLock(sessionId: String, block: suspend () -> T): T {
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        val inherited = ownLease()
        if (inherited != null) {
            check(inherited.sessionId == null || inherited.sessionId == sessionId) { "不能嵌套其它会话的回合" }
            return block()
        }
        enterSession()
        val mutex = sessionLocks.getOrPut(sessionId) { Mutex() }
        try {
            mutex.lock()
        } catch (failure: Throwable) {
            leaveSession()
            throw failure
        }
        return runWithLease(sessionId, { mutex.unlock(); leaveSession() }, block)
    }

    private suspend fun <T> runWithLease(sessionId: String?, release: () -> Unit, block: suspend () -> T): T {
        var cancellation: CancellationException? = null
        var released = false
        val lease = Lease(this, sessionId)
        try {
            return withContext(lease) {
                lease.job = currentCoroutineContext()[Job]
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    cancellation = cancelled
                    throw cancelled
                } finally {
                    // 只允许持有者 Job 顺序重入；子协程等待锁时，父体必须先释放再等待子任务结束。
                    released = true
                    release()
                }
            }
        } catch (cancelled: CancellationException) {
            // 上下文退出可能返回父 Job 的取消异常；保留业务补偿附加的失败证据。
            throw cancellation ?: cancelled
        } finally {
            // 进入上下文之前被取消时，也必须归还已经取得的租约。
            if (!released) release()
        }
    }

    private suspend fun enterSession() {
        while (true) {
            val signal = synchronized(stateLock) {
                if (!globalActive && waitingGlobal == 0) {
                    activeSessions += 1
                    null
                } else changed
            }
            if (signal == null) return
            signal.await()
        }
    }

    private fun leaveSession() {
        val signal = synchronized(stateLock) {
            activeSessions -= 1
            check(activeSessions >= 0) { "会话回合计数损坏" }
            if (activeSessions == 0) advanceLocked() else null
        }
        signal?.complete(Unit)
    }

    private suspend fun enterGlobal() {
        synchronized(stateLock) { waitingGlobal += 1 }
        var acquired = false
        try {
            while (true) {
                val signal = synchronized(stateLock) {
                    if (!globalActive && activeSessions == 0) {
                        globalActive = true
                        waitingGlobal -= 1
                        acquired = true
                        null
                    } else changed
                }
                if (signal == null) return
                signal.await()
            }
        } finally {
            if (!acquired) {
                val signal = synchronized(stateLock) {
                    waitingGlobal -= 1
                    advanceLocked()
                }
                signal.complete(Unit)
            }
        }
    }

    private fun leaveGlobal() {
        val signal = synchronized(stateLock) {
            globalActive = false
            advanceLocked()
        }
        signal.complete(Unit)
    }

    private fun advanceLocked(): CompletableDeferred<Unit> = changed.also { changed = CompletableDeferred() }
}
