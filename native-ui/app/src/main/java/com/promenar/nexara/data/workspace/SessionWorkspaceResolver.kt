package com.promenar.nexara.data.workspace

import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.storage.RealPathExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 会话当前使用的工作区。私有工作区由 Room 树跟踪；自选目录不进 Room，按目录直接读写。 */
sealed interface SessionWorkspace {
    data object Private : SessionWorkspace

    /** [root] 在解析时存在且不是符号链接；目录之后仍可能被外部删除，后端每次访问重新核验。 */
    data class Directory(val workspace: WorkspaceEntity, val root: File) : SessionWorkspace {
        val kind: String get() = workspace.kind
    }

    /** 会话绑定了自选工作区但当前不可用；调用方拒绝执行，不回退到会话私有工作区。 */
    data class Unavailable(val workspaceId: String, val workspace: WorkspaceEntity?, val reason: String) : SessionWorkspace
}

/** 共享存储根只在侧载渠道可用：需要“所有文件访问”授权，并限制在共享卷内、不进入 `Android/`、不经过符号链接。 */
interface SharedWorkspaceAccess {
    /** 返回规范化后的目录；渠道不支持、未授权或目录不可用时抛出 [ExternalFolderException]。 */
    fun root(location: String): File
    fun backend(root: File): ExternalFolderBackend
}

/**
 * 统一解析会话工作区：会话设置的自选工作区优先，未设置时为会话私有工作区。
 * Agent 默认工作区只在新建会话时复制到会话上，这里不读取。
 */
