package com.promenar.nexara.ui.hub

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun AgentRagConfigScreen(
    agentId: String,
    scopeLabel: String,
    viewModel: AgentEditViewModel = viewModel(factory = AgentEditViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(agentId) {
        viewModel.loadAgent(agentId)
    }

    val useInherited by viewModel.useInheritedConfig.collectAsState()
    val ragConfig by viewModel.ragConfig.collectAsState()
    var showResetConfirm by remember { mutableStateOf(false) }
    var showTemplateEditor by remember { mutableStateOf(false) }

    UnifiedPromptEditor(
        show = showTemplateEditor,
        onDismiss = { showTemplateEditor = false },
        onSave = { newTemplate ->
            viewModel.saveRagConfig { it.copy(summaryTemplate = newTemplate) }
        },
        title = stringResource(R.string.agent_rag_section_summary),
        initialText = ragConfig.summaryTemplate,
        placeholder = stringResource(R.string.agent_rag_summary_placeholder),
        mode = EditorMode.DIALOG
    )

    ConfirmDialog(
        show = showResetConfirm,
        onDismiss = { showResetConfirm = false },
        onConfirm = {
            viewModel.resetToGlobal()
            showResetConfirm = false
        },
        title = stringResource(R.string.agent_rag_reset_title),
        description = stringResource(R.string.agent_rag_reset_message),
        confirmLabel = stringResource(R.string.agent_rag_reset_confirm),
        confirmColor = MaterialTheme.colorScheme.tertiary,
        destructive = true
    )

    NexaraPageLayout(
        title = stringResource(R.string.agent_rag_title),
        onBack = onNavigateBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkAdded,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = scopeLabel,
                    style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (useInherited) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (useInherited) stringResource(R.string.agent_rag_status_inherited) else stringResource(R.string.agent_rag_status_custom),
                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                color = if (useInherited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    if (!useInherited) {
                        TextButton(onClick = { showResetConfirm = true }) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.shared_btn_reset),
                                style = NexaraTypography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.agent_rag_section_chunk),
                        style = NexaraTypography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    RagConfigSlider(
                        label = stringResource(R.string.agent_rag_chunk_size),
                        value = ragConfig.docChunkSize.toFloat(),
                        valueRange = 100f..2000f,
                        step = 50f,
                        displayValue = "${ragConfig.docChunkSize.toInt()}",
                        enabled = true,
                        onValueChange = { newVal -> viewModel.updateRagConfig { it.copy(docChunkSize = newVal.toInt()) } }
                    )
                    RagConfigSlider(
                        label = stringResource(R.string.agent_rag_overlap),
                        value = ragConfig.chunkOverlap.toFloat(),
                        valueRange = 0f..500f,
                        step = 10f,
                        displayValue = "${ragConfig.chunkOverlap.toInt()}",
                        enabled = true,
                        onValueChange = { newVal -> viewModel.updateRagConfig { it.copy(chunkOverlap = newVal.toInt()) } }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.agent_rag_section_memory),
                        style = NexaraTypography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    RagConfigSlider(
                        label = stringResource(R.string.agent_rag_memory_chunk),
                        value = ragConfig.memoryChunkSize.toFloat(),
                        valueRange = 500f..3000f,
                        step = 100f,
                        displayValue = "${ragConfig.memoryChunkSize.toInt()}",
                        enabled = true,
                        onValueChange = { newVal -> viewModel.updateRagConfig { it.copy(memoryChunkSize = newVal.toInt()) } }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showTemplateEditor = true
                    },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.agent_rag_section_summary),
                            style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (ragConfig.summaryTemplate.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (ragConfig.summaryTemplate.isNotBlank()) stringResource(R.string.agent_rag_summary_configured) else stringResource(R.string.agent_rag_summary_default),
                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                color = if (ragConfig.summaryTemplate.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ragConfig.summaryTemplate.ifBlank { stringResource(R.string.agent_rag_summary_hint) },
                        style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                        color = if (ragConfig.summaryTemplate.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun RagConfigSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    displayValue: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = NexaraTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = displayValue,
                style = NexaraTypography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        NexaraSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = ((valueRange.endInclusive - valueRange.start) / step).toInt() - 1,
            enabled = enabled
        )
    }
}
