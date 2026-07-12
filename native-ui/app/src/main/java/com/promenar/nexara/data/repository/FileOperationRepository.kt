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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

class FileOperationRepository(
    private val dao: FileEntryDao,
    private val versionDao: FileVersionDao,
    private val currentFileWriter: ((File, String) -> Unit)? = null,
    private val versionCommitter: (suspend (FileVersionEntity, FileEntry) -> Unit)? = null,
) : IFileOperationRepository {
    private val mutationMutex = Mutex()

    override suspend fun writeFileAtomic(
        workspaceRootUuid: String,
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String,
    ): WriteResult = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withLock WriteResult.NotFound
            if (entry.isDirectory) return@withLock WriteResult.NotFound
            if (entry.hash != expectedHash) {
                return@withLock WriteResult.Conflict(
                    currentHash = entry.hash,
                    expectedHash = expectedHash,
                    message = "文件已被其他会话修改。请先读取最新版本再重试。",
                )
            }
            commitContentChange(entry, newContent, sessionId)
            WriteResult.Success(Sha256Utils.hash(newContent))
        }
    }

    override suspend fun readFileRange(
        workspaceRootUuid: String,
        uuid: String,
        startLine: Int?,
        endLine: Int?,
    ): ReadResult = withContext(Dispatchers.IO) {
        val entry = requireFile(workspaceRootUuid, uuid)
        val physicalFile = resolveRootedFile(entry.physicalRootPath, entry.materializedPath)
        val allLines = if (physicalFile.exists()) physicalFile.readLines() else emptyList()
        val total = allLines.size
        val start = startLine?.coerceIn(1, total.coerceAtLeast(1)) ?: 1
        val end = endLine?.coerceIn(1, total.coerceAtLeast(1)) ?: total
        val content = if (total == 0) "" else allLines.subList(start - 1, end).joinToString("\n")
        ReadResult(entry.uuid, entry.name, total, start, end, content, entry.hash, entry.updatedAt)
    }

    override suspend fun diffFile(
        workspaceRootUuid: String,
        uuid: String,
        basisHash: String?,
    ): DiffResult = withContext(Dispatchers.IO) {
        val entry = requireFile(workspaceRootUuid, uuid)
        val physicalFile = resolveRootedFile(entry.physicalRootPath, entry.materializedPath)
        val currentContent = if (physicalFile.exists()) physicalFile.readText() else ""
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

    override suspend fun patchFile(
        workspaceRootUuid: String,
        uuid: String,
        operations: List<PatchOperation>,
        expectedHash: String,
    ): PatchResult = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val entry = dao.getByUuid(workspaceRootUuid, uuid)
                ?: return@withLock fileFailure("FILE_NOT_FOUND", "文件不存在: $uuid", uuid)
            if (entry.isDirectory) return@withLock fileFailure("FILE_NOT_FOUND", "文件不存在: $uuid", uuid)
            if (entry.hash != expectedHash) {
                return@withLock PatchResult.Failure(PatchError(
                    code = "HASH_MISMATCH",
                    message = "文件已被其他会话修改。请先获取最新差异再重试。",
                    operationIndex = -1,
                    fileUuid = uuid,
                    suggestion = "请先调用 diff_file 获取最新差异。",
                ))
            }

            val physicalFile = resolveRootedFile(entry.physicalRootPath, entry.materializedPath)
            val currentContent = if (physicalFile.exists()) physicalFile.readText() else ""
            val lines = currentContent.lines().toMutableList()
            val originalTotal = lines.size
            var lineOffset = 0

            operations.forEachIndexed { index, operation ->
                when (operation.action) {
                    "replace_lines" -> {
                        val start = (operation.startLine ?: 0) + lineOffset
                        val end = (operation.endLine ?: 0) + lineOffset
                        if (start < 1 || end < start || start > lines.size) {
                            return@withLock rangeFailure(operation, index, uuid, originalTotal)
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
                            return@withLock rangeFailure(operation, index, uuid, originalTotal)
                        }
                        val insertion = (operation.newContent ?: "").lines()
                        lines.addAll(indexInCurrent, insertion)
                        lineOffset += insertion.size
                    }
                    "delete_lines" -> {
                        val start = (operation.startLine ?: 0) + lineOffset
                        val end = (operation.endLine ?: 0) + lineOffset
                        if (start < 1 || end < start || start > lines.size) {
                            return@withLock rangeFailure(operation, index, uuid, originalTotal)
                        }
                        val count = end - start + 1
                        lines.subList(start - 1, (start - 1 + count).coerceAtMost(lines.size)).clear()
                        lineOffset -= count
                    }
                    else -> return@withLock PatchResult.Failure(PatchError(
                        code = "INVALID_ACTION",
                        message = "未知的 patch 操作类型: ${operation.action}",
                        operationIndex = index,
                        fileUuid = uuid,
                    ))
                }
            }

            val newContent = lines.joinToString("\n")
            commitContentChange(entry, newContent, null)
            PatchResult.Success(Sha256Utils.hash(newContent), operations.size)
        }
    }

    private suspend fun commitContentChange(entry: FileEntry, newContent: String, sessionId: String?) {
        val physicalFile = resolveRootedFile(entry.physicalRootPath, entry.materializedPath)
        val oldContent = if (physicalFile.exists()) physicalFile.readText() else ""
        val physicalHash = Sha256Utils.hash(oldContent)
        if (physicalHash != entry.hash) {
            throw IllegalStateException("文件内容与数据库哈希不一致，已拒绝覆盖: ${entry.uuid}")
        }
        val newHash = Sha256Utils.hash(newContent)
        if (newHash == entry.hash && oldContent == newContent) return

        val versionId = UUID.randomUUID().toString()
        val snapshot = resolveSnapshotFile(entry, versionId)
        writeTextAtomically(snapshot, oldContent)
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
            updatedAt = System.currentTimeMillis(),
        )

        try {
            (currentFileWriter ?: ::writeTextAtomically).invoke(physicalFile, newContent)
            try {
                (versionCommitter ?: versionDao::commitVersionAndFile).invoke(version, updated)
            } catch (databaseFailure: Throwable) {
                restoreAfterFailure(physicalFile, oldContent, databaseFailure)
                throw databaseFailure
            }
        } catch (failure: Throwable) {
            if (runCatching { physicalFile.exists() && physicalFile.readText() != oldContent }.getOrDefault(true)) {
                restoreAfterFailure(physicalFile, oldContent, failure)
            }
            snapshot.delete()
            throw failure
        }
    }

    private fun restoreAfterFailure(file: File, oldContent: String, original: Throwable) {
        try {
            writeTextAtomically(file, oldContent)
        } catch (rollbackFailure: Throwable) {
            original.addSuppressed(rollbackFailure)
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
        var cursor = rootPath
        rootPath.relativize(rawSnapshotPath).forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw SecurityException("文件版本快照路径包含软链接")
            }
        }
        val snapshot = rawSnapshotPath.toFile().canonicalFile
        if (!snapshot.path.startsWith(expectedDir.toFile().canonicalPath + File.separator) || !snapshot.isFile) {
            throw SecurityException("文件版本快照路径无效")
        }
        return snapshot.readText()
    }

    private fun resolveSnapshotFile(entry: FileEntry, versionId: String): File {
        val snapshot = resolveRootedFile(
            entry.physicalRootPath,
            "/.nexara_versions/${entry.workspaceRootUuid}/${entry.uuid}/$versionId.snapshot",
        )
        snapshot.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) error("无法创建版本目录") }
        return snapshot
    }

    private suspend fun requireFile(workspaceRootUuid: String, uuid: String): FileEntry =
        dao.getByUuid(workspaceRootUuid, uuid)?.takeUnless { it.isDirectory }
            ?: throw NoSuchElementException("File not found: $uuid")

    private fun resolveRootedFile(rootPath: String, materializedPath: String): File {
        val root = File(rootPath).canonicalFile
        val rootNioPath = root.toPath()
        val rawTargetPath = rootNioPath.resolve(materializedPath.trimStart('/', '\\')).normalize()
        if (rawTargetPath != rootNioPath && !rawTargetPath.startsWith(rootNioPath)) {
            throw SecurityException("文件路径越过工作区根: $materializedPath")
        }
        var cursor = rootNioPath
        rootNioPath.relativize(rawTargetPath).forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw SecurityException("文件路径包含软链接: $materializedPath")
            }
        }
        val target = rawTargetPath.toFile().canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw SecurityException("文件路径越过工作区根: $materializedPath")
        }
        return target
    }

    private fun writeTextAtomically(target: File, content: String) {
        val parent = target.parentFile ?: error("文件缺少父目录")
        if (!parent.exists() && !parent.mkdirs()) error("无法创建父目录: ${parent.path}")
        val temp = File.createTempFile("${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
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

    // Task3B 完成 root 透传前，旧入口全部失败关闭。
    override suspend fun writeFileAtomic(uuid: String, newContent: String, sessionId: String, expectedHash: String): WriteResult = WriteResult.NotFound
    override suspend fun readFileRange(uuid: String, startLine: Int?, endLine: Int?): ReadResult = throw NoSuchElementException("workspaceRootUuid required")
    override suspend fun diffFile(uuid: String, basisHash: String?): DiffResult = throw NoSuchElementException("workspaceRootUuid required")
    override suspend fun patchFile(uuid: String, operations: List<PatchOperation>, expectedHash: String): PatchResult =
        fileFailure("FILE_NOT_FOUND", "workspaceRootUuid required", uuid)
}
