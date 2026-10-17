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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.ui.common.NexaraNumericSetting
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsRowLayout
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.rag.RetrievalSettingSpecs
import com.promenar.nexara.ui.rag.RetrievalStepperSetting
import com.promenar.nexara.ui.rag.RetrievalStrategySetting

@Composable
fun AgentAdvancedRetrievalScreen(
    agentId: String,
    scopeLabel: String,
    viewModel: AgentEditViewModel = viewModel(factory = AgentEditViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit
) {
    val presetRerankModel by ProviderManager.getInstance().rerankModelId.collectAsState()
    val isRerankAvailable = presetRerankModel.isNotBlank()

    LaunchedEffect(agentId) {
        viewModel.loadAgent(agentId)
    }

    val useInherited by viewModel.useInheritedConfig.collectAsState()
    val retrievalConfig by viewModel.retrievalConfig.collectAsState()

    AgentAdvancedRetrievalScreenContent(
        state = AgentAdvancedRetrievalScreenState(
            scopeLabel = scopeLabel,
            useInheritedConfig = useInherited,
            retrievalConfig = retrievalConfig,
            isRerankAvailable = isRerankAvailable,
        ),
        actions = AgentAdvancedRetrievalScreenActions(
            onBack = onNavigateBack,
            onRetrievalConfigChanged = { transform -> viewModel.updateRetrievalConfig(transform) },
            onResetToGlobal = viewModel::resetToGlobal,
        ),
    )
}

internal data class AgentAdvancedRetrievalScreenState(
    val scopeLabel: String,
    val useInheritedConfig: Boolean,
    val retrievalConfig: AgentRetrievalConfig,
    val isRerankAvailable: Boolean,
)

internal data class AgentAdvancedRetrievalScreenActions(
    val onBack: () -> Unit = {},
    val onRetrievalConfigChanged: (AgentRetrievalConfig.() -> AgentRetrievalConfig) -> Unit = {},
    val onResetToGlobal: () -> Unit = {},
)

@Composable
internal fun AgentAdvancedRetrievalScreenContent(
    state: AgentAdvancedRetrievalScreenState,
    actions: AgentAdvancedRetrievalScreenActions = AgentAdvancedRetrievalScreenActions(),
) {
    val config = state.retrievalConfig
    var showResetConfirm by remember { mutableStateOf(false) }

    if (showResetConfirm) {
        AlertDialog(
            modifier = Modifier.testTag("agent_retrieval_reset_dialog"),
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.agent_rag_reset_title)) },
            text = { Text(stringResource(R.string.agent_rag_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onResetToGlobal()
                        showResetConfirm = false
                    },
                    modifier = Modifier.testTag("agent_retrieval_reset_confirm"),
                ) {
                    Text(stringResource(R.string.shared_btn_reset), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.common_btn_cancel))
                }
            },
        )
    }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.agent_retrieval_title),
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
                        modifier = Modifier.testTag("agent_retrieval_reset_action"),
                    )
                }
            }

            NexaraSettingsSection(title = stringResource(R.string.agent_retrieval_section_memory)) {
                RetrievalStepperSetting(
                    title = stringResource(R.string.agent_retrieval_memory_limit),
                    value = config.memoryLimit,
                    range = 3..10,
                    onValueChange = { value -> actions.onRetrievalConfigChanged { copy(memoryLimit = value) } },
                    modifier = Modifier.testTag("agent_retrieval_memory_limit_slider"),
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.retrieval_similarity_threshold),
                    value = config.memoryThreshold.toString().toDouble() * 100.0,
                    spec = RetrievalSettingSpecs.agentMemoryThreshold,
                    onValueChange = { value -> actions.onRetrievalConfigChanged { copy(memoryThreshold = (value / 100).toFloat()) } },
                )
            }

            NexaraSettingsSection(title = stringResource(R.string.agent_retrieval_section_document)) {
                RetrievalStepperSetting(
                    title = stringResource(R.string.agent_retrieval_doc_limit),
                    value = config.docLimit,
                    range = 5..15,
                    onValueChange = { value -> actions.onRetrievalConfigChanged { copy(docLimit = value) } },
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.retrieval_similarity_threshold),
                    value = config.docThreshold.toString().toDouble() * 100.0,
                    spec = RetrievalSettingSpecs.agentDocThreshold,
                    onValueChange = { value -> actions.onRetrievalConfigChanged { copy(docThreshold = (value / 100).toFloat()) } },
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.agent_retrieval_section_rerank),
                footer = if (!state.isRerankAvailable) stringResource(R.string.agent_retrieval_rerank_model_unavailable_message) else null,
            ) {
                SettingsToggle(
                    title = stringResource(R.string.agent_retrieval_rerank_enable),
                    description = if (!state.isRerankAvailable) stringResource(R.string.settings_retrieval_model_unavailable_short) else null,
                    checked = state.isRerankAvailable && config.enableRerank,
                    enabled = state.isRerankAvailable,
                    onCheckedChange = { value -> actions.onRetrievalConfigChanged { copy(enableRerank = value) } },
                    modifier = Modifier.testTag("agent_retrieval_rerank_toggle"),
                    switchModifier = Modifier.testTag("agent_retrieval_rerank_switch"),
                )
                if (state.isRerankAvailable && config.enableRerank) {
                    NexaraNumericSetting(
                        title = stringResource(R.string.agent_retrieval_recall_count),
                        value = config.rerankTopK,
                        spec = RetrievalSettingSpecs.agentRerankTopK,
                        onValueChange = { value -> actions.onRetrievalConfigChanged { copy(rerankTopK = value.toInt()) } },
                    )
                    RetrievalStepperSetting(
                        title = stringResource(R.string.agent_retrieval_final_count),
                        value = config.rerankFinalK,
                        range = 3..20,
                        onValueChange = { value -> actions.onRetrievalConfigChanged { copy(rerankFinalK = value) } },
                    )
                }
            }

            NexaraSettingsSection(
                title = stringResource(R.string.agent_retrieval_section_rewrite),
                footer = stringResource(R.string.retrieval_rewrite_desc),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.agent_retrieval_rewrite_enable),
                    checked = config.enableQueryRewrite,
                    onCheckedChange = { value -> actions.onRetrievalConfigChanged { copy(enableQueryRewrite = value) } },
                    modifier = Modifier.testTag("agent_retrieval_rewrite_toggle"),
                    switchModifier = Modifier.testTag("agent_retrieval_rewrite_switch"),
                )
                if (config.enableQueryRewrite) {
                    RetrievalStrategySetting(
                        strategy = config.queryRewriteStrategy,
                        onValueChange = { value -> actions.onRetrievalConfigChanged { copy(queryRewriteStrategy = value) } },
                    )
                    RetrievalStepperSetting(
                        title = stringResource(R.string.agent_retrieval_variant_count),
                        value = config.queryRewriteCount,
                        range = 2..5,
                        onValueChange = { value -> actions.onRetrievalConfigChanged { copy(queryRewriteCount = value) } },
                    )
                }
            }

            NexaraSettingsSection(
                title = stringResource(R.string.agent_retrieval_section_hybrid),
                footer = stringResource(R.string.retrieval_hybrid_desc),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.retrieval_hybrid_enable),
                    checked = config.enableHybridSearch,
                    onCheckedChange = { value -> actions.onRetrievalConfigChanged { copy(enableHybridSearch = value) } },
                    modifier = Modifier.testTag("agent_retrieval_hybrid_toggle"),
                    switchModifier = Modifier.testTag("agent_retrieval_hybrid_switch"),
                )
                if (config.enableHybridSearch) {
                    NexaraNumericSetting(
                        title = stringResource(R.string.agent_retrieval_vector_weight),
                        value = config.hybridAlpha.toString().toDouble() * 100.0,
                        spec = RetrievalSettingSpecs.agentVectorWeight,
                        onValueChange = { value -> actions.onRetrievalConfigChanged { copy(hybridAlpha = (value / 100).toFloat()) } },
                    )
                    NexaraNumericSetting(
                        title = stringResource(R.string.agent_retrieval_bm25_boost),
                        value = config.hybridBM25Boost,
                        spec = RetrievalSettingSpecs.agentBm25Boost,
                        onValueChange = { value -> actions.onRetrievalConfigChanged { copy(hybridBM25Boost = value.toFloat()) } },
                    )
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
