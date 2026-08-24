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
import com.promenar.nexara.data.session.SessionExecutionGate

class FileOperationRepository(
    private val dao: FileEntryDao,
    private val versionDao: FileVersionDao,
    private val fileOps: WorkspaceFileOps = SecureWorkspaceFileOps(),
    private val versionCommitter: (suspend (FileVersionEntity, FileEntry) -> Unit)? = null,
    private val indexEventSink: FileIndexEventSink = FileIndexEventSink.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executionGate: SessionExecutionGate? = null,
    private val textPolicy: WorkspaceTextContentPolicy = WorkspaceTextContentPolicy(),
) : IFileOperationRepository {

    override suspend fun writeFileAtomic(
        workspaceRootUuid: String,
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String,
    ): WriteResult = withSessionWorkspaceAdmission(sessionId, workspaceRootUuid) {
        withContext(Dispatchers.IO) {
        val initial = dao.getActiveByUuid(workspaceRootUuid, uuid)
            ?: return@withContext WriteResult.NotFound
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withBoundRoot(File(initial.physicalRootPath).toPath(), root.hash) {
            val entry = dao.getActiveByUuid(workspaceRootUuid, uuid)
                ?: return@withBoundRoot WriteResult.NotFound
            if (entry.isDirectory) return@withBoundRoot WriteResult.NotFound
            val validated = textPolicy.validate(
                entry.name,
                entry.mimeType,
                newContent.toByteArray(Charsets.UTF_8),
                WorkspaceTextOperation.WRITE,
            )
            if (entry.hash != expectedHash) {
                return@withBoundRoot WriteResult.Conflict(
                    currentHash = entry.hash,
                    expectedHash = expectedHash,
                    message = "文件已被其他会话修改。请先读取最新版本再重试。",
                )
            }
            val committed = commitContentChange(entry, validated.text, sessionId)
            val indexTarget = committed ?: entry.currentIndexTargetIfStale()
            val indexQueued = indexTarget == null || publishIndexEvent(workspaceRootUuid, uuid, indexTarget)
            WriteResult.Success(
                newHash = committed?.contentHash ?: entry.hash,
                indexQueued = indexQueued,
                targetEpoch = committed?.targetEpoch ?: entry.updatedAt,
            )
        }
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
            val allLines = readContent(entry, WorkspaceTextOperation.READ)
                .lines().let { if (it == listOf("")) emptyList() else it }
            val total = allLines.size
            val start = startLine?.coerceIn(1, total.coerceAtLeast(1)) ?: 1
            val end = endLine?.coerceIn(1, total.coerceAtLeast(1)) ?: total
            val content = if (total == 0) "" else allLines.subList(start - 1, end).joinToString("\n")
            textPolicy.validateOutput(content, WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET)
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
            val currentContent = readContent(entry, WorkspaceTextOperation.DIFF)
            val basisContent = when {
                basisHash == null || basisHash == entry.hash -> currentContent
                else -> readVersionContent(entry, basisHash)
            }
            val effectiveBasisHash = basisHash ?: entry.hash
            val hunks = MyersDiff.computeHunks(basisContent.lines(), currentContent.lines())
            textPolicy.validateOutput(
                hunks.asSequence().flatMap { it.lines.asSequence() }.joinToString("\n") { it.content },
                WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET,
            )
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
    ): PatchResult = withWorkspaceAdmission(workspaceRootUuid) {
        withContext(Dispatchers.IO) {
        val initial = dao.getActiveByUuid(workspaceRootUuid, uuid)
            ?: return@withContext fileFailure("FILE_NOT_FOUND", "文件不存在: $uuid", uuid)
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withBoundRoot(File(initial.physicalRootPath).toPath(), root.hash) {
            val entry = dao.getActiveByUuid(workspaceRootUuid, uuid)
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

            val currentContent = try {
                readContent(entry, WorkspaceTextOperation.PATCH)
            } catch (failure: WorkspaceTextPolicyException) {
                return@withBoundRoot policyFailure(failure, uuid)
            }
            val lines = currentContent.lines().let { if (it == listOf("")) emptyList() else it }.toMutableList()
            val originalTotal = lines.size
            validatePatchOperations(operations, lines, uuid)?.let { return@withBoundRoot it }
            operations.withIndex().sortedByDescending { (_, operation) ->
                operation.startLine ?: operation.afterLine ?: -1
            }.forEach { (_, operation) ->
                when (operation.action) {
                    "replace_lines" -> {
                        val start = requireNotNull(operation.startLine)
                        val end = requireNotNull(operation.endLine)
                        lines.subList(start - 1, end).clear()
                        lines.addAll(start - 1, contentLines(operation.newContent.orEmpty()))
                    }
                    "insert_after" -> lines.addAll(
                        requireNotNull(operation.afterLine),
                        contentLines(operation.newContent.orEmpty()),
                    )
                    "delete_lines" -> lines.subList(
                        requireNotNull(operation.startLine) - 1,
                        requireNotNull(operation.endLine),
                    ).clear()
                }
            }

            val newContent = lines.joinToString("\n")
            try {
                textPolicy.validate(
                    entry.name,
                    entry.mimeType,
                    newContent.toByteArray(Charsets.UTF_8),
                    WorkspaceTextOperation.PATCH,
                )
            } catch (failure: WorkspaceTextPolicyException) {
                return@withBoundRoot policyFailure(failure, uuid)
            }
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
    }

    private suspend fun <T> withSessionWorkspaceAdmission(
        sessionId: String,
        workspaceRootUuid: String,
        block: suspend () -> T,
    ): T {
        val gate = executionGate
        return if (gate == null) block()
        else gate.withSessionWorkspaceAdmission(sessionId, workspaceRootUuid, block)
    }

    private suspend fun <T> withWorkspaceAdmission(
        workspaceRootUuid: String,
        block: suspend () -> T,
    ): T {
        val gate = executionGate
        return if (gate == null) block() else gate.withWorkspaceAdmission(workspaceRootUuid, block)
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
        val old = readRawText(entry, WorkspaceTextOperation.WRITE)
        val oldContent = old.text
        val physicalHash = Sha256Utils.hash(old.bytes)
        if (physicalHash != entry.hash) {
            throw IllegalStateException("文件内容与数据库哈希不一致，已拒绝覆盖: ${entry.uuid}")
        }
        val newBytes = newContent.toByteArray(Charsets.UTF_8)
        val newHash = Sha256Utils.hash(newBytes)
        if (newHash == entry.hash && old.bytes.contentEquals(newBytes)) return null
        val targetEpoch = nextMutationEpoch(entry.updatedAt)

        val versionId = UUID.randomUUID().toString()
        val snapshotRelative = snapshotRelative(entry, versionId)
        ensureDirectories(root, snapshotRelative.dropLast(1))
        fileOps.createFile(root, snapshotRelative, old.bytes)
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
            sizeBytes = newBytes.size.toLong(),
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
                newBytes,
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
        if (rawSnapshotPath.toFile().length() > WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxInputBytes) {
            throw WorkspaceTextPolicyException(
                WorkspaceTextErrorCode.INPUT_TOO_LARGE,
                "历史版本超过差异操作上限，请缩小文件后重试。",
            )
        }
        val bytes = try {
            fileOps.readLimited(
                rootPath,
                relative,
                WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxInputBytes,
            )
        } catch (_: WorkspaceFileTooLargeException) {
            throw WorkspaceTextPolicyException(
                WorkspaceTextErrorCode.INPUT_TOO_LARGE,
                "历史版本超过差异操作上限，请缩小文件后重试。",
            )
        }
        if (Sha256Utils.hash(bytes) != version.hash) {
            throw SecurityException("文件版本快照哈希校验失败")
        }
        return textPolicy.validate(
            entry.name,
            entry.mimeType,
            bytes,
            WorkspaceTextOperation.DIFF,
        ).text
    }

    private fun snapshotRelative(entry: FileEntry, versionId: String): List<String> =
        listOf(".nexara_versions", entry.workspaceRootUuid, entry.uuid, "$versionId.snapshot")

    private fun ensureDirectories(root: java.nio.file.Path, directories: List<String>) {
        directories.indices.forEach { index -> fileOps.ensureDirectory(root, directories.take(index + 1)) }
    }

    private data class RawText(val bytes: ByteArray, val text: String)

    private fun readContent(entry: FileEntry, operation: WorkspaceTextOperation): String =
        readRawText(entry, operation).text

    private fun readRawText(entry: FileEntry, operation: WorkspaceTextOperation): RawText {
        textPolicy.requireSupportedType(entry.name, entry.mimeType)
        val budget = WorkspaceTextContentPolicy.budgetFor(operation)
        if (entry.sizeBytes > budget.maxInputBytes) {
            throw WorkspaceTextPolicyException(
                WorkspaceTextErrorCode.INPUT_TOO_LARGE,
                "文件超过本次操作的读取上限，请缩小读取范围或使用专用文件处理能力。",
            )
        }
        val root = File(entry.physicalRootPath).toPath()
        val relative = relative(entry.materializedPath)
        fileOps.reconcileFile(root, relative, entry.hash)
        val bytes = try {
            fileOps.readLimited(root, relative, budget.maxInputBytes)
        } catch (_: WorkspaceFileTooLargeException) {
            throw WorkspaceTextPolicyException(
                WorkspaceTextErrorCode.INPUT_TOO_LARGE,
                "文件超过本次操作的读取上限，请缩小读取范围或使用专用文件处理能力。",
            )
        }
        return RawText(bytes, textPolicy.validate(entry.name, entry.mimeType, bytes, operation, budget).text)
    }

    private fun relative(materializedPath: String): List<String> =
        materializedPath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }

    private suspend fun requireFile(workspaceRootUuid: String, uuid: String): FileEntry =
        bindRoot(workspaceRootUuid).let {
            dao.getActiveByUuid(workspaceRootUuid, uuid)?.takeUnless { it.isDirectory }
        }
            ?: throw NoSuchElementException("File not found: $uuid")

    private suspend fun bindRoot(workspaceRootUuid: String): FileEntry {
        val root = dao.getActiveByUuid(workspaceRootUuid, workspaceRootUuid)
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

    private fun policyFailure(failure: WorkspaceTextPolicyException, uuid: String) =
        PatchResult.Failure(PatchError(
            code = failure.code.name,
            message = failure.safeMessage,
            operationIndex = -1,
            fileUuid = uuid,
            suggestion = "请改用受支持的文本文件，并缩小操作范围。",
        ))

    private fun validatePatchOperations(
        operations: List<PatchOperation>,
        lines: List<String>,
        uuid: String,
    ): PatchResult.Failure? {
        data class ClaimedRange(val start: Int, val end: Int)
        val claimed = mutableListOf<ClaimedRange>()
        val insertions = mutableSetOf<Int>()
        operations.forEachIndexed { index, operation ->
            when (operation.action) {
                "replace_lines", "delete_lines" -> {
                    val start = operation.startLine
                    val end = operation.endLine
                    if (start == null || end == null || start < 1 || end < start || end > lines.size) {
                        return rangeFailure(operation, index, uuid, lines.size)
                    }
                    val range = ClaimedRange(start, end)
                    if (claimed.any { range.start <= it.end && it.start <= range.end }) {
                        return PatchResult.Failure(PatchError(
                            "OVERLAPPING_OPERATIONS",
                            "patch 操作坐标重叠，已拒绝整批修改。",
                            index,
                            uuid,
                            lines.size,
                            "请合并重叠操作并基于同一原始文件坐标重试。",
                        ))
                    }
                    claimed += range
                    operation.expectedContent?.let { expected ->
                        val actual = lines.subList(start - 1, end).joinToString("\n")
                        if (actual != expected) {
                            return PatchResult.Failure(PatchError(
                                "CONTEXT_MISMATCH",
                                "patch 上下文与当前文件不一致，已拒绝修改。",
                                index,
                                uuid,
                                lines.size,
                                "请重新读取文件后生成补丁。",
                            ))
                        }
                    }
                }
                "insert_after" -> {
                    val after = operation.afterLine
                    if (after == null || after < 0 || after > lines.size) {
                        return rangeFailure(operation, index, uuid, lines.size)
                    }
                    if (!insertions.add(after)) {
                        return PatchResult.Failure(PatchError(
                            "OVERLAPPING_OPERATIONS",
                            "同一坐标存在多个插入操作，已拒绝整批修改。",
                            index,
                            uuid,
                            lines.size,
                        ))
                    }
                }
                else -> return PatchResult.Failure(PatchError(
                    code = "INVALID_ACTION",
                    message = "未知的 patch 操作类型: ${operation.action}",
                    operationIndex = index,
                    fileUuid = uuid,
                ))
            }
        }
        return null
    }

    private fun contentLines(content: String): List<String> =
        if (content.isEmpty()) emptyList() else content.lines()

}
