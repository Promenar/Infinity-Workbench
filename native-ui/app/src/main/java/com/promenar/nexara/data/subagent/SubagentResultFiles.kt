package com.promenar.nexara.data.subagent

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.rag.FileIndexEvent
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.data.workspace.WorkspaceFileTarget
import com.promenar.nexara.data.workspace.WorkspaceFileTargetResolver
import com.promenar.nexara.domain.linux.LinuxFileSystem
import com.promenar.nexara.domain.model.SessionRunMode
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalMountOperationBindings
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 将完整结论写入父会话的当前工作区，返回文件工具使用的虚拟路径。 */
class SubagentResultFiles(
    sessions: ISessionRepository,
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    sessionWorkspace: suspend (String) -> SessionWorkspace,
    userSkillsDirectory: File,
    private val indexEvents: FileIndexEventSink = FileIndexEventSink.None,
    modeFor: (suspend (String) -> SessionRunMode?)? = null,
    linuxFiles: LinuxFileSystem? = null,
    directoryBackend: ((SessionWorkspace.Directory) -> ExternalFolderBackend)? = null,
) {
    private val targets = WorkspaceFileTargetResolver(
        sessions, workspace, mounts, sessionWorkspace, userSkillsDirectory,
        directoryBackend = directoryBackend, modeFor = modeFor, linuxFiles = linuxFiles,
    )

    suspend fun write(session: Session, run: SubagentRun, text: String): String = withContext(Dispatchers.IO) {
        require(session.id.isNotBlank()) { "父会话标识不能为空" }
        require(run.id.isEmpty() || RUN_ID.matches(run.id)) { "子代理标识无效" }
        require(run.index >= 0) { "子代理序号无效" }
        locks[session.id.hashCode() and (locks.size - 1)].withLock {
            val destination = targets.primaryDestination(session.id)
                ?: throw ExternalFolderException("UNAVAILABLE", "父会话工作区不可用，无法保存完整结论")
            val presenter = targets.presenter(session.id)
            val name = "${run.id.ifEmpty { "legacy-${run.index}" }}-${UUID.randomUUID()}.md"
            // 私有入口保持显式，主工作区切换后也不会把引用解释到另一个根。
            val path = presenter.present(destination.destination, "/subagent-results/$name", privatePrimary = false)
            val target = targets.resolve(session.id, path, writing = true, destination)
            val checkBinding: suspend () -> Unit = {
                if (targets.primaryDestination(session.id) != destination || targets.presenter(session.id) != presenter) {
                    throw ExternalFolderException("UNAVAILABLE", "父会话工作区或执行模式已变化，无法保存完整结论")
                }
                targets.checkCurrent(target, path)
            }
            withContext(ExternalMountOperationBindings(target.mountIds)) {
                ensureParent(target, path, checkBinding)
                checkBinding()
                create(target, path, text, checkBinding)
                checkBinding()
            }
            path
        }
    }

    private suspend fun ensureParent(target: WorkspaceFileTarget, path: String, checkBinding: suspend () -> Unit) {
        val parentPath = path.substringBeforeLast('/')
        checkBinding()
        val existing = entry(target, parentPath)
        if (existing != null) {
            if (!existing.isDirectory) throw ExternalFolderException("NOT_DIRECTORY", "子代理结论目录被同名文件占用")
            return
        }
        checkBinding()
        try {
            when {
                target.directoryBackend != null -> target.directoryBackend.createDirectory(target.relative(parentPath))
                target.mount != null -> mounts.createDirectory(target.sessionId, target.accessPath(parentPath))
                else -> {
                    val root = requireNotNull(target.root)
                    workspace.createDirectoryInWorkspace(
                        root.uuid, UUID.randomUUID().toString(), "subagent-results", root.uuid,
                        target.materialized(parentPath),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (entry(target, parentPath)?.isDirectory != true) throw failure
        }
    }

    private suspend fun entry(target: WorkspaceFileTarget, path: String): FileEntry? = when {
        target.directoryBackend != null -> target.directoryBackend.stat(target.relative(path))?.let {
            FileEntry(
                uuid = it.identity ?: it.path, parentUuid = null, name = it.name,
                hash = it.sha256.orEmpty(), isDirectory = it.isDirectory, physicalRootPath = "",
                materializedPath = path, createdAt = it.lastModified, updatedAt = it.lastModified,
            )
        }
        target.mount != null -> mounts.entry(target.sessionId, target.accessPath(path))
        else -> workspace.getByMaterializedPath(requireNotNull(target.root).uuid, target.materialized(path))
    }

    private suspend fun create(target: WorkspaceFileTarget, path: String, text: String, checkBinding: suspend () -> Unit) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        checkBinding()
        when {
            target.directoryBackend != null -> target.directoryBackend.write(
                target.relative(path), bytes, createOnly = true,
            )
            target.mount != null -> mounts.write(
                target.sessionId, target.accessPath(path), bytes, expectedSha256 = null, createOnly = true,
            )
            else -> {
                val root = requireNotNull(target.root)
                val parent = entry(target, path.substringBeforeLast('/'))
                    ?: throw ExternalFolderException("NOT_FOUND", "子代理结论目录不存在")
                checkBinding()
                var created: FileEntry? = null
                try {
                    created = workspace.createFileInWorkspaceStreaming(
                        root.uuid, UUID.randomUUID().toString(), path.substringAfterLast('/'),
                        "text/markdown", parent.uuid, target.materialized(path), bytes.size.toLong().coerceAtLeast(1),
                    ) { it.write(bytes) }
                    indexEvents.publish(FileIndexEvent.Changed(root.uuid, created.uuid, created.hash, created.updatedAt.coerceAtLeast(1)))
                    workspace.confirmCreatedEntry(root.uuid, created.uuid)
                } catch (failure: Exception) {
                    created?.let { entry ->
                        withContext(NonCancellable) {
                            try { workspace.rollbackCreatedEntry(root.uuid, entry.uuid) }
                            catch (rollback: Exception) { failure.addSuppressed(rollback) }
                            try { indexEvents.publish(FileIndexEvent.Deleted(root.uuid, entry.uuid)) }
                            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                        }
                    }
                    throw failure
                }
            }
        }
    }

    private companion object {
        val RUN_ID = Regex("sa_[a-z0-9]{6}")
        val locks = Array(32) { Mutex() }
    }
}
