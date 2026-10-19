package com.promenar.nexara.ui.workspace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import com.promenar.nexara.ui.common.NexaraSettingsSection

/** Agent 编辑页“默认工作区”项：显示名称与路径，可清除回到会话私有工作区。 */
@Composable
fun AgentDefaultWorkspaceSection(
    workspaceId: String?,
    workspace: WorkspaceEntity?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NexaraSettingsSection(
        title = stringResource(R.string.workspace_agent_default),
        footer = stringResource(R.string.workspace_agent_footer),
        modifier = modifier.testTag("agent_default_workspace_section"),
    ) {
        val missing = workspaceId != null && workspace == null
        NexaraSettingsItem(
            title = when {
                workspace != null -> workspace.displayName
                missing -> stringResource(R.string.workspace_missing_name)
                else -> stringResource(R.string.workspace_agent_default_none)
            },
            subtitle = workspace?.let { workspaceKindLabel(it.kind) + " · " + compactSharedPath(it.location) },
            subtitleIsIdentifier = workspace != null,
            onClick = onChoose,
            showChevron = true,
            leadingContent = {
                if (missing) Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                else WorkspaceKindIcon(workspace?.kind)
            },
            modifier = Modifier.testTag("agent_default_workspace"),
        )
        if (workspaceId != null) NexaraSettingsItem(
            icon = Icons.Rounded.Close,
            title = stringResource(R.string.workspace_agent_clear),
            onClick = onClear,
            modifier = Modifier.testTag("agent_default_workspace_clear"),
        )
    }
}
