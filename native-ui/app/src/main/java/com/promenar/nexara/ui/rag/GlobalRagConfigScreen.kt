package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.NexaraNumericSetting
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.common.UnifiedPromptEditor
import com.promenar.nexara.ui.common.NexaraCapsuleTabRow
import com.promenar.nexara.ui.common.NexaraTabItem

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
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {

            NexaraSettingsSection(title = stringResource(R.string.rag_config_presets)) {
                val presets = listOf(
                    Triple(Icons.Rounded.OfflineBolt, stringResource(R.string.rag_config_preset_balanced), "balanced"),
                    Triple(Icons.Rounded.Edit, stringResource(R.string.rag_config_preset_writing), "writing"),
                    Triple(Icons.Rounded.Code, stringResource(R.string.rag_config_preset_coding), "coding"),
                )
                NexaraCapsuleTabRow(
                    tabs = presets.map { (icon, title, presetId) ->
                        NexaraTabItem(title = title, icon = icon, testTag = "rag_global_preset_$presetId")
                    },
                    selectedIndex = presets.indexOfFirst { it.third == config.currentPreset },
                    onTabSelected = { index -> actions.onPresetSelected(presets[index].third) },
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("rag_global_presets"),
                    itemRole = Role.RadioButton,
                )
            }

            NexaraSettingsSection(title = stringResource(R.string.rag_config_retrieval_params)) {
                NexaraNumericSetting(
                    title = stringResource(R.string.rag_config_chunk_size),
                    value = config.docChunkSize,
                    spec = RetrievalSettingSpecs.globalChunkSize,
                    onValueChange = { value -> actions.onConfigChanged { copy(docChunkSize = value.toInt()) } },
                    modifier = Modifier.testTag("rag_global_chunk_size_slider"),
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.rag_config_overlap),
                    value = config.chunkOverlap,
                    spec = RetrievalSettingSpecs.globalChunkOverlap,
                    onValueChange = { value -> actions.onConfigChanged { copy(chunkOverlap = value.toInt()) } },
                    modifier = Modifier.testTag("rag_global_chunk_overlap_slider"),
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.memory_state_section),
                footer = stringResource(R.string.memory_state_footer),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.memory_state_title),
                    description = stringResource(R.string.memory_state_desc),
                    checked = config.memoryStateExtractionEnabled,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(memoryStateExtractionEnabled = value) } },
                    modifier = Modifier.testTag("memory_state_toggle"),
                    switchModifier = Modifier.testTag("memory_state_switch"),
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.settings_batch1_generation_template),
            ) {
                NexaraSettingsItem(
                    title = stringResource(R.string.rag_config_section_template),
                    subtitle = stringResource(
                        if (config.summaryTemplate == RagConfiguration().summaryTemplate) R.string.settings_retrieval_template_default
                        else R.string.settings_retrieval_template_custom,
                    ),
                    onClick = { showSummaryTemplateEditor = true },
                    showChevron = true,
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.settings_retrieval_management_section),
            ) {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.rag_config_advanced_link),
                    subtitle = stringResource(R.string.settings_retrieval_graph_short),
                    onClick = actions.onNavigateToAdvanced,
                    showChevron = true,
                )
                NexaraSettingsItem(
                    icon = Icons.Rounded.Storage,
                    title = stringResource(R.string.rag_config_details_link),
                    subtitle = stringResource(R.string.rag_config_details_desc),
                    onClick = actions.onNavigateToDebug,
                    showChevron = true,
                )
            }

            NexaraSettingsSection(title = stringResource(R.string.settings_retrieval_data_section)) {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Delete,
                    title = stringResource(R.string.rag_config_clear_vectors),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { showClearDialog = true },
                    modifier = Modifier.testTag("rag_global_clear_action"),
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showClearDialog) {
        NexaraConfirmDialog(
            modifier = Modifier.testTag("rag_global_clear_dialog"),
            onDismissRequest = { showClearDialog = false },
            title = stringResource(R.string.rag_config_clear_vectors),
            message = stringResource(R.string.rag_config_clear_message),
            confirmText = stringResource(R.string.rag_config_clear_all),
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {
                actions.onClearVectors(clearWithGraph)
                showClearDialog = false
            },
            onCancel = { showClearDialog = false },
            isDestructive = true,
            confirmButtonModifier = Modifier.testTag("rag_global_clear_confirm"),
            content = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 仅清理向量。
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
                        // 同时清理图谱。
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
