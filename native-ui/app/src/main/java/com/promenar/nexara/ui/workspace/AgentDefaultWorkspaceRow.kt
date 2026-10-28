package com.promenar.nexara.ui.workspace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.ui.common.NexaraSettingsItem

/** 智能体编辑页“新会话默认”中的默认工作区一行：显示名称与路径；选择面板中可选回会话私有工作区。 */
@Composable
fun AgentDefaultWorkspaceRow(
    workspaceId: String?,
    workspace: WorkspaceEntity?,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val missing = workspaceId != null && workspace == null
    NexaraSettingsItem(
        title = stringResource(R.string.workspace_agent_default),
        subtitle = when {
            workspace != null -> workspace.displayName + " · " + compactSharedPath(workspace.location)
            missing -> stringResource(R.string.workspace_missing_name)
            else -> stringResource(R.string.workspace_agent_default_none)
        },
        onClick = onChoose,
        showChevron = true,
        leadingContent = {
            if (missing) Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
            else WorkspaceKindIcon(workspace?.kind)
        },
        modifier = modifier.testTag("agent_default_workspace"),
    )
}
