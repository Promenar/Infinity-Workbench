package com.promenar.nexara.ui.chat.manager

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class ContextMutationEntry {
    val mutex = Mutex()
    var users = 0
}

private val contextMutations = mutableMapOf<String, ContextMutationEntry>()

/** 同一会话的摘要与删除操作覆盖数据库提交和界面状态发布两个阶段。 */
internal suspend fun <T> withContextMutation(sessionId: String, block: suspend () -> T): T {
    val entry = synchronized(contextMutations) {
        contextMutations.getOrPut(sessionId) { ContextMutationEntry() }.also { it.users++ }
    }
    return try {
        entry.mutex.withLock { block() }
    } finally {
        synchronized(contextMutations) {
            entry.users--
            if (entry.users == 0) contextMutations.remove(sessionId)
        }
    }
}
