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
fun GlobalRagConfigScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit,
    onNavigateToAdvanced: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {}
) {
    val config by viewModel.config.collectAsState()
    val vectorStats by viewModel.vectorStats.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var showPromptEditor by remember { mutableStateOf(false) }
    var clearWithGraph by remember { mutableStateOf(true) }

    NexaraPageLayout(
        title = stringResource(R.string.rag_config_title),
        onBack = onNavigateBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                text = stringResource(R.string.rag_config_desc),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant
            )

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
                    val isSelected = config.docChunkSize == 800 && config.chunkOverlap == 100
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
                            androidx.compose.material3.Slider(
                                value = value,
                                onValueChange = onChange,
                                valueRange = range,
                                steps = steps,
                                colors = SliderDefaults.colors(
                                    thumbColor = NexaraColors.Primary,
                                    activeTrackColor = NexaraColors.Primary,
                                    inactiveTrackColor = NexaraColors.SurfaceHighest
                                )
                            )
                        }
                    }

                    slider(stringResource(R.string.rag_config_chunk_size), config.docChunkSize.toFloat(), 100f..2000f, 18) {
                        viewModel.updateConfig { c -> c.copy(docChunkSize = it.toInt()) }
                    }
                    slider(stringResource(R.string.rag_config_overlap), config.chunkOverlap.toFloat(), 0f..500f, 9) {
                        viewModel.updateConfig { c -> c.copy(chunkOverlap = it.toInt()) }
                    }
                    slider(stringResource(R.string.rag_config_context_window), config.contextWindow.toFloat(), 4f..128f, 30) {
                        viewModel.updateConfig { c -> c.copy(contextWindow = it.toInt()) }
                    }
                    slider(stringResource(R.string.rag_config_summary_threshold), config.summaryThreshold.toFloat(), 0f..50f, 9) {
                        viewModel.updateConfig { c -> c.copy(summaryThreshold = it.toInt()) }
                    }
                }
            }

            SettingsSectionHeader(stringResource(R.string.rag_config_section_template))
            NexaraGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPromptEditor = true },
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.rag_config_template_hint), style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurfaceVariant)
                    }
                    Text(
                        "System: You are given the following retrieved chunks:\n\n{retrieved_chunks}\n\nBased on these, answer the user query.\n\nUser Query: {query}",
                        style = NexaraTypography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        color = NexaraColors.OnSurface,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.rag_config_vector_status), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                    val stats = vectorStats
                    if (stats != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                 StatCard(stringResource(R.string.rag_config_stats_documents), "${"%,d".format(stats.byType.doc)}")
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                 StatCard(stringResource(R.string.rag_config_stats_vectors), "${"%,d".format(stats.total)}")
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                 StatCard(stringResource(R.string.rag_config_stats_storage), "${"%.1f".format(stats.storageSizeMb)} MB")
                            }
                        }
                    } else {
                        Text(stringResource(R.string.rag_config_no_data), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.rag_config_reranker), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                    SettingsToggle(
                        title = stringResource(R.string.rag_config_reranker_enable),
                        description = stringResource(R.string.rag_config_reranker_desc),
                        checked = config.enableRerank,
                        onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(enableRerank = enabled) } }
                    )
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
                Box(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.StatusWarning.copy(alpha = 0.1f))
                            .border(0.5.dp, NexaraColors.StatusWarning.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .clickable { }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = NexaraColors.StatusWarning, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.rag_config_clean_orphans), style = NexaraTypography.labelMedium, color = NexaraColors.StatusWarning)
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
                modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 40.dp),
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
                            .clickable { showClearDialog = false }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.rag_config_clear_all), style = NexaraTypography.labelMedium, color = NexaraColors.OnError)
                    }
                }
            }
        }
    }

    if (showPromptEditor) {
        var promptText by remember {
            mutableStateOf(
                "System: You are given the following retrieved chunks:\n\n{retrieved_chunks}\n\nBased on these, answer the user query.\n\nUser Query: {query}"
            )
        }
        ModalBottomSheet(
            onDismissRequest = { showPromptEditor = false },
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.rag_config_edit_template), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                NexaraGlassCard(modifier = Modifier.fillMaxWidth().height(200.dp), shape = RoundedCornerShape(8.dp)) {
                    BasicTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        textStyle = NexaraTypography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = NexaraColors.OnSurface),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(NexaraColors.Primary)
                        .clickable { showPromptEditor = false }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.shared_btn_save), style = NexaraTypography.labelMedium, color = NexaraColors.OnPrimary)
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.medium as RoundedCornerShape
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, style = NexaraTypography.headlineMedium.copy(fontWeight = FontWeight.Black), color = NexaraColors.Primary)
            Text(label, style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurfaceVariant)
        }
    }
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
