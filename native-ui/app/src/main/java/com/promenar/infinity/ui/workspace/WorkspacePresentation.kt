package com.promenar.infinity.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.WorkspaceLabel

/** 会话设置“工作区”项的展示数据；目录路径只用于显示，执行层每次使用时重新核验。 */
sealed interface SessionWorkspaceUi {
    data object Loading : SessionWorkspaceUi
    data object Private : SessionWorkspaceUi
    data class Directory(val workspaceId: String, val name: String, val kind: String, val location: String) : SessionWorkspaceUi
    /** 登记已删除时 [name]/[kind]/[location] 为空；界面提供“改用会话私有工作区”或“重新选择”。 */
    data class Unavailable(
        val workspaceId: String,
        val name: String?,
        val kind: String?,
        val location: String?,
        val reason: String,
    ) : SessionWorkspaceUi
}

fun SessionWorkspace.toUi(): SessionWorkspaceUi = when (this) {
    SessionWorkspace.Private -> SessionWorkspaceUi.Private
    is SessionWorkspace.Directory -> SessionWorkspaceUi.Directory(
        workspace.id, workspace.displayName, workspace.kind, workspace.location,
    )
    is SessionWorkspace.Unavailable -> SessionWorkspaceUi.Unavailable(
        workspaceId, workspace?.displayName, workspace?.kind, workspace?.location, reason,
    )
}

/** 当前会话是否固定为 Linux 模式；由聊天页提供，其它入口保持默认值。 */
val LocalSessionLinuxMode = compositionLocalOf { false }

/** 会话私有空间的名称：Linux 模式称“会话目录”（Linux 内为 /home/session），内置模式称“会话专用空间”。 */
@Composable
fun privateWorkspaceLabel(): String =
    stringResource(if (LocalSessionLinuxMode.current) R.string.workspace_kind_session_dir else R.string.workspace_kind_private)

@Composable
fun workspaceKindLabel(kind: String?): String = when (kind) {
    WorkspaceEntity.KIND_PROJECT -> stringResource(R.string.workspace_kind_project)
    WorkspaceEntity.KIND_SHARED -> stringResource(R.string.workspace_kind_shared)
    WorkspaceEntity.KIND_LINUX_HOME -> stringResource(R.string.workspace_kind_linux_home)
    else -> privateWorkspaceLabel()
}

/**
 * 单行位置文本：项目文件夹不显示 App 私有安装路径，只显示项目名；
 * 共享存储路径较深时保留最后两级，避免单行截断后看不到目录本身。
 */
@Composable
fun workspaceLocationText(kind: String, location: String): String = when (kind) {
    WorkspaceEntity.KIND_PROJECT -> stringResource(R.string.workspace_location_project, location)
    // 登记位置带环境代次标记，界面只显示容器内路径。
    WorkspaceEntity.KIND_LINUX_HOME -> location.substringBefore('#')
    else -> compactSharedPath(location)
}

/** 超过三级的绝对路径缩写为“…/倒数第二级/最后一级”。 */
internal fun compactSharedPath(location: String): String {
    val segments = location.split('/').filter(String::isNotEmpty)
    return if (segments.size <= 3) location else "…/" + segments.takeLast(2).joinToString("/")
}

@Composable
fun workspaceLabelText(label: WorkspaceLabel): String = when (label) {
    WorkspaceLabel.Private -> privateWorkspaceLabel()
    is WorkspaceLabel.Registered -> label.name
    is WorkspaceLabel.Missing -> stringResource(R.string.workspace_missing_name)
}
