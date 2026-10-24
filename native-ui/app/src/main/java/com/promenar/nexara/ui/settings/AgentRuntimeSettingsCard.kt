package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.WifiProtectedSetup
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.WorkHistory
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraStepperSetting
import com.promenar.nexara.ui.common.NexaraNumericSetting

internal data class SkillsSettingsVisualState(
    val loopLimit: Int,
    val tokenLimit: Int,
    val repeatLimit: Int,
    val sshHostCount: Int? = null,
    val termuxAvailability: TermuxAvailability? = null,
    val retryLimit: Int = com.promenar.nexara.domain.generation.DEFAULT_STREAM_RETRY_LIMIT,
    val gitCredentialCount: Int? = null,
    val tailscaleState: com.promenar.nexara.data.tailnet.TailnetState? = null,
    /** 共享局域网与本机白名单条数；尚未读取时为 null。 */
    val networkHostCount: Int? = null,
)

internal data class SkillsSettingsVisualActions(
    val onLoopLimitChange: (Int) -> Unit = {},
    val onTokenLimitChange: (Int) -> Unit = {},
    val onRepeatLimitChange: (Int) -> Unit = {},
    val onRetryLimitChange: (Int) -> Unit = {},
    val onResetConsent: () -> Unit = {},
    val onOpenNetworkAccess: () -> Unit = {},
    val onOpenSsh: () -> Unit = {},
    val onOpenTermux: () -> Unit = {},
    val onOpenGitCredentials: () -> Unit = {},
    val onOpenTailscale: () -> Unit = {},
    val onOpenBackground: () -> Unit = {},
)

/** 工具页顶部只消费已有设置值与操作，保持下方标签区和配置契约独立。 */
@Composable
internal fun SkillsSettingsHeaderContent(
    state: SkillsSettingsVisualState,
    actions: SkillsSettingsVisualActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        AgentRuntimeSettingsCard(state, actions)
        NexaraSettingsSection(stringResource(R.string.settings_local_remote)) {
            NexaraSettingsItem(
                icon = Icons.Rounded.Public,
                title = stringResource(R.string.network_access_title),
                subtitle = when (val count = state.networkHostCount) {
                    null -> stringResource(R.string.settings_status_loading)
                    0 -> stringResource(R.string.network_access_entry_empty)
                    else -> pluralStringResource(R.plurals.network_access_entry_count, count, count)
                },
                onClick = actions.onOpenNetworkAccess,
                showChevron = true,
                modifier = Modifier.testTag("network_access_entry"),
            )
            NexaraSettingsItem(
                icon = Icons.Rounded.WorkHistory,
                title = stringResource(R.string.settings_background_title),
                subtitle = stringResource(R.string.settings_background_entry_subtitle),
                onClick = actions.onOpenBackground,
                showChevron = true,
                modifier = Modifier.testTag("background_activity_entry"),
            )
            NexaraSettingsItem(
                icon = Icons.Rounded.Terminal,
                title = stringResource(R.string.ssh_settings_entry),
                subtitle = state.sshHostCount?.let { stringResource(R.string.settings_ssh_hosts_count, it) }
                    ?: stringResource(R.string.settings_status_loading),
                onClick = actions.onOpenSsh,
                showChevron = true,
            )
            NexaraSettingsItem(
                icon = Icons.Rounded.PhoneAndroid,
                title = stringResource(R.string.settings_termux_entry),
                subtitle = stringResource(when (termuxEntryStatus(state.termuxAvailability)) {
                    TermuxEntryStatus.Loading -> R.string.settings_status_loading
                    TermuxEntryStatus.NotInstalled -> R.string.settings_termux_not_installed
                    TermuxEntryStatus.UnsupportedVersion -> R.string.settings_termux_unsupported_version
                    TermuxEntryStatus.PermissionRequired -> R.string.settings_termux_permission_required
                    TermuxEntryStatus.Available -> R.string.settings_termux_available
                }),
                onClick = actions.onOpenTermux,
                showChevron = true,
            )
            NexaraSettingsItem(
                icon = Icons.Rounded.Key,
                title = stringResource(R.string.git_credentials_entry),
                subtitle = when (val count = state.gitCredentialCount) {
                    null -> stringResource(R.string.settings_status_loading)
                    0 -> stringResource(R.string.git_credentials_entry_empty)
                    else -> pluralStringResource(R.plurals.git_credentials_entry_count, count, count)
                },
                onClick = actions.onOpenGitCredentials,
                showChevron = true,
            )
            NexaraSettingsItem(
                icon = Icons.Rounded.Hub,
                title = stringResource(R.string.settings_tailscale_entry),
                subtitle = state.tailscaleState?.let { stringResource(tailscalePhaseText(it)) }
                    ?: stringResource(R.string.settings_status_loading),
                onClick = actions.onOpenTailscale,
                showChevron = true,
            )
        }
    }
}

@Composable
internal fun AgentRuntimeSettingsCard(
    state: SkillsSettingsVisualState,
    actions: SkillsSettingsVisualActions,
) {
    NexaraSettingsSection(title = stringResource(R.string.settings_execution_budget)) {
        LoopLimitCard(state.loopLimit, actions.onLoopLimitChange, icon = Icons.Rounded.Loop)
        NexaraNumericSetting(
            icon = Icons.Rounded.DataUsage,
            title = stringResource(R.string.p0_token_limit),
            value = state.tokenLimit,
            spec = ToolTokenLimitSpec,
            description = stringResource(R.string.p0_runtime_limits_desc),
            onValueChange = { actions.onTokenLimitChange(it.toInt()) },
        )
        NexaraStepperSetting(
            icon = Icons.Rounded.Repeat,
            title = stringResource(R.string.p0_repeat_limit),
            description = stringResource(R.string.tools_repeat_limit_desc),
            value = state.repeatLimit.toString(),
            canDecrease = state.repeatLimit > 2,
            canIncrease = state.repeatLimit < 20,
            onDecrease = { actions.onRepeatLimitChange(state.repeatLimit - 1) },
            onIncrease = { actions.onRepeatLimitChange(state.repeatLimit + 1) },
        )
        NexaraStepperSetting(
            icon = Icons.Rounded.WifiProtectedSetup,
            title = stringResource(R.string.settings_stream_retry_limit),
            description = stringResource(R.string.settings_stream_retry_limit_desc),
            value = state.retryLimit.toString(),
            canDecrease = state.retryLimit > 0,
            canIncrease = state.retryLimit < com.promenar.nexara.domain.generation.MAX_STREAM_RETRY_LIMIT,
            onDecrease = { actions.onRetryLimitChange(state.retryLimit - 1) },
            onIncrease = { actions.onRetryLimitChange(state.retryLimit + 1) },
        )
        NexaraSettingsItem(
            icon = Icons.Rounded.RestartAlt,
            title = stringResource(R.string.p0_reset_consent),
            subtitle = stringResource(R.string.tools_reset_consent_desc),
            onClick = actions.onResetConsent,
        )
    }
}
