package com.promenar.nexara.ui.chat.components

import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.data.workspace.SessionWorkspaceResolver
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderEntry

/**
 * 资源管理器读取会话工作区所需的最小能力。目录后端由 [SessionWorkspaceResolver.backend] 提供，
 * 沿用其符号链接拒绝、越界路径与保留名校验；资源管理器不自行拼接真实路径。
 */
interface ResourceExplorerWorkspaceAccess {
    suspend fun resolve(sessionId: String): SessionWorkspace
    fun backend(directory: SessionWorkspace.Directory): ExternalFolderBackend
}

internal class ResolverWorkspaceAccess(
    private val resolver: SessionWorkspaceResolver,
) : ResourceExplorerWorkspaceAccess {
    override suspend fun resolve(sessionId: String): SessionWorkspace = resolver.resolve(sessionId)
    override fun backend(directory: SessionWorkspace.Directory): ExternalFolderBackend = resolver.backend(directory)
}

/** 资源管理器文件区当前展示的工作区。 */
sealed interface ResourceExplorerWorkspaceMode {
    /** 首次打开或切换会话时正在解析绑定的工作区。 */
    data object Resolving : ResourceExplorerWorkspaceMode

    /** 会话私有工作区：由 Room 树跟踪，支持搜索、导入与回收站。 */
    data object Private : ResourceExplorerWorkspaceMode

    /** 自选目录工作区：直接浏览目录内容，与 Agent 文件工具的 `/` 指向同一目录。 */
    data class Directory(
        val workspaceId: String,
        val displayName: String,
        val kind: String,
        val location: String = "",
    ) : ResourceExplorerWorkspaceMode

    /** 绑定的工作区当前不可用；不回退到会话私有工作区。 */
    data class Unavailable(
        val workspaceId: String,
        val displayName: String?,
        val reason: String,
        val kind: String? = null,
        val location: String? = null,
    ) : ResourceExplorerWorkspaceMode
}

/** 目录读取失败；[reason] 只取目录后端的领域提示，其它异常为 null，由界面显示通用文案。 */
internal data class DirectoryLoadError(val reason: String?)

/** 目录工作区的浏览状态：[path] 为目录内的规范路径，`/` 即工作区根。 */
internal data class DirectoryBrowserState(
    val path: String = "/",
    val entries: List<ExternalFolderEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: DirectoryLoadError? = null,
    val selectedFile: ExternalFolderEntry? = null,
    val preview: ExternalTextPreview? = null,
    val previewError: String? = null,
)

/** 上一级目录；根目录返回 null。 */
internal fun parentDirectoryPath(path: String): String? =
    if (path == "/") null else path.substringBeforeLast('/').ifEmpty { "/" }
