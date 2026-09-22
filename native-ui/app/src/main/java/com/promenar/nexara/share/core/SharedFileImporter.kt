package com.promenar.nexara.share.core

import android.net.Uri
import com.promenar.nexara.ShareRequest
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.repository.WorkspaceFileTooLargeException
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

interface SharedContentSource {
    fun metadata(uri: Uri): SharedContentMetadata
    fun open(uri: Uri): InputStream
    fun preflightReason(uri: Uri): ShareRejectReason? = null
    fun preflightItem(uri: Uri): ShareImportItem? = null
    fun contentSha256(uri: Uri): String? = null
}

fun interface ShareIndexScheduler {
    suspend fun schedule(workspaceRootUuid: String, entry: FileEntry): ShareIndexReceipt
}

class SharedFileImporter(
    private val source: SharedContentSource,
    private val workspace: IWorkspaceRepository,
    private val indexScheduler: ShareIndexScheduler = ShareIndexScheduler { _, entry ->
        ShareIndexReceipt(taskId = "no-op-${entry.uuid}", fileUuid = entry.uuid)
    },
) {
    suspend fun inspect(request: ShareRequest): List<ShareImportItem> = withContext(Dispatchers.IO) {
        request.uris.map { uri ->
            source.preflightItem(uri)?.let { return@map it }
            val metadata = metadataOrNull(uri)
            val item = ShareImportItem(
                uri = uri,
                displayName = metadata?.displayName?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                    ?: "shared-file",
                mimeType = metadata?.mimeType ?: request.mimeType,
                sizeBytes = metadata?.sizeBytes,
            )
            source.preflightReason(uri)?.let { item.reject(it) } ?: item
        }
    }

    suspend fun import(
        request: ShareRequest,
        workspaceRootUuid: String,
        retryUris: Set<Uri>? = null,
        parentUuid: String = workspaceRootUuid,
    ): ShareImportBatchResult = withContext(Dispatchers.IO) {
        if (workspaceRootUuid.isBlank() || parentUuid.isBlank()) {
            return@withContext ShareImportBatchResult(
                inspect(request).map { it.reject(ShareRejectReason.TargetRequired) }
            )
        }
        val target = resolveTarget(workspaceRootUuid, parentUuid)
            ?: return@withContext ShareImportBatchResult(
                inspect(request).map { it.reject(ShareRejectReason.TargetRequired) }
            )
        var acceptedBytes = 0L
        val results = mutableListOf<ShareImportItem>()
        for (uri in request.uris) {
            if (retryUris != null && uri !in retryUris) continue
            source.preflightItem(uri)?.let { completed ->
                results += completed
                continue
            }
            source.preflightReason(uri)?.let { reason ->
                val metadata = metadataOrNull(uri)
                results += ShareImportItem(
                    uri = uri,
                    displayName = metadata?.displayName ?: "shared-file",
                    mimeType = metadata?.mimeType ?: request.mimeType,
                    sizeBytes = metadata?.sizeBytes,
                ).reject(reason)
                continue
            }
            val metadata = try {
                source.metadata(uri)
            } catch (_: SecurityException) {
                results += fallback(uri, request.mimeType).reject(ShareRejectReason.PermissionDenied)
                continue
            } catch (_: IOException) {
                results += fallback(uri, request.mimeType).reject(ShareRejectReason.ReadFailed)
                continue
            } catch (_: IllegalArgumentException) {
                results += fallback(uri, request.mimeType).reject(ShareRejectReason.ReadFailed)
                continue
            }
            val initial = ShareImportItem(
                uri = uri,
                displayName = metadata.displayName ?: uri.lastPathSegment ?: "shared-file",
                mimeType = metadata.mimeType ?: request.mimeType,
                sizeBytes = metadata.sizeBytes,
                status = ShareImportStatus.Importing,
            )
            val safeName = normalizeName(initial.displayName)
            if (safeName == null) {
                results += initial.reject(ShareRejectReason.InvalidName)
                continue
            }
            val declared = normalizeMime(request.mimeType)
            val resolved = normalizeMime(metadata.mimeType ?: request.mimeType)
            if (resolved !in SUPPORTED_MIME_TYPES || declared !in SUPPORTED_MIME_TYPES) {
                results += initial.reject(ShareRejectReason.UnsupportedMime)
                continue
            }
            val size = metadata.sizeBytes
            if (size != null && size <= 0L) {
                results += initial.reject(ShareRejectReason.EmptyFile)
                continue
            }
            if (size != null && size > MAX_ITEM_BYTES) {
                results += initial.reject(ShareRejectReason.ItemTooLarge)
                continue
            }
            if (size != null && acceptedBytes > MAX_BATCH_BYTES - size) {
                results += initial.reject(ShareRejectReason.BatchTooLarge)
                continue
            }
            try {
                val sniffed = sniffSource(uri, resolved)
                if (!mimeCompatible(declared, resolved, sniffed, safeName)) {
                    results += initial.reject(ShareRejectReason.MimeMismatch)
                    continue
                }
                val remainingBatch = MAX_BATCH_BYTES - acceptedBytes
                val itemLimit = minOf(MAX_ITEM_BYTES, remainingBatch)
                val creation = createWithStableName(
                    workspaceRootUuid = workspaceRootUuid,
                    parentUuid = target.uuid,
                    parentPath = target.materializedPath,
                    requestedName = safeName,
                    mimeType = resolved,
                    maxBytes = itemLimit,
                    expectedHash = source.contentSha256(uri),
                ) {
                    openSource(uri).use { input ->
                        try {
                            if (resolved in TEXT_MIME_TYPES) {
                                copyUtf8Validated(input, it)
                            } else {
                                input.copyTo(it, DEFAULT_BUFFER_SIZE)
                            }
                        } catch (failure: IOException) {
                            throw ShareSourceReadException(failure)
                        }
                    }
                }
                val entry = creation.entry
                if (entry.sizeBytes == 0L) {
                    rollbackIfCreatedNow(workspaceRootUuid, creation)
                    results += initial.reject(ShareRejectReason.EmptyFile)
                    continue
                }
                val stagedHash = source.contentSha256(uri)
                if (stagedHash != null && entry.hash != stagedHash) {
                    rollbackIfCreatedNow(workspaceRootUuid, creation)
                    results += initial.reject(ShareRejectReason.ReadFailed)
                    continue
                }
                acceptedBytes += entry.sizeBytes
                try {
                    val receipt = indexScheduler.schedule(workspaceRootUuid, entry)
                    if (creation.createdNow) {
                        workspace.confirmCreatedEntry(workspaceRootUuid, entry.uuid)
                    }
                    results += initial.copy(
                        displayName = entry.name,
                        mimeType = resolved,
                        sizeBytes = entry.sizeBytes,
                        status = ShareImportStatus.Created,
                        created = entry,
                        indexStatus = ShareIndexStatus.Pending,
                        fileUuid = receipt.fileUuid,
                        indexTaskId = receipt.taskId,
                    )
                } catch (cancelled: CancellationException) {
                    try {
                        rollbackIfCreatedNow(workspaceRootUuid, creation, nonCancellable = true)
                    } catch (cleanupFailure: Exception) {
                        cancelled.addSuppressed(cleanupFailure)
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    try {
                        rollbackIfCreatedNow(workspaceRootUuid, creation)
                        // 已成功回滚的项目不占成功导入批次预算；清理失败时保留占额。
                        acceptedBytes -= entry.sizeBytes
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                    if (failure is IOException || failure is IllegalArgumentException ||
                        failure is IllegalStateException || failure is android.database.SQLException
                    ) {
                        results += initial.reject(ShareRejectReason.IndexScheduleFailed)
                    } else {
                        throw failure
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: WorkspaceFileTooLargeException) {
                results += initial.reject(
                    if (acceptedBytes > 0L) ShareRejectReason.BatchTooLarge
                    else ShareRejectReason.ItemTooLarge
                )
            } catch (_: SecurityException) {
                results += initial.reject(ShareRejectReason.PermissionDenied)
            } catch (_: ShareSourceReadException) {
                results += initial.reject(ShareRejectReason.ReadFailed)
            } catch (_: IOException) {
                results += initial.reject(ShareRejectReason.WriteFailed)
            } catch (_: IllegalArgumentException) {
                results += initial.reject(ShareRejectReason.WriteFailed)
            } catch (_: IllegalStateException) {
                results += initial.reject(ShareRejectReason.WriteFailed)
            } catch (_: android.database.SQLException) {
                results += initial.reject(ShareRejectReason.WriteFailed)
            }
        }
        ShareImportBatchResult(results)
    }

    private fun openSource(uri: Uri): InputStream = try {
        source.open(uri)
    } catch (failure: IOException) {
        throw ShareSourceReadException(failure)
    } catch (failure: IllegalArgumentException) {
        throw ShareSourceReadException(IOException(failure))
    }

    private fun metadataOrNull(uri: Uri): SharedContentMetadata? = try {
        source.metadata(uri)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun sniffSource(uri: Uri, expectedMime: String): String? = try {
        openSource(uri).use { sniffMime(it, expectedMime) }
    } catch (failure: ShareSourceReadException) {
        throw failure
    } catch (failure: IOException) {
        throw ShareSourceReadException(failure)
    }

    private suspend fun createWithStableName(
        workspaceRootUuid: String,
        parentUuid: String,
        parentPath: String,
        requestedName: String,
        mimeType: String,
        maxBytes: Long,
        expectedHash: String?,
        writer: (java.io.OutputStream) -> Unit,
    ): FileCreation {
        repeat(MAX_NAME_ATTEMPTS) { collisionIndex ->
            val name = collisionName(requestedName, collisionIndex)
            val path = childPath(parentPath, name)
            workspace.getByMaterializedPath(workspaceRootUuid, path)?.let { existing ->
                if (expectedHash != null && existing.hash == expectedHash) {
                    return FileCreation(existing, createdNow = false)
                }
                return@repeat
            }
            try {
                return FileCreation(workspace.createFileInWorkspaceStreaming(
                    workspaceRootUuid = workspaceRootUuid,
                    uuid = UUID.randomUUID().toString(),
                    name = name,
                    mimeType = mimeType,
                    parentUuid = parentUuid,
                    materializedPath = path,
                    maxBytes = maxBytes,
                    writer = writer,
                ), createdNow = true)
            } catch (collision: IllegalStateException) {
                if (workspace.getByMaterializedPath(workspaceRootUuid, path) == null) throw collision
            }
        }
        throw IllegalStateException("同名文件过多")
    }

    private suspend fun resolveTarget(workspaceRootUuid: String, parentUuid: String): ImportTarget? {
        if (parentUuid == workspaceRootUuid) {
            return ImportTarget(parentUuid, "/")
        }
        val entry = try {
            workspace.getByUuid(workspaceRootUuid, parentUuid)
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        } catch (_: android.database.SQLException) {
            return null
        } ?: return null
        if (!entry.isDirectory || entry.inRecycleBin ||
            (entry.workspaceRootUuid.isNotBlank() && entry.workspaceRootUuid != workspaceRootUuid) ||
            !isSafeParentPath(entry.materializedPath)
        ) return null
        return ImportTarget(entry.uuid, entry.materializedPath)
    }

    private data class ImportTarget(val uuid: String, val materializedPath: String)

    private suspend fun rollbackIfCreatedNow(
        workspaceRootUuid: String,
        creation: FileCreation,
        nonCancellable: Boolean = false,
    ) {
        if (!creation.createdNow) return
        if (nonCancellable) {
            withContext(NonCancellable) {
                workspace.rollbackCreatedEntry(workspaceRootUuid, creation.entry.uuid)
            }
        } else {
            workspace.rollbackCreatedEntry(workspaceRootUuid, creation.entry.uuid)
        }
    }

    private data class FileCreation(val entry: FileEntry, val createdNow: Boolean)

    companion object {
        const val MAX_ITEM_BYTES = 100L * 1024 * 1024
        const val MAX_BATCH_BYTES = 100L * 1024 * 1024
        private const val MAX_NAME_ATTEMPTS = 10_000
        val SUPPORTED_MIME_TYPES = setOf(
            "text/plain",
            "text/markdown",
            "text/csv",
            "text/html",
            "application/json",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        internal fun normalizeName(raw: String): String? {
            val name = raw.trim()
            if (name.isEmpty() || name == "." || name == ".." || name.length > 255 ||
                name.any { it.isISOControl() } || '/' in name || '\\' in name ||
                name.startsWith(".nexara", true) || name.equals(".recycle_bin", true)
            ) return null
            val stem = name.substringBeforeLast('.').uppercase()
            if (stem in WINDOWS_RESERVED || RESERVED_DEVICE.matches(stem)) return null
            return name
        }

        internal fun collisionName(original: String, index: Int): String {
            if (index == 0) return original
            val dot = original.lastIndexOf('.').takeIf { it > 0 }
            val base = if (dot == null) original else original.substring(0, dot)
            val extension = if (dot == null) "" else original.substring(dot)
            return "$base ($index)$extension"
        }

        private fun childPath(parentPath: String, name: String): String =
            if (parentPath == "/") "/$name" else "${parentPath.trimEnd('/')}/$name"

        private fun isSafeParentPath(path: String): Boolean =
            path.startsWith('/') && path.split('/').none { it == "." || it == ".." }

        private fun normalizeMime(value: String): String = value.substringBefore(';').trim().lowercase()

        private fun sniffMime(input: InputStream, expectedMime: String): String? {
            if (expectedMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") {
                var contentTypes = false
                var wordDocument = false
                java.util.zip.ZipInputStream(input).use { zip ->
                    var inspected = 0
                    var expandedBytes = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        inspected += 1
                        if (inspected > 2_048) throw IOException("DOCX预检条目数超过2048")
                        when (entry.name) {
                            "[Content_Types].xml" -> contentTypes = true
                            "word/document.xml" -> wordDocument = true
                        }
                        // nextEntry会隐式读完上一条目；先按实际展开字节有界消费，避免压缩小包绕过导入限额。
                        while (true) {
                            val remaining = DOCX_SNIFF_EXPANDED_BYTES - expandedBytes
                            val count = zip.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
                            if (count < 0) break
                            if (count == 0) throw IOException("DOCX预检读取无进展")
                            expandedBytes += count
                            if (expandedBytes > DOCX_SNIFF_EXPANDED_BYTES) {
                                throw IOException("DOCX预检解压量超过64MiB")
                            }
                        }
                    }
                }
                return if (contentTypes && wordDocument) expectedMime else "application/zip"
            }
            val sampled = java.io.ByteArrayOutputStream(SNIFF_BYTES + MAX_UTF8_SEQUENCE_BYTES - 1)
            var eof = false
            while (sampled.size() < SNIFF_BYTES) {
                val count = readSome(input, sampled, SNIFF_BYTES - sampled.size())
                if (count < 0) {
                    eof = true
                    break
                }
            }
            if (sampled.size() == 0) return null
            var bytes = sampled.toByteArray()
            if (bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-") {
                return "application/pdf"
            }
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
                bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
            ) return "application/zip"
            if (bytes.any { it == 0.toByte() }) return "application/octet-stream"

            while (true) {
                if (isValidUtf8(bytes)) return "text/plain"
                if (eof || sampled.size() >= SNIFF_BYTES + MAX_UTF8_SEQUENCE_BYTES - 1) {
                    return "application/octet-stream"
                }
                val count = readSome(input, sampled, 1)
                if (count < 0) eof = true
                bytes = sampled.toByteArray()
                if (bytes.any { it == 0.toByte() }) return "application/octet-stream"
            }
        }

        private fun readSome(input: InputStream, target: java.io.ByteArrayOutputStream, limit: Int): Int {
            val buffer = ByteArray(limit.coerceAtLeast(1))
            val count = input.read(buffer, 0, buffer.size)
            if (count > 0) target.write(buffer, 0, count)
            if (count != 0) return count
            val single = input.read()
            if (single >= 0) target.write(single)
            return if (single >= 0) 1 else -1
        }

        private fun isValidUtf8(bytes: ByteArray): Boolean = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: java.nio.charset.CharacterCodingException) {
            false
        }

        private fun copyUtf8Validated(input: InputStream, output: OutputStream) {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var carry = ByteArray(0)
            while (true) {
                var count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val byte = input.read()
                    if (byte < 0) break
                    buffer[0] = byte.toByte()
                    count = 1
                }
                if (buffer.copyOfRange(0, count).any { it == 0.toByte() }) {
                    throw IOException("文本包含 NUL 字节")
                }
                val combined = ByteArray(carry.size + count)
                carry.copyInto(combined)
                buffer.copyInto(combined, carry.size, 0, count)
                val bytes = ByteBuffer.wrap(combined)
                val chars = CharBuffer.allocate(combined.size.coerceAtLeast(1))
                val result = decoder.decode(bytes, chars, false)
                if (result.isError) result.throwException()
                carry = ByteArray(bytes.remaining()).also { bytes.get(it) }
                output.write(buffer, 0, count)
            }
            val bytes = ByteBuffer.wrap(carry)
            val chars = CharBuffer.allocate(carry.size.coerceAtLeast(1))
            val decoded = decoder.decode(bytes, chars, true)
            if (decoded.isError) decoded.throwException()
            val flushed = decoder.flush(chars)
            if (flushed.isError) flushed.throwException()
        }

        private fun mimeCompatible(declared: String, resolved: String, sniffed: String?, name: String): Boolean {
            if (sniffed == null) return false
            if (resolved != declared && !(resolved.startsWith("text/") && declared.startsWith("text/"))) return false
            return when (resolved) {
                "application/pdf" -> sniffed == "application/pdf" && name.endsWith(".pdf", true)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    sniffed == resolved && name.endsWith(".docx", true)
                else -> sniffed == "text/plain"
            }
        }

        private fun fallback(uri: Uri, mime: String) = ShareImportItem(
            uri = uri,
            displayName = uri.lastPathSegment ?: "shared-file",
            mimeType = mime,
            sizeBytes = null,
        )

        private fun ShareImportItem.reject(reason: ShareRejectReason) = copy(
            status = ShareImportStatus.Rejected,
            reason = reason,
            created = null,
        )

        private val WINDOWS_RESERVED = setOf("CON", "PRN", "AUX", "NUL")
        private val RESERVED_DEVICE = Regex("(?:COM|LPT)[1-9]")
        private const val SNIFF_BYTES = 8_192
        private const val MAX_UTF8_SEQUENCE_BYTES = 4
        private const val DOCX_SNIFF_EXPANDED_BYTES = 64L * 1024 * 1024
        private val TEXT_MIME_TYPES = setOf(
            "text/plain",
            "text/markdown",
            "text/csv",
            "text/html",
            "application/json",
        )
    }
}

private class ShareSourceReadException(cause: IOException) : IOException(cause)
