package com.promenar.nexara.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.tailnet.TailnetFailure
import com.promenar.nexara.data.tailnet.TailnetPeer
import com.promenar.nexara.data.tailnet.TailnetPhase
import com.promenar.nexara.data.tailnet.TailnetSettingsProblem
import com.promenar.nexara.data.tailnet.TailnetState
import com.promenar.nexara.data.tailnet.TailnetStatusSummary
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsRowLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SecretField
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forInput

internal data class TailscaleSettingsActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onLogin: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSaveAuthKey: (CharArray) -> Unit = { it.fill('\u0000') },
    val onRevealAuthKey: suspend () -> CharArray? = { null },
    val onClearAuthKey: () -> Unit = {},
    val onSaveControlUrl: (String) -> Unit = {},
    val onControlUrlEdited: () -> Unit = {},
    val onEphemeralChange: (Boolean) -> Unit = {},
    val onSaveAllowedPeers: (String) -> Unit = {},
    val onAllowedPeersEdited: () -> Unit = {},
)

@Composable
fun TailscaleSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: TailscaleSettingsViewModel =
        viewModel(factory = TailscaleSettingsViewModel.factory(context.applicationContext as android.app.Application))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val noticeHost = remember { SnackbarHostState() }

    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { event ->
            when (event) {
                is TailscaleSettingsEvent.OpenLogin -> try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    noticeHost.showSnackbar(resources.getString(R.string.tailscale_error_browser))
                }
            }
        }
    }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        viewModel.consumeNotice()
        noticeHost.currentSnackbarData?.dismiss()
        noticeHost.showSnackbar(resources.getString(tailscaleNoticeText(notice)))
    }

    NexaraSettingsPageLayout(title = stringResource(R.string.tailscale_title), onBack = onBack) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            TailscaleSettingsContent(
                state = state,
                actions = TailscaleSettingsActions(
                    onEnabledChange = viewModel::setEnabled,
                    onLogin = viewModel::login,
                    onLogout = viewModel::logout,
                    onRetry = viewModel::retry,
                    onRefresh = viewModel::refresh,
                    onSaveAuthKey = viewModel::saveAuthKey,
                    onRevealAuthKey = viewModel::revealAuthKey,
                    onClearAuthKey = viewModel::clearAuthKey,
                    onSaveControlUrl = viewModel::saveControlUrl,
                    onControlUrlEdited = viewModel::clearControlUrlError,
                    onEphemeralChange = viewModel::setEphemeral,
                    onSaveAllowedPeers = viewModel::saveAllowedPeers,
                    onAllowedPeersEdited = viewModel::clearPeersError,
                ),
                contentPadding = contentPadding,
            )
            SnackbarHost(
                hostState = noticeHost,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = NexaraSpacing.Large)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}

private fun tailscaleNoticeText(notice: TailscaleNotice): Int = when (notice) {
    TailscaleNotice.AUTH_KEY_SAVED -> R.string.tailscale_notice_auth_key_saved
    TailscaleNotice.AUTH_KEY_INVALID -> R.string.tailscale_notice_auth_key_invalid
    TailscaleNotice.AUTH_KEY_CLEARED -> R.string.tailscale_notice_auth_key_cleared
    TailscaleNotice.CONTROL_URL_SAVED -> R.string.tailscale_notice_control_saved
    TailscaleNotice.PEERS_SAVED -> R.string.tailscale_notice_peers_saved
    TailscaleNotice.LOGIN_UNAVAILABLE -> R.string.tailscale_notice_login_unavailable
    TailscaleNotice.LOGGED_OUT -> R.string.tailscale_notice_logged_out
    TailscaleNotice.LOGOUT_FAILED -> R.string.tailscale_notice_logout_failed
    TailscaleNotice.OPERATION_FAILED -> R.string.tailscale_notice_failed
}

/** 阶段文案；入口副标题与设置页共用。 */
internal fun tailscalePhaseText(state: TailnetState): Int = when (state.phase) {
    TailnetPhase.UNAVAILABLE -> R.string.tailscale_phase_unavailable
    TailnetPhase.DISABLED -> R.string.tailscale_phase_disabled
    TailnetPhase.STOPPED -> R.string.tailscale_phase_stopped
    TailnetPhase.STARTING -> R.string.tailscale_phase_starting
    TailnetPhase.NEEDS_LOGIN -> if (state.summary?.backendState == TailnetStatusSummary.STATE_NEEDS_MACHINE_AUTH) {
        R.string.tailscale_phase_needs_approval
    } else {
        R.string.tailscale_phase_needs_login
    }
    TailnetPhase.RUNNING -> R.string.tailscale_phase_running
    TailnetPhase.FAILED -> if (state.failure == TailnetFailure.EXITED) R.string.tailscale_phase_exited else R.string.tailscale_phase_failed
}

