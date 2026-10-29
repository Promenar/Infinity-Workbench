package com.promenar.infinity.share.core

import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 枚举显式落点；其它会话只列出，由用户选择。 */
class ShareImportTargetProvider(
    private val sessionDao: SessionDao,
    private val workspace: IWorkspaceRepository,
    private val ensureKnowledgeRoot: suspend () -> FileEntry,
) {
    private var linuxState: () -> LinuxRuntimeState? = { null }

    constructor(sessionDao: SessionDao, workspace: IWorkspaceRepository, ensureKnowledgeRoot: suspend () -> FileEntry,
        linuxRuntime: com.promenar.infinity.domain.linux.LinuxRuntime) : this(sessionDao, workspace, ensureKnowledgeRoot) {
        linuxState = { linuxRuntime.state.value }
    }

    suspend fun options(currentSessionId: String?, currentSessionTitle: String?): ShareImportOptions = withContext(Dispatchers.IO) {
        val session = currentSessionId?.takeIf { it.isNotBlank() && it != GLOBAL_KNOWLEDGE_SESSION_ID }
            ?.let { sessionDao.getById(it) }?.takeIf { available(it.worktreeDirectory) }
        ShareImportOptions(
            currentSession = session?.let { ShareSessionChoice(it.id, currentSessionTitle?.takeIf(String::isNotBlank) ?: it.title, it.updatedAt) },
            knowledgeRootUuid = ensureKnowledgeRoot().uuid,
            linux = when (linuxState()) {
                is LinuxRuntimeState.Ready -> ShareLinuxAvailability.Ready
                is LinuxRuntimeState.Unavailable, null -> ShareLinuxAvailability.Unsupported
                else -> ShareLinuxAvailability.NeedsInstall
            },
        )
    }

    suspend fun sessions(excludeSessionId: String?): List<ShareSessionChoice> = withContext(Dispatchers.IO) {
        sessionDao.getAll().filter { it.id.isNotBlank() && it.id != excludeSessionId && it.id != GLOBAL_KNOWLEDGE_SESSION_ID && available(it.worktreeDirectory) }
            .sortedWith(compareByDescending<com.promenar.infinity.data.local.db.entity.SessionEntity> { it.updatedAt }.thenBy { it.id })
            .map { ShareSessionChoice(it.id, it.title, it.updatedAt) }
    }

    suspend fun knowledgeFolders(parentUuid: String): List<ShareKnowledgeFolder> = withContext(Dispatchers.IO) {
        val root = ensureKnowledgeRoot()
        val parent = if (parentUuid == root.uuid) root else workspace.getByUuid(root.uuid, parentUuid) ?: return@withContext emptyList()
        if (!validFolder(parent, root.uuid)) return@withContext emptyList()
        workspace.observeChildren(root.uuid, parentUuid).first().filter {
            it.parentUuid == parentUuid && validFolder(it, root.uuid)
        }.sortedWith(compareBy<FileEntry> { it.name.lowercase() }.thenBy { it.name })
            .map { ShareKnowledgeFolder(it.uuid, it.name, it.materializedPath) }
    }

    suspend fun sessionDestination(sessionId: String, current: Boolean): ShareImportDestination.SessionFolder? = withContext(Dispatchers.IO) {
        if (sessionId.isBlank() || sessionId == GLOBAL_KNOWLEDGE_SESSION_ID) return@withContext null
        val session = sessionDao.getById(sessionId)?.takeIf { available(it.worktreeDirectory) } ?: return@withContext null
        val root = workspace.ensureSessionRoot(session.id)
        ShareImportDestination.SessionFolder(session.id, root.uuid, current)
    }

    private fun available(worktreeDirectory: String?): Boolean = worktreeDirectory == null || java.io.File(worktreeDirectory).isDirectory

    private fun validFolder(entry: FileEntry, root: String): Boolean = entry.isDirectory && !entry.inRecycleBin &&
        (entry.workspaceRootUuid.isBlank() || entry.workspaceRootUuid == root) && entry.materializedPath.startsWith('/') &&
        entry.materializedPath.split('/').filter(String::isNotEmpty).none { it == "." || it == ".." || WorkspaceInternalPaths.isReservedName(it) }

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
        const val GLOBAL_KNOWLEDGE_SESSION_ID = "__infinity_rag_workspace__"
    }
}
