package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.WorkspaceSeqDao
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationType
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.RenameResult
import com.promenar.nexara.domain.repository.RenameIndexTarget
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.io.OutputStream
import com.promenar.nexara.data.session.SessionExecutionGate


interface WorkspaceDeleteBarrierLease {
    suspend fun awaitReady()
    suspend fun commit()
    suspend fun abort()
}

private object NoOpWorkspaceDeleteBarrierLease : WorkspaceDeleteBarrierLease {
    override suspend fun awaitReady() = Unit
    override suspend fun commit() = Unit
    override suspend fun abort() = Unit
}

enum class WorkspaceLifecycleErrorCode {
    ACTIVE_PERMANENT_DELETE_FORBIDDEN,
    CREATION_ROLLBACK_NOT_AUTHORIZED,
    TARGET_COLLISION,
}

class WorkspaceLifecycleException(
    val code: WorkspaceLifecycleErrorCode,
    val safeMessage: String,
) : IllegalStateException(safeMessage)

class WorkspaceRepository(
    private val dao: FileEntryDao,
    private val seqDao: WorkspaceSeqDao,
    private val defaultWorkspaceParent: File? = null,
    private val fileOps: WorkspaceFileOps = SecureWorkspaceFileOps(),
    private val insertCommitter: (suspend (FileEntry) -> Unit)? = null,
    private val updateCommitter: (suspend (List<FileEntry>) -> Unit)? = null,
    private val deleteCommitter: (suspend (String, List<String>) -> Unit)? = null,
    private val beforeDeleteCommitted: suspend (String, List<String>) -> WorkspaceDeleteBarrierLease =
        { _, _ -> NoOpWorkspaceDeleteBarrierLease },
    private val afterDeleteCommitted: suspend (String, List<String>) -> Unit = { _, _ -> },
    private val recycleCommitter: (suspend (String, List<FileEntry>, List<String>) -> Unit)? = null,
    private val restoreCommitter: (suspend (String, List<FileEntry>, List<RenameIndexTarget>) -> Unit)? = null,
    private val beforeRecycleCommitted: suspend (String, List<String>) -> WorkspaceDeleteBarrierLease =
        { _, _ -> NoOpWorkspaceDeleteBarrierLease },
    private val afterRecycleCommitted: suspend (String, List<String>) -> Unit = { _, _ -> },
    private val afterRestoreCommitted: suspend (String, List<RenameIndexTarget>) -> Unit = { _, _ -> },
    private val bulkDeleteSnapshotHook: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val executionGate: SessionExecutionGate? = null,
    private val mutationJournal: WorkspaceFileMutationJournal? = null,
) : IWorkspaceRepository {
    @Volatile
    private var defaultParentIdentity: String? = null
    private val creationRollbackAuthorizations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override suspend fun ensureSessionRoot(sessionId: String): FileEntry = withSessionAdmission(sessionId) {
        withContext(Dispatchers.IO) {
        val session = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        val path = session.workspacePath?.takeIf { it.isNotBlank() }
            ?: session.workspaceRootUuid?.let { rootUuid ->
                dao.getActiveByUuid(rootUuid, rootUuid)?.physicalRootPath
            }
            ?: defaultWorkspaceParent?.let { parent ->
                File(parent, Sha256Utils.hash(sessionId)).path
            }
            ?: throw IllegalStateException("Session workspace path is missing: $sessionId")
        ensureSessionRootInternal(sessionId, path)
        }
    }

    override suspend fun ensureSessionRoot(
        sessionId: String,
        physicalRootPath: String,
    ): FileEntry = withSessionAdmission(sessionId) {
        withContext(Dispatchers.IO) { ensureSessionRootInternal(sessionId, physicalRootPath) }
    }

    private suspend fun ensureSessionRootInternal(sessionId: String, physicalRootPath: String): FileEntry {
        val declaredRootPath = File(physicalRootPath.trim()).absoluteFile.toPath().normalize()
        if (java.nio.file.Files.isSymbolicLink(declaredRootPath)) {
            throw SecurityException("Session workspace root cannot be a symbolic link")
        }
        val physicalRoot = declaredRootPath.toFile().canonicalFile
        val before = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        val beforeRoot = before.workspaceRootUuid?.let { rootUuid ->
            val claimed = dao.getActiveByUuid(rootUuid, rootUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
            validateClaimedRoot(claimed, physicalRoot)
            claimed
        }
        val action: suspend () -> FileEntry = action@{
        val session = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        if (dao.countOtherSessionsForPhysicalRoot(physicalRoot.path, sessionId) != 0) {
            throw SecurityException("工作区物理根已被其他 Session 使用")
        }
        val existingRoot = session.workspaceRootUuid?.let { rootUuid ->
            val claimed = dao.getActiveByUuid(rootUuid, rootUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
            validateClaimedRoot(claimed, physicalRoot)
            claimed
        }
        var parentPath: java.nio.file.Path? = null
        var parentIdentity: String? = null
        if (defaultWorkspaceParent != null && physicalRoot.parentFile == defaultWorkspaceParent.canonicalFile) {
            parentPath = defaultWorkspaceParent.canonicalFile.toPath()
            val expectedParent = defaultParentIdentity
            parentIdentity = fileOps.ensureRoot(
                parentPath,
                initializeIdentity = expectedParent == null,
                expectedIdentity = expectedParent,
                allowUnboundParent = true,
            )
            defaultParentIdentity = parentIdentity
        }
        val ensurePhysical: suspend () -> String = {
            val legacyIdentityAdoption = existingRoot?.hash.isNullOrBlank() &&
                session.workspaceRootUuid != null &&
                isTrustedLegacyRoot(sessionId, declaredRootPath, physicalRoot)
            if (existingRoot?.hash.isNullOrBlank() && session.workspaceRootUuid != null && !legacyIdentityAdoption) {
                throw SecurityException("旧版工作区根不在应用私有目录内")
            }
            fileOps.ensureRoot(
                physicalRoot.toPath(),
                initializeIdentity = session.workspaceRootUuid == null || legacyIdentityAdoption,
                expectedIdentity = existingRoot?.hash?.takeIf { it.isNotBlank() },
            )
        }
        val rootIdentity = if (parentPath != null && parentIdentity != null) {
            WorkspaceMutationCoordinator.withBoundRoot(parentPath, parentIdentity, ensurePhysical)
        } else ensurePhysical()
        WorkspaceMutationCoordinator.bindIdentityWhileHeld(physicalRoot.toPath(), rootIdentity)
        session.workspacePath?.takeIf { it.isNotBlank() }?.let { declaredPath ->
            if (File(declaredPath.trim()).canonicalFile != physicalRoot) {
                throw SecurityException("Session workspace path cannot be changed")
            }
        }
        session.workspaceRootUuid?.let { existingUuid ->
            if (dao.countOtherSessionsForRoot(existingUuid, sessionId) != 0) {
                throw SecurityException("工作区根被多个 Session 共享")
            }
            var existing = dao.getActiveByUuid(existingUuid, existingUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
            if (File(existing.physicalRootPath).canonicalFile != physicalRoot) {
                throw SecurityException("Session workspace root path cannot be changed")
            }
            if (existing.hash.isBlank()) {
                existing = existing.copy(hash = rootIdentity, updatedAt = clock())
                dao.update(existing)
            }
            dao.canonicalizeSessionRootClaim(sessionId, existingUuid, physicalRoot.path, System.currentTimeMillis())
            return@action existing
        }

        val rootUuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.ensureSessionRoot(
            candidate = FileEntry(
                uuid = rootUuid,
                workspaceRootUuid = rootUuid,
                parentUuid = null,
                name = physicalRoot.name.ifBlank { "workspace" },
                hash = rootIdentity,
                isDirectory = true,
                physicalRootPath = physicalRoot.path,
                materializedPath = "/",
                createdAt = now,
                updatedAt = now,
            ),
            sessionId = sessionId,
        ).also { claimed ->
            if (File(claimed.physicalRootPath).canonicalFile != physicalRoot) {
                throw SecurityException("Concurrent workspace root path mismatch")
            }
            if (claimed.hash != rootIdentity) throw SecurityException("Concurrent workspace root identity mismatch")
        }
        }
        return if (beforeRoot?.hash?.isNotBlank() == true) {
            WorkspaceMutationCoordinator.withBoundRoot(physicalRoot.toPath(), beforeRoot.hash, action)
        } else {
            WorkspaceMutationCoordinator.withRoot(physicalRoot.toPath(), action)
        }
    }

    private fun validateClaimedRoot(root: FileEntry, physicalRoot: File) {
        if (
            root.uuid != root.workspaceRootUuid ||
            root.parentUuid != null ||
            !root.isDirectory ||
            root.materializedPath != "/"
        ) {
            throw SecurityException("Session workspace root record is invalid")
        }
        if (File(root.physicalRootPath).canonicalFile != physicalRoot) {
            throw SecurityException("Session workspace root path cannot be changed")
        }
    }

    private fun isTrustedLegacyRoot(
        sessionId: String,
        declaredRootPath: java.nio.file.Path,
        root: File,
    ): Boolean {
        val sessionParent = defaultWorkspaceParent?.canonicalFile ?: return false
        val appFilesDir = sessionParent.parentFile?.canonicalFile ?: return false
        val expected = if (sessionId == LEGACY_RAG_SESSION_ID) {
            File(appFilesDir, LEGACY_RAG_DIRECTORY)
        } else {
            File(sessionParent, Sha256Utils.hash(sessionId))
        }
        val expectedPath = expected.absoluteFile.toPath().normalize()
        if (declaredRootPath != expectedPath || java.nio.file.Files.isSymbolicLink(declaredRootPath)) {
            return false
        }
        return root == expected.canonicalFile
    }

    private companion object {
        const val LEGACY_RAG_SESSION_ID = "__nexara_rag_workspace__"
        const val LEGACY_RAG_DIRECTORY = "rag_workspace"
    }

    override fun observeRoots(workspaceRootUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.observeRoots(workspaceRootUuid)

    override fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank() || parentUuid.isBlank()) emptyFlow()
        else dao.observeChildren(workspaceRootUuid, parentUuid)

    override fun observeRecycleBin(workspaceRootUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.observeRecycleBin(workspaceRootUuid)

    override suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry? =
        if (workspaceRootUuid.isBlank() || uuid.isBlank()) null else dao.getActiveByUuid(workspaceRootUuid, uuid)

    override suspend fun getByMaterializedPath(
        workspaceRootUuid: String,
        materializedPath: String,
    ): FileEntry? = withContext(Dispatchers.IO) {
        val root = requireRoot(workspaceRootUuid)
        dao.getActiveByMaterializedPath(workspaceRootUuid, validateMaterializedPath(root, materializedPath))
    }

    override suspend fun getSubtree(
        workspaceRootUuid: String,
        materializedPathPrefix: String,
        inRecycleBin: Boolean,
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val root = requireRoot(workspaceRootUuid)
        dao.getSubtree(
            workspaceRootUuid,
            validateMaterializedPath(root, materializedPathPrefix),
            inRecycleBin,
        )
    }

    override fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.searchByName(workspaceRootUuid, query)

    override suspend fun createFileInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        content: String,
        parentUuid: String?,
        materializedPath: String,
    ): FileEntry = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) { root ->
        val parent = requireParent(root, parentUuid)
        validateName(name)
        val normalizedPath = validateMaterializedPath(root, materializedPath)
        if (normalizedPath.substringAfterLast('/') != name) throw SecurityException("文件名与路径不一致")
        if (normalizedPath != joinMaterializedPath(parent.materializedPath, name)) {
            throw SecurityException("文件路径与父目录不一致")
        }
        if (dao.getActiveByMaterializedPath(workspaceRootUuid, normalizedPath) != null) {
            throw IllegalStateException("工作区文件已存在: $normalizedPath")
        }
        val rootPath = File(root.physicalRootPath).toPath()
        val relative = relative(normalizedPath)
        requirePhysicalTargetAbsent(rootPath, relative, normalizedPath)
        val staged = prepareMutation(root, WorkspaceMutationType.CREATE, normalizedPath, normalizedPath, uuid)
        prepareCreateOwnership(rootPath, staged)
        val bytes = content.toByteArray(Charsets.UTF_8)
        var databaseCommitted = false
        var placement: WorkspaceFileRollback? = null
        try {
            placement = createAndPlace(rootPath, relative, staged) { location ->
                fileOps.createFile(rootPath, location, bytes)
            }.second
        } catch (failure: Throwable) {
            handleCreateAttemptFailure(rootPath, relative, staged, failure)
            throw failure
        }
        val now = System.currentTimeMillis()
        val entry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = parentUuid,
            name = name,
            hash = Sha256Utils.hash(content),
            mimeType = "text/plain",
            sizeBytes = bytes.size.toLong(),
            physicalRootPath = root.physicalRootPath,
            materializedPath = normalizedPath,
            createdAt = now,
            updatedAt = now,
        )
        try {
            commitDatabaseMutation(staged) { (insertCommitter ?: dao::insertAbort).invoke(entry) }
            databaseCommitted = true
            placement?.commit()
            completeCreatedMutation(rootPath, staged)
            entry
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                rollbackCreatedPlacement(rootPath, relative, staged, placement, failure)
            }
            throw failure
        }
    } }

    override suspend fun createFileInWorkspaceStreaming(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        mimeType: String,
        parentUuid: String?,
        materializedPath: String,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
    ): FileEntry = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) { root ->
        require(maxBytes > 0) { "maxBytes 必须大于 0" }
        val parent = requireParent(root, parentUuid)
        validateName(name)
        val normalizedPath = validateMaterializedPath(root, materializedPath)
        if (normalizedPath.substringAfterLast('/') != name) throw SecurityException("文件名与路径不一致")
        if (normalizedPath != joinMaterializedPath(parent.materializedPath, name)) {
            throw SecurityException("文件路径与父目录不一致")
        }
        if (dao.getActiveByMaterializedPath(workspaceRootUuid, normalizedPath) != null) {
            throw IllegalStateException("工作区文件已存在: $normalizedPath")
        }
        val rootPath = File(root.physicalRootPath).toPath()
        val relative = relative(normalizedPath)
        requirePhysicalTargetAbsent(rootPath, relative, normalizedPath)
        val staged = prepareMutation(root, WorkspaceMutationType.CREATE_STREAMING, normalizedPath, normalizedPath, uuid)
        prepareCreateOwnership(rootPath, staged)
        var placement: WorkspaceFileRollback? = null
        val writeResult = try {
            createAndPlace(rootPath, relative, staged) { location ->
                fileOps.createFileStreaming(rootPath, location, maxBytes, writer)
            }.also { placement = it.second }.first
        } catch (failure: Throwable) {
            handleCreateAttemptFailure(rootPath, relative, staged, failure)
            throw failure
        }
        val now = System.currentTimeMillis()
        val entry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = parentUuid,
            name = name,
            hash = writeResult.sha256,
            mimeType = mimeType,
            sizeBytes = writeResult.sizeBytes,
            physicalRootPath = root.physicalRootPath,
            materializedPath = normalizedPath,
            createdAt = now,
            updatedAt = now,
        )
        var databaseCommitted = false
        try {
            commitDatabaseMutation(staged) {
                (insertCommitter ?: dao::insertAbort).invoke(entry)
                creationRollbackAuthorizations += creationRollbackKey(workspaceRootUuid, entry.uuid)
            }
            databaseCommitted = true
            placement?.commit()
            completeCreatedMutation(rootPath, staged)
            entry
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                creationRollbackAuthorizations.remove(creationRollbackKey(workspaceRootUuid, entry.uuid))
                rollbackCreatedPlacement(rootPath, relative, staged, placement, failure)
            }
            throw failure
        }
    } }

    override suspend fun createDirectoryInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        parentUuid: String?,
        materializedPath: String,
    ): FileEntry = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) { root ->
        val parent = requireParent(root, parentUuid)
        validateName(name)
        val normalizedPath = validateMaterializedPath(root, materializedPath)
        if (normalizedPath.substringAfterLast('/') != name) throw SecurityException("目录名与路径不一致")
        if (normalizedPath != joinMaterializedPath(parent.materializedPath, name)) {
            throw SecurityException("目录路径与父目录不一致")
        }
        if (dao.getActiveByMaterializedPath(workspaceRootUuid, normalizedPath) != null) {
            throw IllegalStateException("工作区目录已存在: $normalizedPath")
        }
        val rootPath = File(root.physicalRootPath).toPath()
        val relative = relative(normalizedPath)
        requirePhysicalTargetAbsent(rootPath, relative, normalizedPath)
        val staged = prepareMutation(root, WorkspaceMutationType.MKDIR, normalizedPath, normalizedPath, uuid)
        prepareCreateOwnership(rootPath, staged)
        var placement: WorkspaceFileRollback? = null
        try {
            placement = createAndPlace(rootPath, relative, staged) { location ->
                fileOps.createDirectory(rootPath, location)
            }.second
        } catch (failure: Throwable) {
            handleCreateAttemptFailure(rootPath, relative, staged, failure)
            throw failure
        }
        val now = System.currentTimeMillis()
        val entry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = parentUuid,
            name = name,
            hash = "",
            isDirectory = true,
            physicalRootPath = root.physicalRootPath,
            materializedPath = normalizedPath,
            createdAt = now,
            updatedAt = now,
        )
        var databaseCommitted = false
        try {
            commitDatabaseMutation(staged) { (insertCommitter ?: dao::insertAbort).invoke(entry) }
            databaseCommitted = true
            placement?.commit()
            completeCreatedMutation(rootPath, staged)
            entry
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                rollbackCreatedPlacement(rootPath, relative, staged, placement, failure)
            }
            throw failure
        }
    } }

    override suspend fun moveToRecycleBin(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) {
        val entry = dao.getAnyStateByUuidForLifecycle(workspaceRootUuid, uuid) ?: return@withRootMutation
        if (entry.uuid == workspaceRootUuid) throw SecurityException("工作区根目录不可回收")
        if (entry.inRecycleBin) return@withRootMutation
        val originalPath = entry.materializedPath
        // 物理移动目标始终位于已存在的回收站目录直属层，避免隐式创建父链。
        val recyclePath = "/.recycle_bin/${entry.uuid}"
        val recycleParent = resolveOrCreateRecycleBinDir(entry)
        val now = System.currentTimeMillis()
        val updates = mutableListOf(entry.copy(
            inRecycleBin = true,
            recycledAt = now,
            originalParentUuid = entry.parentUuid,
            originalMaterializedPath = originalPath,
            parentUuid = recycleParent,
            materializedPath = recyclePath,
            updatedAt = now,
        ))
        if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, originalPath, false)
                .filterNot { it.uuid == uuid }
                .forEach { child ->
                    val childPath = recyclePath + child.materializedPath.removePrefix(originalPath)
                    updates += child.copy(
                        inRecycleBin = true,
                        recycledAt = now,
                        originalParentUuid = child.parentUuid,
                        originalMaterializedPath = child.materializedPath,
                        materializedPath = childPath,
                        updatedAt = now,
                    )
                }
        }
        commitRecycleMove(entry, originalPath, recyclePath, updates)
    } }

    override suspend fun restoreFromRecycleBin(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) {
        val entry = dao.getAnyStateByUuidForLifecycle(workspaceRootUuid, uuid) ?: return@withRootMutation
        if (!entry.inRecycleBin) return@withRootMutation
        val originalPath = entry.originalMaterializedPath
            ?: throw IllegalStateException("回收站记录缺少原始路径，无法恢复。")
        val originalParent = entry.originalParentUuid
            ?: throw IllegalStateException("回收站记录缺少原父目录，无法恢复。")
        if (dao.getActiveByUuid(workspaceRootUuid, originalParent) == null) {
            throw SecurityException("原父目录不属于当前工作区")
        }
        val recycledSubtree = if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, entry.materializedPath, true)
        } else listOf(entry)
        val subtreeIds = recycledSubtree.mapTo(mutableSetOf()) { it.uuid }
        val workspaceRoot = requireRoot(workspaceRootUuid)
        val validatedRootOriginal = validateMaterializedPath(workspaceRoot, originalPath)
        val originalPaths = recycledSubtree.associate { child ->
            val raw = child.originalMaterializedPath
                ?: throw IllegalStateException("回收子树缺少原始路径，无法恢复。")
            val validated = validateMaterializedPath(workspaceRoot, raw)
            val expected = if (child.uuid == entry.uuid) {
                validatedRootOriginal
            } else {
                validatedRootOriginal + child.materializedPath.removePrefix(entry.materializedPath)
            }
            if (validated != expected || !validated.startsWith(validatedRootOriginal.trimEnd('/') + "/") && child.uuid != entry.uuid) {
                throw IllegalStateException("回收子树原始路径结构不一致，无法恢复。")
            }
            val originalParentUuid = child.originalParentUuid
                ?: throw IllegalStateException("回收子树缺少原父目录，无法恢复。")
            if (originalParentUuid !in subtreeIds && dao.getActiveByUuid(workspaceRootUuid, originalParentUuid) == null) {
                throw IllegalStateException("回收子树原父目录无效，无法恢复。")
            }
            child.uuid to validated
        }
        if (originalPaths.values.toSet().size != originalPaths.size) {
            throw IllegalStateException("回收子树原始路径重复，无法恢复。")
        }
        originalPaths.values.forEach { path ->
            val occupied = dao.getActiveByMaterializedPath(workspaceRootUuid, path)
            if (occupied != null && occupied.uuid !in subtreeIds) throw WorkspaceLifecycleException(
                WorkspaceLifecycleErrorCode.TARGET_COLLISION,
                "恢复目标路径已被占用。",
            )
        }
        requireMoveTargetAbsent(entry, originalPath)
        val now = System.currentTimeMillis()
        val updates = mutableListOf(entry.copy(
            inRecycleBin = false,
            recycledAt = null,
            originalParentUuid = null,
            originalMaterializedPath = null,
            parentUuid = originalParent,
            materializedPath = originalPath,
            updatedAt = now,
        ))
        if (entry.isDirectory) {
            recycledSubtree
                .filterNot { it.uuid == uuid }
                .forEach { child ->
                    val childOriginal = originalPaths.getValue(child.uuid)
                    updates += child.copy(
                        inRecycleBin = false,
                        recycledAt = null,
                        originalParentUuid = null,
                        originalMaterializedPath = null,
                        materializedPath = childOriginal,
                        updatedAt = now,
                    )
                }
        }
        commitRestoreMove(entry, entry.materializedPath, originalPath, updates)
    } }

    override suspend fun permanentDelete(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) {
        permanentDeleteLocked(workspaceRootUuid, uuid)
    } }

    override suspend fun rollbackCreatedEntry(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid) {
            val key = creationRollbackKey(workspaceRootUuid, uuid)
            if (!creationRollbackAuthorizations.remove(key)) {
                throw WorkspaceLifecycleException(
                    WorkspaceLifecycleErrorCode.CREATION_ROLLBACK_NOT_AUTHORIZED,
                    "该文件不属于当前未确认的创建事务，已拒绝回滚删除。",
                )
            }
            permanentDeleteLocked(workspaceRootUuid, uuid, allowActiveCreationRollback = true)
        }
    }

    override suspend fun confirmCreatedEntry(workspaceRootUuid: String, uuid: String) {
        creationRollbackAuthorizations.remove(creationRollbackKey(workspaceRootUuid, uuid))
    }

    private suspend fun permanentDeleteLocked(
        workspaceRootUuid: String,
        uuid: String,
        allowActiveCreationRollback: Boolean = false,
    ) {
        val entry = dao.getAnyStateByUuidForLifecycle(workspaceRootUuid, uuid) ?: return
        if (entry.uuid == workspaceRootUuid) throw SecurityException("工作区根目录不可删除")
        if (!entry.inRecycleBin && !allowActiveCreationRollback) {
            throw WorkspaceLifecycleException(
                WorkspaceLifecycleErrorCode.ACTIVE_PERMANENT_DELETE_FORBIDDEN,
                "活动文件只能先移入回收站，再从回收站永久删除。",
            )
        }
        val children = if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, entry.materializedPath, entry.inRecycleBin)
                .filterNot { it.uuid == uuid }
                .sortedByDescending { it.materializedPath.length }
        } else emptyList()
        reconcileEntryTree(entry)
        val root = File(entry.physicalRootPath).toPath()
        val rootEntry = requireRoot(workspaceRootUuid)
        val deletionToken = workspaceDeletionToken(entry.uuid)
        val journalStage = prepareMutation(
            rootEntry,
            WorkspaceMutationType.DELETE,
            entry.materializedPath,
            ".nexara_tombstones/$deletionToken",
            entry.uuid,
        )
        val stagedRollbacks = mutableListOf<WorkspaceFileRollback>()
        val ids = children.map { it.uuid } + uuid
        var barrier: WorkspaceDeleteBarrierLease? = null
        var databaseCommitted = false
        var physicalStageInProgress = false
        try {
            physicalStageInProgress = true
            stagedRollbacks += fileOps.stageDelete(
                root,
                relative(entry.materializedPath),
                deletionToken,
            )
            physicalStageInProgress = false
            ids.forEach { fileUuid ->
                val versionPath = workspaceVersionSnapshotDirectory(workspaceRootUuid, fileUuid)
                if (fileOps.exists(root, versionPath)) {
                    physicalStageInProgress = true
                    stagedRollbacks += fileOps.stageDelete(
                        root,
                        versionPath,
                        workspaceVersionDeletionToken(fileUuid),
                    )
                    physicalStageInProgress = false
                }
            }
            barrier = beforeDeleteCommitted(workspaceRootUuid, ids)
            barrier.awaitReady()
            commitDatabaseMutation(journalStage) {
                requireNotNull(deleteCommitter) {
                    "永久删除必须配置包含派生数据清理的事务提交器"
                }.invoke(workspaceRootUuid, ids)
            }
            databaseCommitted = true
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                var rollbackFailed = physicalStageInProgress
                stagedRollbacks.asReversed().forEach { rollback ->
                    runCatching { rollback.rollback() }.exceptionOrNull()?.let { rollbackFailure ->
                        rollbackFailed = true
                        failure.addSuppressed(rollbackFailure)
                    }
                }
                if (!rollbackFailed) abortMutation(journalStage, failure)
            }
            withContext(NonCancellable) {
                if (!databaseCommitted) {
                    runCatching { barrier?.abort() }.exceptionOrNull()?.let(failure::addSuppressed)
                }
            }
            throw failure
        }
        withContext(NonCancellable) {
            checkNotNull(barrier).commit()
            runCatching { afterDeleteCommitted(workspaceRootUuid, ids) }
                .exceptionOrNull()
                ?.let { failure ->
                    runCatching { NexaraLogger.logError("WorkspaceDelete.afterCommitted", failure) }
                }
        }
        // 清理失败时保留 DB_COMMITTED journal 与 tombstone，由启动恢复幂等收敛。
        stagedRollbacks.forEach { rollback -> rollback.commit() }
        completeMutation(journalStage)
        Unit
    }

    override suspend fun emptyRecycleBin(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid) {
            val recycleRoot = dao.getAnyStateByMaterializedPathForLifecycle(workspaceRootUuid, "/.recycle_bin")
                ?: return@withRootMutation
            val topLevel = dao.observeRecycleBin(workspaceRootUuid).first()
                .filter { it.parentUuid == recycleRoot.uuid }
            bulkDeleteSnapshotHook()
            topLevel.forEach { permanentDeleteLocked(workspaceRootUuid, it.uuid) }
        }
    }

    suspend fun cleanupStaleRecycleBin(workspaceRootUuid: String, cutoff: Long) = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid) { root ->
            recoverPendingTombstonesLocked(root)
            val staleFiles = dao.observeRecycleBin(workspaceRootUuid).first()
                .filter { (it.recycledAt ?: 0) < cutoff }
            val staleRoots = staleFiles.filter { candidate ->
                staleFiles.none { ancestor ->
                    ancestor.uuid != candidate.uuid &&
                        candidate.materializedPath.startsWith(ancestor.materializedPath.trimEnd('/') + "/")
                }
            }
            bulkDeleteSnapshotHook()
            staleRoots.forEach { permanentDeleteLocked(workspaceRootUuid, it.uuid) }
        }
    }

    suspend fun cleanupPendingTombstones(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid) { root ->
            recoverPendingTombstonesLocked(root)
        }
    }

    private suspend fun recoverPendingTombstonesLocked(root: FileEntry): TombstoneRecoveryReport {
        val restorePaths = buildMap {
            dao.getAllStatesByWorkspaceRootForCleanup(root.uuid).forEach { entry ->
                put(workspaceDeletionToken(entry.uuid), relative(entry.materializedPath))
                put(workspaceVersionDeletionToken(entry.uuid), workspaceVersionSnapshotDirectory(root.uuid, entry.uuid))
            }
        }
        return fileOps.recoverTombstones(File(root.physicalRootPath).toPath(), restorePaths::get).also { report ->
            if (report.attentionTokens.isNotEmpty()) {
                NexaraLogger.log("[WorkspaceDeleteRecovery] root=${root.uuid} attention=${report.attentionTokens.size}")
            }
        }
    }

    override suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String) =
        withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) {
            val entry = dao.getActiveByUuid(workspaceRootUuid, uuid) ?: return@withRootMutation
            val parent = dao.getActiveByUuid(workspaceRootUuid, newParentUuid)
                ?: throw SecurityException("目标父目录不属于当前工作区")
            if (!parent.isDirectory || parent.inRecycleBin) throw SecurityException("目标父节点不可用")
            if (entry.uuid == workspaceRootUuid || newParentUuid == uuid ||
                parent.materializedPath.startsWith(entry.materializedPath.trimEnd('/') + "/")) {
                throw SecurityException("不允许形成循环目录关系")
            }
            val newPath = joinMaterializedPath(parent.materializedPath, entry.name)
            requireMoveTargetAbsent(entry, newPath)
            val now = System.currentTimeMillis()
            val oldPrefix = entry.materializedPath
            val updates = mutableListOf(entry.copy(parentUuid = newParentUuid, materializedPath = newPath, updatedAt = now))
            if (entry.isDirectory) {
                dao.getSubtree(workspaceRootUuid, oldPrefix, entry.inRecycleBin)
                    .filterNot { it.uuid == uuid }
                    .forEach { child ->
                        updates += child.copy(
                            materializedPath = newPath + child.materializedPath.removePrefix(oldPrefix),
                            updatedAt = now,
                        )
                    }
            }
            commitMove(WorkspaceMutationType.MOVE, entry, oldPrefix, newPath, updates)
        } }

    override suspend fun rename(
        workspaceRootUuid: String,
        uuid: String,
        newName: String,
        expectedName: String?,
    ): RenameResult = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid) {
                    validateName(newName)
                    val entry = dao.getActiveByUuid(workspaceRootUuid, uuid)
                        ?: return@withRootMutation RenameResult.NotFound
                    if (entry.uuid == workspaceRootUuid) {
                        throw SecurityException("工作区根目录不可重命名")
                    }
                    if (entry.name == newName) {
                        return@withRootMutation RenameResult.Success(
                            name = entry.name,
                            targetHash = entry.hash,
                            targetEpoch = entry.updatedAt,
                            changed = false,
                        )
                    }
                    if (expectedName != null && entry.name != expectedName) {
                        return@withRootMutation RenameResult.Conflict(
                            expected = expectedName,
                            current = entry.name,
                        )
                    }
                    val parent = entry.parentUuid?.let { dao.getActiveByUuid(workspaceRootUuid, it) }
                        ?: throw SecurityException("父目录不属于当前工作区")
                    val newPath = joinMaterializedPath(parent.materializedPath, newName)
                    if (dao.getActiveByMaterializedPath(workspaceRootUuid, newPath) != null) {
                        throw IllegalStateException("目标名称已存在")
                    }
                    val commitClock = clock()
                    val nextEpoch = nextMutationEpoch(commitClock, entry.updatedAt)
                    val oldPrefix = entry.materializedPath
                    val updates = mutableListOf(
                        entry.copy(
                            name = newName,
                            materializedPath = newPath,
                            updatedAt = nextEpoch,
                            vectorizedAt = null,
                            kgExtractedAt = null,
                        ),
                    )
                    if (entry.isDirectory) {
                        dao.getSubtree(workspaceRootUuid, oldPrefix, entry.inRecycleBin)
                            .filterNot { it.uuid == uuid }
                            .forEach { child ->
                                updates += child.copy(
                                    materializedPath = newPath + child.materializedPath.removePrefix(oldPrefix),
                                    updatedAt = nextMutationEpoch(commitClock, child.updatedAt),
                                    vectorizedAt = null,
                                    kgExtractedAt = null,
                                )
                            }
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        commitMove(WorkspaceMutationType.RENAME, entry, oldPrefix, newPath, updates)
                        RenameResult.Success(
                            name = newName,
                            targetHash = entry.hash,
                            targetEpoch = nextEpoch,
                            changed = true,
                            affectedTargets = updates.filterNot { it.isDirectory }.map { updated ->
                                RenameIndexTarget(
                                    fileUuid = updated.uuid,
                                    targetHash = updated.hash,
                                    targetEpoch = updated.updatedAt,
                                )
                            },
                        )
                    }
                }
            }

    override suspend fun getNextSeqForDate(dateKey: String): Int = seqDao.getNextSeqForDate(dateKey)

    override suspend fun resetAllRAGStatus(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        requireRoot(workspaceRootUuid)
        dao.resetAllRAGStatus(workspaceRootUuid)
    }

    private suspend fun requireRoot(workspaceRootUuid: String): FileEntry {
        if (workspaceRootUuid.isBlank()) throw SecurityException("workspaceRootUuid 不能为空")
        return dao.getActiveByUuid(workspaceRootUuid, workspaceRootUuid)
            ?.takeIf { it.isDirectory && it.parentUuid == null && it.workspaceRootUuid == workspaceRootUuid }
            ?: throw SecurityException("工作区根不存在或无效")
    }

    private suspend fun <T> withRootMutation(
        workspaceRootUuid: String,
        block: suspend (FileEntry) -> T,
    ): T {
        val gate = executionGate
        return if (gate == null) {
            withAdmittedRootMutation(workspaceRootUuid, block)
        } else {
            gate.withWorkspaceAdmission(workspaceRootUuid) {
                withAdmittedRootMutation(workspaceRootUuid, block)
            }
        }
    }

    private suspend fun <T> withAdmittedRootMutation(
        workspaceRootUuid: String,
        block: suspend (FileEntry) -> T,
    ): T {
        val initial = requireRoot(workspaceRootUuid)
        return WorkspaceMutationCoordinator.withBoundRoot(
            File(initial.physicalRootPath).toPath(), initial.hash,
        ) {
            val currentRoot = requireRoot(workspaceRootUuid)
            recoverPendingTombstonesLocked(currentRoot)
            block(requireRoot(workspaceRootUuid))
        }
    }

    private suspend fun <T> withSessionAdmission(sessionId: String, block: suspend () -> T): T {
        val gate = executionGate
        return if (gate == null) block() else gate.withSessionAdmission(sessionId, block)
    }

    private suspend fun requireParent(root: FileEntry, parentUuid: String?): FileEntry {
        val parentId = parentUuid ?: throw SecurityException("非根节点必须指定父目录")
        val parent = dao.getActiveByUuid(root.uuid, parentId)
            ?: throw SecurityException("父目录不属于当前工作区")
        if (!parent.isDirectory || parent.inRecycleBin) throw SecurityException("父节点不是可用目录")
        return parent
    }

    private suspend fun resolveOrCreateRecycleBinDir(entry: FileEntry): String {
        dao.getAnyStateByMaterializedPathForLifecycle(entry.workspaceRootUuid, "/.recycle_bin")?.let { return it.uuid }
        val rootEntry = requireRoot(entry.workspaceRootUuid)
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val root = File(entry.physicalRootPath).toPath()
        if (fileOps.exists(root, listOf(".recycle_bin"))) {
            throw WorkspaceLifecycleException(
                WorkspaceLifecycleErrorCode.TARGET_COLLISION,
                "回收站物理目录缺少数据库归属，已拒绝自动收养。",
            )
        }
        val staged = prepareMutation(
            rootEntry,
            WorkspaceMutationType.MKDIR,
            "/.recycle_bin",
            "/.recycle_bin",
            uuid,
        )
        prepareCreateOwnership(root, staged)
        var placement: WorkspaceFileRollback? = null
        try {
            placement = createAndPlace(root, listOf(".recycle_bin"), staged) { location ->
                fileOps.createDirectory(root, location)
            }.second
        } catch (failure: Throwable) {
            handleCreateAttemptFailure(root, listOf(".recycle_bin"), staged, failure)
            throw failure
        }
        val recycleEntry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = entry.workspaceRootUuid,
            parentUuid = entry.workspaceRootUuid,
            name = ".recycle_bin",
            hash = "",
            isDirectory = true,
            physicalRootPath = entry.physicalRootPath,
            materializedPath = "/.recycle_bin",
            inRecycleBin = true,
            createdAt = now,
            updatedAt = now,
        )
        var databaseCommitted = false
        try {
            commitDatabaseMutation(staged) { (insertCommitter ?: dao::insertAbort).invoke(recycleEntry) }
            databaseCommitted = true
            placement?.commit()
            completeCreatedMutation(root, staged)
        } catch (failure: Throwable) {
            // 并发赢家存在时复用；其它失败保留系统目录但不继续业务移动。
            dao.getAnyStateByMaterializedPathForLifecycle(entry.workspaceRootUuid, "/.recycle_bin")?.let { winner ->
                rollbackCreatedPlacement(root, listOf(".recycle_bin"), staged, placement, failure)
                return winner.uuid
            }
            if (!databaseCommitted) rollbackCreatedPlacement(root, listOf(".recycle_bin"), staged, placement, failure)
            throw failure
        }
        return uuid
    }

    private suspend fun commitMove(
        type: WorkspaceMutationType,
        entry: FileEntry,
        sourcePath: String,
        targetPath: String,
        updates: List<FileEntry>,
    ) {
        requireMoveTargetAbsent(entry, targetPath)
        reconcileEntryTree(entry)
        val root = requireRoot(entry.workspaceRootUuid)
        val staged = prepareMutation(root, type, sourcePath, targetPath, entry.uuid)
        val rollback = try {
            fileOps.move(
                File(entry.physicalRootPath).toPath(),
                relative(sourcePath),
                relative(targetPath),
            )
        } catch (failure: Throwable) {
            // move 抛错时无法从返回值判定物理状态，保留 journal 供恢复器核对 source/target。
            throw failure
        }
        var databaseCommitted = false
        try {
            commitDatabaseMutation(staged) { (updateCommitter ?: dao::updateAll).invoke(updates) }
            databaseCommitted = true
            rollback.commit()
            completeMutation(staged)
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                rollbackPhysicalOrPreserveJournal(rollback, staged, failure)
            }
            throw failure
        }
    }

    private suspend fun commitRecycleMove(
        entry: FileEntry,
        sourcePath: String,
        targetPath: String,
        updates: List<FileEntry>,
    ) {
        requireMoveTargetAbsent(entry, targetPath)
        val ids = updates.map { it.uuid }
        val root = requireRoot(entry.workspaceRootUuid)
        val staged = prepareMutation(
            root,
            WorkspaceMutationType.RECYCLE,
            sourcePath,
            targetPath,
            entry.uuid,
        )
        val barrier = beforeRecycleCommitted(entry.workspaceRootUuid, ids)
        var rollback: WorkspaceFileRollback? = null
        var databaseCommitted = false
        var physicalMutationAttempted = false
        try {
            barrier.awaitReady()
            reconcileEntryTree(entry)
            physicalMutationAttempted = true
            rollback = fileOps.move(
                File(entry.physicalRootPath).toPath(),
                relative(sourcePath),
                relative(targetPath),
            )
            val commit = recycleCommitter
            commitDatabaseMutation(staged) {
                if (commit == null) (updateCommitter ?: dao::updateAll).invoke(updates)
                else commit(entry.workspaceRootUuid, updates, ids)
            }
            databaseCommitted = true
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                when {
                    rollback != null -> rollbackPhysicalOrPreserveJournal(rollback, staged, failure)
                    !physicalMutationAttempted -> abortMutation(staged, failure)
                    // move 已尝试但未返回 rollback；保留 journal 供 source/target 核对。
                }
            }
            withContext(NonCancellable) {
                if (!databaseCommitted) {
                    runCatching { barrier.abort() }.exceptionOrNull()?.let(failure::addSuppressed)
                }
            }
            throw failure
        }
        withContext(NonCancellable) {
            barrier.commit()
            runCatching { afterRecycleCommitted(entry.workspaceRootUuid, ids) }
                .onFailure { NexaraLogger.logError("WorkspaceRecycle.afterCommitted", it) }
            rollback?.commit()
            completeMutation(staged)
        }
    }

    private suspend fun commitRestoreMove(
        entry: FileEntry,
        sourcePath: String,
        targetPath: String,
        updates: List<FileEntry>,
    ) {
        requireMoveTargetAbsent(entry, targetPath)
        reconcileEntryTree(entry)
        val root = requireRoot(entry.workspaceRootUuid)
        val staged = prepareMutation(
            root,
            WorkspaceMutationType.RESTORE,
            sourcePath,
            targetPath,
            entry.uuid,
        )
        val rollback = try {
            fileOps.move(
                File(entry.physicalRootPath).toPath(),
                relative(sourcePath),
                relative(targetPath),
            )
        } catch (failure: Throwable) {
            // 保留 PREPARED，启动恢复器以 DB 与 source/target 实际布局为准。
            throw failure
        }
        val targets = updates.filterNot { it.isDirectory }.map { updated ->
            RenameIndexTarget(updated.uuid, updated.hash, updated.updatedAt)
        }
        var databaseCommitted = false
        try {
            val commit = restoreCommitter
            commitDatabaseMutation(staged) {
                if (commit == null) (updateCommitter ?: dao::updateAll).invoke(updates)
                else commit(entry.workspaceRootUuid, updates, targets)
            }
            databaseCommitted = true
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                rollbackPhysicalOrPreserveJournal(rollback, staged, failure)
            }
            throw failure
        }
        withContext(NonCancellable) {
            runCatching { afterRestoreCommitted(entry.workspaceRootUuid, targets) }
                .onFailure { NexaraLogger.logError("WorkspaceRestore.afterCommitted", it) }
            rollback.commit()
            completeMutation(staged)
        }
    }

    private suspend fun prepareMutation(
        root: FileEntry,
        type: WorkspaceMutationType,
        sourcePath: String,
        targetPath: String,
        targetUuid: String,
    ): StagedWorkspaceFileMutation? = mutationJournal?.prepare(
        workspaceRootUuid = root.uuid,
        operationType = type,
        sourceRelativePath = journalRelative(sourcePath),
        targetRelativePath = journalRelative(targetPath),
        databaseTargetUuid = targetUuid,
        rootIdentity = root.hash,
    )

    private suspend fun commitDatabaseMutation(
        staged: StagedWorkspaceFileMutation?,
        mutation: suspend () -> Unit,
    ) {
        if (staged == null) mutation() else mutationJournal!!.commitDatabase(staged, mutation)
    }

    private suspend fun completeMutation(staged: StagedWorkspaceFileMutation?) {
        if (staged != null) mutationJournal!!.complete(staged)
    }

    private suspend fun abortMutation(staged: StagedWorkspaceFileMutation?, failure: Throwable) {
        if (staged != null) {
            runCatching { mutationJournal!!.abort(staged) }.exceptionOrNull()?.let(failure::addSuppressed)
        }
    }

    private fun <T> createAndPlace(
        root: java.nio.file.Path,
        target: List<String>,
        staged: StagedWorkspaceFileMutation?,
        create: (List<String>) -> T,
    ): Pair<T, WorkspaceFileRollback?> {
        if (staged == null) return create(target) to null
        val stagedNode = createOwnershipRelative(staged) + CREATE_STAGED_NODE
        val result = create(stagedNode)
        val identity = fileOps.inspect(root, stagedNode)
        val manifest = WorkspaceCreateOwnershipManifest(target.joinToString("/"), identity)
        fileOps.createFile(
            root,
            createOwnershipRelative(staged) + CREATE_MANIFEST,
            encodeCreateOwnershipManifest(manifest),
        )
        return result to fileOps.move(root, stagedNode, target)
    }

    private suspend fun handleCreateAttemptFailure(
        root: java.nio.file.Path,
        target: List<String>,
        staged: StagedWorkspaceFileMutation?,
        failure: Throwable,
    ) {
        if (staged == null) return
        val targetExists = runCatching { fileOps.exists(root, target) }.getOrDefault(true)
        if (targetExists) return // 物理布局有歧义，保留 journal 交由恢复器按 manifest 身份核验。
        val cleanupFailure = runCatching { cleanupCreateOwnership(root, staged) }.exceptionOrNull()
        if (cleanupFailure == null) abortMutation(staged, failure) else failure.addSuppressed(cleanupFailure)
    }

    private suspend fun rollbackCreatedPlacement(
        root: java.nio.file.Path,
        target: List<String>,
        staged: StagedWorkspaceFileMutation?,
        placement: WorkspaceFileRollback?,
        failure: Throwable,
    ) {
        val cleanupFailure = runCatching {
            if (placement != null) placement.rollback()
            else if (staged == null && fileOps.exists(root, target)) fileOps.delete(root, target)
            cleanupCreateOwnership(root, staged)
        }.exceptionOrNull()
        if (cleanupFailure == null) abortMutation(staged, failure) else failure.addSuppressed(cleanupFailure)
    }

    private suspend fun prepareCreateOwnership(
        root: java.nio.file.Path,
        staged: StagedWorkspaceFileMutation?,
    ) {
        if (staged == null) return
        try {
            fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
            fileOps.createDirectory(root, createOwnershipRelative(staged))
        } catch (failure: Throwable) {
            runCatching { mutationJournal?.abort(staged) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private suspend fun completeCreatedMutation(
        root: java.nio.file.Path,
        staged: StagedWorkspaceFileMutation?,
    ) {
        if (staged == null) return
        cleanupCreateOwnership(root, staged)
        completeMutation(staged)
    }

    private fun cleanupCreateOwnership(root: java.nio.file.Path, staged: StagedWorkspaceFileMutation?) {
        if (staged == null) return
        val ownership = createOwnershipRelative(staged)
        if (fileOps.exists(root, ownership)) fileOps.delete(root, ownership)
    }

    private fun createOwnershipRelative(staged: StagedWorkspaceFileMutation): List<String> =
        listOf(CREATE_OWNERSHIP_DIRECTORY, staged.operationId)

    private fun requirePhysicalTargetAbsent(
        root: java.nio.file.Path,
        target: List<String>,
        displayPath: String,
    ) {
        if (fileOps.exists(root, target)) throw WorkspaceLifecycleException(
            WorkspaceLifecycleErrorCode.TARGET_COLLISION,
            "目标路径已存在，未执行文件操作: $displayPath",
        )
    }

    private suspend fun requireMoveTargetAbsent(entry: FileEntry, targetPath: String) {
        val databaseTarget = dao.getActiveByMaterializedPath(entry.workspaceRootUuid, targetPath)
        if (databaseTarget != null && databaseTarget.uuid != entry.uuid) throw WorkspaceLifecycleException(
            WorkspaceLifecycleErrorCode.TARGET_COLLISION,
            "目标路径已被占用，未执行移动。",
        )
        val root = File(entry.physicalRootPath).toPath()
        val target = relative(targetPath)
        if (fileOps.exists(root, target) && normalizeMaterializedPath(entry.materializedPath) != normalizeMaterializedPath(targetPath)) {
            throw WorkspaceLifecycleException(
                WorkspaceLifecycleErrorCode.TARGET_COLLISION,
                "目标物理路径已存在，未执行移动。",
            )
        }
    }

    private suspend fun rollbackPhysicalOrPreserveJournal(
        rollback: WorkspaceFileRollback,
        staged: StagedWorkspaceFileMutation?,
        failure: Throwable,
    ) {
        val rollbackFailure = runCatching { rollback.rollback() }.exceptionOrNull()
        if (rollbackFailure == null) {
            abortMutation(staged, failure)
        } else {
            failure.addSuppressed(rollbackFailure)
        }
    }

    private fun journalRelative(materializedPath: String): String =
        relative(materializedPath).joinToString("/").takeIf(String::isNotBlank)
            ?: throw SecurityException("工作区 journal 不允许根路径")

    private fun relative(materializedPath: String): List<String> =
        materializedPath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }

    private fun validateMaterializedPath(root: FileEntry, path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || '\u0000' in trimmed) throw SecurityException("工作区路径无效")
        val rootPath = File(root.physicalRootPath).canonicalFile.path
        if (trimmed == rootPath || trimmed.startsWith(rootPath + File.separator) ||
            Regex("^[A-Za-z]:[\\\\/].*").matches(trimmed) || trimmed.startsWith("\\\\")) {
            throw SecurityException("禁止使用物理绝对路径")
        }
        val segments = trimmed.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) throw SecurityException("工作区路径越界: $path")
        if (segments.any { it.startsWith(".nexara", ignoreCase = true) } ||
            segments.firstOrNull() == ".recycle_bin") {
            throw SecurityException("工作区路径属于系统保留目录")
        }
        return "/" + segments.joinToString("/")
    }

    private fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name ||
            name.startsWith(".nexara", ignoreCase = true) || name == ".recycle_bin") {
            throw SecurityException("工作区名称无效")
        }
    }

    private fun nextMutationEpoch(clockValue: Long, previousEpoch: Long): Long {
        check(previousEpoch != Long.MAX_VALUE) { "文件修改时间已达上限" }
        return maxOf(clockValue, previousEpoch + 1L)
    }

    private suspend fun reconcileEntryTree(entry: FileEntry) {
        val files = if (entry.isDirectory) {
            dao.getSubtree(entry.workspaceRootUuid, entry.materializedPath, entry.inRecycleBin)
                .filterNot { it.isDirectory }
        } else listOf(entry)
        val root = File(entry.physicalRootPath).toPath()
        files.forEach { file ->
            fileOps.reconcileFile(root, relative(file.materializedPath), file.hash)
        }
    }

    private fun normalizeMaterializedPath(path: String): String = "/" + path.replace('\\', '/').trim('/')
    private fun joinMaterializedPath(parentPath: String, name: String): String =
        normalizeMaterializedPath(parentPath).trimEnd('/') + "/" + name

}

internal fun workspaceVersionDeletionToken(fileUuid: String): String =
    Sha256Utils.hash("workspace-version:$fileUuid").take(24)

internal fun workspaceVersionSnapshotDirectory(workspaceRootUuid: String, fileUuid: String): List<String> =
    listOf(".nexara_versions", workspaceRootUuid, fileUuid)

internal fun workspaceDeletionToken(fileUuid: String): String = Sha256Utils.hash(fileUuid).take(24)

private fun creationRollbackKey(workspaceRootUuid: String, fileUuid: String): String =
    "$workspaceRootUuid\u0000$fileUuid"
