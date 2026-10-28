package com.promenar.infinity.ui.workspace

import com.promenar.infinity.data.workspace.LinuxWorkspaceLocation
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.ui.theme.InfinityMonospace
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
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.promenar.infinity.ui.theme.forReading
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.settings.NetworkAccessRows
import com.promenar.infinity.ui.settings.NetworkRunMode
import com.promenar.infinity.ui.settings.networkMatrixFooter
import com.promenar.infinity.ui.settings.tailnetSummary
import com.promenar.infinity.ui.theme.InfinitySpacing

internal data class AgentReachActions(
    /** 局域网白名单是全局设置：非 null 时该行关闭面板并打开全局网络访问页（R3），为 null 时只显示项数。 */
    val onOpenNetworkAccess: (() -> Unit)? = null,
    /** 所在页面无法跳转设置时为 null，Tailscale 行只显示状态。 */
    val onOpenTailscale: (() -> Unit)? = null,
    /** 非 null 时 Linux 行可点按，进入 Linux 环境页安装、修复或开关。 */
    val onOpenLinux: (() -> Unit)? = null,
    /** 非 null 时工作区一行提供切换；从会话顶栏打开面板时传入。 */
    val workspaceSwitch: AgentReachWorkspaceSwitch? = null,
)

/** 面板内的工作区切换：生成进行中或有待审批时停用，切换失败时在工作区下方提示。 */
internal data class AgentReachWorkspaceSwitch(
    val busy: Boolean,
    val failed: Boolean,
    val onChoose: () -> Unit,
)

/**
 * 会话的工作区与“Agent 可达范围”面板，从顶栏状态行的工作区打开；[executionMode] 为会话保存的执行模式原值。
 * [workspaceKey] 变化（切换了工作区）时重新解析工作区。
 */
@Composable
internal fun AgentReachSheet(
    sessionId: String,
    executionMode: String?,
    onDismiss: () -> Unit,
    onOpenTailscaleSettings: (() -> Unit)? = null,
    workspaceSwitch: AgentReachWorkspaceSwitch? = null,
    workspaceKey: Any? = null,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: AgentReachViewModel = viewModel(
        key = "agent-reach-$sessionId",
        factory = AgentReachViewModel.factory(application, sessionId),
    )
    LaunchedEffect(viewModel, workspaceKey) { viewModel.refresh() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val runMode by viewModel.runMode.collectAsStateWithLifecycle()
    val linuxNavigation = com.promenar.infinity.ui.linux.LocalLinuxNavigation.current
    val sessionSettingsNavigation = com.promenar.infinity.ui.chat.LocalSessionSettingsNavigation.current
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.agent_reach_title),
        height = InfinitySheetHeight.Full,
    ) {
        AgentReachContent(
            state = state.copy(executionMode = ExecutionModeCodec.parseOrWorkspace(executionMode)),
            runMode = runMode,
            actions = AgentReachActions(
                onOpenNetworkAccess = sessionSettingsNavigation?.let { navigation -> { onDismiss(); navigation.openNetworkAccess() } },
                onOpenTailscale = onOpenTailscaleSettings?.let { open -> { onDismiss(); open() } },
                onOpenLinux = linuxNavigation?.let { navigation -> { onDismiss(); navigation.openEnvironment() } },
                workspaceSwitch = workspaceSwitch,
            ),
        )
    }
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
    /** 会话保存的运行模式；未取得时按 Linux 环境是否接管 shell 推断。 */
    runMode: SessionRunMode? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = InfinitySpacing.XLarge)
            .testTag("agent_reach_content"),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.SectionGap),
    ) {
        WorkspaceSection(state, actions.workspaceSwitch)
        PathSection(state)
        BackendSection(state, actions)
        ApprovalSection(state)
        NetworkSection(state, actions, reachNetworkRunMode(runMode, state))
    }
}

