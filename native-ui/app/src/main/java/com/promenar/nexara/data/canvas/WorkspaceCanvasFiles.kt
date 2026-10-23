package com.promenar.nexara.data.canvas

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.rag.DocumentReferenceExtractor
import com.promenar.nexara.data.rag.FileIndexEvent
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.storage.AndroidRealPathFileSystem
import com.promenar.nexara.data.storage.RealPathExternalFolderBackend
import com.promenar.nexara.data.storage.RealPathFileSystem
import com.promenar.nexara.data.workspace.WorkspaceFileTarget
import com.promenar.nexara.data.workspace.WorkspaceFileTargetResolver
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.WriteResult
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import com.promenar.nexara.domain.storage.ExternalMountOperationBindings
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 写入必须同步工作区文件树；expectedHash 为空时只允许新建。 */
interface CanvasWorkspaceFiles {
    suspend fun exists(sessionId: String, path: String): Boolean
    suspend fun read(sessionId: String, path: String, maxBytes: Int): ByteArray?
    suspend fun write(sessionId: String, path: String, html: String, expectedHash: String?)
}

internal class WorkspaceCanvasFiles(
    private val targets: WorkspaceFileTargetResolver,
    private val workspace: IWorkspaceRepository,
    private val fileOperations: IFileOperationRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val indexEvents: FileIndexEventSink,
    private val fileSystem: RealPathFileSystem = AndroidRealPathFileSystem,
) : CanvasWorkspaceFiles {
    override suspend fun exists(sessionId: String, path: String): Boolean = access(sessionId, path) { target, normalized ->
        entry(target, normalized) != null || target.root != null &&
            Files.exists(target.directory!!.toPath().resolve(normalized.trimStart('/')), NOFOLLOW_LINKS)
    }

    override suspend fun read(sessionId: String, path: String, maxBytes: Int): ByteArray? = access(sessionId, path) { target, normalized ->
        try {
            val entry = entry(target, normalized) ?: return@access null
            if (entry.isDirectory) throw ExternalFolderException("IS_DIRECTORY", "画布路径指向目录")
            if (target.mount != null) mounts.read(sessionId, normalized, maxBytes.toLong())
            else RealPathExternalFolderBackend(target.directory!!, emptySet(), { true }, fileSystem).read(normalized, maxBytes.toLong())
        } catch (failure: ExternalFolderException) {
            if (failure.code == "NOT_FOUND") null else throw failure
        }
    }

    override suspend fun write(sessionId: String, path: String, html: String, expectedHash: String?) = access(sessionId, path) { target, normalized ->
        if (target.mount?.readOnly == true) throw ExternalFolderException("READ_ONLY", "画布工作区为只读")
        ensureParents(target, normalized)
        targets.checkCurrent(target, normalized)
        val bytes = html.toByteArray(Charsets.UTF_8)
        if (target.mount != null) {
            mounts.write(sessionId, normalized, bytes, expectedHash, createOnly = expectedHash == null)
        } else {
            val root = target.root ?: throw ExternalFolderException("INVALID_PATH", "画布路径没有工作区根")
            val existing = entry(target, normalized)
            if (existing != null && expectedHash != null) {
                when (val result = fileOperations.writeFileAtomicExact(root.uuid, existing.uuid, html, sessionId, expectedHash)) {
                    is WriteResult.Success -> if (result.newHash != canvasSha256(bytes)) throw ExternalFolderException("IO", "画布写入字节不一致")
                    is WriteResult.Conflict -> throw ExternalFolderException("CONFLICT", "画布文件已被修改，请重新读取")
                    WriteResult.NotFound -> throw ExternalFolderException("NOT_FOUND", "画布文件已被删除")
                }
            } else {
                if (existing != null || expectedHash != null) throw ExternalFolderException("CONFLICT", "画布目标已变化，请重新读取")
                val parent = entry(target, normalized.substringBeforeLast('/')) ?: throw ExternalFolderException("NOT_FOUND", "画布父目录不存在")
                var created: FileEntry? = null
                try {
                    created = workspace.createFileInWorkspaceStreaming(root.uuid, UUID.randomUUID().toString(), normalized.substringAfterLast('/'), "text/html", parent.uuid, normalized, bytes.size.toLong().coerceAtLeast(1)) { it.write(bytes) }
                    if (DocumentReferenceExtractor.supports(created)) {
                        indexEvents.publish(FileIndexEvent.Changed(root.uuid, created.uuid, created.hash, created.updatedAt.coerceAtLeast(1)))
                    }
                    workspace.confirmCreatedEntry(root.uuid, created.uuid)
                } catch (failure: Exception) {
                    created?.let { createdEntry ->
                        withContext(NonCancellable) {
                            try { workspace.rollbackCreatedEntry(root.uuid, createdEntry.uuid) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                            try { indexEvents.publish(FileIndexEvent.Deleted(root.uuid, createdEntry.uuid)) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                        }
                    }
                    throw failure
                }
            }
        }
        Unit
    }

    private suspend fun entry(target: WorkspaceFileTarget, path: String): FileEntry? =
        if (target.mount != null) mounts.entry(target.sessionId, path)
        else workspace.getByMaterializedPath(target.root!!.uuid, path)

    private suspend fun ensureParents(target: WorkspaceFileTarget, path: String) {
        var parent = target.virtualRoot
        for (component in target.relative(path).trim('/').split('/').dropLast(1)) {
            val previous = parent
            parent = if (parent == "/") "/$component" else "$parent/$component"
            val existing = entry(target, parent)
            if (existing != null) {
                if (!existing.isDirectory) throw ExternalFolderException("INVALID_PATH", "画布父路径包含文件")
                continue
            }
            targets.checkCurrent(target, path)
            try {
                if (target.mount != null) mounts.createDirectory(target.sessionId, parent)
                else {
                    val parentEntry = entry(target, previous) ?: throw ExternalFolderException("NOT_FOUND", "画布父目录不可用")
                    workspace.createDirectoryInWorkspace(target.root!!.uuid, UUID.randomUUID().toString(), component, parentEntry.uuid, parent)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (entry(target, parent)?.isDirectory != true) throw failure
            }
        }
    }

    private suspend fun <T> access(sessionId: String, path: String, block: suspend (WorkspaceFileTarget, String) -> T): T = withContext(Dispatchers.IO) {
        // 元数据中的画布只能落到自身固定目录，不接受 URI、挂载别名或路径穿越。
        if (!CANVAS_PATH.matches(path)) throw ExternalFolderException("INVALID_PATH", "画布工作区路径无效")
        val normalized = ExternalFolderPaths.normalize(path)
        val target = targets.resolve(sessionId, normalized, writing = true)
        withContext(ExternalMountOperationBindings(target.mountIds)) {
            targets.checkCurrent(target, normalized)
            block(target, normalized)
        }
    }
}

internal val CANVAS_PATH = Regex("canvas/[\\p{IsHan}\\p{L}\\p{N}-]+\\.html")
internal fun canvasSha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