/** 无应用依赖的设置页内容，供截图测试与真实页面共用。 */
@Composable
internal fun TailscaleSettingsContent(
    state: TailscaleSettingsUiState,
    actions: TailscaleSettingsActions = TailscaleSettingsActions(),
    contentPadding: PaddingValues = PaddingValues(NexaraSpacing.ScreenHorizontal),
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val settings = state.settings
    val tailnet = state.tailnet
    val summary = tailnet.summary
    val enabled = state.available && settings.enabled
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("tailscale_settings_screen"),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
    ) {
        item {
            NexaraSettingsSection(
                title = stringResource(R.string.tailscale_section_node),
                footer = stringResource(if (state.available) R.string.tailscale_node_footer else R.string.tailscale_unavailable_desc),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.tailscale_enable),
                    description = stringResource(R.string.tailscale_enable_desc),
                    checked = enabled,
                    onCheckedChange = actions.onEnabledChange,
                    icon = Icons.Rounded.Hub,
                    enabled = state.available && !state.busy,
                    modifier = Modifier.testTag("tailscale_enable"),
                )
            }
        }
        if (enabled) {
            item {
                NexaraSettingsSection(title = stringResource(R.string.tailscale_section_status)) {
                    NexaraSettingsItem(
                        title = stringResource(R.string.tailscale_status_title),
                        subtitle = stringResource(tailscalePhaseText(tailnet)),
                        onClick = actions.onRefresh,
                        modifier = Modifier.testTag("tailscale_status"),
                        onClickLabel = stringResource(R.string.tailscale_refresh),
                        icon = Icons.Rounded.Refresh,
                    )
                    // 设置行副标题只显示一行，本机名称、IP 与 tailnet 名称各占一行设置行。
                    summary?.takeIf { tailnet.phase == TailnetPhase.RUNNING }?.let { self ->
                        self.selfName?.let { name ->
                            NexaraSettingsRowLayout(
                                title = stringResource(R.string.tailscale_self_name, name),
                                subtitle = self.selfIps.joinToString(" · ").ifEmpty { null },
                                leadingSpacer = true,
                                modifier = Modifier.testTag("tailscale_self"),
                            )
                        }
                        self.tailnetName?.let { name ->
                            NexaraSettingsRowLayout(
                                title = stringResource(R.string.tailscale_self_tailnet, name),
                                leadingSpacer = true,
                            )
                        }
                    }
                    when (tailnet.phase) {
                        TailnetPhase.NEEDS_LOGIN, TailnetPhase.STARTING -> NexaraSettingsItem(
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            title = stringResource(R.string.tailscale_login),
                            subtitle = stringResource(R.string.tailscale_login_desc),
                            onClick = actions.onLogin,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("tailscale_login"),
                        )
                        TailnetPhase.FAILED, TailnetPhase.STOPPED -> NexaraSettingsItem(
                            icon = Icons.Rounded.Refresh,
                            title = stringResource(R.string.common_retry),
                            onClick = actions.onRetry,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("tailscale_retry"),
                        )
                        else -> Unit
                    }
                    if (tailnet.phase == TailnetPhase.RUNNING || tailnet.phase == TailnetPhase.NEEDS_LOGIN) {
                        NexaraSettingsItem(
                            icon = Icons.Rounded.CloudOff,
                            title = stringResource(R.string.tailscale_logout),
                            subtitle = stringResource(R.string.tailscale_logout_desc),
                            onClick = actions.onLogout,
                            enabled = !state.busy,
                            titleColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("tailscale_logout"),
                        )
                    }
                }
            }
            if (!summary?.health.isNullOrEmpty()) {
                item {
                    NexaraSettingsSection(title = stringResource(R.string.tailscale_health_title)) {
                        summary!!.health.take(5).forEach { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = NexaraSpacing.RowHorizontal, vertical = NexaraSpacing.Small),
                            )
                        }
                    }
                }
            }
            if (tailnet.phase == TailnetPhase.RUNNING) {
                item {
                    NexaraSettingsSection(
                        title = stringResource(R.string.tailscale_section_peers),
                        footer = if (tailnet.peers.isEmpty()) stringResource(R.string.tailscale_peers_empty) else null,
                    ) {
                        tailnet.peers.forEach { peer -> TailscalePeerRow(peer) }
                    }
                }
            }
        }
        item {
            NexaraSettingsSection(title = stringResource(R.string.tailscale_section_login)) {
                Column(
                    modifier = Modifier.padding(horizontal = NexaraSpacing.RowHorizontal, vertical = NexaraSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                ) {
                    TailscaleAuthKeyEditor(state, actions)
                    TailscaleControlUrlEditor(state, actions)
                }
                SettingsToggle(
                    title = stringResource(R.string.tailscale_ephemeral),
                    description = stringResource(R.string.tailscale_ephemeral_desc),
                    checked = settings.ephemeral,
                    onCheckedChange = actions.onEphemeralChange,
                    icon = Icons.Rounded.Timer,
                    enabled = state.available && !state.busy,
                )
            }
        }
        item {
            NexaraSettingsSection(
                title = stringResource(R.string.tailscale_section_allowlist),
                footer = stringResource(R.string.tailscale_allowlist_desc),
            ) {
                TailscaleAllowlistEditor(state, actions)
            }
        }
    }
}

