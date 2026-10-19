package com.promenar.nexara.ui.workspace

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.workspace.WorkspaceSwitchResult
import com.promenar.nexara.ui.chat.ChatViewModel
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forReading
import kotlinx.coroutines.CancellationException

/** 会话设置中的“工作区”项：解析当前工作区并提供切换入口；生成进行中禁止切换。 */
@Composable
fun SessionWorkspaceSettings(session: Session?, chatViewModel: ChatViewModel, isGenerating: Boolean) {
    val app = LocalContext.current.applicationContext as? NexaraApplication ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val sessionId = session?.id
    val workspace by produceState<SessionWorkspaceUi>(SessionWorkspaceUi.Loading, sessionId, session?.workspaceId, refresh) {
        value = try {
            app.sessionWorkspaceResolver.resolve(sessionId).toUi()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SessionWorkspaceUi.Unavailable(session?.workspaceId.orEmpty(), null, null, null, failure.message ?: "目录不可用")
        }
    }
    val notice by chatViewModel.workspaceSwitchNotice.collectAsStateWithLifecycle()
    var failed by remember(sessionId) { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val busy = sessionId == null || isGenerating || session.approvalRequest != null
    val onResult: (WorkspaceSwitchResult) -> Unit = { result ->
        failed = result is WorkspaceSwitchResult.Failed || result is WorkspaceSwitchResult.RejectedBusy
    }
    val noticeText = notice?.takeIf { it.sessionId == sessionId }?.let {
        stringResource(R.string.workspace_switch_notice, workspaceLabelText(it.from), workspaceLabelText(it.to))
    }

    SessionWorkspaceSection(
        workspace = workspace,
        busy = busy,
        notice = if (failed) stringResource(R.string.workspace_switch_failed) else noticeText,
        onChoose = { picking = true },
        onUsePrivate = { chatViewModel.switchSessionWorkspace(null, onResult) },
    )
    WorkspacePickerSheet(
        show = picking && !busy,
        onDismiss = { picking = false },
        currentWorkspaceId = session?.workspaceId,
        onPicked = { picked ->
            picking = false
            chatViewModel.switchSessionWorkspace(picked.id, onResult)
        },
    )
}

/** 无状态展示：当前工作区名称、类型与路径；不可用时给出原因与恢复操作。 */
@Composable
fun SessionWorkspaceSection(
    workspace: SessionWorkspaceUi,
    busy: Boolean,
    modifier: Modifier = Modifier,
    notice: String? = null,
    onChoose: () -> Unit = {},
    onUsePrivate: () -> Unit = {},
) {
    val footer = listOfNotNull(
        notice,
        if (busy) stringResource(R.string.workspace_session_busy) else stringResource(R.string.workspace_session_footer),
    ).joinToString("\n")
    NexaraSettingsSection(
        title = stringResource(R.string.workspace_section_title),
        footer = footer,
        modifier = modifier.testTag("session_workspace_section"),
    ) {
        when (workspace) {
            SessionWorkspaceUi.Loading -> NexaraSettingsItem(
                title = stringResource(R.string.workspace_session_current),
                subtitle = "…",
                onClick = {},
                enabled = false,
            )
            SessionWorkspaceUi.Private -> NexaraSettingsItem(
                title = stringResource(R.string.workspace_kind_private),
                subtitle = stringResource(R.string.workspace_session_private_desc),
                onClick = onChoose,
                enabled = !busy,
                leadingContent = { WorkspaceKindIcon(null) },
                modifier = Modifier.testTag("session_workspace_current"),
            )
            is SessionWorkspaceUi.Directory -> NexaraSettingsItem(
                title = workspace.name,
                subtitle = workspaceKindLabel(workspace.kind) + " · " + compactSharedPath(workspace.location),
                subtitleIsIdentifier = true,
                onClick = onChoose,
                enabled = !busy,
                leadingContent = { WorkspaceKindIcon(workspace.kind) },
                modifier = Modifier.testTag("session_workspace_current"),
            )
            is SessionWorkspaceUi.Unavailable -> {
                NexaraSettingsItem(
                    title = workspace.name ?: stringResource(R.string.workspace_missing_name),
                    subtitle = workspace.kind?.let { kind -> workspaceKindLabel(kind) + " · " + compactSharedPath(workspace.location.orEmpty()) },
                    subtitleIsIdentifier = true,
                    onClick = onChoose,
                    enabled = !busy,
                    leadingContent = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.testTag("session_workspace_current"),
                )
                Text(
                    text = stringResource(R.string.workspace_unavailable_reason, workspace.reason),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NexaraSpacing.RowHorizontal)
                        .padding(bottom = NexaraSpacing.Small)
                        .testTag("session_workspace_unavailable_reason"),
                )
            }
        }
        if (workspace is SessionWorkspaceUi.Unavailable) {
            NexaraSettingsItem(
                icon = Icons.Rounded.Lock,
                title = stringResource(R.string.workspace_session_use_private),
                onClick = onUsePrivate,
                enabled = !busy,
                modifier = Modifier.testTag("session_workspace_use_private"),
            )
            NexaraSettingsItem(
                icon = Icons.Rounded.SwapHoriz,
                title = stringResource(R.string.workspace_session_reselect),
                onClick = onChoose,
                enabled = !busy,
                showChevron = true,
                modifier = Modifier.testTag("session_workspace_choose"),
            )
        } else if (workspace != SessionWorkspaceUi.Loading) {
            NexaraSettingsItem(
                icon = Icons.Rounded.SwapHoriz,
                title = stringResource(R.string.workspace_session_choose),
                onClick = onChoose,
                enabled = !busy,
                showChevron = true,
                modifier = Modifier.testTag("session_workspace_choose"),
            )
            if (workspace.canUsePrivate) NexaraSettingsItem(
                icon = Icons.Rounded.Lock,
                title = stringResource(R.string.workspace_session_use_private),
                onClick = onUsePrivate,
                enabled = !busy,
                modifier = Modifier.testTag("session_workspace_use_private"),
            )
        }
    }
}
