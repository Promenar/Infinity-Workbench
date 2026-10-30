package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.linux.LinuxFileAndroidMetadata
import com.promenar.infinity.data.linux.LinuxFileSystemImpl
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.domain.linux.LinuxFileSystem
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/** 身份摘要用于审批绑定，不把宿主物理路径暴露给模型。 */
data class SftpBoundLocalFile(val file: SftpLocalFile, val identity: String)

/** 与媒体及画布使用同一会话工作区解析器，目录失效时不退回私有根。 */
class SftpWorkspaceFiles(
    sessions: ISessionRepository,
    private val repository: IWorkspaceRepository,
    mounts: ExternalWorkspaceAccess,
    sessionWorkspace: suspend (String) -> SessionWorkspace,
    userSkillsDirectory: File,
    registeredWorkspace: (suspend (String) -> SessionWorkspace)? = null,
    directoryBackend: ((SessionWorkspace.Directory) -> ExternalFolderBackend)? = null,
    private val indexEvents: FileIndexEventSink = FileIndexEventSink.None,
    private val directoryFiles: (File) -> LinuxFileSystem = { LinuxFileSystemImpl(it, { true }, LinuxFileAndroidMetadata).asWorkspaceDirectory() },
) {
    // workspace 范围始终以当前工作区为 /，不因会话运行模式切换成 Linux 根命名空间。
    private val targets = WorkspaceFileTargetResolver(sessions, repository, mounts, sessionWorkspace,
        userSkillsDirectory, registeredWorkspace, directoryBackend)

    suspend fun resolve(sessionId: String, rootUuid: String, rawPath: String, writing: Boolean): SftpBoundLocalFile {
        val path = normalizeSftpWorkspacePath(rawPath)
        val target = targets.resolve(sessionId, path, writing)
        val identity = identity(target)
        val materialized = target.materialized(path)
        if (target.mount?.readOnly == true && writing) throw SshFailure("LOCAL_READ_ONLY")
        val file = if (target.root != null) {
            if (rootUuid != target.root.uuid) throw SshFailure("LOCAL_AUTHORIZATION_CHANGED")
            SftpWorkspaceFile.create(repository, target.root.uuid, sessionId, materialized, indexEvents)
        } else {
            val directory = target.directory ?: throw SshFailure("WORKSPACE_UNAVAILABLE")
            val files = directoryFiles(directory)
            SftpLinuxFile(files, target.relative(path))
        }
        suspend fun verify() {
            val current = targets.resolve(sessionId, path, writing)
            if (identity(current) != identity) throw SshFailure("LOCAL_AUTHORIZATION_CHANGED")
        }
        return SftpBoundLocalFile(object : SftpLocalFile {
            override suspend fun stat(): SftpLocalStat? { verify(); return file.stat() }
            override suspend fun <T> read(reader: (InputStream) -> T): T { verify(); return file.read(reader) }
            override suspend fun write(size: Long, overwrite: Boolean, expected: SftpLocalStat?, check: () -> Unit, beforeCommit: () -> Unit, onCommitted: (String) -> Unit, writer: (OutputStream) -> String): String {
                verify()
                return file.write(size, overwrite, expected, check, {
                    beforeCommit()
                    // 此回调在真正发布前调用；重新解析可防止工作区在审批后被切换。
                    runBlocking { verify() }
                }, onCommitted, writer)
            }
        }, identity)
    }

    private fun identity(target: WorkspaceFileTarget): String {
        val inode = target.directory?.let { java.nio.file.Files.readAttributes(it.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).also { attrs ->
                if (!attrs.isDirectory || attrs.isSymbolicLink) throw SshFailure("INVALID_LOCAL_PATH")
            }.fileKey()?.toString() }
        return MessageDigest.getInstance("SHA-256").digest((target.identity + inode)
            .joinToString("\u0000") { it ?: "" }.toByteArray(Charsets.UTF_8)).toHex()
    }
}