/** 网络可达范围按会话运行模式灰显：会话未保存模式时以 Linux 环境是否接管 shell 为准。 */
internal fun reachNetworkRunMode(runMode: SessionRunMode?, state: AgentReachState): NetworkRunMode = when (runMode) {
    SessionRunMode.LINUX -> NetworkRunMode.LINUX
    SessionRunMode.BUILTIN -> NetworkRunMode.BUILTIN
    null -> if (state.backends.linuxRunsShell()) NetworkRunMode.LINUX else NetworkRunMode.BUILTIN
}

@Composable
private fun WorkspaceSection(state: AgentReachState, switch: AgentReachWorkspaceSwitch?) {
    val workspace = state.workspace
    InfinitySettingsSection(title = stringResource(R.string.agent_reach_section_workspace)) {
        InfinitySettingsRowLayout(
            title = when (workspace) {
                SessionWorkspaceUi.Loading -> "…"
                SessionWorkspaceUi.Private -> privateWorkspaceLabel()
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
            trailingContent = switch?.let { entry ->
                {
                    val label = stringResource(R.string.agent_reach_workspace_switch)
                    TextButton(
                        onClick = entry.onChoose,
                        enabled = !entry.busy && workspace != SessionWorkspaceUi.Loading,
                        modifier = Modifier
                            .semantics { contentDescription = label }
                            .testTag("agent_reach_workspace_switch"),
                    ) {
                        Text(
                            stringResource(
                                if (workspace is SessionWorkspaceUi.Unavailable) R.string.workspace_session_reselect
                                else R.string.workspace_bar_switch,
                            ),
                        )
                    }
                }
            },
            modifier = Modifier.testTag("agent_reach_workspace"),
        )
        if (switch != null && (switch.busy || switch.failed)) {
            Text(
                text = stringResource(if (switch.failed) R.string.workspace_switch_failed else R.string.workspace_session_busy),
                style = MaterialTheme.typography.bodySmall.forReading(),
                color = if (switch.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = InfinitySpacing.RowHorizontal)
                    .padding(bottom = InfinitySpacing.Small),
            )
        }
        if (workspace is SessionWorkspaceUi.Private || workspace is SessionWorkspaceUi.Directory) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = InfinitySpacing.RowHorizontal)
                    .padding(bottom = InfinitySpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                workspaceKindTags((workspace as? SessionWorkspaceUi.Directory)?.kind).forEach { tag ->
                    WorkspaceTagChip(stringResource(tag.label), tag.tone)
                }
            }
        }
    }
}

@Composable
private fun PathRow(path: String, meaning: String, tag: String, knowledgeBase: Boolean = false) {
    InfinitySettingsRowLayout(
        title = path,
        subtitle = meaning,
        titleTextStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = InfinityMonospace),
        subtitleMaxLines = 2,
        modifier = Modifier.testTag(tag),
        trailingContent = if (knowledgeBase) ({ KnowledgeReadOnlyTag() }) else null,
    )
}

/** 绑定 Linux 目录时命令工作目录就是该目录；其它工作区经绑定挂到 /workspace。 */
internal fun linuxWorkspacePath(workspace: SessionWorkspaceUi): String =
    (workspace as? SessionWorkspaceUi.Directory)
        ?.takeIf { it.kind == WorkspaceEntity.KIND_LINUX_HOME }
        ?.let { LinuxWorkspaceLocation.parse(it.location)?.directory }
        ?: "/workspace"

