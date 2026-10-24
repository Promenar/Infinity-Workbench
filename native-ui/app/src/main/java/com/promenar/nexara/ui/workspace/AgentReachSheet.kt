package com.promenar.nexara.ui.workspace

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.domain.model.ExecutionModeCodec
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsRowLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraSheetHeight
import com.promenar.nexara.ui.settings.LanHostsEditorSheet
import com.promenar.nexara.ui.settings.NetworkAccessRows
import com.promenar.nexara.ui.settings.lanHostsSummary
import com.promenar.nexara.ui.settings.saveLanHosts
import com.promenar.nexara.ui.settings.tailnetSummary
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forReading

internal data class AgentReachActions(
    val onEditLanHosts: () -> Unit = {},
    /** 所在页面无法跳转设置时为 null，Tailscale 行只显示状态。 */
    val onOpenTailscale: (() -> Unit)? = null,
)

/** 会话资源管理器中的“Agent 可达范围”面板；[executionMode] 为会话保存的执行模式原值。 */
@Composable
fun AgentReachSheet(
    sessionId: String,
    executionMode: String?,
    onDismiss: () -> Unit,
    onOpenTailscaleSettings: (() -> Unit)? = null,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: AgentReachViewModel = viewModel(
        key = "agent-reach-$sessionId",
        factory = AgentReachViewModel.factory(application, sessionId),
    )
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingHosts by remember { mutableStateOf(false) }
    NexaraBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.agent_reach_title),
        height = NexaraSheetHeight.Full,
    ) {
        AgentReachContent(
            state = state.copy(executionMode = ExecutionModeCodec.parseOrSemi(executionMode)),
            actions = AgentReachActions(
                onEditLanHosts = { editingHosts = true },
                onOpenTailscale = onOpenTailscaleSettings?.let { open -> { onDismiss(); open() } },
            ),
        )
    }
    val access = (application as NexaraApplication).agentNetworkAccess
    LanHostsEditorSheet(
        show = editingHosts,
        stored = state.network?.lanHosts.orEmpty().joinToString("\n"),
        onDismiss = { editingHosts = false },
        onSave = { text -> saveLanHosts(access::setLanHosts, text) },
    )
}

/**
 * 面板正文，按“在哪里 → 路径怎么解释 → 谁在执行 → 什么时候问我 → 能连到哪里”的顺序排列，
 * 每一节只陈述当前会话的事实，与执行层规则一一对应。
 */
@Composable
internal fun AgentReachContent(
    state: AgentReachState,
    actions: AgentReachActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = NexaraSpacing.XLarge)
            .testTag("agent_reach_content"),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.SectionGap),
    ) {
        Text(
            text = stringResource(R.string.agent_reach_intro),
            style = MaterialTheme.typography.bodyMedium.forReading(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WorkspaceSection(state)
        PathSection(state)
        BackendSection(state)
        ApprovalSection(state)
        NetworkSection(state, actions)
    }
}

@Composable
private fun WorkspaceSection(state: AgentReachState) {
    val workspace = state.workspace
    NexaraSettingsSection(
        title = stringResource(R.string.agent_reach_section_workspace),
        footer = stringResource(R.string.agent_reach_workspace_footer),
    ) {
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
                is SessionWorkspaceUi.Directory -> workspaceKindLabel(workspace.kind) + " · " + workspaceLocationText(workspace.kind, workspace.location)
                is SessionWorkspaceUi.Unavailable -> workspace.reason.ifBlank { stringResource(R.string.agent_reach_workspace_unavailable) }
            },
            subtitleIsIdentifier = workspace is SessionWorkspaceUi.Directory,
            subtitleMaxLines = 2,
            leadingContent = {
                if (workspace is SessionWorkspaceUi.Unavailable) {
                    Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                } else {
                    WorkspaceKindIcon((workspace as? SessionWorkspaceUi.Directory)?.kind)
                }
            },
            modifier = Modifier.testTag("agent_reach_workspace"),
        )
        workspace.traits?.let { traits ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NexaraSpacing.RowHorizontal)
                    .padding(bottom = NexaraSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                TraitLine(traits.tracked, R.string.agent_reach_trait_tracked_yes, R.string.agent_reach_trait_tracked_no)
                TraitLine(traits.backedUp, R.string.agent_reach_trait_backup_yes, R.string.agent_reach_trait_backup_no)
                TraitLine(traits.termuxVisible, R.string.agent_reach_trait_termux_yes, R.string.agent_reach_trait_termux_no)
                TraitLine(traits.survivesUninstall, R.string.agent_reach_trait_uninstall_yes, R.string.agent_reach_trait_uninstall_no)
            }
        }
    }
}

