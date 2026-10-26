package com.promenar.nexara.ui.chat

import com.promenar.nexara.ui.theme.NexaraMonospace
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.theme.NexaraSpacing

@Composable
fun ScriptFileApprovalDialog(broker: ScriptFileApprovalBroker, sessionTitle: (String) -> String? = { null }) {
    val requests by broker.pending.collectAsState()
    val request = requests.firstOrNull() ?: return
    val title = sessionTitle(request.sessionId)?.takeIf(String::isNotBlank)
    NexaraConfirmDialog(
        onDismissRequest = { broker.resolve(request.id, false) },
        title = stringResource(if (request.risk == ToolRisk.DELETE) R.string.script_delete_approval else R.string.script_write_approval),
        message = "",
        confirmText = stringResource(R.string.script_allow_file),
        cancelText = stringResource(R.string.script_deny_file),
        onConfirm = { broker.resolve(request.id, true) },
        onCancel = { broker.resolve(request.id, false) },
        confirmButtonModifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
        cancelButtonModifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
        content = {
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
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace),
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(NexaraSpacing.Medium),
                            )
                        }
                    }
                }
            }
        },
    )
}
