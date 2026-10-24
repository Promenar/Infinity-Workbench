package com.promenar.nexara.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forReading

/**
 * 资源管理器顶部的工作区栏：一行突出当前工作区名称与类型，行尾是可达范围与切换。
 * 改回会话专用空间在选择器中完成；生成进行中或有待审批时禁止切换。
 */
@Composable
fun SessionWorkspaceBar(
    workspace: SessionWorkspaceUi,
    busy: Boolean,
    modifier: Modifier = Modifier,
    switchFailed: Boolean = false,
    onChoose: () -> Unit = {},
    /** 打开“Agent 可达范围”面板；为 null 时不显示该按钮。 */
    onOpenReach: (() -> Unit)? = null,
) {
    val canSwitch = !busy && workspace != SessionWorkspaceUi.Loading
    BettboxListGroup(modifier = modifier.testTag("session_workspace_bar")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.RowTwoLineHeight)
                .padding(start = NexaraSpacing.RowHorizontal, end = NexaraSpacing.Small)
                .testTag("session_workspace_current"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (workspace) {
                is SessionWorkspaceUi.Unavailable -> Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                is SessionWorkspaceUi.Directory -> WorkspaceKindIcon(workspace.kind)
                else -> WorkspaceKindIcon(null)
            }
            Spacer(Modifier.size(NexaraSpacing.Medium))
            Column(Modifier.weight(1f).padding(vertical = NexaraSpacing.Small), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall)) {
                Text(
                    text = when (workspace) {
                        SessionWorkspaceUi.Loading -> "…"
                        SessionWorkspaceUi.Private -> stringResource(R.string.workspace_kind_private)
                        is SessionWorkspaceUi.Directory -> workspace.name
                        is SessionWorkspaceUi.Unavailable -> workspace.name ?: stringResource(R.string.workspace_missing_name)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 目录工作区标出所属类型；专用空间的名称已是类型，改为提示它会随会话删除。
                when (workspace) {
                    SessionWorkspaceUi.Private -> workspaceKindTags(null).last().let { tag ->
                        WorkspaceTagChip(stringResource(tag.label), tag.tone)
                    }
                    is SessionWorkspaceUi.Directory -> WorkspaceTagChip(workspaceKindLabel(workspace.kind), WorkspaceTagTone.NEUTRAL)
                    is SessionWorkspaceUi.Unavailable -> workspace.kind?.let {
                        WorkspaceTagChip(workspaceKindLabel(it), WorkspaceTagTone.NEUTRAL)
                    }
                    SessionWorkspaceUi.Loading -> Unit
                }
            }
            if (onOpenReach != null) {
                IconButton(onClick = onOpenReach, modifier = Modifier.testTag("session_workspace_reach")) {
                    Icon(Icons.Outlined.Explore, contentDescription = stringResource(R.string.agent_reach_title))
                }
            }
            TextButton(onClick = onChoose, enabled = canSwitch, modifier = Modifier.testTag("session_workspace_choose")) {
                Text(
                    stringResource(
                        if (workspace is SessionWorkspaceUi.Unavailable) R.string.workspace_session_reselect
                        else R.string.workspace_bar_switch,
                    ),
                )
            }
        }
        val errors = listOfNotNull(
            (workspace as? SessionWorkspaceUi.Unavailable)?.let { stringResource(R.string.workspace_unavailable_reason, it.reason) },
            if (switchFailed) stringResource(R.string.workspace_switch_failed) else null,
        )
        val notes = listOfNotNull(if (busy) stringResource(R.string.workspace_session_busy) else null)
        if (errors.isNotEmpty() || notes.isNotEmpty()) {
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
            }
        }
    }
}

