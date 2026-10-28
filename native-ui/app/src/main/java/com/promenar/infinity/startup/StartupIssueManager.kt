package com.promenar.infinity.startup

import com.promenar.infinity.startup.diagnostics.AppDiagnostics
import com.promenar.infinity.startup.diagnostics.DiagnosticRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 全局关键步骤不能通过局部隔离放行 writer。 */
enum class StartupStep(val critical: Boolean = false) {
    DUAL_DATABASE(true), BACKUP_RESTORE(true), WRITER_INITIALIZATION(true),
    WORKSPACE_INVENTORY, LEGACY_ADOPTION, WORKSPACE_JOURNAL, SESSION_JOURNAL, TOMBSTONES,
    TOOL_LEDGER, VECTOR_QUEUE, BACKGROUND_JOBS, HEADLESS_INTERACTIONS, SCHEDULED_TASKS,
    MODEL_CATALOG, MEMORY_CLEANUP,
}
enum class StartupScopeKind { GLOBAL, WORKSPACE_ROOT, SESSION, SUBSYSTEM }
enum class StartupSeverity { BLOCKING, ISOLATED }
enum class StartupSubsystem { WORKSPACE_RECOVERY, TOOL_LEDGER, VECTOR_QUEUE, BACKGROUND_JOBS, HEADLESS_INTERACTIONS, SCHEDULED_TASKS, MODEL_CATALOG, MEMORY_CLEANUP }
enum class StartupIssueAction { RETRY, ABANDON }

data class StartupIssueScope(val kind: StartupScopeKind, val id: String, val displayName: String) {
    companion object {
        fun subsystem(subsystem: StartupSubsystem) = StartupIssueScope(StartupScopeKind.SUBSYSTEM, subsystem.name, subsystem.name)
        val Global = StartupIssueScope(StartupScopeKind.GLOBAL, "application", "应用")
    }
}
data class StartupIssue(
    val id: String,
    val step: StartupStep,
    val severity: StartupSeverity,
    val scope: StartupIssueScope,
    val summary: String,
    val actions: Set<StartupIssueAction>,
    val occurredAt: Long,
) { val type: StartupStep get() = step }
sealed interface StartupIssueResolution {
    data object Resolved : StartupIssueResolution
    data object ConfirmationRequired : StartupIssueResolution
    data object NotFound : StartupIssueResolution
    data object UnsupportedAction : StartupIssueResolution
    data class Failed(val summary: String) : StartupIssueResolution
}
class StartupIsolationException(val issue: StartupIssue, message: String) : IllegalStateException(message)
class StartupRecoveryStepException(val step: StartupStep, cause: Exception) : IllegalStateException(cause)
class StartupCriticalRecoveryException(val issue: StartupIssue, cause: Exception) : IllegalStateException(issue.summary, cause)

