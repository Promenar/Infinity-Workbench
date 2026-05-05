package com.promenar.nexara.ui.rag

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagAdvancedScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as android.app.Application)),
    onNavigateBack: () -> Unit,
    onNavigateToGraph: () -> Unit = {}
) {
    val config by viewModel.config.collectAsState()
    var showPromptEditor by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    val extractionModels = remember {
        listOf(
            "GPT-4 Omni" to "gpt-4o",
            "Claude 3 Opus" to "claude-3-opus",
            "Gemini 1.5 Pro" to "gemini-1.5-pro"
        )
    }

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

            SettingsSectionHeader(stringResource(R.string.rag_advanced_kg_section))
            SettingsToggle(
                title = stringResource(R.string.rag_advanced_kg_enable),
                description = stringResource(R.string.rag_advanced_kg_desc),
                checked = config.enableKnowledgeGraph,
                onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(enableKnowledgeGraph = enabled) } }
            )

            if (config.enableKnowledgeGraph) {
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
                                    Text(
                                        config.kgExtractionModel ?: "GPT-4 Omni (Recommended)",
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

                        SettingsSectionHeader(stringResource(R.string.rag_advanced_jit_section))
                        SettingsToggle(
                            title = stringResource(R.string.rag_advanced_jit_enable),
                            description = stringResource(R.string.rag_advanced_jit_desc),
                            checked = config.jitMaxChunks > 0,
                            onCheckedChange = { enabled ->
                                viewModel.updateConfig { it.copy(jitMaxChunks = if (enabled) 128 else 0) }
                            }
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
                            title = stringResource(R.string.rag_advanced_jit_free_mode),
                            description = stringResource(R.string.rag_advanced_jit_free_desc),
                            checked = config.kgFreeMode,
                            onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(kgFreeMode = enabled) } }
                        )
                        SettingsToggle(
                            title = stringResource(R.string.rag_advanced_jit_domain),
                            description = stringResource(R.string.rag_advanced_jit_domain_desc),
                            checked = config.kgDomainAuto,
                            onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(kgDomainAuto = enabled) } }
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
                        SettingsSectionHeader(stringResource(R.string.rag_advanced_cost_section))
                        val strategies = listOf(
                            Triple("summary", stringResource(R.string.rag_advanced_cost_summary), stringResource(R.string.rag_advanced_cost_summary_desc)),
                            Triple("on-demand", stringResource(R.string.rag_advanced_cost_on_demand), stringResource(R.string.rag_advanced_cost_on_demand_desc)),
                            Triple("full-scan", stringResource(R.string.rag_advanced_cost_full_scan), stringResource(R.string.rag_advanced_cost_full_scan_desc))
                        )

                        strategies.forEach { (value, label, description) ->
                            val isSelected = config.costStrategy == value
                            val bgColor by animateColorAsState(
                                if (isSelected) NexaraColors.Primary.copy(alpha = 0.08f) else NexaraColors.SurfaceContainer,
                                label = "cost_$value"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bgColor)
                                    .border(
                                        0.5.dp,
                                        if (isSelected) NexaraColors.Primary.copy(alpha = 0.3f) else NexaraColors.GlassBorder,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { viewModel.updateConfig { it.copy(costStrategy = value) } }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.updateConfig { it.copy(costStrategy = value) } },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = NexaraColors.Primary,
                                        unselectedColor = NexaraColors.Outline
                                    )
                                )
                                Column {
                                    Text(label, style = NexaraTypography.labelMedium, color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurface)
                                    Text(description, style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
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
                        SettingsToggle(
                            title = stringResource(R.string.rag_advanced_rule_prefilter),
                            description = stringResource(R.string.rag_advanced_rule_prefilter_desc),
                            checked = config.enableLocalPreprocess,
                            onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(enableLocalPreprocess = enabled) } }
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
                                    config.kgExtractionPrompt ?: "Extract named entities, concepts, and relationships from the following text. Format as JSON with nodes and edges arrays.",
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
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showModelPicker) {
        ModalBottomSheet(
            onDismissRequest = { showModelPicker = false },
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.rag_advanced_extract_model), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                extractionModels.forEach { (name, id) ->
                    val isSelected = config.kgExtractionModel == id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) NexaraColors.Primary.copy(alpha = 0.1f)
                                else NexaraColors.SurfaceContainer
                            )
                            .clickable {
                                viewModel.updateConfig { it.copy(kgExtractionModel = id) }
                                showModelPicker = false
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        if (isSelected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    if (showPromptEditor) {
        ModalBottomSheet(
            onDismissRequest = { showPromptEditor = false },
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            var promptText by remember { mutableStateOf(config.kgExtractionPrompt ?: "") }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.rag_advanced_edit_prompt), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                NexaraGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        textStyle = NexaraTypography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.Primary)
                            .clickable {
                                viewModel.updateConfig { it.copy(kgExtractionPrompt = promptText.ifBlank { null }) }
                                showPromptEditor = false
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.shared_btn_save), style = NexaraTypography.labelMedium, color = NexaraColors.OnPrimary)
                    }
                }
            }
        }
    }
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
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = NexaraColors.Primary,
                activeTrackColor = NexaraColors.Primary,
                inactiveTrackColor = NexaraColors.SurfaceHighest
            )
        )
    }
}
