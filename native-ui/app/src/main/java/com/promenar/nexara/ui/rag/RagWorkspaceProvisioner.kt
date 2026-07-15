package com.promenar.nexara.ui.rag

import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 为全局知识库建立系统 Session，并把新装根交给 WorkspaceRepository 的统一受信父目录。
 *
 * 旧版可能已经在 filesDir/rag_workspace 中保存用户资料。该目录存在时继续原位认领，
 * 避免未经验证的跨目录移动造成数据丢失；仅修复“Session 已写入旧路径但目录从未创建”的
 * 失败残留，让它重新走统一默认父目录。
 */
internal class RagWorkspaceProvisioner(
    private val filesDir: File,
    private val database: NexaraDatabase,
    private val workspaceRepository: IWorkspaceRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    suspend fun ensureRoot(): FileEntry = withContext(ioContext) {
        provisionMutex.withLock {
            val legacyRoot = File(filesDir, LEGACY_DIRECTORY_NAME)
            val sessionDao = database.sessionDao()
            val current = sessionDao.getById(SESSION_ID)
            when {
                current == null -> {
                    val legacyPath = existingLegacyPath(legacyRoot)
                    val timestamp = now()
                    sessionDao.insert(
                        SessionEntity(
                            id = SESSION_ID,
                            agentId = SYSTEM_AGENT_ID,
                            title = SESSION_TITLE,
                            workspacePath = legacyPath,
                            createdAt = timestamp,
                            updatedAt = timestamp,
                        ),
                    )
                }

                current.isUnclaimedMissingLegacy(legacyRoot) -> {
                    sessionDao.update(
                        current.copy(
                            workspacePath = null,
                            updatedAt = now(),
                        ),
                    )
                }
            }
            workspaceRepository.ensureSessionRoot(SESSION_ID)
        }
    }

    private fun existingLegacyPath(legacyRoot: File): String? {
        if (!legacyRoot.exists()) return null
        if (Files.isSymbolicLink(legacyRoot.toPath())) {
            throw SecurityException("旧知识库根不能是符号链接")
        }
        if (!legacyRoot.isDirectory) {
            throw SecurityException("旧知识库根不是目录")
        }
        return legacyRoot.canonicalPath
    }

    private fun SessionEntity.isUnclaimedMissingLegacy(legacyRoot: File): Boolean {
        if (workspaceRootUuid != null || legacyRoot.exists()) return false
        val declared = workspacePath?.takeIf { it.isNotBlank() } ?: return false
        return File(declared).canonicalFile == legacyRoot.canonicalFile
    }

    companion object {
        internal const val SESSION_ID = "__nexara_rag_workspace__"
        private const val SYSTEM_AGENT_ID = "__system__"
        private const val SESSION_TITLE = "RAG Workspace"
        private const val LEGACY_DIRECTORY_NAME = "rag_workspace"
        private val provisionMutex = Mutex()
    }
}
