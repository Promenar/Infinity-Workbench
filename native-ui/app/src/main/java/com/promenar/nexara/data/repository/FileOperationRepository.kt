package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.FileEntryDao
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
import kotlinx.coroutines.withContext
import java.io.File

class FileOperationRepository(
    private val dao: FileEntryDao
) : IFileOperationRepository {

    override suspend fun writeFileAtomic(
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String
    ): WriteResult = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(uuid) ?: return@withContext WriteResult.NotFound

        if (entry.hash != expectedHash) {
            return@withContext WriteResult.Conflict(
                currentHash = entry.hash,
                expectedHash = expectedHash,
                message = "文件已被其他会话修改。请先读取最新版本再重试。"
            )
        }

        val newHash = Sha256Utils.hash(newContent)

        val physicalFile = File(entry.physicalRootPath, entry.materializedPath)
        physicalFile.writeText(newContent)

        val now = System.currentTimeMillis()
        dao.update(
            entry.copy(
                hash = newHash,
                sizeBytes = newContent.toByteArray(Charsets.UTF_8).size.toLong(),
                lastWriteSessionId = sessionId,
                updatedAt = now
            )
        )

        if (entry.hash != newHash && entry.vectorizedAt != null) {
            // TODO: 接入向量化队列，触发异步重索引
        }

        WriteResult.Success(newHash)
    }

    override suspend fun readFileRange(
        uuid: String,
        startLine: Int?,
        endLine: Int?
    ): ReadResult = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(uuid) ?: throw NoSuchElementException("File not found: $uuid")

        val physicalFile = File(entry.physicalRootPath, entry.materializedPath)
        val allLines = if (physicalFile.exists()) physicalFile.readLines() else emptyList()
        val totalLines = allLines.size

        val s = startLine?.coerceIn(1, totalLines) ?: 1
        val e = endLine?.coerceIn(1, totalLines) ?: totalLines

        val content = if (totalLines == 0) {
            ""
        } else {
            allLines.subList((s - 1).coerceAtMost(totalLines), e.coerceAtMost(totalLines))
                .joinToString("\n")
        }

        ReadResult(
            uuid = entry.uuid,
            name = entry.name,
            totalLines = totalLines,
            startLine = s,
            endLine = e,
            content = content,
            hash = entry.hash,
            lastModified = entry.updatedAt
        )
    }

    override suspend fun diffFile(
        uuid: String,
        basisHash: String?
    ): DiffResult = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(uuid) ?: throw NoSuchElementException("File not found: $uuid")

        val physicalFile = File(entry.physicalRootPath, entry.materializedPath)
        val currentContent = if (physicalFile.exists()) physicalFile.readText() else ""
        val currentLines = currentContent.lines()

        val basisContent = if (basisHash != null) {
            reconstructBasisContent(entry, basisHash, physicalFile, currentContent)
        } else {
            currentContent
        }
        val basisLines = basisContent.lines()

        val effectiveBasisHash = basisHash ?: entry.hash

        val hunks = MyersDiff.computeHunks(basisLines, currentLines)

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
                    lines = hunk.lines.map { line ->
                        DiffLine(type = line.type, content = line.content)
                    }
                )
            }
        )
    }

    override suspend fun patchFile(
        uuid: String,
        operations: List<PatchOperation>,
        expectedHash: String
    ): PatchResult = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(uuid)
            ?: return@withContext PatchResult.Failure(
                PatchError(
                    code = "FILE_NOT_FOUND",
                    message = "文件不存在: $uuid",
                    operationIndex = -1,
                    fileUuid = uuid
                )
            )

        if (entry.hash != expectedHash) {
            return@withContext PatchResult.Failure(
                PatchError(
                    code = "HASH_MISMATCH",
                    message = "文件已被其他会话修改 (当前: ${entry.hash}, 期望: $expectedHash)。请先 diff_file 获取最新差异，再重试。",
                    operationIndex = -1,
                    fileUuid = uuid,
                    suggestion = "请先调用 diff_file(uuid='$uuid') 获取最新差异，再重新规划 patch 操作。"
                )
            )
        }

        val physicalFile = File(entry.physicalRootPath, entry.materializedPath)
        val currentContent = if (physicalFile.exists()) physicalFile.readText() else ""
        val lines = currentContent.lines().toMutableList()
        val totalLines = lines.size

        var lineOffset = 0

        for ((index, op) in operations.withIndex()) {
            when (op.action) {
                "replace_lines" -> {
                    val start = (op.startLine ?: 0) + lineOffset
                    val end = (op.endLine ?: 0) + lineOffset
                    if (start < 1 || end < start || start > totalLines + lineOffset) {
                        return@withContext PatchResult.Failure(
                            PatchError(
                                code = "LINE_OUT_OF_RANGE",
                                message = "操作 'replace_lines' 指定的 startLine=${op.startLine}, endLine=${op.endLine} 超出文件行数 (当前共 $totalLines 行)。请检查文件长度后重试。",
                                operationIndex = index,
                                fileUuid = uuid,
                                totalLines = totalLines,
                                suggestion = "请先调用 read_file(uuid='$uuid', mode='range', startLine=1, endLine=$totalLines) 获取当前完整内容，再重新规划 patch 操作。"
                            )
                        )
                    }
                    val newLines = (op.newContent ?: "").lines()
                    val replaceCount = end - start + 1
                    lines.subList(start - 1, (start - 1 + replaceCount).coerceAtMost(lines.size))
                        .clear()
                    lines.addAll(start - 1, newLines)
                    lineOffset += newLines.size - replaceCount
                }

                "insert_after" -> {
                    val afterLine = op.afterLine ?: 0
                    if (afterLine < 0 || afterLine > totalLines + lineOffset) {
                        return@withContext PatchResult.Failure(
                            PatchError(
                                code = "LINE_OUT_OF_RANGE",
                                message = "操作 'insert_after' 指定的 afterLine=$afterLine 超出文件行数 (当前共 $totalLines 行)。请检查文件长度后重试。",
                                operationIndex = index,
                                fileUuid = uuid,
                                totalLines = totalLines,
                                suggestion = "请先调用 read_file(uuid='$uuid', mode='range', startLine=1, endLine=$totalLines) 获取当前完整内容，再重新规划 patch 操作。"
                            )
                        )
                    }
                    val newLines = (op.newContent ?: "").lines()
                    lines.addAll(afterLine + lineOffset, newLines)
                    lineOffset += newLines.size
                }

                "delete_lines" -> {
                    val start = (op.startLine ?: 0) + lineOffset
                    val end = (op.endLine ?: 0) + lineOffset
                    if (start < 1 || end < start || start > lines.size) {
                        return@withContext PatchResult.Failure(
                            PatchError(
                                code = "LINE_OUT_OF_RANGE",
                                message = "操作 'delete_lines' 指定的 startLine=${op.startLine}, endLine=${op.endLine} 超出文件行数 (当前共 $totalLines 行)。请检查文件长度后重试。",
                                operationIndex = index,
                                fileUuid = uuid,
                                totalLines = totalLines,
                                suggestion = "请先调用 read_file(uuid='$uuid', mode='range', startLine=1, endLine=$totalLines) 获取当前完整内容，再重新规划 patch 操作。"
                            )
                        )
                    }
                    val deleteCount = end - start + 1
                    lines.subList(start - 1, (start - 1 + deleteCount).coerceAtMost(lines.size))
                        .clear()
                    lineOffset -= deleteCount
                }

                else -> {
                    return@withContext PatchResult.Failure(
                        PatchError(
                            code = "INVALID_ACTION",
                            message = "未知的 patch 操作类型: '${op.action}'。支持的操作: replace_lines, insert_after, delete_lines",
                            operationIndex = index,
                            fileUuid = uuid
                        )
                    )
                }
            }
        }

        val newContent = lines.joinToString("\n")
        val newHash = Sha256Utils.hash(newContent)

        physicalFile.writeText(newContent)

        val now = System.currentTimeMillis()
        dao.update(
            entry.copy(
                hash = newHash,
                sizeBytes = newContent.toByteArray(Charsets.UTF_8).size.toLong(),
                lastWriteSessionId = entry.lastWriteSessionId,
                updatedAt = now
            )
        )

        if (entry.hash != newHash && entry.vectorizedAt != null) {
            // TODO: 接入向量化队列，触发异步重索引
        }

        PatchResult.Success(newHash = newHash, appliedOperations = operations.size)
    }

    private fun reconstructBasisContent(
        entry: com.promenar.nexara.data.local.db.entity.FileEntry,
        basisHash: String,
        physicalFile: File,
        currentContent: String
    ): String {
        if (basisHash == entry.hash && physicalFile.exists()) {
            return currentContent
        }
        return ""
    }
}
