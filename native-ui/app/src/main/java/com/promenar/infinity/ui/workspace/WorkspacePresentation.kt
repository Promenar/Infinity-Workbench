package com.promenar.infinity.ui.workspace

import androidx.compose.runtime.Composable
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

/** 会话私有空间的名称；两种运行模式下是同一个文件夹（Linux 中挂载为 /home/session），统一称“本会话文件夹”。 */
@Composable
fun privateWorkspaceLabel(): String = stringResource(R.string.workspace_kind_private)

/** 工作区的存放位置：会话私有空间与共享文件夹都在应用内存储。 */
@Composable
fun workspaceStorageLabel(kind: String?): String = when (kind) {
    WorkspaceEntity.KIND_SHARED -> stringResource(R.string.workspace_kind_shared)
    WorkspaceEntity.KIND_LINUX_HOME -> stringResource(R.string.workspace_kind_linux_home)
    else -> stringResource(R.string.workspace_kind_project)
}

/** 工作区类型名：私有空间显示为“本会话文件夹”，其余显示存放位置。 */
@Composable
fun workspaceKindLabel(kind: String?): String = if (kind == null) privateWorkspaceLabel() else workspaceStorageLabel(kind)

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
