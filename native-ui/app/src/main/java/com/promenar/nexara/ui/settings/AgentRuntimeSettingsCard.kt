package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R

/** 复用现有 Material 3 控件，保护配置标签的完整换行空间。 */
@Composable
internal fun AgentRuntimeSettingsCard(viewModel: SettingsViewModel) {
    val tokenLimit by viewModel.taskTokenLimit.collectAsState()
    val ratio by viewModel.toolContextRatio.collectAsState()
    val keep by viewModel.toolContextKeepRounds.collectAsState()
    val repeatLimit by viewModel.toolLoopRepeatLimit.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.p0_runtime_limits), style = MaterialTheme.typography.titleMedium)
        RuntimeNumberRow(
            stringResource(R.string.p0_token_limit),
            if (tokenLimit == 0) stringResource(R.string.p0_disabled) else tokenLimit.toString(),
            tokenLimit > 0, tokenLimit < 10_000_000,
            { viewModel.updateTaskTokenLimit((tokenLimit - 10_000).coerceAtLeast(0)) },
            { viewModel.updateTaskTokenLimit(tokenLimit + 10_000) },
        )
        RuntimeNumberRow(stringResource(R.string.p0_context_ratio), "$ratio%", ratio > 10, ratio < 90,
            { viewModel.updateToolContextRatio(ratio - 10) }, { viewModel.updateToolContextRatio(ratio + 10) })
        RuntimeNumberRow(stringResource(R.string.p0_keep_rounds), keep.toString(), keep > 1, keep < 10,
            { viewModel.updateToolContextKeepRounds(keep - 1) }, { viewModel.updateToolContextKeepRounds(keep + 1) })
        RuntimeNumberRow(stringResource(R.string.p0_repeat_limit), repeatLimit.toString(), repeatLimit > 2, repeatLimit < 20,
            { viewModel.updateToolLoopRepeatLimit(repeatLimit - 1) }, { viewModel.updateToolLoopRepeatLimit(repeatLimit + 1) })
        Text(stringResource(R.string.p0_runtime_limits_desc), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = viewModel::resetUnlimitedAutoConsent, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.p0_reset_consent))
        }
        ScriptRuntimeSettingsCard(viewModel)
    }
}

@Composable
private fun RuntimeNumberRow(
    label: String, value: String, canDecrease: Boolean, canIncrease: Boolean,
    decrease: () -> Unit, increase: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = decrease, enabled = canDecrease, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Remove, stringResource(R.string.p0_decrease_setting, label))
        }
        Text(value, style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = increase, enabled = canIncrease, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Add, stringResource(R.string.p0_increase_setting, label))
        }
    }
}
