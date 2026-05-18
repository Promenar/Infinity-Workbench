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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagAdvancedScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGraph: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(context.applicationContext as Application))
    val config by viewModel.config.collectAsState()
    val settingsViewModel: SettingsViewModel = viewModel(
        modelClass = SettingsViewModel::class.java,
        factory = SettingsViewModel.factory(context.applicationContext as Application)
    )
    val allModels by settingsViewModel.providerModels.collectAsState()

    var showPromptEditor by remember { mutableStateOf(false) }
    var showSummaryTemplateEditor by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    NexaraPageLayout(
        title = stringResource(R.string.rag_advanced_title),
        onBack = onNavigateBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                stringResource(R.string.rag_advanced_desc),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant
            )

            // === 摘要模板（始终可见） ===
            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingsSectionHeader(stringResource(R.string.rag_config_section_template))
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

            // === 知识图谱（全局配置，会话级开关由聊天设置面板控制，默认关闭） ===

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SettingsSectionHeader(stringResource(R.string.rag_advanced_extract_config))

                        NexaraGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showModelPicker = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(stringResource(R.string.rag_advanced_extract_model), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                    val selectedModelName = allModels.find { it.id == config.kgExtractionModel }?.name
                                    Text(
                                        selectedModelName ?: stringResource(R.string.rag_advanced_select_model_placeholder),
                                        style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                                        color = NexaraColors.OnSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = NexaraColors.Outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        ConfigSlider(
                            label = stringResource(R.string.rag_advanced_kg_timeout),
                            value = config.kgExtractionTimeoutSeconds.toFloat(),
                            valueRange = 5f..120f,
                            onValueChange = { v -> viewModel.updateConfig { c -> c.copy(kgExtractionTimeoutSeconds = v.toInt()) } }
                        )
                        Text(
                            stringResource(R.string.rag_advanced_kg_timeout_desc),
                            style = NexaraTypography.labelSmall.copy(fontSize = 11.sp),
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 4.dp)
                        )

                        SettingsSectionHeader(stringResource(R.string.rag_advanced_jit_section))
                        SettingsToggle(
                            title = stringResource(R.string.rag_advanced_jit_enable),
                            description = stringResource(R.string.rag_advanced_jit_desc),
                            checked = config.jitMaxChunks > 0,
                            onCheckedChange = { enabled ->
                                viewModel.updateConfig { it.copy(jitMaxChunks = if (enabled) 128 else 0) }
                            }
                        )
                        Text(
                            stringResource(R.string.rag_advanced_coming_soon),
                            style = NexaraTypography.labelSmall.copy(fontSize = 10.sp),
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 4.dp)
                        )

                        if (config.jitMaxChunks > 0) {
                            ConfigSlider(
                                label = stringResource(R.string.rag_advanced_jit_max_blocks),
                                value = config.jitMaxChunks.toFloat(),
                                valueRange = 16f..512f,
                                onValueChange = { v -> viewModel.updateConfig { c -> c.copy(jitMaxChunks = v.toInt()) } }
                            )
                        }

                        SettingsToggle(
                            title = stringResource(R.string.rag_advanced_jit_domain),
                            description = stringResource(R.string.rag_advanced_jit_domain_desc),
                            checked = config.kgDomainAuto,
                            onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(kgDomainAuto = enabled) } }
                        )
                        Text(
                            stringResource(R.string.rag_advanced_coming_soon),
                            style = NexaraTypography.labelSmall.copy(fontSize = 10.sp),
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }

                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsSectionHeader(stringResource(R.string.rag_advanced_optimization_section))
                        SettingsToggle(
                            title = stringResource(R.string.rag_advanced_incremental_hash),
                            description = stringResource(R.string.rag_advanced_incremental_hash_desc),
                            checked = config.enableIncrementalHash,
                            onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(enableIncrementalHash = enabled) } }
                        )
                        Text(
                            stringResource(R.string.rag_advanced_coming_soon),
                            style = NexaraTypography.labelSmall.copy(fontSize = 10.sp),
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        SettingsToggle(
                            title = stringResource(R.string.rag_advanced_rule_prefilter),
                            description = stringResource(R.string.rag_advanced_rule_prefilter_desc),
                            checked = config.enableLocalPreprocess,
                            onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(enableLocalPreprocess = enabled) } }
                        )
                        Text(
                            stringResource(R.string.rag_advanced_coming_soon),
                            style = NexaraTypography.labelSmall.copy(fontSize = 10.sp),
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }

                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsSectionHeader(stringResource(R.string.rag_advanced_prompt_section))
                        NexaraGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPromptEditor = true },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(NexaraColors.StatusSuccess, CircleShape)
                                    )
                                    Text(
                                        stringResource(R.string.rag_advanced_active_prompt),
                                        style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                                        color = NexaraColors.StatusSuccess
                                    )
                                }
                                Text(
                                    config.kgExtractionPrompt ?: stringResource(R.string.rag_advanced_default_prompt),
                                    style = NexaraTypography.bodySmall.copy(fontSize = 12.sp),
                                    color = NexaraColors.OnSurface,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                stringResource(R.string.rag_advanced_reset_default),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.Error,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        viewModel.updateConfig { it.copy(kgExtractionPrompt = null) }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                NexaraGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToGraph() },
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(NexaraColors.SurfaceHigh, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(20.dp))
                            }
                            Text(stringResource(R.string.rag_advanced_view_graph), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = NexaraColors.Outline)
                    }
                }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    val modelItems = remember(allModels) {
        allModels.map { info ->
            ModelItem(
                id = info.id,
                name = info.name,
                providerName = info.providerName,
                capabilities = info.capabilities.mapNotNull { capStr ->
                    try { ModelCapability.valueOf(capStr.uppercase()) } catch (_: Exception) { null }
                },
                contextLength = info.contextLength
            )
        }
    }

    ModelPicker(
        show = showModelPicker,
        onDismiss = { showModelPicker = false },
        filterTag = "chat",
        models = modelItems,
        currentModelId = config.kgExtractionModel ?: "",
        onSelect = { id, _ ->
            viewModel.updateConfig { it.copy(kgExtractionModel = id) }
            showModelPicker = false
        }
    )

    UnifiedPromptEditor(
        show = showPromptEditor,
        onDismiss = { showPromptEditor = false },
        initialText = config.kgExtractionPrompt ?: "",
        title = stringResource(R.string.rag_advanced_edit_prompt),
        onSave = { text -> viewModel.updateConfig { it.copy(kgExtractionPrompt = text.ifBlank { null }) } },
        placeholder = stringResource(R.string.rag_advanced_extract_prompt_placeholder)
    )

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
private fun ConfigSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
            Text("${value.toInt()}", style = NexaraTypography.bodySmall, color = NexaraColors.Primary)
        }
        Spacer(modifier = Modifier.height(4.dp))
        NexaraSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
