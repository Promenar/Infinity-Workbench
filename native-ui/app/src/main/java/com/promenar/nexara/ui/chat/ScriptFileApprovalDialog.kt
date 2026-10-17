package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.script.ScriptFileApprovalBroker
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.theme.NexaraSpacing

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
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium)) {
                BettboxListGroup {
                    Column(
                        modifier = Modifier.padding(NexaraSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                    ) {
                        Text(stringResource(R.string.script_approval_session, title ?: request.sessionId))
                        if (title != null) NexaraIdentifierText(request.sessionId, style = MaterialTheme.typography.bodySmall)
                        NexaraIdentifierText(ToolExecutionTargets.display(request.target))
                    }
                }
                if (request.preview.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        SelectionContainer {
                            Text(
                                request.preview,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(NexaraSpacing.Medium),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .toggleable(value = rememberTarget, role = Role.Checkbox, onValueChange = { rememberTarget = it }),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rememberTarget, onCheckedChange = null)
                    Text(stringResource(R.string.script_remember_file_target), modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget), onClick = { broker.resolve(request.id, true, rememberTarget) }) { Text(stringResource(R.string.script_allow_file)) } },
        dismissButton = { TextButton(modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget), onClick = { broker.resolve(request.id, false) }) { Text(stringResource(R.string.script_deny_file)) } },
    )
}
