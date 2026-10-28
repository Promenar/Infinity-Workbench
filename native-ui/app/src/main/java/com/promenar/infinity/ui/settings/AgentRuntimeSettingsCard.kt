package com.promenar.infinity.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.WifiProtectedSetup
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityStepperSetting
import com.promenar.infinity.ui.common.InfinityNumericSetting

/** “执行轮次与预算”页的设置值。 */
internal data class SkillsSettingsVisualState(
    val loopLimit: Int,
    val tokenLimit: Int,
    val repeatLimit: Int,
    val retryLimit: Int = com.promenar.infinity.domain.generation.DEFAULT_STREAM_RETRY_LIMIT,
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
    InfinitySettingsSection(title = stringResource(R.string.settings_execution_limits_section)) {
        LoopLimitCard(state.loopLimit, actions.onLoopLimitChange, icon = Icons.Rounded.Loop)
        InfinityNumericSetting(
            icon = Icons.Rounded.DataUsage,
            title = stringResource(R.string.p0_token_limit),
            value = state.tokenLimit,
            spec = ToolTokenLimitSpec,
            description = stringResource(R.string.p0_runtime_limits_desc),
            onValueChange = { actions.onTokenLimitChange(it.toInt()) },
        )
        InfinityStepperSetting(
            icon = Icons.Rounded.Repeat,
            title = stringResource(R.string.p0_repeat_limit),
            description = stringResource(R.string.tools_repeat_limit_desc),
            value = state.repeatLimit.toString(),
            canDecrease = state.repeatLimit > 2,
            canIncrease = state.repeatLimit < 20,
            onDecrease = { actions.onRepeatLimitChange(state.repeatLimit - 1) },
            onIncrease = { actions.onRepeatLimitChange(state.repeatLimit + 1) },
        )
        InfinityStepperSetting(
            icon = Icons.Rounded.WifiProtectedSetup,
            title = stringResource(R.string.settings_stream_retry_limit),
            description = stringResource(R.string.settings_stream_retry_limit_desc),
            value = state.retryLimit.toString(),
            canDecrease = state.retryLimit > 0,
            canIncrease = state.retryLimit < com.promenar.infinity.domain.generation.MAX_STREAM_RETRY_LIMIT,
            onDecrease = { actions.onRetryLimitChange(state.retryLimit - 1) },
            onIncrease = { actions.onRetryLimitChange(state.retryLimit + 1) },
        )
        InfinitySettingsItem(
            icon = Icons.Rounded.RestartAlt,
            title = stringResource(R.string.p0_reset_consent),
            subtitle = stringResource(R.string.tools_reset_consent_desc),
            onClick = actions.onResetConsent,
        )
    }
}