@Composable
private fun PathSection(state: AgentReachState) {
    InfinitySettingsSection(
        title = stringResource(R.string.agent_reach_section_paths),
        // Linux 接管 shell_exec 时，命令看到的是 Alpine 根目录而不是设备根目录。
        footer = if (state.backends.linuxRunsShell()) {
            stringResource(R.string.linux_reach_paths_footer, linuxWorkspacePath(state.workspace))
        } else {
            stringResource(R.string.agent_reach_paths_footer)
        },
    ) {
        PathRow("/", stringResource(R.string.agent_reach_path_root), "agent_reach_path_root")
        PathRow("\$INFINITY_WORKSPACE", stringResource(R.string.agent_reach_path_workspace_var), "agent_reach_path_workspace_var")
        PathRow("/skills", stringResource(R.string.agent_reach_path_skills), "agent_reach_path_skills")
        if (state.mounts.isEmpty()) {
            PathRow("/mnt", stringResource(R.string.agent_reach_path_mnt_empty), "agent_reach_path_mnt_empty")
        } else {
            state.mounts.forEach { mount ->
                val attributes = listOfNotNull(
                    if (mount.knowledgeBase) null
                    else stringResource(if (mount.source == ReachMountSource.PATH) R.string.agent_reach_mount_path else R.string.agent_reach_mount_saf),
                    stringResource(if (mount.commandAccessible) R.string.agent_reach_mount_command_visible else R.string.agent_reach_mount_files_only),
                    if (mount.unavailable) stringResource(R.string.agent_reach_mount_unavailable) else null,
                )
                PathRow("/mnt/${mount.alias}", attributes.joinToString(" · "), "agent_reach_path_mount", knowledgeBase = mount.knowledgeBase)
            }
        }
    }
}

@Composable
private fun BackendSection(state: AgentReachState, actions: AgentReachActions) {
    val traits = state.workspace.traits
    val builtinTitle = if (state.backends.linuxRunsShell()) R.string.linux_reach_backend_builtin_python else R.string.agent_reach_backend_builtin
    InfinitySettingsSection(
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
                InfinitySettingsItem(
                    icon = icon,
                    title = stringResource(title),
                    subtitle = subtitle,
                    subtitleMaxLines = 2,
                    onClick = openLinux,
                    showChevron = true,
                    modifier = tag,
                )
            } else {
                InfinitySettingsRowLayout(
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
    InfinitySettingsSection(
        title = stringResource(R.string.agent_reach_section_approval),
        footer = stringResource(R.string.agent_reach_approval_footer),
    ) {
        InfinitySettingsRowLayout(
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
private fun NetworkSection(state: AgentReachState, actions: AgentReachActions, runMode: NetworkRunMode) {
    InfinitySettingsSection(title = stringResource(R.string.agent_reach_section_network), footer = networkMatrixFooter()) {
        val network = state.network
        if (network == null) {
            InfinitySettingsRowLayout(title = stringResource(R.string.settings_status_loading))
            return@InfinitySettingsSection
        }
        val lanSummary = pluralStringResource(R.plurals.settings_ia_reach_lan_summary, network.lanHosts.size, network.lanHosts.size)
        val openNetworkAccess = actions.onOpenNetworkAccess
        if (openNetworkAccess != null) {
            InfinityValueRow(
                icon = Icons.Rounded.Dns,
                title = stringResource(R.string.network_lan_hosts_title),
                subtitle = lanSummary,
                value = stringResource(R.string.settings_ia_reach_lan_change),
                onClick = openNetworkAccess,
                modifier = Modifier.testTag("agent_reach_lan_hosts"),
            )
        } else {
            InfinitySettingsRowLayout(
                title = stringResource(R.string.network_lan_hosts_title),
                subtitle = lanSummary,
                icon = Icons.Rounded.Dns,
                modifier = Modifier.testTag("agent_reach_lan_hosts"),
            )
        }
        val openTailscale = actions.onOpenTailscale
        if (openTailscale != null) {
            InfinitySettingsItem(
                icon = Icons.Rounded.Hub,
                title = stringResource(R.string.network_tailnet_title),
                subtitle = tailnetSummary(network),
                onClick = openTailscale,
                showChevron = true,
                subtitleMaxLines = 2,
            )
        } else {
            InfinitySettingsRowLayout(
                title = stringResource(R.string.network_tailnet_title),
                subtitle = tailnetSummary(network),
                icon = Icons.Rounded.Hub,
                subtitleMaxLines = 2,
            )
        }
        NetworkAccessRows(network, runMode = runMode)
    }
}
