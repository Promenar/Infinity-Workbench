package com.promenar.infinity.startup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 恢复租约只豁免当前处置的问题，其它范围和其它步骤的隔离保持生效。 */
object StartupWriteBarrier {
    @Volatile private var manager: StartupIssueManager? = null
    fun install(manager: StartupIssueManager) { this.manager = manager }
    internal fun resetForTesting() { manager = null }
    fun isReadOnly(rootId: String? = null, sessionId: String? = null, path: String? = null): Boolean =
        try { manager?.requireWritable(rootId, sessionId, path); false } catch (_: StartupIsolationException) { true }

    fun isSubsystemIsolated(subsystem: StartupSubsystem): Boolean = manager?.isSubsystemIsolated(subsystem) == true
    suspend fun requireWritable(rootId: String? = null, sessionId: String? = null, path: String? = null, subsystem: StartupSubsystem? = null) {
        manager?.checkWritable(rootId, sessionId, path, subsystem, currentCoroutineContext()[RecoveryContext]?.issues.orEmpty())
    }
    internal suspend fun <T> forRecovery(issueId: String, block: suspend () -> T): T =
        withContext(RecoveryContext(currentCoroutineContext()[RecoveryContext]?.issues.orEmpty() + issueId)) { block() }
    private class RecoveryContext(val issues: Set<String>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RecoveryContext>
    }
}