/** 只保存当前进程问题；每个动作保持原范围关闭，成功后才原子解除对应问题。 */
class StartupIssueManager(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Recovery(val retry: suspend () -> Unit, val abandon: (suspend () -> Unit)?)
    private val monitor = Any()
    private val actionMutex = Mutex()
    private val recoveries = mutableMapOf<String, Recovery>()
    private val _issues = MutableStateFlow<List<StartupIssue>>(emptyList())
    val issues: StateFlow<List<StartupIssue>> = _issues.asStateFlow()
    private val sessionRoots = ConcurrentHashMap<String, Set<String>>()
    private val rootPaths = ConcurrentHashMap<String, String>()
    var scopeDisplayName: (StartupIssueScope) -> String = { it.displayName }
    var onResolved: suspend (StartupIssue) -> Unit = {}
    var writeBlockedMessage: (StartupIssue) -> String = { issue ->
        when (issue.scope.kind) {
            StartupScopeKind.SESSION -> "该会话因启动恢复冲突已隔离，可查看内容；请先处理启动问题再删除或执行写入工具。"
            StartupScopeKind.WORKSPACE_ROOT -> "该工作区因启动恢复冲突已隔离，只能读取；请先处理启动问题再写入。"
            else -> "该功能因启动恢复失败已停用，请先处理启动问题。"
        }
    }

    fun bindSessionRoot(sessionId: String, rootId: String) { sessionRoots.compute(sessionId) { _, roots -> roots.orEmpty() + rootId } }
    fun bindRoot(rootId: String, canonicalPath: String, sessionId: String? = null) {
        rootPaths[rootId] = canonicalPath
        sessionId?.let { bindSessionRoot(it, rootId) }
    }

    suspend fun recover(
        step: StartupStep,
        scope: StartupIssueScope,
        abandon: (suspend () -> Unit)? = null,
        retry: suspend () -> Unit,
    ): Boolean {
        val id = "${step.name}:${scope.kind.name}:${DiagnosticRedactor.safeIdentifier(scope.id)}"
        synchronized(monitor) { recoveries[id] = Recovery(retry, abandon) }
        try {
            StartupWriteBarrier.forRecovery(id) { retry() }
            synchronized(monitor) {
                _issues.value = _issues.value.filterNot { it.id == id }
                recoveries.remove(id)
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val issue = publish(id, step, scope, abandon, error)
            if (step.critical) throw StartupCriticalRecoveryException(issue, error)
            return false
        }
    }

    private fun publish(id: String, step: StartupStep, scope: StartupIssueScope, abandon: (suspend () -> Unit)?, error: Exception): StartupIssue {
        val actualStep = (error as? StartupRecoveryStepException)?.step ?: step
        val actualError = (error as? StartupRecoveryStepException)?.cause ?: error
        val issue = StartupIssue(id, actualStep, if (actualStep.critical) StartupSeverity.BLOCKING else StartupSeverity.ISOLATED,
            scope.copy(displayName = scopeDisplayName(scope)), DiagnosticRedactor.summary(actualError),
            if (abandon == null || actualStep !in setOf(StartupStep.WORKSPACE_JOURNAL, StartupStep.SESSION_JOURNAL)) setOf(StartupIssueAction.RETRY) else setOf(StartupIssueAction.RETRY, StartupIssueAction.ABANDON), clock())
        synchronized(monitor) { _issues.value = _issues.value.filterNot { it.id == id } + issue }
        AppDiagnostics.recordStartupIssue(actualStep.name, scope.kind.name, scope.id, actualError)
        return issue
    }

    suspend fun resolve(id: String, action: StartupIssueAction, confirmed: Boolean = false): StartupIssueResolution = actionMutex.withLock {
        val (issue, recovery) = synchronized(monitor) {
            val issue = _issues.value.find { it.id == id } ?: return@withLock StartupIssueResolution.NotFound
            issue to (recoveries[id] ?: return@withLock StartupIssueResolution.UnsupportedAction)
        }
        if (action !in issue.actions) return@withLock StartupIssueResolution.UnsupportedAction
        if (action == StartupIssueAction.ABANDON && !confirmed) return@withLock StartupIssueResolution.ConfirmationRequired
        try {
            StartupWriteBarrier.forRecovery(id) {
                when (action) {
                    StartupIssueAction.RETRY -> recovery.retry()
                    StartupIssueAction.ABANDON -> checkNotNull(recovery.abandon).invoke()
                }
            }
            // 重启全局恢复前先解除已修复的问题；保留句柄到回调成功，回调失败仍可重试。
            synchronized(monitor) { _issues.value = _issues.value.filterNot { it.id == id } }
            onResolved(issue)
            synchronized(monitor) { recoveries.remove(id) }
            StartupIssueResolution.Resolved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(monitor) { recoveries[id] = recovery }
            publish(id, issue.step, issue.scope, recovery.abandon, error)
            StartupIssueResolution.Failed(DiagnosticRedactor.summary(error))
        }
    }

    fun isRootIsolated(rootId: String): Boolean = issues.value.any {
        it.scope.kind == StartupScopeKind.WORKSPACE_ROOT && (it.scope.id == rootId || rootPaths[rootId] != null && rootPaths[rootId] == rootPaths[it.scope.id]) ||
            it.scope.kind == StartupScopeKind.SESSION && rootId in sessionRoots[it.scope.id].orEmpty()
    }
    fun isSessionIsolated(sessionId: String): Boolean = issues.value.any {
        it.scope.kind == StartupScopeKind.SESSION && it.scope.id == sessionId
    } || sessionRoots[sessionId].orEmpty().any(::isRootIsolated)
    fun isSubsystemIsolated(subsystem: StartupSubsystem): Boolean = issues.value.any {
        it.scope.kind == StartupScopeKind.SUBSYSTEM && it.scope.id == subsystem.name
    }

    fun requireWritable(rootId: String? = null, sessionId: String? = null, path: String? = null, subsystem: StartupSubsystem? = null) =
        checkWritable(rootId, sessionId, path, subsystem, emptySet())

    internal fun checkWritable(rootId: String?, sessionId: String?, path: String?, subsystem: StartupSubsystem?, ignoredIssues: Set<String>) {
        val effectiveRoots = setOfNotNull(rootId) + sessionId?.let { sessionRoots[it] }.orEmpty()
        val effectivePaths = listOfNotNull(path) + effectiveRoots.mapNotNull(rootPaths::get)
        fun matchesPath(root: String): Boolean = rootPaths[root]?.let { base -> effectivePaths.any { requested ->
            java.nio.file.Paths.get(requested).normalize().startsWith(java.nio.file.Paths.get(base).normalize())
        } } == true
        val issue = issues.value.firstOrNull { issue ->
            issue.id !in ignoredIssues && when (issue.scope.kind) {
                StartupScopeKind.GLOBAL -> true
                StartupScopeKind.SESSION -> issue.scope.id == sessionId || sessionRoots[issue.scope.id].orEmpty().any {
                    it in effectiveRoots || matchesPath(it)
                }
                StartupScopeKind.WORKSPACE_ROOT -> issue.scope.id in effectiveRoots || matchesPath(issue.scope.id)
                StartupScopeKind.SUBSYSTEM -> issue.scope.id == subsystem?.name ||
                    issue.scope.id == StartupSubsystem.WORKSPACE_RECOVERY.name && (rootId != null || sessionId != null || path != null)
            }
        }
        if (issue != null) throw StartupIsolationException(issue, writeBlockedMessage(issue))
    }

    /** 导出格式只包含枚举、脱敏标识和固定摘要，不含界面名称或物理路径。 */
    fun formatIssues(): String = issues.value.joinToString("\n") { issue ->
        "issue=${issue.id} type=${issue.step.name} severity=${issue.severity.name} " +
            "scope=${DiagnosticRedactor.safeIdentifier(issue.scope.id)} step=${issue.step.name} summary=${issue.summary} " +
            "actions=${issue.actions.joinToString(",") { it.name }} occurredAt=${issue.occurredAt}"
    }
}