class SessionWorkspaceResolver(
    private val sessionDao: SessionDao,
    private val workspaceDao: WorkspaceDao,
    /** App 私有项目根，项目文件夹位于其下的 [WorkspaceEntity.location]。 */
    val projectsRoot: File,
    private val sharedAccess: SharedWorkspaceAccess,
    private val projectBackend: (File) -> ExternalFolderBackend = ::RealPathExternalFolderBackend,
    private val linuxHome: LinuxHomeWorkspaceSource? = null,
    private val branchWorktreeResolver: com.promenar.nexara.data.branch.BranchWorktreeWorkspaceResolver? = null,
    private val resolveSharedPrivateRoot: suspend (String) -> File? = { null },
) {
    suspend fun resolve(sessionId: String?): SessionWorkspace = withContext(Dispatchers.IO) {
        val session = sessionId?.takeIf(String::isNotBlank)?.let { sessionDao.getById(it) }
        if (session?.branchPlanJson != null && session.worktreeDirectory == null) {
            val plan = com.promenar.nexara.data.branch.BranchPlanProtocol.decode(session.branchPlanJson, session.executionMode).getOrNull()
            if (plan == null || plan.workMode == com.promenar.nexara.domain.branch.BranchWorkMode.WORKTREE) {
                return@withContext SessionWorkspace.Unavailable("branch-worktree:${session.id}", null, "分支 worktree 尚未准备完成，不能使用共用工作区")
            }
        }
        if (session?.worktreeDirectory != null) {
            val id = com.promenar.nexara.data.branch.BranchWorktreeWorkspaceResolver.ID_PREFIX + session.id
            return@withContext try {
                branchWorktreeResolver?.resolve(session)
                    ?: SessionWorkspace.Unavailable(id, null, "Linux worktree 解析器不可用")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { SessionWorkspace.Unavailable(id, null, failure.message ?: "Linux worktree 目录不可用") }
        }
        val workspaceId = session?.workspaceId ?: return@withContext SessionWorkspace.Private
        resolveWorkspace(workspaceId)
    }

    /** 只返回 [SessionWorkspace.Directory] 或 [SessionWorkspace.Unavailable]。 */
    suspend fun resolveWorkspace(workspaceId: String): SessionWorkspace = withContext(Dispatchers.IO) {
        val workspace = workspaceDao.getById(workspaceId)
            ?: return@withContext SessionWorkspace.Unavailable(workspaceId, null, "工作区登记已删除")
        if (!WorkspaceEntity.isValidLocation(workspace.kind, workspace.location)) {
            return@withContext SessionWorkspace.Unavailable(workspaceId, workspace, "工作区位置格式无效")
        }
        try {
            val root = when (workspace.kind) {
                // Android 的 filesDir 位于 /data/user/0 这一系统符号链接下；目录后端逐级拒绝符号链接，
                // 因此先规范化项目根本身，项目目录仍以不跟随链接的方式核验。
                WorkspaceEntity.KIND_PROJECT -> File(projectsRoot.canonicalFile, workspace.location).also {
                    if (!Files.isDirectory(it.toPath(), NOFOLLOW_LINKS)) throw ExternalFolderException("NOT_FOUND", "项目文件夹不存在")
                }
                WorkspaceEntity.KIND_SHARED -> sharedAccess.root(workspace.location)
                WorkspaceEntity.KIND_LINUX_HOME -> linuxHome?.root(workspace.location)
                    ?: throw ExternalFolderException("UNAVAILABLE", "Linux 主目录不可用")
                "branch_shared" -> {
                    val path = (resolveSharedPrivateRoot(workspace.location)
                        ?: throw ExternalFolderException("UNAVAILABLE", "共用分支工作区根已删除")).absoluteFile.toPath().normalize()
                    if (!path.isAbsolute || path.parent == null) throw ExternalFolderException("INVALID_PATH", "共用分支工作区路径无效")
                    var current = path.root
                    for (name in path) {
                        current = current.resolve(name)
                        if (!Files.isDirectory(current, NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                            throw ExternalFolderException("UNAVAILABLE", "共用分支工作区不存在或经过符号链接")
                        }
                    }
                    path.toFile()
                }
                else -> throw ExternalFolderException("UNSUPPORTED", "未知的工作区类型")
            }
            SessionWorkspace.Directory(workspace, root)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ExternalFolderException) {
            SessionWorkspace.Unavailable(workspaceId, workspace, failure.message ?: "目录不可用")
        } catch (_: SecurityException) {
            SessionWorkspace.Unavailable(workspaceId, workspace, "目录访问被拒绝")
        } catch (_: java.io.IOException) {
            SessionWorkspace.Unavailable(workspaceId, workspace, "目录不可用")
        }
    }

    fun backend(directory: SessionWorkspace.Directory): ExternalFolderBackend = when (directory.kind) {
        WorkspaceEntity.KIND_SHARED -> sharedAccess.backend(directory.root)
        WorkspaceEntity.KIND_LINUX_HOME -> com.promenar.nexara.data.storage.LinuxHomeExternalFolderBackend(requireNotNull(linuxHome).files, LinuxWorkspaceLocation.requireLocation(directory.workspace.location).directory) {
            runCatching { linuxHome?.root(directory.workspace.location) == directory.root }.getOrDefault(false)
        }
        com.promenar.nexara.data.branch.BranchWorktreeWorkspaceResolver.KIND -> requireNotNull(branchWorktreeResolver).backend(directory)
        else -> projectBackend(directory.root)
    }

    companion object {
        /** 命令环境变量 `NEXARA_WORKSPACE_KIND` 的取值；内置 git 据此给出私有工作区 clone 提示与共享存储兼容配置。 */
        const val KIND_PRIVATE = "private"

        fun kindOf(workspace: SessionWorkspace): String = when (workspace) {
            // 现有内置 git 只识别 private/project/shared；Linux 主目录使用私有目录的执行兼容配置。
            is SessionWorkspace.Directory -> if (workspace.kind == WorkspaceEntity.KIND_LINUX_HOME || workspace.kind == com.promenar.nexara.data.branch.BranchWorktreeWorkspaceResolver.KIND || workspace.kind == "branch_shared") WorkspaceEntity.KIND_PROJECT else workspace.kind
            is SessionWorkspace.Unavailable -> workspace.workspace?.kind ?: KIND_PRIVATE
            SessionWorkspace.Private -> KIND_PRIVATE
        }
    }
}
