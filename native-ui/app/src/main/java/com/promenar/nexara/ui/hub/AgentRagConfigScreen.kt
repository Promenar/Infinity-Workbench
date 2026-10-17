package com.promenar.nexara.ui.hub

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.ui.common.ConfirmDialog
import com.promenar.nexara.ui.common.EditorMode
import com.promenar.nexara.ui.common.NexaraNumericSetting
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsRowLayout
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.UnifiedPromptEditor
import com.promenar.nexara.ui.rag.RetrievalSettingSpecs

@Composable
fun AgentRagConfigScreen(
    agentId: String,
    scopeLabel: String,
    viewModel: AgentEditViewModel = viewModel(factory = AgentEditViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit,
) {
    LaunchedEffect(agentId) { viewModel.loadAgent(agentId) }
    val useInherited by viewModel.useInheritedConfig.collectAsState()
    val ragConfig by viewModel.ragConfig.collectAsState()

    AgentRagConfigScreenContent(
        state = AgentRagConfigScreenState(scopeLabel, useInherited, ragConfig),
        actions = AgentRagConfigScreenActions(
            onBack = onNavigateBack,
            onConfigChanged = { transform -> viewModel.updateRagConfig(transform) },
            onSaveSummaryTemplate = { template -> viewModel.saveRagConfig { it.copy(summaryTemplate = template) } },
            onResetToGlobal = viewModel::resetToGlobal,
        ),
    )
}

internal data class AgentRagConfigScreenState(
    val scopeLabel: String,
    val useInheritedConfig: Boolean,
    val ragConfig: AgentRagConfig,
)

internal data class AgentRagConfigScreenActions(
    val onBack: () -> Unit = {},
    val onConfigChanged: (AgentRagConfig.() -> AgentRagConfig) -> Unit = {},
    val onSaveSummaryTemplate: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    val onResetToGlobal: () -> Unit = {},
)

@Composable
internal fun AgentRagConfigScreenContent(
    state: AgentRagConfigScreenState,
    actions: AgentRagConfigScreenActions = AgentRagConfigScreenActions(),
) {
    val config = state.ragConfig
    var showResetConfirm by remember { mutableStateOf(false) }
    var showTemplateEditor by remember { mutableStateOf(false) }

    UnifiedPromptEditor(
        show = showTemplateEditor,
        onDismiss = { showTemplateEditor = false },
        onSave = actions.onSaveSummaryTemplate,
        title = stringResource(R.string.agent_rag_section_summary),
        initialText = config.summaryTemplate,
        placeholder = stringResource(R.string.agent_rag_summary_placeholder),
        mode = EditorMode.DIALOG,
    )
    ConfirmDialog(
        show = showResetConfirm,
        onDismiss = { showResetConfirm = false },
        onConfirm = {
            actions.onResetToGlobal()
            showResetConfirm = false
        },
        title = stringResource(R.string.agent_rag_reset_title),
        description = stringResource(R.string.agent_rag_reset_message),
        confirmLabel = stringResource(R.string.agent_rag_reset_confirm),
        confirmColor = MaterialTheme.colorScheme.error,
        destructive = true,
    )

    NexaraSettingsPageLayout(
        title = stringResource(R.string.agent_rag_title),
        onBack = actions.onBack,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            NexaraSettingsSection(title = state.scopeLabel) {
                NexaraSettingsRowLayout(
                    title = stringResource(R.string.settings_retrieval_config_source_title),
                    subtitle = stringResource(
                        if (state.useInheritedConfig) R.string.agent_rag_status_inherited else R.string.agent_rag_status_custom,
                    ),
                )
                if (!state.useInheritedConfig) {
                    NexaraSettingsItem(
                        title = stringResource(R.string.settings_retrieval_reset_global_title),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showResetConfirm = true },
                    )
                }
            }

            NexaraSettingsSection(title = stringResource(R.string.agent_rag_section_chunk)) {
                NexaraNumericSetting(
                    title = stringResource(R.string.agent_rag_chunk_size),
                    value = config.docChunkSize,
                    spec = RetrievalSettingSpecs.agentChunkSize,
                    onValueChange = { value -> actions.onConfigChanged { copy(docChunkSize = value.toInt()) } },
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.agent_rag_overlap),
                    value = config.chunkOverlap,
                    spec = RetrievalSettingSpecs.agentChunkOverlap,
                    onValueChange = { value -> actions.onConfigChanged { copy(chunkOverlap = value.toInt()) } },
                )
            }

            NexaraSettingsSection(title = stringResource(R.string.agent_rag_section_memory)) {
                NexaraNumericSetting(
                    title = stringResource(R.string.agent_rag_memory_chunk),
                    value = config.memoryChunkSize,
                    spec = RetrievalSettingSpecs.agentMemoryChunk,
                    onValueChange = { value -> actions.onConfigChanged { copy(memoryChunkSize = value.toInt()) } },
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.settings_batch1_memory_generation),
            ) {
                NexaraSettingsItem(
                    title = stringResource(R.string.agent_rag_section_summary),
                    subtitle = stringResource(
                        if (config.summaryTemplate.isBlank()) R.string.agent_rag_summary_default
                        else R.string.agent_rag_summary_configured,
                    ),
                    onClick = { showTemplateEditor = true },
                    showChevron = true,
                )
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
