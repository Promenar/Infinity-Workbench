package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.forInput

/** 脚本能力设置沿用工具设置页的 Material 3 控件。 */
@Composable
internal fun ScriptRuntimeSettingsCard(viewModel: SettingsViewModel) {
    val storedHosts by viewModel.scriptLanHosts.collectAsState()
    val approveWrites by viewModel.scriptApproveFileWrites.collectAsState()
    var draft by remember(storedHosts) { mutableStateOf(storedHosts) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.script_runtime_title), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.script_approve_writes), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.script_approve_writes_desc), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = approveWrites, onCheckedChange = viewModel::updateScriptApproveFileWrites)
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.script_lan_hosts)) },
            supportingText = { Text(error ?: stringResource(R.string.script_lan_hosts_desc)) },
            isError = error != null,
            textStyle = MaterialTheme.typography.bodyMedium.forInput(),
            minLines = 2,
            maxLines = 5,
        )
        TextButton(onClick = { error = viewModel.updateScriptLanHosts(draft) }, enabled = draft != storedHosts) {
            Text(stringResource(R.string.script_save_hosts))
        }
    }
}
