package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.script.ScriptFileApprovalBroker
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import com.promenar.nexara.domain.tool.ToolRisk

@Composable
fun ScriptFileApprovalDialog(broker: ScriptFileApprovalBroker, sessionTitle: (String) -> String? = { null }) {
    val requests by broker.pending.collectAsState()
    val request = requests.firstOrNull() ?: return
    val title = sessionTitle(request.sessionId)?.takeIf(String::isNotBlank)
    var rememberTarget by remember(request.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { broker.resolve(request.id, false) },
        title = { Text(stringResource(if (request.risk == ToolRisk.DELETE) R.string.script_delete_approval else R.string.script_write_approval)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.script_approval_session, title ?: request.sessionId))
                if (title != null) Text(request.sessionId, style = MaterialTheme.typography.bodySmall)
                Text(ToolExecutionTargets.display(request.target))
                if (request.preview.isNotBlank()) Text(request.preview, style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(checked = rememberTarget, onCheckedChange = { rememberTarget = it })
                    Text(stringResource(R.string.script_remember_file_target), modifier = Modifier.weight(1f).padding(top = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { broker.resolve(request.id, true, rememberTarget) }) { Text(stringResource(R.string.script_allow_file)) } },
        dismissButton = { TextButton(onClick = { broker.resolve(request.id, false) }) { Text(stringResource(R.string.script_deny_file)) } },
    )
}