@Composable
private fun TailscalePeerRow(peer: TailnetPeer) {
    // 设置行副标题只有一行：首个 tailnet IP（无 IP 时用 MagicDNS 名称）在前，在线状态与系统在后。
    val subtitle = listOfNotNull(
        (peer.ips.firstOrNull() ?: peer.dnsName)?.takeIf { it.isNotBlank() },
        stringResource(if (peer.online) R.string.tailscale_peer_online else R.string.tailscale_peer_offline),
        peer.os?.takeIf { it.isNotBlank() },
        if (!peer.allowed) stringResource(R.string.tailscale_peer_blocked) else null,
    ).joinToString(" · ")
    NexaraSettingsRowLayout(
        title = peer.name,
        subtitle = subtitle,
        enabled = peer.allowed,
    )
}

@Composable
private fun TailscaleAuthKeyEditor(state: TailscaleSettingsUiState, actions: TailscaleSettingsActions) {
    var edit by remember { mutableStateOf("") }
    LaunchedEffect(state.settings.authKeyRevision) { edit = "" }
    SecretField(
        value = edit,
        onValueChange = { edit = it },
        hasStoredSecret = state.settings.hasAuthKey,
        onRevealRequest = actions.onRevealAuthKey,
        onClear = {
            edit = ""
            actions.onClearAuthKey()
        },
        label = stringResource(R.string.tailscale_auth_key),
        supportingText = stringResource(R.string.tailscale_auth_key_desc),
        modifier = Modifier.fillMaxWidth().testTag("tailscale_auth_key"),
    )
    if (edit.isNotBlank()) {
        Button(
            onClick = { actions.onSaveAuthKey(edit.toCharArray()) },
            enabled = state.available && !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("tailscale_auth_key_save"),
        ) { Text(stringResource(R.string.shared_btn_save)) }
    }
}

@Composable
private fun TailscaleControlUrlEditor(state: TailscaleSettingsUiState, actions: TailscaleSettingsActions) {
    var draft by remember(state.settings.controlUrl) { mutableStateOf(state.settings.controlUrl) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            actions.onControlUrlEdited()
        },
        modifier = Modifier.fillMaxWidth().testTag("tailscale_control_url"),
        label = { Text(stringResource(R.string.tailscale_control_url)) },
        placeholder = { Text("https://headscale.example.com") },
        supportingText = {
            Text(stringResource(if (state.controlUrlInvalid) R.string.tailscale_control_url_invalid else R.string.tailscale_control_url_desc))
        },
        isError = state.controlUrlInvalid,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.forInput(),
        shape = MaterialTheme.shapes.small,
        enabled = state.available,
    )
    if (draft.trim() != state.settings.controlUrl) {
        Button(
            onClick = { actions.onSaveControlUrl(draft) },
            enabled = state.available && !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("tailscale_control_url_save"),
        ) { Text(stringResource(R.string.shared_btn_save)) }
    }
}

@Composable
private fun TailscaleAllowlistEditor(state: TailscaleSettingsUiState, actions: TailscaleSettingsActions) {
    val stored = state.settings.allowedPeers.joinToString("\n")
    var draft by remember(stored) { mutableStateOf(stored) }
    Column(
        modifier = Modifier.padding(horizontal = NexaraSpacing.RowHorizontal, vertical = NexaraSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                actions.onAllowedPeersEdited()
            },
            modifier = Modifier.fillMaxWidth().testTag("tailscale_allowlist"),
            label = { Text(stringResource(R.string.tailscale_allowlist_label)) },
            placeholder = { Text("nas, laptop") },
            supportingText = state.peersProblem?.let { problem ->
                {
                    Text(
                        stringResource(
                            if (problem == TailnetSettingsProblem.TOO_MANY_PEERS) R.string.tailscale_allowlist_too_many else R.string.tailscale_allowlist_invalid,
                        ),
                    )
                }
            },
            isError = state.peersProblem != null,
            textStyle = MaterialTheme.typography.bodyMedium.forInput(),
            shape = MaterialTheme.shapes.small,
            minLines = 2,
            maxLines = 6,
            enabled = state.available,
        )
        if (draft != stored) {
            Button(
                onClick = { actions.onSaveAllowedPeers(draft) },
                enabled = state.available && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("tailscale_allowlist_save"),
            ) { Text(stringResource(R.string.shared_btn_save)) }
        }
    }
}
