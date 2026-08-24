package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.UnifiedPromptEditor
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

    GlobalRagConfigScreenContent(
        state = GlobalRagConfigScreenState(config = config),
        actions = GlobalRagConfigScreenActions(
            onBack = onNavigateBack,
            onPresetSelected = viewModel::applyPreset,
            onConfigChanged = { transform -> viewModel.updateConfig(transform) },
            onClearVectors = viewModel::clearAllVectors,
            onNavigateToAdvanced = onNavigateToAdvanced,
            onNavigateToDebug = onNavigateToDebug,
        ),
    )
}

internal data class GlobalRagConfigScreenState(
    val config: RagConfiguration,
)

internal data class GlobalRagConfigScreenActions(
    val onBack: () -> Unit = {},
    val onPresetSelected: (String) -> Unit = {},
    val onConfigChanged: (RagConfiguration.() -> RagConfiguration) -> Unit = {},
    val onClearVectors: (Boolean) -> Unit = {},
    val onNavigateToAdvanced: () -> Unit = {},
    val onNavigateToDebug: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlobalRagConfigScreenContent(
    state: GlobalRagConfigScreenState,
    actions: GlobalRagConfigScreenActions = GlobalRagConfigScreenActions(),
) {
    val config = state.config
    var showClearDialog by remember { mutableStateOf(false) }
    var clearWithGraph by remember { mutableStateOf(true) }
    var showSummaryTemplateEditor by remember { mutableStateOf(false) }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.rag_config_title),
        onBack = actions.onBack
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {

            SettingsSectionHeader(stringResource(R.string.rag_config_presets))

            val balancedLabel = stringResource(R.string.rag_config_preset_balanced)
            val writingLabel = stringResource(R.string.rag_config_preset_writing)
            val codingLabel = stringResource(R.string.rag_config_preset_coding)
            val presets = listOf(
                Triple(Icons.Rounded.OfflineBolt, balancedLabel, "balanced"),
                Triple(Icons.Rounded.Edit, writingLabel, "writing"),
                Triple(Icons.Rounded.Code, codingLabel, "coding")
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .testTag("rag_global_presets")
            ) {
                presets.forEachIndexed { index, (icon, title, presetId) ->
                    val isSelected = config.currentPreset == presetId
                    SegmentedButton(
                        modifier = Modifier
                            .sizeIn(minHeight = 48.dp)
                            .testTag("rag_global_preset_$presetId"),
                        selected = isSelected,
                        onClick = { actions.onPresetSelected(presetId) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = isSelected) {
                                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        },
                        label = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 检索参数分组
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.rag_config_retrieval_params),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.rag_config_chunk_size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${config.docChunkSize}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("rag_global_chunk_size_slider"),
                        value = config.docChunkSize.toFloat(),
                        onValueChange = { actions.onConfigChanged { copy(docChunkSize = it.toInt()) } },
                        valueRange = 100f..2000f,
                        steps = 18
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.rag_config_overlap),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${config.chunkOverlap}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("rag_global_chunk_overlap_slider"),
                        value = config.chunkOverlap.toFloat(),
                        onValueChange = { actions.onConfigChanged { copy(chunkOverlap = it.toInt()) } },
                        valueRange = 0f..500f,
                        steps = 9
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 摘要提示词 ===
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSummaryTemplateEditor = true },
                headlineContent = {
                    Text(
                        text = stringResource(R.string.rag_config_section_template),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                supportingContent = {
                    Text(
                        text = config.summaryTemplate.take(100) + if (config.summaryTemplate.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 导航链接 ===
            Column(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onNavigateToAdvanced() },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.rag_config_advanced_link),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.rag_config_advanced_desc),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onNavigateToDebug() },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.rag_config_details_link),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.rag_config_details_desc),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 清空按钮 ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.testTag("rag_global_clear_action"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.rag_config_clear_vectors),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            modifier = Modifier.testTag("rag_global_clear_dialog"),
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.rag_config_clear_vectors),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.rag_config_clear_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Option 1: 仅向量
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("rag_global_clear_vectors_row")
                                .selectable(
                                    selected = !clearWithGraph,
                                    onClick = { clearWithGraph = false },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !clearWithGraph,
                                onClick = null,
                                modifier = Modifier.testTag("rag_global_clear_vectors_radio"),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.rag_config_clear_only_vectors),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // Option 2: 含图谱
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("rag_global_clear_graph_row")
                                .selectable(
                                    selected = clearWithGraph,
                                    onClick = { clearWithGraph = true },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = clearWithGraph,
                                onClick = null,
                                modifier = Modifier.testTag("rag_global_clear_graph_radio"),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.rag_config_clear_with_kg),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onClearVectors(clearWithGraph)
                        showClearDialog = false
                    },
                    modifier = Modifier.testTag("rag_global_clear_confirm"),
                ) {
                    Text(
                        text = stringResource(R.string.rag_config_clear_all),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(text = stringResource(R.string.common_btn_cancel))
                }
            }
        )
    }

    UnifiedPromptEditor(
        show = showSummaryTemplateEditor,
        onDismiss = { showSummaryTemplateEditor = false },
        initialText = config.summaryTemplate,
        title = stringResource(R.string.rag_advanced_summary_template_title),
        onSave = { text ->
            runCatching {
                actions.onConfigChanged {
                    copy(summaryTemplate = text.ifBlank { RagConfiguration().summaryTemplate })
                }
            }
        },
        placeholder = stringResource(R.string.rag_config_summary_template_placeholder)
    )
}
