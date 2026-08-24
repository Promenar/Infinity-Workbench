package com.promenar.nexara.data.session

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 删除会话期间的进程内强制写屏障。
 *
 * 写入口必须在完整副作用期间持有 admission，而不是只在入口瞬时检查。相同协程中对同一
 * session/root 的嵌套 admission 可重入，仅最外层计数；删除先原子关闭 admission，再等待
 * 已进入的写租约全部退出。
 */
class SessionExecutionGate {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val stateMutex = Mutex()
    private val deletingSessions = ConcurrentHashMap.newKeySet<String>()
    private val deletingWorkspaceRoots = ConcurrentHashMap<String, String>()
    private val activeSessionAdmissions = mutableMapOf<String, Int>()
    private val activeWorkspaceAdmissions = mutableMapOf<String, Int>()
    private val stateEpoch = MutableStateFlow(0L)

    suspend fun <T> withSessionAdmission(sessionId: String, block: suspend () -> T): T =
        withAdmission(setOf(requireIdentifier(sessionId, "sessionId")), emptySet(), block = block)

    /** 新业务入口即使嵌套在旧租约中，也必须重新服从删除 tombstone。 */
    suspend fun <T> withNewSessionAdmission(sessionId: String, block: suspend () -> T): T =
        withAdmission(
            sessions = setOf(requireIdentifier(sessionId, "sessionId")),
            roots = emptySet(),
            rejectNestedAfterDeletion = true,
            block = block,
        )

    suspend fun <T> withWorkspaceAdmission(workspaceRootUuid: String, block: suspend () -> T): T =
        withAdmission(emptySet(), setOf(requireIdentifier(workspaceRootUuid, "workspaceRootUuid")), block = block)

    suspend fun <T> withSessionWorkspaceAdmission(
        sessionId: String,
        workspaceRootUuid: String,
        block: suspend () -> T,
    ): T = withAdmission(
        setOf(requireIdentifier(sessionId, "sessionId")),
        setOf(requireIdentifier(workspaceRootUuid, "workspaceRootUuid")),
        block = block,
    )

    suspend fun <T> withDeletion(
        sessionId: String,
        workspaceRootUuid: String?,
        beforeAdmissionsDrained: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T {
        val checkedSessionId = requireIdentifier(sessionId, "sessionId")
        val checkedRoot = workspaceRootUuid?.takeIf { it.isNotBlank() }
        val existingAdmission = currentCoroutineContext()[AdmissionContext]
        check(existingAdmission?.gate !== this ||
            (checkedSessionId !in existingAdmission.sessions && checkedRoot !in existingAdmission.roots)) {
            "不能在同一会话或工作区的写租约内启动删除"
        }
        val mutex = sessionLocks.computeIfAbsent(checkedSessionId) { Mutex() }
        return mutex.withLock {
            stateMutex.withLock {
                checkedRoot?.let { root ->
                    val owner = deletingWorkspaceRoots[root]
                    check(owner == null || owner == checkedSessionId) { "工作区根正在由其他会话删除" }
                }
                check(deletingSessions.add(checkedSessionId)) { "会话删除状态重复" }
                checkedRoot?.let { deletingWorkspaceRoots[it] = checkedSessionId }
                advanceEpochLocked()
            }
            try {
                beforeAdmissionsDrained()
                awaitAdmissionsDrained(checkedSessionId, checkedRoot)
                block()
            } finally {
                withContext(NonCancellable) {
                    stateMutex.withLock {
                        deletingSessions.remove(checkedSessionId)
                        checkedRoot?.let { deletingWorkspaceRoots.remove(it, checkedSessionId) }
                        advanceEpochLocked()
                    }
                }
            }
        }
    }

    /** 仅保留给无副作用的快速前置检查；持久化入口必须使用 admission。 */
    fun requireSessionWritable(sessionId: String) {
        if (sessionId in deletingSessions) throw SessionDeletingException(sessionId)
    }

    /** 仅保留给无副作用的快速前置检查；持久化入口必须使用 admission。 */
    fun requireWorkspaceWritable(workspaceRootUuid: String) {
        deletingWorkspaceRoots[workspaceRootUuid]?.let { throw SessionDeletingException(it) }
    }

    fun isDeleting(sessionId: String): Boolean = sessionId in deletingSessions

    private suspend fun <T> withAdmission(
        sessions: Set<String>,
        roots: Set<String>,
        rejectNestedAfterDeletion: Boolean = false,
        block: suspend () -> T,
    ): T {
        val inherited = currentCoroutineContext()[AdmissionContext]?.takeIf { it.gate === this }
        val newSessions = sessions - inherited.orEmptySessions()
        val newRoots = roots - inherited.orEmptyRoots()
        if (newSessions.isEmpty() && newRoots.isEmpty() && !rejectNestedAfterDeletion) return block()

        stateMutex.withLock {
            sessions.firstOrNull { it in deletingSessions }?.let { throw SessionDeletingException(it) }
            roots.firstNotNullOfOrNull { deletingWorkspaceRoots[it] }
                ?.let { throw SessionDeletingException(it) }
            newSessions.forEach { activeSessionAdmissions[it] = activeSessionAdmissions.getOrDefault(it, 0) + 1 }
            newRoots.forEach { activeWorkspaceAdmissions[it] = activeWorkspaceAdmissions.getOrDefault(it, 0) + 1 }
            advanceEpochLocked()
        }
        val context = AdmissionContext(
            gate = this,
            sessions = inherited.orEmptySessions() + sessions,
            roots = inherited.orEmptyRoots() + roots,
        )
        return try {
            withContext(context) { block() }
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    newSessions.forEach { decrement(activeSessionAdmissions, it) }
                    newRoots.forEach { decrement(activeWorkspaceAdmissions, it) }
                    advanceEpochLocked()
                }
            }
        }
    }

    private suspend fun awaitAdmissionsDrained(sessionId: String, workspaceRootUuid: String?) {
        while (true) {
            val observedEpoch = stateMutex.withLock {
                if (activeSessionAdmissions.getOrDefault(sessionId, 0) == 0 &&
                    (workspaceRootUuid == null || activeWorkspaceAdmissions.getOrDefault(workspaceRootUuid, 0) == 0)
                ) return
                stateEpoch.value
            }
            stateEpoch.first { it != observedEpoch }
        }
    }

    private fun decrement(counts: MutableMap<String, Int>, key: String) {
        val remaining = checkNotNull(counts[key]) - 1
        check(remaining >= 0) { "写租约计数损坏" }
        if (remaining == 0) counts.remove(key) else counts[key] = remaining
    }

    private fun advanceEpochLocked() {
        stateEpoch.value = stateEpoch.value + 1
    }

    private fun requireIdentifier(value: String, name: String): String =
        value.also { require(it.isNotBlank()) { "$name 不能为空" } }

    private fun AdmissionContext?.orEmptySessions(): Set<String> = this?.sessions.orEmpty()
    private fun AdmissionContext?.orEmptyRoots(): Set<String> = this?.roots.orEmpty()

    private class AdmissionContext(
        val gate: SessionExecutionGate,
        val sessions: Set<String>,
        val roots: Set<String>,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<AdmissionContext>
    }
}

class SessionDeletingException(val sessionId: String) :
    IllegalStateException("会话正在删除，拒绝新的执行或持久化写入")
