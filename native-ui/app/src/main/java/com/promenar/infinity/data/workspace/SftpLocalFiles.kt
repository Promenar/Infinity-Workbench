package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.linux.LinuxFileAndroidMetadata
import com.promenar.infinity.data.linux.LinuxFileSystemImpl
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.WorkspaceCommandHost
import com.promenar.infinity.data.repository.WorkspaceCommandMimeTypes
import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 两种本地范围共用流式端口，工作区路径从仓库根解析，不接受模型提供的宿主路径。 */
class SftpLinuxFile(private val files: LinuxFileSystem, private val path: String) : SftpLocalFile {
    override suspend fun stat(): SftpLocalStat? = files.stat(path)?.also {
        if (it.type != LinuxFileType.FILE) throw SshFailure("UNSUPPORTED_TRANSFER_TARGET")
    }?.let { SftpLocalStat(it.size, "${it.modifiedMs}:${it.mode}:${it.path}") }
    override suspend fun <T> read(reader: (InputStream) -> T): T = files.readStreaming(path, MAX_SFTP_TRANSFER_BYTES, reader)
    override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit, beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (OutputStream) -> String): String {
        val before = files.stat(path)
        if (stat() != expected || before != null && !overwrite) throw SshFailure("TARGET_CONFLICT")
        var hash = ""
        files.writeStreaming(path, size, before, check, beforeCommit, { onCommitted(hash) }) { output -> writer(output).also { hash = it } }
        return hash
    }
}

internal fun normalizeSftpWorkspacePath(raw: String): String {
    if (raw.isBlank() || '\u0000' in raw || raw.startsWith("\\\\") || Regex("^[A-Za-z]:").containsMatchIn(raw)) throw SshFailure("INVALID_LOCAL_PATH")
    val parts = raw.trim().replace('\\', '/').split('/').filter(String::isNotEmpty)
    if (parts.isEmpty() || parts.any { it == "." || it == ".." || WorkspaceInternalPaths.isReservedName(it) }) throw SshFailure("INVALID_LOCAL_PATH")
    return "/" + parts.joinToString("/")
}

class SftpWorkspaceFile internal constructor(
    private val repository: IWorkspaceRepository,
    private val root: String,
    private val session: String,
    private val path: String,
    private val files: LinuxFileSystem,
    private val indexEvents: FileIndexEventSink,
) : SftpLocalFile {
    override suspend fun stat() = SftpLinuxFile(files, path).stat()
    override suspend fun <T> read(reader: (InputStream) -> T): T {
        val entry = repository.getByMaterializedPath(root, path) ?: throw SshFailure("LOCAL_NOT_FOUND")
        if (entry.isDirectory) throw SshFailure("UNSUPPORTED_TRANSFER_TARGET")
        return files.readStreaming(path, MAX_SFTP_TRANSFER_BYTES, reader)
    }
    override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit, beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (OutputStream) -> String): String = withContext(Dispatchers.IO) {
        if (stat() != expected || expected != null && !overwrite) throw SshFailure("TARGET_CONFLICT")
        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        if (files.availableBytes(parentPath) < size) throw SshFailure("NO_SPACE")
        val parent = repository.getByMaterializedPath(root, parentPath)?.takeIf { it.isDirectory }
            ?: throw SshFailure("LOCAL_PARENT_MISSING")
        if (expected != null) {
            // 复用命令写锁、磁盘快照与索引对账，覆盖后保留既有 UUID。
            val host = repository as? WorkspaceCommandHost ?: throw SshFailure("WORKSPACE_UNAVAILABLE")
            val outcome = host.runCommandInWorkspace(root, session) {
                if (stat() != expected) throw SshFailure("TARGET_CONFLICT")
                SftpLinuxFile(files, path).write(size, true, expected, check, beforeCommit, onCommitted, writer)
            }
            if (outcome.reconcileError != null) throw SshFailure("WORKSPACE_INDEX_FAILED")
            return@withContext outcome.value
        }
        val uuid = UUID.randomUUID().toString()
        val name = ".sftp-${UUID.randomUUID()}.tmp"
        val temporary = parentPath.trimEnd('/') + "/$name"
        var created = false
        var published = false
        var checksum = ""
        try {
            repository.createFileInWorkspaceStreaming(root, uuid, name, WorkspaceCommandMimeTypes.guess(path.substringAfterLast('/'), size) { null }, parent.uuid,
                temporary, MAX_SFTP_TRANSFER_BYTES) { output -> checksum = writer(output) }
            created = true
            val actual = files.readStreaming(temporary, MAX_SFTP_TRANSFER_BYTES) { sftpHash(it, size, check) }
            if (actual != checksum) throw SshFailure("CHECKSUM_MISMATCH")
            if (stat() != null) throw SshFailure("TARGET_CONFLICT")
            check()
            beforeCommit()
            check()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            when (repository.rename(root, uuid, path.substringAfterLast('/'), expectedName = name)) {
                is RenameResult.Success -> Unit
                else -> throw SshFailure("TARGET_CONFLICT")
            }
            published = true
            withContext(NonCancellable) {
                onCommitted(checksum)
                repository.confirmCreatedEntry(root, uuid)
                val entry = repository.getByUuid(root, uuid) ?: throw SshFailure("WORKSPACE_INDEX_FAILED")
                try { indexEvents.publish(FileIndexEvent.Changed(root, uuid, checksum, entry.updatedAt.coerceAtLeast(1))) }
                catch (_: Exception) { throw SshFailure("WORKSPACE_INDEX_FAILED") }
            }
            checksum
        } finally {
            if (created && !published) withContext(NonCancellable) {
                // rename 的不可取消事务可能已经提交，而返回调用方时才收到取消。
                val entry = repository.getByUuid(root, uuid)
                if (entry?.materializedPath == path && entry.hash == checksum) {
                    onCommitted(checksum)
                    repository.confirmCreatedEntry(root, uuid)
                    try { indexEvents.publish(FileIndexEvent.Changed(root, uuid, checksum, entry.updatedAt.coerceAtLeast(1))) }
                    catch (_: Exception) { throw SshFailure("WORKSPACE_INDEX_FAILED") }
                } else repository.rollbackCreatedEntry(root, uuid)
            }
        }
    }

    companion object {
        suspend fun create(repository: IWorkspaceRepository, root: String, session: String, rawPath: String,
            indexEvents: FileIndexEventSink = FileIndexEventSink.None): SftpWorkspaceFile {
            val path = normalizeSftpWorkspacePath(rawPath)
            val entry = repository.getByUuid(root, root)?.takeIf { it.isDirectory && it.parentUuid == null }
                ?: throw SshFailure("WORKSPACE_UNAVAILABLE")
            val physical = File(entry.physicalRootPath)
            val files = LinuxFileSystemImpl(physical, { true }, LinuxFileAndroidMetadata).asWorkspaceDirectory()
            return SftpWorkspaceFile(repository, root, session, path, files, indexEvents)
        }
    }
}
