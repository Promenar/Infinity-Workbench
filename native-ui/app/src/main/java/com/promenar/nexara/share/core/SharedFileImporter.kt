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
                            input.copyTo(it, DEFAULT_BUFFER_SIZE)
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
                    if (creation.createdNow) {
                        workspace.confirmCreatedEntry(workspaceRootUuid, entry.uuid)
                    }
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
                    while (inspected < 256) {
                        val entry = zip.nextEntry ?: break
                        inspected += 1
                        when (entry.name) {
                            "[Content_Types].xml" -> contentTypes = true
                            "word/document.xml" -> wordDocument = true
                        }
                        if (contentTypes && wordDocument) return expectedMime
                    }
                }
                return "application/zip"
            }
            val prefix = ByteArray(8_192)
            val count = input.read(prefix)
            if (count <= 0) return null
            val bytes = prefix.copyOf(count)
            if (count >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-") {
                return "application/pdf"
            }
            if (count >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
                bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
            ) return "application/zip"
            if (bytes.any { it == 0.toByte() }) return "application/octet-stream"
            return try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
                "text/plain"
            } catch (_: java.nio.charset.CharacterCodingException) {
                "application/octet-stream"
            }
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
    }
}

private class ShareSourceReadException(cause: IOException) : IOException(cause)
