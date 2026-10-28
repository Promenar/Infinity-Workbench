package com.promenar.infinity.startup

import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class StartupDiagnosticSessionStrings(
    val title: (String) -> String,
    val draft: (String) -> String,
    val noIssues: String,
)

/** 只有专用工作区和草稿均已持久化的会话才能交给调用方发布到聊天界面。 */
class StartupDiagnosticSessionFactory(
    private val manager: StartupIssueManager,
    private val sessionRepository: ISessionRepository,
    private val workspaceRepository: IWorkspaceRepository,
    private val strings: StartupDiagnosticSessionStrings,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun createDiagnosticSession(issueIds: List<String>, agentId: String, modelId: String? = null): Session {
        require(agentId.isNotBlank()) { "诊断会话必须指定 Agent" }
        val selectedIds = issueIds.toSet()
        val issues = manager.issues.value.filter { selectedIds.isEmpty() || it.id in selectedIds }
        val title = strings.title(issues.firstOrNull()?.summary ?: strings.noIssues)
        // 问题 id 可直接用于处置工具；界面名称、物理路径与异常原文不进入草稿。
        val summary = issues.joinToString("\n") { "[${it.id}] ${it.step.name}: ${it.summary}" }
            .ifBlank { strings.noIssues }
        val draft = strings.draft(summary)
        val id = newSessionId().also { require(it.isNotBlank()) { "诊断会话标识不能为空" } }
        check(sessionRepository.getById(id) == null) { "诊断会话标识已被使用" }
        val now = clock()
        val prepared = Session(
            id = id,
            agentId = agentId,
            title = "",
            modelId = modelId,
            workspaceId = null,
            loopStatus = LoopStatus.PAUSED,
            createdAt = now,
            updatedAt = now,
        )
        var creationAttempted = false
        try {
            // 当前根目录契约依赖数据库中的会话；准备记录没有可发送草稿，也不写入 ChatStore。
            creationAttempted = true
            sessionRepository.create(prepared)
            val root = workspaceRepository.ensureSessionRoot(id)
            check(root.isDirectory && root.parentUuid == null && root.materializedPath == "/" &&
                root.uuid.isNotBlank() && root.uuid == root.workspaceRootUuid && root.physicalRootPath.isNotBlank()) {
                "诊断会话的专用工作区无效"
            }
            manager.requireWritable(rootId = root.uuid, sessionId = id, path = root.physicalRootPath)
            sessionRepository.updatePartial(id, mapOf(
                "title" to title,
                "draft" to draft,
                "workspaceId" to null,
                "workspacePath" to root.physicalRootPath,
                "workspaceRootUuid" to root.uuid,
                "loopStatus" to LoopStatus.IDLE,
                "updatedAt" to clock(),
            ))
            val saved = checkNotNull(sessionRepository.getById(id)) { "诊断会话保存失败" }
            check(saved.agentId == agentId && saved.modelId == modelId && saved.workspaceId == null &&
                saved.workspacePath == root.physicalRootPath && saved.workspaceRootUuid == root.uuid &&
                saved.title == title && saved.draft == draft && saved.messages.isEmpty() && saved.loopStatus == LoopStatus.IDLE) {
                "诊断会话未完整保存"
            }
            return saved
        } catch (failure: Throwable) {
            if (creationAttempted) withContext(NonCancellable) {
                try {
                    // 仅删除本工厂新建的数据库记录，根目录可能含用户文件，不能递归清理。
                    sessionRepository.delete(id)
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }
}
