package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraStepperSetting

internal data class SkillsSettingsVisualState(
    val loopLimit: Int,
    val tokenLimit: Int,
    val contextRatio: Int,
    val keepRounds: Int,
    val repeatLimit: Int,
    val scriptHosts: String,
    val approveScriptWrites: Boolean,
    val sshHostCount: Int? = null,
    val termuxAvailability: TermuxAvailability? = null,
)

internal data class SkillsSettingsVisualActions(
    val onLoopLimitChange: (Int) -> Unit = {},
    val onTokenLimitChange: (Int) -> Unit = {},
    val onContextRatioChange: (Int) -> Unit = {},
    val onKeepRoundsChange: (Int) -> Unit = {},
    val onRepeatLimitChange: (Int) -> Unit = {},
    val onResetConsent: () -> Unit = {},
    val onApproveScriptWritesChange: (Boolean) -> Unit = {},
    val onSaveScriptHosts: (String) -> String? = { null },
    val onScriptHostsSaved: () -> Unit = {},
    val onOpenSsh: () -> Unit = {},
    val onOpenTermux: () -> Unit = {},
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
        ScriptRuntimeSettingsCard(
            storedHosts = state.scriptHosts,
            approveWrites = state.approveScriptWrites,
            onApproveWritesChange = actions.onApproveScriptWritesChange,
            onSaveHosts = actions.onSaveScriptHosts,
            onSaved = actions.onScriptHostsSaved,
        )
        NexaraSettingsSection(stringResource(R.string.settings_local_remote)) {
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
        }
    }
}

@Composable
internal fun AgentRuntimeSettingsCard(
    state: SkillsSettingsVisualState,
    actions: SkillsSettingsVisualActions,
) {
    NexaraSettingsSection(stringResource(R.string.settings_execution_budget)) {
        LoopLimitCard(state.loopLimit, actions.onLoopLimitChange)
        NexaraStepperSetting(
            title = stringResource(R.string.p0_token_limit),
            description = stringResource(R.string.p0_runtime_limits_desc),
            value = if (state.tokenLimit == 0) stringResource(R.string.p0_disabled) else state.tokenLimit.toString(),
            canDecrease = state.tokenLimit > 0,
            canIncrease = state.tokenLimit < 10_000_000,
            onDecrease = { actions.onTokenLimitChange((state.tokenLimit - 10_000).coerceAtLeast(0)) },
            onIncrease = { actions.onTokenLimitChange(state.tokenLimit + 10_000) },
        )
        NexaraStepperSetting(
            title = stringResource(R.string.p0_repeat_limit),
            description = stringResource(R.string.settings_repeat_limit_description),
            value = state.repeatLimit.toString(),
            canDecrease = state.repeatLimit > 2,
            canIncrease = state.repeatLimit < 20,
            onDecrease = { actions.onRepeatLimitChange(state.repeatLimit - 1) },
            onIncrease = { actions.onRepeatLimitChange(state.repeatLimit + 1) },
        )
        NexaraSettingsItem(
            icon = Icons.Rounded.RestartAlt,
            title = stringResource(R.string.p0_reset_consent),
            subtitle = stringResource(R.string.settings_reset_consent_description),
            onClick = actions.onResetConsent,
        )
    }
    NexaraSettingsSection(stringResource(R.string.settings_tool_context)) {
        NexaraStepperSetting(
            title = stringResource(R.string.p0_context_ratio),
            description = stringResource(R.string.settings_context_ratio_description),
            value = stringResource(R.string.settings_percent_value, state.contextRatio),
            canDecrease = state.contextRatio > 10,
            canIncrease = state.contextRatio < 90,
            onDecrease = { actions.onContextRatioChange(state.contextRatio - 10) },
            onIncrease = { actions.onContextRatioChange(state.contextRatio + 10) },
        )
        NexaraStepperSetting(
            title = stringResource(R.string.p0_keep_rounds),
            description = stringResource(R.string.settings_keep_rounds_description),
            value = state.keepRounds.toString(),
            canDecrease = state.keepRounds > 1,
            canIncrease = state.keepRounds < 10,
            onDecrease = { actions.onKeepRoundsChange(state.keepRounds - 1) },
            onIncrease = { actions.onKeepRoundsChange(state.keepRounds + 1) },
        )
    }
}
