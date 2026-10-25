package com.promenar.nexara.startup

import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.repository.LegacyWorkspaceRootAdoptionCoordinator
import com.promenar.nexara.data.repository.WorkspaceFileMutationRecoveryCoordinator
import com.promenar.nexara.data.repository.WorkspaceMutationRecoveryCoordinator
import com.promenar.nexara.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 恢复依赖在同一范围内完整闭合；失败后重试仍执行该范围的全部检查。 */
internal class StartupWorkspaceRecovery(
    private val database: NexaraDatabase,
    private val manager: StartupIssueManager,
    private val fileJournal: WorkspaceFileMutationRecoveryCoordinator,
    private val sessionJournal: WorkspaceMutationRecoveryCoordinator,
    private val legacy: LegacyWorkspaceRootAdoptionCoordinator,
    private val tombstones: WorkspaceRepository,
) {
    suspend fun recover() = withContext(Dispatchers.IO) {
        manager.recover(StartupStep.WORKSPACE_INVENTORY, StartupIssueScope.subsystem(StartupSubsystem.WORKSPACE_RECOVERY)) {
            val roots = database.fileEntryDao().getAllWorkspaceRootsForMaintenance()
            val sessions = database.sessionDao().getAll()
            roots.forEach { root -> manager.bindRoot(root.uuid, File(root.physicalRootPath).canonicalPath) }
            sessions.forEach { session -> session.workspaceRootUuid?.let { manager.bindSessionRoot(session.id, it) } }
            val pending = database.workspaceMutationDao().getUnfinished()
            val sessionRows = pending.mapNotNull { row -> sessionJournal.sessionIdOrNull(row)?.let { it to row } }.groupBy({ it.first }, { it.second })
            for ((sessionId, rows) in sessionRows) {
                val rootIds = (rows.map { it.workspaceRootUuid } + sessions.filter { it.id == sessionId }.mapNotNull { it.workspaceRootUuid }).distinct()
                rootIds.forEach { manager.bindSessionRoot(sessionId, it) }
                val scope = StartupIssueScope(StartupScopeKind.SESSION, sessionId, sessions.firstOrNull { it.id == sessionId }?.title?.takeIf(String::isNotBlank) ?: "会话")
                manager.recover(StartupStep.SESSION_JOURNAL, scope,
                    abandon = {
                        rootIds.forEach { fileJournal.adoptLegacyRootIdentityOrThrow(it) }
                        sessionJournal.abandonSessionOrThrow(sessionId)
                        rootIds.forEach { recoverRoot(it) }
                    },
                ) {
                    // 删除 journal 按根认领核验目录；旧格式认领须先迁移，否则任何根都无法通过核验。
                    rootIds.forEach { fileJournal.adoptLegacyRootIdentityOrThrow(it) }
                    sessionJournal.recoverSessionOrThrow(sessionId)
                    // 删除事务回滚后继续验证该根；提交删除且根已不存在时没有 writer 可打开。
                    rootIds.forEach { recoverRoot(it) }
                }
            }
            val allRootIds = (roots.map { it.uuid } + pending.filterNot { row -> sessionRows.values.any { row in it } }.map { it.workspaceRootUuid }).distinct()
            for (rootId in allRootIds) {
                if (manager.isRootIsolated(rootId)) continue
                manager.recover(StartupStep.WORKSPACE_JOURNAL,
                    StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, rootId,
                        sessions.firstOrNull { it.workspaceRootUuid == rootId }?.title?.takeIf(String::isNotBlank)
                            ?: roots.firstOrNull { it.uuid == rootId }?.name?.takeIf(String::isNotBlank) ?: "工作区"),
                    abandon = {
                        fileJournal.abandonRootOrThrow(rootId)
                        recoverRoot(rootId)
                    },
                ) { recoverRoot(rootId) }
            }
            // 有会话引用却没有根记录时，仍必须阻止该会话创建新根来掩盖坏引用。
            sessions.filter { it.workspaceRootUuid != null && roots.none { root -> root.uuid == it.workspaceRootUuid } }.forEach { session ->
                manager.recover(StartupStep.LEGACY_ADOPTION, StartupIssueScope(StartupScopeKind.SESSION, session.id, "会话")) {
                    legacy.adoptSessionOrThrow(session.id)
                }
            }
        }
    }

    private suspend fun recoverRoot(rootId: String) {
        val sessions = database.sessionDao().getAll().filter { it.workspaceRootUuid == rootId }
        atStep(StartupStep.LEGACY_ADOPTION) {
            val root = database.fileEntryDao().getAnyStateByUuidForLifecycle(rootId, rootId)
            if (root != null) {
                val declared = File(root.physicalRootPath).absoluteFile.toPath().normalize()
                check(!java.nio.file.Files.isSymbolicLink(declared)) { "工作区根不能是符号链接" }
                val physical = declared.toFile().canonicalFile
                database.fileEntryDao().getStructurallyValidWorkspaceRootCandidates().forEach { other ->
                    if (other.uuid != rootId) {
                        val otherPath = File(other.physicalRootPath).canonicalFile
                        check(otherPath != physical && !runCatching { java.nio.file.Files.isSameFile(otherPath.toPath(), physical.toPath()) }.getOrDefault(false)) {
                            "多个工作区根记录指向同一规范物理目录"
                        }
                    }
                }
            }
            sessions.forEach { legacy.adoptSessionOrThrow(it.id) }
        }
        atStep(StartupStep.WORKSPACE_JOURNAL) { fileJournal.recoverRootOrThrow(rootId) }
        val root = database.fileEntryDao().getAnyStateByUuidForLifecycle(rootId, rootId)
        if (root != null) atStep(StartupStep.TOMBSTONES) {
            val report = tombstones.cleanupPendingTombstones(rootId)
            check(report.attentionTokens.isEmpty()) { "工作区 tombstone 恢复存在冲突" }
        }
    }

    private suspend fun atStep(step: StartupStep, block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw StartupRecoveryStepException(step, failure) }
    }
}
