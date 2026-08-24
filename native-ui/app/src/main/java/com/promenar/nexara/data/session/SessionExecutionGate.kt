package com.promenar.nexara.data.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 删除会话期间的进程内强制写屏障。所有后台入口必须主动检查，不能依赖界面状态。 */
class SessionExecutionGate {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val deletingSessions = ConcurrentHashMap.newKeySet<String>()
    private val deletingWorkspaceRoots = ConcurrentHashMap<String, String>()

    suspend fun <T> withDeletion(
        sessionId: String,
        workspaceRootUuid: String?,
        block: suspend () -> T,
    ): T {
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        val mutex = sessionLocks.computeIfAbsent(sessionId) { Mutex() }
        return mutex.withLock {
            deletingSessions.add(sessionId)
            workspaceRootUuid?.takeIf { it.isNotBlank() }?.let { root ->
                val owner = deletingWorkspaceRoots.putIfAbsent(root, sessionId)
                check(owner == null || owner == sessionId) { "工作区根正在由其他会话删除" }
            }
            try {
                block()
            } finally {
                deletingSessions.remove(sessionId)
                workspaceRootUuid?.takeIf { it.isNotBlank() }?.let { root ->
                    deletingWorkspaceRoots.remove(root, sessionId)
                }
            }
        }
    }

    fun requireSessionWritable(sessionId: String) {
        if (sessionId in deletingSessions) throw SessionDeletingException(sessionId)
    }

    fun requireWorkspaceWritable(workspaceRootUuid: String) {
        deletingWorkspaceRoots[workspaceRootUuid]?.let { throw SessionDeletingException(it) }
    }

    fun isDeleting(sessionId: String): Boolean = sessionId in deletingSessions
}

class SessionDeletingException(val sessionId: String) :
    IllegalStateException("会话正在删除，拒绝新的执行或持久化写入")
