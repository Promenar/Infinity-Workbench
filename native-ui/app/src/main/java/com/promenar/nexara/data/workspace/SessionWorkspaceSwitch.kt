package com.promenar.nexara.data.workspace

import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import kotlinx.coroutines.CancellationException

/** 切换提示中的一端：私有工作区、已登记目录，或登记已被删除的目录。 */
sealed interface WorkspaceLabel {
    data object Private : WorkspaceLabel
    data class Registered(val name: String, val kind: String, val location: String) : WorkspaceLabel
    data class Missing(val workspaceId: String) : WorkspaceLabel

    companion object {
        fun of(workspaceId: String?, workspace: WorkspaceEntity?): WorkspaceLabel = when {
            workspaceId == null -> Private
            workspace == null -> Missing(workspaceId)
            else -> Registered(workspace.displayName, workspace.kind, workspace.location)
        }
    }
}

sealed interface WorkspaceSwitchResult {
    data class Switched(val sessionId: String, val from: WorkspaceLabel, val to: WorkspaceLabel) : WorkspaceSwitchResult
    data object Unchanged : WorkspaceSwitchResult
    /** 生成进行中或有待审批的工具调用：执行目标已按旧工作区解析，切换会让后续写入落到另一目录。 */
    data object RejectedBusy : WorkspaceSwitchResult
    data class Failed(val error: Throwable) : WorkspaceSwitchResult
}

/**
 * 会话切换工作区的统一入口：生成中拒绝；写入 `workspaceId`（null 为会话私有工作区）后返回切换前后的标签，
 * 供界面给出“从哪里切换到哪里”的提示。不移动、不复制、不删除任何目录内容。
 */
suspend fun switchSessionWorkspace(
    sessionId: String,
    currentWorkspaceId: String?,
    targetWorkspaceId: String?,
    busy: Boolean,
    persist: suspend (String?) -> Unit,
    lookup: suspend (String) -> WorkspaceEntity?,
): WorkspaceSwitchResult {
    if (busy) return WorkspaceSwitchResult.RejectedBusy
    if (currentWorkspaceId == targetWorkspaceId) return WorkspaceSwitchResult.Unchanged
    return try {
        val from = WorkspaceLabel.of(currentWorkspaceId, currentWorkspaceId?.let { lookup(it) })
        val to = WorkspaceLabel.of(targetWorkspaceId, targetWorkspaceId?.let { lookup(it) })
        if (to is WorkspaceLabel.Missing) return WorkspaceSwitchResult.Failed(IllegalStateException("工作区登记已删除"))
        persist(targetWorkspaceId)
        WorkspaceSwitchResult.Switched(sessionId, from, to)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        WorkspaceSwitchResult.Failed(failure)
    }
}
