package com.promenar.nexara.ui.hub

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun AgentRagConfigScreen(
    agentId: String,
    scopeLabel: String,
    onNavigateBack: () -> Unit
) {
    var docChunkSize by remember { mutableFloatStateOf(800f) }
    var chunkOverlap by remember { mutableFloatStateOf(100f) }
    var memoryChunkSize by remember { mutableFloatStateOf(1000f) }
    var contextWindow by remember { mutableFloatStateOf(20f) }
    var summaryThreshold by remember { mutableFloatStateOf(10f) }
    var summaryTemplate by remember { mutableStateOf("") }
    var useInherited by remember { mutableStateOf(true) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showTemplateEditor by remember { mutableStateOf(false) }

    FloatingTextEditor(
        show = showTemplateEditor,
        onDismiss = { showTemplateEditor = false },
        onSave = {
            summaryTemplate = it
            showTemplateEditor = false
        },
        title = stringResource(R.string.agent_rag_section_summary),
        initialText = summaryTemplate,
        placeholder = stringResource(R.string.agent_rag_summary_placeholder)
    )

    ConfirmDialog(
        show = showResetConfirm,
        onDismiss = { showResetConfirm = false },
        onConfirm = {
            useInherited = true
            docChunkSize = 800f
            chunkOverlap = 100f
            memoryChunkSize = 1000f
            contextWindow = 20f
            summaryThreshold = 10f
            summaryTemplate = ""
            showResetConfirm = false
        },
        title = stringResource(R.string.agent_rag_reset_title),
        description = stringResource(R.string.agent_rag_reset_message),
        confirmLabel = stringResource(R.string.agent_rag_reset_confirm),
        confirmColor = NexaraColors.StatusWarning,
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
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = scopeLabel,
                    style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NexaraColors.Primary
                )
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
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
                                    if (useInherited) NexaraColors.Primary.copy(alpha = 0.15f)
                                    else NexaraColors.StatusWarning.copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (useInherited) stringResource(R.string.agent_rag_status_inherited) else stringResource(R.string.agent_rag_status_custom),
                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                color = if (useInherited) NexaraColors.Primary else NexaraColors.StatusWarning
                            )
                        }
                    }
                    if (!useInherited) {
                        TextButton(onClick = { showResetConfirm = true }) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                tint = NexaraColors.StatusWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.shared_btn_reset),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.StatusWarning
                            )
                        }
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
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
                        color = NexaraColors.OnSurface
                    )
                    RagConfigSlider(
                        label = stringResource(R.string.agent_rag_chunk_size),
                        value = docChunkSize,
                        valueRange = 200f..2000f,
                        step = 100f,
                        displayValue = "${docChunkSize.toInt()} ${stringResource(R.string.agent_rag_unit_char)}",
                        enabled = true,
                        onValueChange = {
                            docChunkSize = it
                            useInherited = false
                        }
                    )
                    RagConfigSlider(
                        label = stringResource(R.string.agent_rag_overlap),
                        value = chunkOverlap,
                        valueRange = 0f..500f,
                        step = 50f,
                        displayValue = "${chunkOverlap.toInt()} ${stringResource(R.string.agent_rag_unit_char)}",
                        enabled = true,
                        onValueChange = {
                            chunkOverlap = it
                            useInherited = false
                        }
                    )
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
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
                        color = NexaraColors.OnSurface
                    )
                    RagConfigSlider(
                        label = stringResource(R.string.agent_rag_memory_chunk),
                        value = memoryChunkSize,
                        valueRange = 500f..2000f,
                        step = 100f,
                        displayValue = "${memoryChunkSize.toInt()} ${stringResource(R.string.agent_rag_unit_char)}",
                        enabled = true,
                        onValueChange = {
                            memoryChunkSize = it
                            useInherited = false
                        }
                    )
                    RagConfigSlider(
                        label = stringResource(R.string.agent_rag_active_window),
                        value = contextWindow,
                        valueRange = 10f..50f,
                        step = 5f,
                        displayValue = "${contextWindow.toInt()} ${stringResource(R.string.agent_rag_unit_messages)}",
                        enabled = true,
                        onValueChange = {
                            contextWindow = it
                            useInherited = false
                        }
                    )
                    RagConfigSlider(
                        label = stringResource(R.string.agent_rag_summary_threshold),
                        value = summaryThreshold,
                        valueRange = 5f..30f,
                        step = 5f,
                        displayValue = "${summaryThreshold.toInt()} ${stringResource(R.string.agent_rag_unit_messages)}",
                        enabled = true,
                        onValueChange = {
                            summaryThreshold = it
                            useInherited = false
                        }
                    )
                }
            }

            NexaraGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!useInherited) showTemplateEditor = true
                        else {
                            useInherited = false
                            showTemplateEditor = true
                        }
                    },
                shape = RoundedCornerShape(12.dp)
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
                            color = NexaraColors.OnSurface
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (summaryTemplate.isNotBlank()) NexaraColors.Primary.copy(alpha = 0.15f)
                                    else NexaraColors.GlassSurface
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (summaryTemplate.isNotBlank()) stringResource(R.string.agent_rag_summary_configured) else stringResource(R.string.agent_rag_summary_default),
                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                color = if (summaryTemplate.isNotBlank()) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = summaryTemplate.ifBlank { stringResource(R.string.agent_rag_summary_hint) },
                        style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                        color = if (summaryTemplate.isNotBlank()) NexaraColors.OnSurfaceVariant else NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
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
                color = NexaraColors.OnSurfaceVariant
            )
            Text(
                text = displayValue,
                style = NexaraTypography.bodySmall,
                color = NexaraColors.Primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = ((valueRange.endInclusive - valueRange.start) / step).toInt() - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = NexaraColors.Primary,
                activeTrackColor = NexaraColors.Primary,
                inactiveTrackColor = NexaraColors.SurfaceHighest,
                disabledThumbColor = NexaraColors.Outline,
                disabledActiveTrackColor = NexaraColors.OutlineVariant
            )
        )
    }
}
