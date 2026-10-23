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
import java.io.File

internal data class WorkspaceFileTarget(
    val sessionId: String,
    val root: FileEntry?,
    val directory: File?,
    val mount: ExternalMount?,
    val workspaceMount: ExternalMount? = null,
) {
    val identity: List<String?> get() = listOf(root?.uuid, root?.hash, root?.physicalRootPath, directory?.path, mount?.id, mount?.sourceType, mount?.sourceId, workspaceMount?.id)
    val mountIds: Map<String, String> get() = listOfNotNull(workspaceMount, mount).associate { it.alias to it.id }
    fun relative(path: String): String = ExternalMountPaths.parse(path)?.second ?: path
    val virtualRoot: String get() = when {
        mount == null || mount.isWorkspaceRoot -> "/"
        mount.isUserSkills -> "/skills"
        else -> "/mnt/${mount.alias}"
    }
}

/** 媒体与画布共用同一命名空间，工作区失效时拒绝回退到私有目录。 */
internal class WorkspaceFileTargetResolver(
    private val sessions: ISessionRepository,
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val sessionWorkspace: suspend (String) -> SessionWorkspace,
    private val userSkillsDirectory: File,
) {
    suspend fun resolve(sessionId: String, path: String, writing: Boolean): WorkspaceFileTarget {
        val current = sessionWorkspace(sessionId)
        if (current is SessionWorkspace.Unavailable) unavailable("当前会话工作区不可用，请检查目录与授权")
        val workspaceMount = mounts.workspaceMount(sessionId)
        if (current is SessionWorkspace.Directory && workspaceMount == null || current is SessionWorkspace.Private && workspaceMount != null) {
            unavailable("会话工作区正在变化，请重新执行文件操作")
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
            return WorkspaceFileTarget(sessionId, null, directory, mount, workspaceMount)
        }
        if (path == "/mnt") return WorkspaceFileTarget(sessionId, null, null, null, workspaceMount)
        val session = sessions.getById(sessionId) ?: unavailable("会话不存在，无法访问工作区")
        val root = session.workspaceRootUuid?.let { workspace.getByUuid(it, it) }
            ?: if (writing) workspace.ensureSessionRoot(sessionId) else unavailable("会话私有工作区尚不可用")
        return WorkspaceFileTarget(sessionId, root, File(root.physicalRootPath), null)
    }

    suspend fun checkCurrent(target: WorkspaceFileTarget, path: String) {
        if (resolve(target.sessionId, path, writing = false).identity != target.identity) {
            unavailable("会话工作区或挂载在操作期间发生变化，已停止访问")
        }
    }

    private fun unavailable(message: String): Nothing = throw ExternalFolderException("UNAVAILABLE", message)
}
