package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.FileVersionDao
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.FileVersionEntity
import com.promenar.nexara.domain.repository.DiffHunk
import com.promenar.nexara.domain.repository.DiffLine
import com.promenar.nexara.domain.repository.DiffResult
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.PatchError
import com.promenar.nexara.domain.repository.PatchOperation
import com.promenar.nexara.domain.repository.PatchResult
import com.promenar.nexara.domain.repository.ReadResult
import com.promenar.nexara.domain.repository.WriteResult
import com.promenar.nexara.infra.util.MyersDiff
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.data.rag.FileIndexEvent
import com.promenar.nexara.data.rag.FileIndexEventSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class FileOperationRepository(
    private val dao: FileEntryDao,
    private val versionDao: FileVersionDao,
    private val fileOps: WorkspaceFileOps = SecureWorkspaceFileOps(),
    private val versionCommitter: (suspend (FileVersionEntity, FileEntry) -> Unit)? = null,
    private val indexEventSink: FileIndexEventSink = FileIndexEventSink.None,
    private val clock: () -> Long = System::currentTimeMillis,
) : IFileOperationRepository {

    override suspend fun writeFileAtomic(
        workspaceRootUuid: String,
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String,
    ): WriteResult = withContext(Dispatchers.IO) {
        val initial = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withContext WriteResult.NotFound
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withBoundRoot(File(initial.physicalRootPath).toPath(), root.hash) {
            val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withBoundRoot WriteResult.NotFound
            if (entry.isDirectory) return@withBoundRoot WriteResult.NotFound
            if (entry.hash != expectedHash) {
                return@withBoundRoot WriteResult.Conflict(
                    currentHash = entry.hash,
                    expectedHash = expectedHash,
                    message = "文件已被其他会话修改。请先读取最新版本再重试。",
                )
            }
            val committed = commitContentChange(entry, newContent, sessionId)
            val indexTarget = committed ?: entry.currentIndexTargetIfStale()
            val indexQueued = indexTarget == null || publishIndexEvent(workspaceRootUuid, uuid, indexTarget)
            WriteResult.Success(
                newHash = committed?.contentHash ?: entry.hash,
                indexQueued = indexQueued,
                targetEpoch = committed?.targetEpoch ?: entry.updatedAt,
            )
        }
    }

    override suspend fun readFileRange(
        workspaceRootUuid: String,
        uuid: String,
        startLine: Int?,
        endLine: Int?,
    ): ReadResult = withContext(Dispatchers.IO) {
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withBoundRoot(File(root.physicalRootPath).toPath(), root.hash) {
            val entry = requireFile(workspaceRootUuid, uuid)
            val allLines = readContent(entry).lines().let { if (it == listOf("")) emptyList() else it }
            val total = allLines.size
            val start = startLine?.coerceIn(1, total.coerceAtLeast(1)) ?: 1
            val end = endLine?.coerceIn(1, total.coerceAtLeast(1)) ?: total
            val content = if (total == 0) "" else allLines.subList(start - 1, end).joinToString("\n")
            ReadResult(entry.uuid, entry.name, total, start, end, content, entry.hash, entry.updatedAt)
        }
    }

    override suspend fun diffFile(
        workspaceRootUuid: String,
        uuid: String,
        basisHash: String?,
    ): DiffResult = withContext(Dispatchers.IO) {
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withBoundRoot(File(root.physicalRootPath).toPath(), root.hash) {
            val entry = requireFile(workspaceRootUuid, uuid)
            val currentContent = readContent(entry)
            val basisContent = when {
                basisHash == null || basisHash == entry.hash -> currentContent
                else -> readVersionContent(entry, basisHash)
            }
            val effectiveBasisHash = basisHash ?: entry.hash
            val hunks = MyersDiff.computeHunks(basisContent.lines(), currentContent.lines())
            DiffResult(
            uuid = entry.uuid,
            basisHash = effectiveBasisHash,
            currentHash = entry.hash,
            hunks = hunks.map { hunk ->
                DiffHunk(
                    oldStart = hunk.oldStart,
                    oldCount = hunk.oldCount,
                    newStart = hunk.newStart,
                    newCount = hunk.newCount,
                    lines = hunk.lines.map { DiffLine(it.type, it.content) },
                )
            },
            )
        }
    }

    override suspend fun patchFile(
        workspaceRootUuid: String,
        uuid: String,
        operations: List<PatchOperation>,
        expectedHash: String,
    ): PatchResult = withContext(Dispatchers.IO) {
        val initial = dao.getByUuid(workspaceRootUuid, uuid)
            ?: return@withContext fileFailure("FILE_NOT_FOUND", "文件不存在: $uuid", uuid)
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withBoundRoot(File(initial.physicalRootPath).toPath(), root.hash) {
            val entry = dao.getByUuid(workspaceRootUuid, uuid)
                ?: return@withBoundRoot fileFailure("FILE_NOT_FOUND", "文件不存在: $uuid", uuid)
            if (entry.isDirectory) return@withBoundRoot fileFailure("FILE_NOT_FOUND", "文件不存在: $uuid", uuid)
            if (entry.hash != expectedHash) {
                return@withBoundRoot PatchResult.Failure(PatchError(
                    code = "HASH_MISMATCH",
                    message = "文件已被其他会话修改。请先获取最新差异再重试。",
                    operationIndex = -1,
                    fileUuid = uuid,
                    suggestion = "请先调用 diff_file 获取最新差异。",
                ))
            }

            val currentContent = readContent(entry)
            val lines = currentContent.lines().toMutableList()
            val originalTotal = lines.size
            var lineOffset = 0

            operations.forEachIndexed { index, operation ->
                when (operation.action) {
                    "replace_lines" -> {
                        val start = (operation.startLine ?: 0) + lineOffset
                        val end = (operation.endLine ?: 0) + lineOffset
                        if (start < 1 || end < start || start > lines.size) {
                            return@withBoundRoot rangeFailure(operation, index, uuid, originalTotal)
                        }
                        val replacement = (operation.newContent ?: "").lines()
                        val count = end - start + 1
                        lines.subList(start - 1, (start - 1 + count).coerceAtMost(lines.size)).clear()
                        lines.addAll(start - 1, replacement)
                        lineOffset += replacement.size - count
                    }
                    "insert_after" -> {
                        val after = operation.afterLine ?: -1
                        val indexInCurrent = after + lineOffset
                        if (after < 0 || indexInCurrent > lines.size) {
                            return@withBoundRoot rangeFailure(operation, index, uuid, originalTotal)
                        }
                        val insertion = (operation.newContent ?: "").lines()
                        lines.addAll(indexInCurrent, insertion)
                        lineOffset += insertion.size
                    }
                    "delete_lines" -> {
                        val start = (operation.startLine ?: 0) + lineOffset
                        val end = (operation.endLine ?: 0) + lineOffset
                        if (start < 1 || end < start || start > lines.size) {
                            return@withBoundRoot rangeFailure(operation, index, uuid, originalTotal)
                        }
                        val count = end - start + 1
                        lines.subList(start - 1, (start - 1 + count).coerceAtMost(lines.size)).clear()
                        lineOffset -= count
                    }
                    else -> return@withBoundRoot PatchResult.Failure(PatchError(
                        code = "INVALID_ACTION",
                        message = "未知的 patch 操作类型: ${operation.action}",
                        operationIndex = index,
                        fileUuid = uuid,
                    ))
                }
            }

            val newContent = lines.joinToString("\n")
            val committed = commitContentChange(entry, newContent, null)
            val indexTarget = committed ?: entry.currentIndexTargetIfStale()
            val indexQueued = indexTarget == null || publishIndexEvent(workspaceRootUuid, uuid, indexTarget)
            PatchResult.Success(
                newHash = committed?.contentHash ?: entry.hash,
                appliedOperations = operations.size,
                indexQueued = indexQueued,
                targetEpoch = committed?.targetEpoch ?: entry.updatedAt,
            )
        }
    }

    private suspend fun publishIndexEvent(
        workspaceRootUuid: String,
        uuid: String,
        target: CommittedIndexTarget,
    ): Boolean =
        try {
            indexEventSink.publish(
                FileIndexEvent.Changed(workspaceRootUuid, uuid, target.contentHash, target.targetEpoch),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 文件与版本记录已经提交；普通发布失败标记为待补偿，取消必须原样传播。
            false
        }

    private suspend fun commitContentChange(
        entry: FileEntry,
        newContent: String,
        sessionId: String?,
    ): CommittedIndexTarget? {
        val root = File(entry.physicalRootPath).toPath()
        val oldContent = readContent(entry)
        val physicalHash = Sha256Utils.hash(oldContent)
        if (physicalHash != entry.hash) {
            throw IllegalStateException("文件内容与数据库哈希不一致，已拒绝覆盖: ${entry.uuid}")
        }
        val newHash = Sha256Utils.hash(newContent)
        if (newHash == entry.hash && oldContent == newContent) return null
        val targetEpoch = nextMutationEpoch(entry.updatedAt)

        val versionId = UUID.randomUUID().toString()
        val snapshotRelative = snapshotRelative(entry, versionId)
        ensureDirectories(root, snapshotRelative.dropLast(1))
        fileOps.createFile(root, snapshotRelative, oldContent.toByteArray(Charsets.UTF_8))
        val snapshot = root.resolve(snapshotRelative.joinToString(File.separator)).toFile()
        val version = FileVersionEntity(
            id = versionId,
            fileUuid = entry.uuid,
            workspaceRootUuid = entry.workspaceRootUuid,
            hash = entry.hash,
            contentPath = snapshot.absolutePath,
            createdBySessionId = sessionId,
            createdAt = System.currentTimeMillis(),
        )
        val updated = entry.copy(
            hash = newHash,
            sizeBytes = newContent.toByteArray(Charsets.UTF_8).size.toLong(),
            lastWriteSessionId = sessionId,
            vectorizedAt = null,
            kgExtractedAt = null,
            updatedAt = targetEpoch,
        )

        var replacement: WorkspaceFileRollback? = null
        var databaseCommitted = false
        try {
            val activeReplacement = fileOps.replaceFile(
                root,
                relative(entry.materializedPath),
                newContent.toByteArray(Charsets.UTF_8),
            )
            replacement = activeReplacement
            (versionCommitter ?: versionDao::commitVersionAndFile).invoke(version, updated)
            databaseCommitted = true
            // 备份清理失败不反转已经提交的 DB/物理新版本；残留隐藏备份可由维护任务清理。
            runCatching { activeReplacement.commit() }
            return CommittedIndexTarget(newHash, updated.updatedAt)
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                if (replacement != null) rollbackReplacement(replacement, failure)
                runCatching { fileOps.delete(root, snapshotRelative) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    private data class CommittedIndexTarget(
        val contentHash: String,
        val targetEpoch: Long,
    )

    private fun FileEntry.currentIndexTargetIfStale(): CommittedIndexTarget? =
        if (vectorizedAt == null || vectorizedAt < updatedAt) {
            CommittedIndexTarget(hash, updatedAt)
        } else {
            null
        }

    private fun nextMutationEpoch(previousEpoch: Long): Long {
        check(previousEpoch != Long.MAX_VALUE) { "文件修改时间已达上限" }
        return maxOf(clock(), previousEpoch + 1L)
    }

    private fun rollbackReplacement(replacement: WorkspaceFileRollback, failure: Throwable) {
        val first = runCatching { replacement.rollback() }.exceptionOrNull() ?: return
        val second = runCatching { replacement.rollback() }.exceptionOrNull()
        if (second != null) {
            second.addSuppressed(first)
            failure.addSuppressed(second)
        }
    }

    private suspend fun readVersionContent(entry: FileEntry, basisHash: String): String {
        val version = versionDao.getByHash(entry.workspaceRootUuid, entry.uuid, basisHash)
            ?: throw NoSuchElementException("File version not found: ${entry.uuid}@$basisHash")
        val rootPath = File(entry.physicalRootPath).canonicalFile.toPath()
        val expectedDir = rootPath.resolve(".nexara_versions").normalize()
        val rawSnapshotPath = File(version.contentPath).toPath().toAbsolutePath().normalize()
        if (!rawSnapshotPath.startsWith(expectedDir)) {
            throw SecurityException("文件版本快照路径无效")
        }
        val relative = rootPath.relativize(rawSnapshotPath).map { it.toString() }
        return fileOps.read(rootPath, relative).toString(Charsets.UTF_8).also { content ->
            if (Sha256Utils.hash(content) != version.hash) {
                throw SecurityException("文件版本快照哈希校验失败")
            }
        }
    }

    private fun snapshotRelative(entry: FileEntry, versionId: String): List<String> =
        listOf(".nexara_versions", entry.workspaceRootUuid, entry.uuid, "$versionId.snapshot")

    private fun ensureDirectories(root: java.nio.file.Path, directories: List<String>) {
        directories.indices.forEach { index -> fileOps.ensureDirectory(root, directories.take(index + 1)) }
    }

    private fun readContent(entry: FileEntry): String {
        val root = File(entry.physicalRootPath).toPath()
        val relative = relative(entry.materializedPath)
        fileOps.reconcileFile(root, relative, entry.hash)
        return fileOps.read(root, relative).toString(Charsets.UTF_8)
    }

    private fun relative(materializedPath: String): List<String> =
        materializedPath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }

    private suspend fun requireFile(workspaceRootUuid: String, uuid: String): FileEntry =
        bindRoot(workspaceRootUuid).let {
            dao.getByUuid(workspaceRootUuid, uuid)?.takeUnless { it.isDirectory }
        }
            ?: throw NoSuchElementException("File not found: $uuid")

    private suspend fun bindRoot(workspaceRootUuid: String): FileEntry {
        val root = dao.getByUuid(workspaceRootUuid, workspaceRootUuid)
            ?.takeIf { it.isDirectory && it.parentUuid == null }
            ?: throw SecurityException("工作区根不存在或无效")
        return root
    }


    private fun fileFailure(code: String, message: String, uuid: String) =
        PatchResult.Failure(PatchError(code, message, -1, uuid))

    private fun rangeFailure(operation: PatchOperation, index: Int, uuid: String, total: Int) =
        PatchResult.Failure(PatchError(
            code = "LINE_OUT_OF_RANGE",
            message = "操作 ${operation.action} 超出文件行数（共 $total 行）。",
            operationIndex = index,
            fileUuid = uuid,
            totalLines = total,
            suggestion = "请先读取当前文件内容后重试。",
        ))

}
