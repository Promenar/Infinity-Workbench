package com.promenar.nexara.data.branch

import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.storage.RealPathExternalFolderBackend
import com.promenar.nexara.data.storage.AndroidRealPathFileSystem
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** 以已核验的 Linux worktree 目录作为工作区根，不登记新的用户工作区或回退到父目录。 */
class BranchWorktreeWorkspaceResolver(
    private val runtime: LinuxRuntime,
    /** `/workspace`、`/mnt` 绑定不属于 rootfs；由装配点按父会话的真实挂载映射。 */
    private val resolveMountedDirectory: suspend (SessionEntity, String) -> File? = { _, _ -> null },
) {
    suspend fun resolve(session: SessionEntity): SessionWorkspace.Directory = withContext(Dispatchers.IO) {
        if (runtime.state.value !is LinuxRuntimeState.Ready) unavailable("Linux 环境未就绪")
        val guest = BranchWorktreeCommands.absoluteDirectory(requireNotNull(session.worktreeDirectory) { "会话没有 worktree 目录" })
        val mounted = guest == "/workspace" || guest.startsWith("/workspace/") || guest == "/mnt" || guest.startsWith("/mnt/")
        val root = if (mounted) {
            resolveMountedDirectory(session, guest)?.also(::requireDirectory)
                ?: unavailable("worktree 所在的 Linux 挂载不可用")
        } else {
            // 仅规范化系统安装根；其下逐级 NOFOLLOW，拒绝容器链接被宿主 File 语义错误解释。
            val configured = runtime.paths.rootfs.absoluteFile
            val rootfs = File(requireNotNull(configured.parentFile).canonicalFile, configured.name)
            requireDirectory(rootfs)
            guest.removePrefix("/").split('/').fold(rootfs) { parent, name -> File(parent, name).also(::requireDirectory) }
        }
        SessionWorkspace.Directory(
            WorkspaceEntity("$ID_PREFIX${session.id}", KIND, session.title, guest, session.createdAt), root,
        )
    }

    fun backend(directory: SessionWorkspace.Directory): ExternalFolderBackend {
        require(directory.kind == KIND) { "工作区不是分支 worktree" }
        return RealPathExternalFolderBackend(directory.root, emptySet(),
            { runtime.state.value is LinuxRuntimeState.Ready }, AndroidRealPathFileSystem)
    }

    private fun requireDirectory(directory: File) {
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) || Files.isSymbolicLink(directory.toPath())) {
            unavailable("worktree 目录不存在或经过符号链接")
        }
    }

    private fun unavailable(message: String): Nothing = throw ExternalFolderException("UNAVAILABLE", message)

    companion object {
        const val KIND = "branch_worktree"
        const val ID_PREFIX = "branch-worktree:"
    }
}
