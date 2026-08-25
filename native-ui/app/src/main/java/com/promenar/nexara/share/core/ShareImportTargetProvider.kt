package com.promenar.nexara.share.core

import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository

/** 只暴露当前聊天工作区与全局知识库，禁止静默选择其它会话。 */
class ShareImportTargetProvider(
    private val sessionDao: SessionDao,
    private val workspace: IWorkspaceRepository,
    private val ensureKnowledgeRoot: suspend () -> FileEntry,
) {
    suspend fun load(
        currentSessionId: String?,
        currentSessionTitle: String?,
        knowledgeBaseLabel: String,
    ): List<ShareImportTarget> {
        val targets = mutableListOf<ShareImportTarget>()
        currentSessionId?.takeIf { it.isNotBlank() && it != GLOBAL_KNOWLEDGE_SESSION_ID }?.let { sessionId ->
            sessionDao.getById(sessionId)?.let { session ->
                val root = workspace.ensureSessionRoot(sessionId)
                targets += ShareImportTarget(
                    workspaceRootUuid = root.uuid,
                    label = currentSessionTitle?.takeIf { it.isNotBlank() } ?: session.title,
                    kind = ShareTargetKind.CurrentSession,
                )
            }
        }

        val knowledgeRoot = ensureKnowledgeRoot()
        targets += ShareImportTarget(
            workspaceRootUuid = knowledgeRoot.uuid,
            label = knowledgeBaseLabel,
            kind = ShareTargetKind.KnowledgeBase,
        )
        return targets
    }

    companion object {
        const val GLOBAL_KNOWLEDGE_SESSION_ID = "__nexara_rag_workspace__"
    }
}