@Composable
private fun TraitLine(value: Boolean, yes: Int, no: Int) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
        Icon(
            imageVector = if (value) Icons.Rounded.Check else Icons.Rounded.Close,
            contentDescription = null,
            tint = if (value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(NexaraSpacing.RowIconSize),
        )
        Text(
            text = stringResource(if (value) yes else no),
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PathRow(path: String, meaning: String, tag: String) {
    NexaraSettingsRowLayout(
        title = path,
        subtitle = meaning,
        titleTextStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        subtitleMaxLines = 5,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun PathSection(state: AgentReachState) {
    NexaraSettingsSection(
        title = stringResource(R.string.agent_reach_section_paths),
        footer = stringResource(R.string.agent_reach_paths_footer),
    ) {
        PathRow("/", stringResource(R.string.agent_reach_path_root), "agent_reach_path_root")
        PathRow("\$NEXARA_WORKSPACE", stringResource(R.string.agent_reach_path_workspace_var), "agent_reach_path_workspace_var")
        PathRow("/skills", stringResource(R.string.agent_reach_path_skills), "agent_reach_path_skills")
        if (state.mounts.isEmpty()) {
            PathRow("/mnt", stringResource(R.string.agent_reach_path_mnt_empty), "agent_reach_path_mnt_empty")
        } else {
            state.mounts.forEach { mount ->
                val attributes = listOf(
                    stringResource(if (mount.source == ReachMountSource.PATH) R.string.agent_reach_mount_path else R.string.agent_reach_mount_saf),
                    stringResource(if (mount.global) R.string.mount_scope_global else R.string.mount_scope_current),
                    stringResource(if (mount.readOnly) R.string.agent_reach_mount_read_only else R.string.agent_reach_mount_read_write),
                ) + listOfNotNull(if (mount.unavailable) stringResource(R.string.agent_reach_mount_unavailable) else null)
                PathRow(
                    "/mnt/${mount.alias}",
                    attributes.joinToString(" · ") + "\n" + stringResource(R.string.agent_reach_path_mnt),
                    "agent_reach_path_mount",
                )
            }
        }
    }
}

@Composable
private fun BackendSection(state: AgentReachState) {
    val traits = state.workspace.traits
    NexaraSettingsSection(
        title = stringResource(R.string.agent_reach_section_backends),
        footer = stringResource(R.string.agent_reach_backends_footer),
    ) {
        state.backends.forEach { backend ->
            val (icon, title, scope) = when (backend.kind) {
                ReachBackendKind.FILES -> Triple(Icons.Rounded.Description, R.string.agent_reach_backend_files, R.string.agent_reach_backend_files_scope)
                ReachBackendKind.BUILTIN -> Triple(Icons.Rounded.Terminal, R.string.agent_reach_backend_builtin, R.string.agent_reach_backend_builtin_scope)
                ReachBackendKind.SANDBOX -> Triple(Icons.Rounded.Code, R.string.agent_reach_backend_sandbox, R.string.agent_reach_backend_sandbox_scope)
                ReachBackendKind.TERMUX -> Triple(
                    Icons.Rounded.PhoneAndroid,
                    R.string.agent_reach_backend_termux,
                    if (traits?.termuxVisible == true) R.string.agent_reach_backend_termux_scope_shared else R.string.agent_reach_backend_termux_scope,
                )
                ReachBackendKind.SSH -> Triple(Icons.Rounded.Dns, R.string.agent_reach_backend_ssh, R.string.agent_reach_backend_ssh_scope)
            }
            NexaraSettingsRowLayout(
                title = stringResource(title),
                subtitle = backendStatusText(backend) + " · " + stringResource(scope),
                icon = icon,
                subtitleMaxLines = 6,
                enabled = backend.status != ReachBackendStatus.DISABLED,
                modifier = Modifier.testTag("agent_reach_backend_${backend.kind.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun backendStatusText(backend: ReachBackend): String = when (backend.status) {
    ReachBackendStatus.READY -> if (backend.kind == ReachBackendKind.SSH && backend.count != null) {
        pluralStringResource(R.plurals.agent_reach_ssh_hosts, backend.count, backend.count)
    } else stringResource(R.string.agent_reach_status_ready)
    ReachBackendStatus.SHELL_ONLY -> stringResource(R.string.agent_reach_status_shell_only)
    ReachBackendStatus.JS_ONLY -> stringResource(R.string.agent_reach_status_js_only)
    ReachBackendStatus.DISABLED -> stringResource(R.string.agent_reach_status_disabled)
    ReachBackendStatus.NOT_INSTALLED -> stringResource(R.string.settings_termux_not_installed)
    ReachBackendStatus.PERMISSION_REQUIRED -> stringResource(R.string.settings_termux_permission_required)
    ReachBackendStatus.UNSUPPORTED_VERSION -> stringResource(R.string.settings_termux_unsupported_version)
    ReachBackendStatus.NO_HOSTS -> stringResource(R.string.agent_reach_status_no_hosts)
    ReachBackendStatus.LOADING -> stringResource(R.string.settings_status_loading)
}

@Composable
private fun ApprovalSection(state: AgentReachState) {
    NexaraSettingsSection(
        title = stringResource(R.string.agent_reach_section_approval),
        footer = stringResource(R.string.agent_reach_approval_footer),
    ) {
        NexaraSettingsRowLayout(
            title = stringResource(
                when (state.executionMode) {
                    ExecutionMode.AUTO -> R.string.common_mode_auto
                    ExecutionMode.SEMI -> R.string.common_mode_semi
                    ExecutionMode.MANUAL -> R.string.common_mode_manual
                },
            ),
            subtitle = stringResource(
                when (state.executionMode) {
                    ExecutionMode.AUTO -> R.string.agent_reach_mode_auto
                    ExecutionMode.SEMI -> R.string.agent_reach_mode_semi
                    ExecutionMode.MANUAL -> R.string.agent_reach_mode_manual
                },
            ),
            icon = Icons.Rounded.VerifiedUser,
            subtitleMaxLines = 5,
            modifier = Modifier.testTag("agent_reach_mode"),
        )
        NexaraSettingsRowLayout(
            title = stringResource(R.string.agent_reach_always_allowed),
            subtitle = when (val count = state.alwaysAllowedCount) {
                null -> stringResource(R.string.settings_status_loading)
                0 -> stringResource(R.string.agent_reach_always_allowed_none)
                else -> pluralStringResource(R.plurals.agent_reach_always_allowed_count, count, count)
            },
            leadingSpacer = true,
        )
    }
}

@Composable
private fun NetworkSection(state: AgentReachState, actions: AgentReachActions) {
    NexaraSettingsSection(
        title = stringResource(R.string.agent_reach_section_network),
        footer = stringResource(R.string.network_section_matrix_footer),
    ) {
        val network = state.network
        if (network == null) {
            NexaraSettingsRowLayout(title = stringResource(R.string.settings_status_loading))
            return@NexaraSettingsSection
        }
        NexaraSettingsItem(
            icon = Icons.Rounded.Dns,
            title = stringResource(R.string.network_lan_hosts_title),
            subtitle = lanHostsSummary(network),
            onClick = actions.onEditLanHosts,
            showChevron = true,
            subtitleMaxLines = 2,
            modifier = Modifier.testTag("agent_reach_lan_hosts"),
        )
        val openTailscale = actions.onOpenTailscale
        if (openTailscale != null) {
            NexaraSettingsItem(
                icon = Icons.Rounded.Hub,
                title = stringResource(R.string.network_tailnet_title),
                subtitle = tailnetSummary(network),
                onClick = openTailscale,
                showChevron = true,
                subtitleMaxLines = 2,
            )
        } else {
            NexaraSettingsRowLayout(
                title = stringResource(R.string.network_tailnet_title),
                subtitle = tailnetSummary(network),
                icon = Icons.Rounded.Hub,
                subtitleMaxLines = 2,
            )
        }
        NetworkAccessRows(network)
    }
}
