package com.promenar.nexara.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.NexaraSettingsRowLayout
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forReading

/**
 * 资源管理器顶部的工作区栏：当前工作区占一行，行尾切换；目录或不可用时可改回会话私有工作区。
 * 生成进行中或有待审批时禁止切换，[busy] 时按钮停用并说明原因。
 */
@Composable
fun SessionWorkspaceBar(
    workspace: SessionWorkspaceUi,
    busy: Boolean,
    modifier: Modifier = Modifier,
    switchFailed: Boolean = false,
    footnote: String? = null,
    onChoose: () -> Unit = {},
    onUsePrivate: () -> Unit = {},
) {
    val canSwitch = !busy && workspace != SessionWorkspaceUi.Loading
    BettboxListGroup(modifier = modifier.testTag("session_workspace_bar")) {
        NexaraSettingsRowLayout(
            title = when (workspace) {
                SessionWorkspaceUi.Loading -> stringResource(R.string.workspace_session_current)
                SessionWorkspaceUi.Private -> stringResource(R.string.workspace_kind_private)
                is SessionWorkspaceUi.Directory -> workspace.name
                is SessionWorkspaceUi.Unavailable -> workspace.name ?: stringResource(R.string.workspace_missing_name)
            },
            subtitle = when (workspace) {
                SessionWorkspaceUi.Loading -> "…"
                SessionWorkspaceUi.Private -> stringResource(R.string.workspace_session_private_desc)
                is SessionWorkspaceUi.Directory -> workspaceKindLabel(workspace.kind) + " · " + compactSharedPath(workspace.location)
                is SessionWorkspaceUi.Unavailable -> workspace.kind?.let { kind ->
                    workspaceKindLabel(kind) + workspace.location?.let { " · " + compactSharedPath(it) }.orEmpty()
                }
            },
            subtitleIsIdentifier = workspace is SessionWorkspaceUi.Directory || workspace is SessionWorkspaceUi.Unavailable,
            leadingContent = {
                when (workspace) {
                    is SessionWorkspaceUi.Unavailable -> Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                    is SessionWorkspaceUi.Directory -> WorkspaceKindIcon(workspace.kind)
                    else -> WorkspaceKindIcon(null)
                }
            },
            modifier = Modifier.testTag("session_workspace_current"),
            trailingContent = {
                TextButton(
                    onClick = onChoose,
                    enabled = canSwitch,
                    modifier = Modifier.testTag("session_workspace_choose"),
                ) {
                    Text(
                        stringResource(
                            if (workspace is SessionWorkspaceUi.Unavailable) R.string.workspace_session_reselect
                            else R.string.workspace_bar_switch,
                        ),
                    )
                }
            },
        )
        // 错误类说明（不可用原因、切换失败）用错误色，其余为次要说明。
        val errors = listOfNotNull(
            (workspace as? SessionWorkspaceUi.Unavailable)?.let { stringResource(R.string.workspace_unavailable_reason, it.reason) },
            if (switchFailed) stringResource(R.string.workspace_switch_failed) else null,
        )
        val notes = listOfNotNull(if (busy) stringResource(R.string.workspace_session_busy) else null, footnote)
        if (errors.isNotEmpty() || notes.isNotEmpty() || workspace.canUsePrivate) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NexaraSpacing.RowHorizontal)
                    .padding(bottom = NexaraSpacing.Small),
            ) {
                errors.forEach { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("session_workspace_error"),
                    )
                }
                notes.forEach { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (workspace.canUsePrivate) {
                    TextButton(
                        onClick = onUsePrivate,
                        enabled = !busy,
                        modifier = Modifier.testTag("session_workspace_use_private"),
                    ) { Text(stringResource(R.string.workspace_session_use_private)) }
                }
            }
        }
    }
}
