package com.promenar.infinity.data.workspace

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 界面可提供发送准备中的即时状态，释放界面时关闭注册以免持有 ViewModel。 */
class DirectoryWorkspaceBusyChecks {
    private val checks = ConcurrentHashMap<String, (String) -> Boolean>()

    fun register(check: (String) -> Boolean): AutoCloseable {
        val id = UUID.randomUUID().toString()
        checks[id] = check
        return AutoCloseable { checks.remove(id) }
    }

    fun isBusy(sessionId: String): Boolean = checks.values.any { it(sessionId) }
}
