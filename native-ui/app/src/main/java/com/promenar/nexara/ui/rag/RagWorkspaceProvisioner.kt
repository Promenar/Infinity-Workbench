package com.promenar.nexara.ui.rag

import androidx.room.withTransaction
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

data class RagWorkspaceSource(
    val sessionId: String,
    val workspaceRootUuid: String,
    val title: String,
)

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
            ensureDefaultRootLocked()
        }
    }

    /**
     * 列出可浏览的受控知识库来源。每一个候选都必须再次由仓库校验 Session 与物理根的
     * 绑定关系，不能仅凭 sessions 表中的 UUID 把来源交给 UI。
     */
    suspend fun listAvailableRoots(): List<RagWorkspaceSource> = withContext(ioContext) {
        provisionMutex.withLock {
            ensureDefaultRootLocked()
            val sessions = database.sessionDao().getAll()
                .asSequence()
                .filter { session ->
                    session.agentId == SYSTEM_AGENT_ID &&
                        !session.workspaceRootUuid.isNullOrBlank()
                }
                .sortedWith(
                    compareBy<SessionEntity>({ it.id != SESSION_ID }, { it.title }, { it.id }),
                )
            val sources = mutableListOf<RagWorkspaceSource>()
            sessions.forEach { session ->
                    val expectedRootUuid = requireNotNull(session.workspaceRootUuid).trim()
                    val root = workspaceRepository.ensureSessionRoot(session.id)
                    check(root.uuid == expectedRootUuid) {
                        "知识库 Session 根身份不一致: ${session.id}"
                    }
                    sources += RagWorkspaceSource(
                        sessionId = session.id,
                        workspaceRootUuid = root.uuid,
                        title = session.title,
                    )
                }
            sources
        }
    }

    /**
     * 验证并返回指定的受控来源。调用者只能选择 listAvailableRoots 返回的 Session 形态：
     * 系统 Agent 且已经声明 workspaceRootUuid，随后仍由仓库执行最终身份校验。
     */
    suspend fun ensureRoot(sessionId: String): FileEntry = withContext(ioContext) {
        require(sessionId.isNotBlank()) { "知识库 Session ID 不能为空" }
        provisionMutex.withLock {
            val session = database.sessionDao().getById(sessionId)
                ?: throw IllegalArgumentException("知识库 Session 不存在: $sessionId")
            require(session.agentId == SYSTEM_AGENT_ID) { "知识库来源必须属于系统 Agent" }
            val expectedRootUuid = session.workspaceRootUuid?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("知识库 Session 没有受控根: $sessionId")
            val root = workspaceRepository.ensureSessionRoot(sessionId)
            check(root.uuid == expectedRootUuid) {
                "知识库 Session 根身份不一致: $sessionId"
            }
            root
        }
    }

    private suspend fun ensureDefaultRootLocked(): FileEntry {
        val legacyRoot = File(filesDir, LEGACY_DIRECTORY_NAME)
        val sessionDao = database.sessionDao()
        database.withTransaction {
            val current = sessionDao.getById(SESSION_ID)
            when {
                current == null -> {
                    val legacyPath = existingLegacyPath(legacyRoot)
                    val migratedRootUuid = legacyPath?.let { path -> resolveMigratedRootUuid(path) }
                    val timestamp = now()
                    sessionDao.insert(
                        SessionEntity(
                            id = SESSION_ID,
                            agentId = SYSTEM_AGENT_ID,
                            title = SESSION_TITLE,
                            workspacePath = legacyPath,
                            workspaceRootUuid = migratedRootUuid,
                            createdAt = timestamp,
                            updatedAt = timestamp,
                        ),
                    )
                }

                current.agentId != SYSTEM_AGENT_ID -> {
                    throw SecurityException("RAG 默认 Session 必须属于系统 Agent")
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
        }
        return workspaceRepository.ensureSessionRoot(SESSION_ID)
    }

    private suspend fun resolveMigratedRootUuid(canonicalLegacyPath: String): String? {
        val legacyRoot = File(canonicalLegacyPath).canonicalFile
        val candidates = database.fileEntryDao()
            .getStructurallyValidWorkspaceRootCandidates()
            .filter { candidate ->
                val declared = File(candidate.physicalRootPath.trim()).absoluteFile.toPath().normalize()
                val matches = declared.toFile().canonicalFile == legacyRoot
                if (matches && Files.isSymbolicLink(declared)) {
                    throw SecurityException("旧知识库物理根不能是符号链接")
                }
                matches
            }
        if (candidates.size > 1) {
            throw SecurityException("旧知识库物理根存在多个候选记录")
        }
        return candidates.singleOrNull()?.also { candidate ->
            if (database.fileEntryDao().countOtherSessionsForRoot(candidate.uuid, SESSION_ID) != 0) {
                throw SecurityException("旧知识库物理根已被其他 Session 认领")
            }
        }?.uuid
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
        internal const val SYSTEM_AGENT_ID = "__system__"
        private const val SESSION_TITLE = "RAG Workspace"
        private const val LEGACY_DIRECTORY_NAME = "rag_workspace"
        private val provisionMutex = Mutex()
    }
}
