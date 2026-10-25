package com.promenar.nexara.data.workspace

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.storage.isUserSkills
import com.promenar.nexara.data.storage.isWorkspaceRoot
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import java.io.File

internal data class WorkspaceFileTarget(
    val sessionId: String,
    val root: FileEntry?,
    val directory: File?,
    val mount: ExternalMount?,
    val workspaceMount: ExternalMount? = null,
    val destination: WorkspaceFileDestinationBinding? = null,
    val directoryBackend: ExternalFolderBackend? = null,
    val privatePathPrefix: String? = null,
) {
    val identity: List<String?> get() = listOf(root?.uuid, root?.hash, root?.physicalRootPath, directory?.path, mount?.id, mount?.sourceType, mount?.sourceId, workspaceMount?.id, destination?.workspaceId, destination?.destination?.name)
    val mountIds: Map<String, String> get() = listOfNotNull(workspaceMount, mount).associate { it.alias to it.id }
    fun relative(path: String): String = privatePathPrefix?.let { path.removePrefix(it).ifEmpty { "/" } }
        ?: ExternalMountPaths.parse(path)?.second ?: path
    fun materialized(path: String): String = if (root != null || directoryBackend != null) relative(path) else path
    val virtualRoot: String get() = when {
        mount == null || mount.isWorkspaceRoot -> "/"
        mount.isUserSkills -> "/skills"
        else -> "/mnt/${mount.alias}"
    }
}

/** 媒体与画布共用命名空间；主根失效不隐式回退，显式私有目标独立解析。 */
internal class WorkspaceFileTargetResolver(
    private val sessions: ISessionRepository,
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val sessionWorkspace: suspend (String) -> SessionWorkspace,
    private val userSkillsDirectory: File,
    private val registeredWorkspace: (suspend (String) -> SessionWorkspace)? = null,
    private val directoryBackend: ((SessionWorkspace.Directory) -> ExternalFolderBackend)? = null,
    private val pathPresenter: WorkspaceFilePathPresenter = BuiltinWorkspaceFilePathPresenter,
) {
    suspend fun defaultDestination(sessionId: String): WorkspaceFileDestinationBinding {
        val current = sessionWorkspace(sessionId)
        val destination = defaultWorkspaceFileDestination(current, mounts.visible(sessionId))
        return WorkspaceFileDestinationBinding(destination, (current as? SessionWorkspace.Directory)?.workspace?.id.takeIf { destination == WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE })
    }

    suspend fun primaryDestination(sessionId: String): WorkspaceFileDestinationBinding? = when (val current = sessionWorkspace(sessionId)) {
        SessionWorkspace.Private -> WorkspaceFileDestinationBinding(WorkspaceDefaultFileDestination.SESSION_PRIVATE)
        is SessionWorkspace.Directory -> WorkspaceFileDestinationBinding(WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE, current.workspace.id)
        is SessionWorkspace.Unavailable -> null
    }

    suspend fun present(sessionId: String, destination: WorkspaceFileDestinationBinding, path: String): String =
        pathPresenter.present(destination.destination, path, sessionWorkspace(sessionId) == SessionWorkspace.Private)

    suspend fun resolve(
        sessionId: String,
        path: String,
        writing: Boolean,
        destination: WorkspaceFileDestinationBinding? = null,
        initializeMissingPrivateRoot: Boolean = false,
    ): WorkspaceFileTarget {
        if (writing) com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId)
        val privateRelative = pathPresenter.privateRelativePath(path)
        if (privateRelative != null || destination?.destination == WorkspaceDefaultFileDestination.SESSION_PRIVATE) {
            return privateTarget(sessionId, writing, destination, privateRelative?.let { path.removeSuffix(it).ifEmpty { SessionPrivateWorkspacePaths.ROOT } },
                initializeMissingPrivateRoot)
        }
        val current = sessionWorkspace(sessionId)
        if (destination?.destination == WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE) {
            val id = destination.workspaceId ?: unavailable("画布镜像缺少工作区身份")
            val bound = if (current is SessionWorkspace.Directory && current.workspace.id == id) current
                else registeredWorkspace?.invoke(id) ?: unavailable("画布原工作区不可用")
            if (bound !is SessionWorkspace.Directory) unavailable("画布原工作区不可用")
            val backend = directoryBackend?.invoke(bound)
            if (backend != null) {
                if (writing) com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = bound.root.canonicalPath)
                return WorkspaceFileTarget(sessionId, null, bound.root, null, destination = destination, directoryBackend = backend)
            }
            if (current !is SessionWorkspace.Directory || current.workspace.id != id) unavailable("默认媒体主工作区已变化，已停止保存")
        }
        if (current is SessionWorkspace.Unavailable) unavailable("当前会话工作区不可用，请检查目录与授权")
        val workspaceMount = mounts.workspaceMount(sessionId)
        if (current is SessionWorkspace.Directory && workspaceMount == null || current is SessionWorkspace.Private && workspaceMount != null) {
            unavailable("会话工作区正在变化，请重新执行文件操作")
        }
        if (current is SessionWorkspace.Directory && workspaceMount?.sourceId != current.workspace.id) {
            unavailable("会话主工作区身份正在变化，请重新执行文件操作")
        }
        val alias = ExternalMountPaths.aliasOf(path, workspaceMount != null)
        if (alias != null) {
            val mount = if (alias == ExternalMountPaths.WORKSPACE_ALIAS) workspaceMount
                else mounts.visible(sessionId).singleOrNull { it.alias == alias }
            if (mount == null || mount.unavailableReason != null) unavailable("挂载已失效或不属于当前会话")
            val directory = when {
                mount.isWorkspaceRoot -> (current as? SessionWorkspace.Directory)?.root
                mount.isUserSkills -> userSkillsDirectory.canonicalFile
                mount.sourceType == "real_path" -> File(mount.sourceId)
                mount.sourceType == "saf_tree" -> null
                else -> unavailable("不支持该挂载来源")
            }
            if (writing) com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = directory?.canonicalPath)
            return WorkspaceFileTarget(sessionId, null, directory, mount, workspaceMount, destination = destination)
        }
        if (path == "/mnt") return WorkspaceFileTarget(sessionId, null, null, null, workspaceMount)
        return privateTarget(sessionId, writing, initializeMissingPrivateRoot = initializeMissingPrivateRoot)
    }

    private suspend fun privateTarget(
        sessionId: String,
        writing: Boolean,
        destination: WorkspaceFileDestinationBinding? = null,
        prefix: String? = null,
        initializeMissingPrivateRoot: Boolean = false,
    ): WorkspaceFileTarget {
        val session = sessions.getById(sessionId) ?: unavailable("会话不存在，无法访问工作区")
        val mayInitialize = writing || initializeMissingPrivateRoot && session.workspaceRootUuid == null
        val root = session.workspaceRootUuid?.let { workspace.getByUuid(it, it) }
            ?: if (mayInitialize) workspace.ensureSessionRoot(sessionId) else unavailable("会话私有工作区尚不可用")
        if (writing) com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, rootId = root.uuid, path = root.physicalRootPath)
        return WorkspaceFileTarget(sessionId, root, File(root.physicalRootPath), null, destination = destination, privatePathPrefix = prefix)
    }

    suspend fun checkCurrent(target: WorkspaceFileTarget, path: String) {
        if (resolve(target.sessionId, path, writing = false, target.destination).identity != target.identity) {
            unavailable("会话工作区或挂载在操作期间发生变化，已停止访问")
        }
    }

    private fun unavailable(message: String): Nothing = throw ExternalFolderException("UNAVAILABLE", message)
}
