package com.promenar.nexara.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.WifiProtectedSetup
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraStepperSetting
import com.promenar.nexara.ui.common.NexaraNumericSetting

/** “执行轮次与预算”页的设置值。 */
internal data class SkillsSettingsVisualState(
    val loopLimit: Int,
    val tokenLimit: Int,
    val repeatLimit: Int,
    val retryLimit: Int = com.promenar.nexara.domain.generation.DEFAULT_STREAM_RETRY_LIMIT,
)

internal data class SkillsSettingsVisualActions(
    val onLoopLimitChange: (Int) -> Unit = {},
    val onTokenLimitChange: (Int) -> Unit = {},
    val onRepeatLimitChange: (Int) -> Unit = {},
    val onRetryLimitChange: (Int) -> Unit = {},
    val onResetConsent: () -> Unit = {},
)

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
