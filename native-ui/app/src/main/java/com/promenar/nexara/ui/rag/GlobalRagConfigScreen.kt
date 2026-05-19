package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.data.rag.RagConfiguration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalRagConfigScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit,
    onNavigateToAdvanced: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {}
) {
    val config by viewModel.config.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var clearWithGraph by remember { mutableStateOf(true) }
    var showSummaryTemplateEditor by remember { mutableStateOf(false) }

    NexaraPageLayout(
        title = stringResource(R.string.rag_config_title),
        onBack = onNavigateBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {

            SettingsSectionHeader(stringResource(R.string.rag_config_presets))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val balancedLabel = stringResource(R.string.rag_config_preset_balanced)
                val writingLabel = stringResource(R.string.rag_config_preset_writing)
                val codingLabel = stringResource(R.string.rag_config_preset_coding)

                listOf(
                    Triple(Icons.Rounded.OfflineBolt, balancedLabel, "balanced"),
                    Triple(Icons.Rounded.Edit, writingLabel, "writing"),
                    Triple(Icons.Rounded.Code, codingLabel, "coding")
                ).forEach { (icon, title, presetId) ->
                    val isSelected = config.currentPreset == presetId
                    val bgColor by animateColorAsState(
                        if (isSelected) NexaraColors.Primary.copy(alpha = 0.08f) else NexaraColors.GlassSurface,
                        label = title
                    )
                    val borderColor by animateColorAsState(
                        if (isSelected) NexaraColors.Primary.copy(alpha = 0.4f) else NexaraColors.GlassBorder,
                        label = "$title-border"
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        NexaraGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    if (isSelected) 1.5.dp else 0.5.dp,
                                    borderColor,
                                    NexaraShapes.large as RoundedCornerShape
                                ),
                            shape = NexaraShapes.large as RoundedCornerShape,
                            onClick = {
                                viewModel.applyPreset(presetId)
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor)
                                    .padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = title,
                                    tint = if (isSelected) NexaraColors.Primary else NexaraColors.Outline,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = title,
                                    style = NexaraTypography.labelMedium,
                                    color = if (isSelected) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.rag_config_retrieval_params), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)

                    val slider = @Composable { label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                Text("${value.toInt()}", style = NexaraTypography.bodySmall, color = NexaraColors.Primary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            NexaraSlider(
                                value = value,
                                onValueChange = onChange,
                                valueRange = range,
                                steps = steps,
                                enabled = true
                            )
                        }
                    }

                    slider(stringResource(R.string.rag_config_chunk_size), config.docChunkSize.toFloat(), 100f..2000f, 18) {
                        viewModel.updateConfig { c -> c.copy(docChunkSize = it.toInt()) }
                    }
                    slider(stringResource(R.string.rag_config_overlap), config.chunkOverlap.toFloat(), 0f..500f, 9) {
                        viewModel.updateConfig { c -> c.copy(chunkOverlap = it.toInt()) }
                    }
                }
            }



            // Embedding 模型高级设置
            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.rag_config_embed_section), style = NexaraTypography.titleMedium, fontWeight = FontWeight.SemiBold, color = NexaraColors.OnSurface)

                    // Embed 维度
                    val dimSlider = @Composable { label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                Text(if (value == 0f) "自动" else value.toInt().toString(), style = NexaraTypography.bodySmall, color = NexaraColors.Primary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            NexaraSlider(
                                value = value,
                                onValueChange = onChange,
                                valueRange = range,
                                steps = steps,
                                enabled = true
                            )
                        }
                    }
                    dimSlider(
                        stringResource(R.string.rag_config_embed_dimension),
                        (config.embedDimension ?: 0).toFloat(),
                        0f..4096f,
                        15
                    ) {
                        viewModel.updateConfig { c -> c.copy(embedDimension = it.toInt().takeIf { v -> v > 0 }) }
                    }

                    dimSlider(
                        stringResource(R.string.rag_config_max_embed_tokens),
                        config.maxEmbedTokensPerCall.toFloat(),
                        256f..16384f,
                        15
                    ) {
                        viewModel.updateConfig { c -> c.copy(maxEmbedTokensPerCall = it.toInt()) }
                    }
                }
            }

            // === 摘要提示词 ===
            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.rag_config_section_template),
                        style = NexaraTypography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NexaraColors.OnSurface
                    )
                    NexaraGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSummaryTemplateEditor = true },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                config.summaryTemplate.take(100) + if (config.summaryTemplate.length > 100) "..." else "",
                                style = NexaraTypography.bodySmall.copy(fontSize = 12.sp),
                                color = NexaraColors.OnSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    NavigationRow(Icons.Rounded.Tune, stringResource(R.string.rag_config_advanced_link), stringResource(R.string.rag_config_advanced_desc)) {
                        onNavigateToAdvanced()
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(NexaraColors.GlassBorder))
                    NavigationRow(Icons.Rounded.Storage, stringResource(R.string.rag_config_details_link), stringResource(R.string.rag_config_details_desc)) {
                        onNavigateToDebug()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.Error.copy(alpha = 0.1f))
                            .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .clickable { showClearDialog = true }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = NexaraColors.Error, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.rag_config_clear_vectors), style = NexaraTypography.labelMedium, color = NexaraColors.Error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showClearDialog) {
        ModalBottomSheet(
            onDismissRequest = { showClearDialog = false },
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.rag_config_clear_vectors), style = NexaraTypography.headlineMedium, color = NexaraColors.Error)
                Text(stringResource(R.string.rag_config_clear_message), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(NexaraColors.SurfaceContainer)
                        .clickable { clearWithGraph = false }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Storage, contentDescription = null, tint = NexaraColors.OnSurface, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.rag_config_clear_only_vectors), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(NexaraColors.SurfaceContainer)
                        .clickable { clearWithGraph = true }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = NexaraColors.OnSurface, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.rag_config_clear_with_kg), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .clickable { showClearDialog = false }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.common_btn_cancel), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                    }
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.Error)
                            .clickable {
                                viewModel.clearAllVectors(withGraph = clearWithGraph)
                                showClearDialog = false
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.rag_config_clear_all), style = NexaraTypography.labelMedium, color = NexaraColors.OnError)
                    }
                }
            }
        }
    }

    UnifiedPromptEditor(
        show = showSummaryTemplateEditor,
        onDismiss = { showSummaryTemplateEditor = false },
        initialText = config.summaryTemplate,
        title = stringResource(R.string.rag_advanced_summary_template_title),
        onSave = { text -> viewModel.updateConfig { it.copy(summaryTemplate = text.ifBlank { RagConfiguration().summaryTemplate }) } },
        placeholder = stringResource(R.string.rag_config_summary_template_placeholder)
    )
}



@Composable
private fun NavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(NexaraColors.SurfaceHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
            }
            Column {
                Text(title, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                Text(subtitle, style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
            }
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = NexaraColors.Outline, modifier = Modifier.size(20.dp))
    }
}
