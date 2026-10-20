package com.promenar.nexara.ui.settings

import com.promenar.nexara.ui.common.NexaraSheetHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forInput

@Composable
internal fun ScriptRuntimeSettingsCard(
    storedHosts: String,
    approveWrites: Boolean,
    onApproveWritesChange: (Boolean) -> Unit,
    onSaveHosts: (String) -> String?,
    onSaved: () -> Unit,
) {
    var showHostsEditor by remember { mutableStateOf(false) }
    var draft by remember(storedHosts) { mutableStateOf(storedHosts) }
    var hasError by remember { mutableStateOf(false) }
    val hostCount = remember(storedHosts) { scriptLanHostCount(storedHosts) }
    NexaraSettingsSection(
        title = stringResource(R.string.script_runtime_title),
        footer = stringResource(R.string.script_approve_writes_desc),
    ) {
        SettingsToggle(
            title = stringResource(R.string.script_approve_writes),
            description = stringResource(R.string.settings_batch1_script_write_summary),
            checked = approveWrites,
            onCheckedChange = onApproveWritesChange,
            icon = Icons.Rounded.Security,
        )
        NexaraSettingsItem(
            icon = Icons.Rounded.Dns,
            title = stringResource(R.string.script_lan_hosts),
            subtitle = when (hostCount) {
                null -> stringResource(R.string.settings_hosts_review)
                0 -> stringResource(R.string.settings_hosts_empty)
                else -> stringResource(R.string.settings_hosts_count, hostCount)
            },
            showChevron = true,
            onClick = { draft = storedHosts; hasError = false; showHostsEditor = true },
        )
    }
    NexaraBottomSheet(
        show = showHostsEditor,
        title = stringResource(R.string.script_lan_hosts),
        height = NexaraSheetHeight.Full,
        onDismiss = { showHostsEditor = false },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; hasError = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.script_lan_hosts)) },
                supportingText = {
                    Text(stringResource(if (hasError) R.string.settings_hosts_invalid else R.string.script_lan_hosts_desc))
                },
                isError = hasError,
                textStyle = MaterialTheme.typography.bodyMedium.forInput(),
                shape = MaterialTheme.shapes.small,
                minLines = 2,
                maxLines = 5,
            )
            Button(
                onClick = {
                    hasError = onSaveHosts(draft) != null
                    if (!hasError) { showHostsEditor = false; onSaved() }
                },
                enabled = draft != storedHosts,
                modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget),
            ) { Text(stringResource(R.string.script_save_hosts)) }
        }
    }
}
