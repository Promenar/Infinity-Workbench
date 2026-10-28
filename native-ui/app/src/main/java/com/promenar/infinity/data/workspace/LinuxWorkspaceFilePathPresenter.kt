package com.promenar.infinity.data.workspace

import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.ExternalFolderPaths

/** 逻辑落点使用会话持久模式呈现；私有主根的两个 Linux 入口指向同一目录。 */
object LinuxWorkspaceFilePathPresenter : WorkspaceFilePathPresenter {
    override fun present(destination: WorkspaceDefaultFileDestination, relativePath: String, privatePrimary: Boolean): String {
        val root = if (destination == WorkspaceDefaultFileDestination.SESSION_PRIVATE || privatePrimary) {
            LinuxSessionPaths.PRIVATE_ROOT
        } else LinuxSessionPaths.WORKSPACE_ROOT
        return root + ExternalFolderPaths.normalize(relativePath).takeUnless { it == "/" }.orEmpty()
    }

    override fun privateRelativePath(path: String): String? {
        val normalized = LinuxSessionPaths.normalize(path)
        return listOf(LinuxSessionPaths.PRIVATE_ROOT, LinuxSessionPaths.WORKSPACE_ROOT).firstNotNullOfOrNull { root ->
            when {
                normalized == root -> "/"
                normalized.startsWith("$root/") -> normalized.removePrefix(root)
                else -> null
            }
        }
    }
}

fun workspaceFilePathPresenter(mode: SessionRunMode?): WorkspaceFilePathPresenter =
    if (mode == SessionRunMode.LINUX) LinuxWorkspaceFilePathPresenter else BuiltinWorkspaceFilePathPresenter

internal fun workspaceFileRelativePath(path: String): String =
    LinuxWorkspaceFilePathPresenter.privateRelativePath(path) ?: SessionPrivateWorkspacePaths.relative(path) ?: path
