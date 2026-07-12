package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.WorkspaceSeqDao
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.io.OutputStream

class WorkspaceRepository(
    private val dao: FileEntryDao,
    private val seqDao: WorkspaceSeqDao,
    private val defaultWorkspaceParent: File? = null,
    private val fileOps: WorkspaceFileOps = SecureWorkspaceFileOps(),
    private val insertCommitter: (suspend (FileEntry) -> Unit)? = null,
    private val updateCommitter: (suspend (List<FileEntry>) -> Unit)? = null,
    private val deleteCommitter: (suspend (String, List<String>) -> Unit)? = null,
    private val onDeleteCommitted: (List<String>) -> Unit = {},
    private val bulkDeleteSnapshotHook: suspend () -> Unit = {},
) : IWorkspaceRepository {
    @Volatile
    private var defaultParentIdentity: String? = null

    override suspend fun ensureSessionRoot(sessionId: String): FileEntry = withContext(Dispatchers.IO) {
        val session = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        val path = session.workspacePath?.takeIf { it.isNotBlank() }
            ?: session.workspaceRootUuid?.let { rootUuid ->
                dao.getByUuid(rootUuid, rootUuid)?.physicalRootPath
            }
            ?: defaultWorkspaceParent?.let { parent ->
                File(parent, Sha256Utils.hash(sessionId)).path
            }
            ?: throw IllegalStateException("Session workspace path is missing: $sessionId")
        ensureSessionRootInternal(sessionId, path)
    }

    override suspend fun ensureSessionRoot(
        sessionId: String,
        physicalRootPath: String,
    ): FileEntry = withContext(Dispatchers.IO) {
        ensureSessionRootInternal(sessionId, physicalRootPath)
    }

    private suspend fun ensureSessionRootInternal(sessionId: String, physicalRootPath: String): FileEntry {
        val physicalRoot = File(physicalRootPath.trim()).canonicalFile
        val before = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        val beforeRoot = before.workspaceRootUuid?.let { dao.getByUuid(it, it) }
        if (beforeRoot != null && File(beforeRoot.physicalRootPath).canonicalFile != physicalRoot) {
            throw SecurityException("Session workspace root path cannot be changed")
        }
        val action: suspend () -> FileEntry = action@{
        val session = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        if (dao.countOtherSessionsForPhysicalRoot(physicalRoot.path, sessionId) != 0) {
            throw SecurityException("工作区物理根已被其他 Session 使用")
        }
        val existingRoot = session.workspaceRootUuid?.let { rootUuid -> dao.getByUuid(rootUuid, rootUuid) }
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
            fileOps.ensureRoot(
                physicalRoot.toPath(),
                initializeIdentity = session.workspaceRootUuid == null,
                expectedIdentity = existingRoot?.hash,
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
            val existing = dao.getByUuid(existingUuid, existingUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
            if (File(existing.physicalRootPath).canonicalFile != physicalRoot) {
                throw SecurityException("Session workspace root path cannot be changed")
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
        return if (beforeRoot != null) {
            WorkspaceMutationCoordinator.withBoundRoot(physicalRoot.toPath(), beforeRoot.hash, action)
        } else {
            WorkspaceMutationCoordinator.withRoot(physicalRoot.toPath(), action)
        }
    }

    override fun observeRoots(workspaceRootUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.observeRoots(workspaceRootUuid)

    override fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank() || parentUuid.isBlank()) emptyFlow()
        else dao.observeChildren(workspaceRootUuid, parentUuid)

    override fun observeRecycleBin(workspaceRootUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.observeRecycleBin(workspaceRootUuid)

    override suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry? =
        if (workspaceRootUuid.isBlank() || uuid.isBlank()) null else dao.getByUuid(workspaceRootUuid, uuid)

    override suspend fun getByMaterializedPath(
        workspaceRootUuid: String,
        materializedPath: String,
    ): FileEntry? = withContext(Dispatchers.IO) {
        val root = requireRoot(workspaceRootUuid)
        dao.getByMaterializedPath(workspaceRootUuid, validateMaterializedPath(root, materializedPath))
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
        if (dao.getByRootAndMaterializedPath(workspaceRootUuid, normalizedPath) != null) {
            throw IllegalStateException("工作区文件已存在: $normalizedPath")
        }
        val rootPath = File(root.physicalRootPath).toPath()
        val relative = relative(normalizedPath)
        fileOps.createFile(rootPath, relative, content.toByteArray(Charsets.UTF_8))
        val now = System.currentTimeMillis()
        FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = parentUuid,
            name = name,
            hash = Sha256Utils.hash(content),
            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            physicalRootPath = root.physicalRootPath,
            materializedPath = normalizedPath,
            createdAt = now,
            updatedAt = now,
        ).also { entry ->
            try {
                (insertCommitter ?: dao::insertAbort).invoke(entry)
            } catch (failure: Throwable) {
                runCatching { fileOps.delete(rootPath, relative) }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
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
        if (dao.getByRootAndMaterializedPath(workspaceRootUuid, normalizedPath) != null) {
            throw IllegalStateException("工作区文件已存在: $normalizedPath")
        }
        val rootPath = File(root.physicalRootPath).toPath()
        val relative = relative(normalizedPath)
        val writeResult = fileOps.createFileStreaming(rootPath, relative, maxBytes, writer)
        val now = System.currentTimeMillis()
        FileEntry(
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
        ).also { entry ->
            try {
                (insertCommitter ?: dao::insertAbort).invoke(entry)
            } catch (failure: Throwable) {
                runCatching { fileOps.delete(rootPath, relative) }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
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
        if (dao.getByRootAndMaterializedPath(workspaceRootUuid, normalizedPath) != null) {
            throw IllegalStateException("工作区目录已存在: $normalizedPath")
        }
        val rootPath = File(root.physicalRootPath).toPath()
        val relative = relative(normalizedPath)
        fileOps.createDirectory(rootPath, relative)
        val now = System.currentTimeMillis()
        FileEntry(
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
        ).also { entry ->
            try {
                (insertCommitter ?: dao::insertAbort).invoke(entry)
            } catch (failure: Throwable) {
                runCatching { fileOps.delete(rootPath, relative) }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    } }

    override suspend fun moveToRecycleBin(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) {
        val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withRootMutation
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
        commitMove(entry, originalPath, recyclePath, updates)
    } }

    override suspend fun restoreFromRecycleBin(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) {
        val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withRootMutation
        if (!entry.inRecycleBin) return@withRootMutation
        val originalPath = entry.originalMaterializedPath ?: return@withRootMutation
        val originalParent = entry.originalParentUuid
        if (originalParent != null && dao.getByUuid(workspaceRootUuid, originalParent) == null) {
            throw SecurityException("原父目录不属于当前工作区")
        }
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
            dao.getSubtree(workspaceRootUuid, entry.materializedPath, true)
                .filterNot { it.uuid == uuid }
                .forEach { child ->
                    val childOriginal = child.originalMaterializedPath ?: return@forEach
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
        commitMove(entry, entry.materializedPath, originalPath, updates)
    } }

    override suspend fun permanentDelete(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) {
        permanentDeleteLocked(workspaceRootUuid, uuid)
    } }

    private suspend fun permanentDeleteLocked(workspaceRootUuid: String, uuid: String) {
        val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return
        if (entry.uuid == workspaceRootUuid) throw SecurityException("工作区根目录不可删除")
        val children = if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, entry.materializedPath, entry.inRecycleBin)
                .filterNot { it.uuid == uuid }
                .sortedByDescending { it.materializedPath.length }
        } else emptyList()
        reconcileEntryTree(entry)
        val root = File(entry.physicalRootPath).toPath()
        val staged = fileOps.stageDelete(
            root,
            relative(entry.materializedPath),
            workspaceDeletionToken(entry.uuid),
        )
        val ids = children.map { it.uuid } + uuid
        try {
            requireNotNull(deleteCommitter) {
                "永久删除必须配置包含派生数据清理的事务提交器"
            }.invoke(workspaceRootUuid, ids)
        } catch (failure: Throwable) {
            runCatching { staged.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        try {
            onDeleteCommitted(ids)
        } catch (_: Exception) {
            // 数据库与物理删除已提交；运行时队列通知失败不能反转持久状态。
        }
        // Tombstone 清理失败时保留在受控目录，后续维护清理可幂等重试。
        runCatching { staged.commit() }
        Unit
    }

    override suspend fun emptyRecycleBin(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid) {
            val recycleRoot = dao.getByRootAndMaterializedPath(workspaceRootUuid, "/.recycle_bin")
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
        val restorePaths = dao.getAllByWorkspaceRoot(root.uuid).associate { entry ->
            workspaceDeletionToken(entry.uuid) to relative(entry.materializedPath)
        }
        return fileOps.recoverTombstones(File(root.physicalRootPath).toPath(), restorePaths::get).also { report ->
            if (report.attentionTokens.isNotEmpty()) {
                NexaraLogger.log("[WorkspaceDeleteRecovery] root=${root.uuid} attention=${report.attentionTokens.size}")
            }
        }
    }

    override suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String) =
        withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) {
            val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withRootMutation
            val parent = dao.getByUuid(workspaceRootUuid, newParentUuid)
                ?: throw SecurityException("目标父目录不属于当前工作区")
            if (!parent.isDirectory || parent.inRecycleBin) throw SecurityException("目标父节点不可用")
            if (entry.uuid == workspaceRootUuid || newParentUuid == uuid ||
                parent.materializedPath.startsWith(entry.materializedPath.trimEnd('/') + "/")) {
                throw SecurityException("不允许形成循环目录关系")
            }
            val newPath = joinMaterializedPath(parent.materializedPath, entry.name)
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
            commitMove(entry, oldPrefix, newPath, updates)
        } }

    override suspend fun rename(workspaceRootUuid: String, uuid: String, newName: String) =
        withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) {
            validateName(newName)
            val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withRootMutation
            if (entry.uuid == workspaceRootUuid) throw SecurityException("工作区根目录不可重命名")
            val parent = entry.parentUuid?.let { dao.getByUuid(workspaceRootUuid, it) }
                ?: throw SecurityException("父目录不属于当前工作区")
            val newPath = joinMaterializedPath(parent.materializedPath, newName)
            if (dao.getByRootAndMaterializedPath(workspaceRootUuid, newPath) != null) {
                throw IllegalStateException("目标名称已存在")
            }
            val now = System.currentTimeMillis()
            val oldPrefix = entry.materializedPath
            val updates = mutableListOf(entry.copy(name = newName, materializedPath = newPath, updatedAt = now))
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
            commitMove(entry, oldPrefix, newPath, updates)
        } }

    override suspend fun getNextSeqForDate(dateKey: String): Int = seqDao.getNextSeqForDate(dateKey)

    override suspend fun resetAllRAGStatus(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        requireRoot(workspaceRootUuid)
        dao.resetAllRAGStatus(workspaceRootUuid)
    }

    private suspend fun requireRoot(workspaceRootUuid: String): FileEntry {
        if (workspaceRootUuid.isBlank()) throw SecurityException("workspaceRootUuid 不能为空")
        return dao.getByUuid(workspaceRootUuid, workspaceRootUuid)
            ?.takeIf { it.isDirectory && it.parentUuid == null && it.workspaceRootUuid == workspaceRootUuid }
            ?: throw SecurityException("工作区根不存在或无效")
    }

    private suspend fun <T> withRootMutation(
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

    private suspend fun requireParent(root: FileEntry, parentUuid: String?): FileEntry {
        val parentId = parentUuid ?: throw SecurityException("非根节点必须指定父目录")
        val parent = dao.getByUuid(root.uuid, parentId)
            ?: throw SecurityException("父目录不属于当前工作区")
        if (!parent.isDirectory || parent.inRecycleBin) throw SecurityException("父节点不是可用目录")
        return parent
    }

    private suspend fun resolveOrCreateRecycleBinDir(entry: FileEntry): String {
        dao.getByRootAndMaterializedPath(entry.workspaceRootUuid, "/.recycle_bin")?.let { return it.uuid }
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val root = File(entry.physicalRootPath).toPath()
        fileOps.ensureDirectory(root, listOf(".recycle_bin"))
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
        try {
            (insertCommitter ?: dao::insertAbort).invoke(recycleEntry)
        } catch (failure: Throwable) {
            // 并发赢家存在时复用；其它失败保留系统目录但不继续业务移动。
            dao.getByRootAndMaterializedPath(entry.workspaceRootUuid, "/.recycle_bin")?.let { return it.uuid }
            throw failure
        }
        return uuid
    }

    private suspend fun commitMove(
        entry: FileEntry,
        sourcePath: String,
        targetPath: String,
        updates: List<FileEntry>,
    ) {
        reconcileEntryTree(entry)
        val rollback = fileOps.move(
            File(entry.physicalRootPath).toPath(),
            relative(sourcePath),
            relative(targetPath),
        )
        try {
            (updateCommitter ?: dao::updateAll).invoke(updates)
            rollback.commit()
        } catch (failure: Throwable) {
            runCatching { rollback.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

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

internal fun workspaceDeletionToken(fileUuid: String): String = Sha256Utils.hash(fileUuid).take(24)
