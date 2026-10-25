package com.promenar.nexara.ui.workspace

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

internal data class AgentReachActions(
    val onEditLanHosts: () -> Unit = {},
    /** 所在页面无法跳转设置时为 null，Tailscale 行只显示状态。 */
    val onOpenTailscale: (() -> Unit)? = null,
    /** 非 null 时 Linux 行可点按，进入 Linux 环境页安装、修复或开关。 */
    val onOpenLinux: (() -> Unit)? = null,
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
    val linuxNavigation = com.promenar.nexara.ui.linux.LocalLinuxNavigation.current
    NexaraBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.agent_reach_title),
        height = NexaraSheetHeight.Full,
    ) {
        AgentReachContent(
            state = state.copy(executionMode = ExecutionModeCodec.parseOrWorkspace(executionMode)),
            actions = AgentReachActions(
                onEditLanHosts = { editingHosts = true },
                onOpenTailscale = onOpenTailscaleSettings?.let { open -> { onDismiss(); open() } },
                onOpenLinux = linuxNavigation?.let { navigation -> { onDismiss(); navigation.openEnvironment() } },
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
        WorkspaceSection(state)
        PathSection(state)
        BackendSection(state, actions)
        ApprovalSection(state)
        NetworkSection(state, actions)
    }
}

@Composable
private fun WorkspaceSection(state: AgentReachState) {
    val workspace = state.workspace
    NexaraSettingsSection(title = stringResource(R.string.agent_reach_section_workspace)) {
        NexaraSettingsRowLayout(
            title = when (workspace) {
                SessionWorkspaceUi.Loading -> "…"
                SessionWorkspaceUi.Private -> stringResource(R.string.workspace_kind_private)
                is SessionWorkspaceUi.Directory -> workspace.name
                is SessionWorkspaceUi.Unavailable -> workspace.name ?: stringResource(R.string.workspace_missing_name)
            },
            subtitle = when (workspace) {
                is SessionWorkspaceUi.Directory -> workspaceKindLabel(workspace.kind)
                is SessionWorkspaceUi.Unavailable -> workspace.reason.ifBlank { stringResource(R.string.agent_reach_workspace_unavailable) }
                else -> null
            },
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
        if (workspace is SessionWorkspaceUi.Private || workspace is SessionWorkspaceUi.Directory) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NexaraSpacing.RowHorizontal)
                    .padding(bottom = NexaraSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
            ) {
                workspaceKindTags((workspace as? SessionWorkspaceUi.Directory)?.kind).forEach { tag ->
                    WorkspaceTagChip(stringResource(tag.label), tag.tone)
                }
            }
        }
    }
}

@Composable
private fun PathRow(path: String, meaning: String, tag: String) {
    NexaraSettingsRowLayout(
        title = path,
        subtitle = meaning,
        titleTextStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        subtitleMaxLines = 2,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun PathSection(state: AgentReachState) {
    NexaraSettingsSection(
        title = stringResource(R.string.agent_reach_section_paths),
        // Linux 接管 shell_exec 时，命令看到的是 Alpine 根目录而不是设备根目录。
        footer = stringResource(if (state.backends.linuxRunsShell()) R.string.linux_reach_paths_footer else R.string.agent_reach_paths_footer),
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
                    stringResource(if (mount.commandAccessible) R.string.agent_reach_mount_command_visible else R.string.agent_reach_mount_files_only),
                ) + listOfNotNull(if (mount.unavailable) stringResource(R.string.agent_reach_mount_unavailable) else null)
                PathRow("/mnt/${mount.alias}", attributes.joinToString(" · "), "agent_reach_path_mount")
            }
        }
    }
}

@Composable
private fun BackendSection(state: AgentReachState, actions: AgentReachActions) {
    val traits = state.workspace.traits
    val builtinTitle = if (state.backends.linuxRunsShell()) R.string.linux_reach_backend_builtin_python else R.string.agent_reach_backend_builtin
    NexaraSettingsSection(
        title = stringResource(R.string.agent_reach_section_backends),
        footer = stringResource(R.string.agent_reach_backends_footer),
    ) {
        state.backends.forEach { backend ->
            val (icon, title, scope) = when (backend.kind) {
                ReachBackendKind.FILES -> Triple(Icons.Rounded.Description, R.string.agent_reach_backend_files, R.string.agent_reach_backend_files_scope)
                ReachBackendKind.BUILTIN -> Triple(Icons.Rounded.Terminal, builtinTitle, R.string.agent_reach_backend_builtin_scope)
                ReachBackendKind.LINUX -> Triple(Icons.Rounded.Terminal, R.string.linux_runner_name, R.string.linux_reach_scope)
                ReachBackendKind.SANDBOX -> Triple(Icons.Rounded.Code, R.string.agent_reach_backend_sandbox, R.string.agent_reach_backend_sandbox_scope)
                ReachBackendKind.TERMUX -> Triple(
                    Icons.Rounded.PhoneAndroid,
                    R.string.agent_reach_backend_termux,
                    if (traits?.termuxVisible == true) R.string.agent_reach_backend_termux_scope_shared else R.string.agent_reach_backend_termux_scope,
                )
                ReachBackendKind.SSH -> Triple(Icons.Rounded.Dns, R.string.agent_reach_backend_ssh, R.string.agent_reach_backend_ssh_scope)
            }
            // 可用时说明作用范围，不可用时只说状态。
            val subtitle = if (backend.status == ReachBackendStatus.READY && backend.count == null) stringResource(scope)
            else backendStatusText(backend)
            val tag = Modifier.testTag("agent_reach_backend_${backend.kind.name.lowercase()}")
            val openLinux = actions.onOpenLinux.takeIf { backend.kind == ReachBackendKind.LINUX && backend.status != ReachBackendStatus.UNAVAILABLE }
            if (openLinux != null) {
                // Linux 的安装、修复与开关都在环境页完成，行本身就是去处理的入口。
                NexaraSettingsItem(
                    icon = icon,
                    title = stringResource(title),
                    subtitle = subtitle,
                    subtitleMaxLines = 2,
                    onClick = openLinux,
                    showChevron = true,
                    modifier = tag,
                )
            } else {
                NexaraSettingsRowLayout(
                    title = stringResource(title),
                    subtitle = subtitle,
                    icon = icon,
                    subtitleMaxLines = 2,
                    enabled = backend.status != ReachBackendStatus.DISABLED,
                    modifier = tag,
                )
            }
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
    ReachBackendStatus.INSTALLING -> stringResource(R.string.linux_reach_installing)
    ReachBackendStatus.BROKEN -> stringResource(R.string.linux_reach_broken)
    ReachBackendStatus.UNAVAILABLE -> stringResource(R.string.linux_reach_unavailable)
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
                    ExecutionMode.WORKSPACE -> R.string.common_mode_workspace
                    ExecutionMode.FULL_ACCESS -> R.string.common_mode_full_access
                },
            ),
            subtitle = stringResource(
                when (state.executionMode) {
                    ExecutionMode.WORKSPACE -> R.string.common_mode_workspace_desc
                    ExecutionMode.FULL_ACCESS -> R.string.common_mode_full_access_desc
                },
            ),
            icon = Icons.Rounded.VerifiedUser,
            subtitleMaxLines = 3,
            modifier = Modifier.testTag("agent_reach_mode"),
        )
    }
}

@Composable
private fun NetworkSection(state: AgentReachState, actions: AgentReachActions) {
    NexaraSettingsSection(title = stringResource(R.string.agent_reach_section_network)) {
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
