package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraNumericSetting
import com.promenar.nexara.ui.common.NexaraSettingsExpandableRow
import com.promenar.nexara.ui.common.NexaraSettingsGroup
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraSettingsSubheader
import com.promenar.nexara.ui.common.NexaraSnackbar
import com.promenar.nexara.ui.common.NexaraSnackbarData
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.SnackbarType
import com.promenar.nexara.ui.common.UnifiedPromptEditor
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.nexaraDomainColors
import kotlinx.coroutines.launch

/**
 * 调试与高级：分块改动后的重建提示（A2）、索引数据（向量统计、全部重新向量化 A4、清除向量），
 * 以及收起的高级参数（A1，七组原始检索参数，不记住展开状态）。
 */
@Composable
fun KnowledgeDebugSettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenVectorStats: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(context.applicationContext as Application))
    val config by viewModel.config.collectAsState()
    val indexed by viewModel.indexedChunkSettings.collectAsState()
    val vectorStats by viewModel.vectorStats.collectAsState()
    val rerankModelId by ProviderManager.getInstance().rerankModelId.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = context.resources
    Box(Modifier.fillMaxSize()) {
        KnowledgeDebugSettingsContent(
            state = KnowledgeDebugSettingsState(
                config = config,
                indexedChunkSettings = indexed,
                vectorCount = vectorStats?.total ?: 0,
                rerankAvailable = rerankModelId.isNotBlank(),
            ),
            actions = KnowledgeDebugSettingsActions(
                onBack = onNavigateBack,
                onOpenVectorStats = onOpenVectorStats,
                onReindexAll = {
                    viewModel.reindexAllDocuments { submitted ->
                        val message = if (submitted == 0) {
                            resources.getString(R.string.settings_ia_reindex_empty)
                        } else {
                            resources.getQuantityString(R.plurals.settings_ia_reindex_submitted, submitted, submitted)
                        }
                        scope.launch { snackbarHost.showSnackbar(message) }
                    }
                },
                onClearVectors = viewModel::clearAllVectors,
                onConfigChanged = { transform -> viewModel.updateConfig(transform) },
            ),
        )
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        ) { data -> NexaraSnackbar(NexaraSnackbarData(data.visuals.message, SnackbarType.SUCCESS)) }
    }
}

internal data class KnowledgeDebugSettingsState(
    val config: RagConfiguration,
    val indexedChunkSettings: ChunkSettings? = null,
    val vectorCount: Int = 0,
    val rerankAvailable: Boolean = true,
    /** 仅供预览与测试直接展开高级参数；页面本身每次进入都是收起的。 */
    val initiallyExpanded: Boolean = false,
) {
    val chunkSettingsChanged: Boolean get() = chunkSettingsChanged(config, indexedChunkSettings)
}

internal data class KnowledgeDebugSettingsActions(
    val onBack: () -> Unit = {},
    val onOpenVectorStats: () -> Unit = {},
    val onReindexAll: () -> Unit = {},
    val onClearVectors: (withGraph: Boolean) -> Unit = {},
    val onConfigChanged: (RagConfiguration.() -> RagConfiguration) -> Unit = {},
)

@Composable
internal fun KnowledgeDebugSettingsContent(
    state: KnowledgeDebugSettingsState,
    actions: KnowledgeDebugSettingsActions = KnowledgeDebugSettingsActions(),
) {
    val config = state.config
    var showClearDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(state.initiallyExpanded) }
    var showSummaryEditor by remember { mutableStateOf(false) }
    var showExtractionPromptEditor by remember { mutableStateOf(false) }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.settings_ia_debug_title),
        onBack = actions.onBack,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .testTag("kb_debug_content"),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (state.chunkSettingsChanged) {
                ChunkChangedNotice(vectorCount = state.vectorCount, onReindex = actions.onReindexAll)
            }

            NexaraSettingsSection(
                title = stringResource(R.string.settings_ia_index_section),
                footer = stringResource(R.string.settings_ia_index_footer),
            ) {
                NexaraValueRow(
                    title = stringResource(R.string.settings_ia_vector_stats),
                    value = pluralStringResource(R.plurals.settings_ia_vector_chunks, state.vectorCount, state.vectorCount),
                    icon = Icons.Rounded.Storage,
                    onClick = actions.onOpenVectorStats,
                    modifier = Modifier.testTag("kb_debug_vector_stats_row"),
                )
                NexaraSettingsItem(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.settings_ia_reindex_all),
                    subtitle = stringResource(R.string.settings_ia_reindex_all_desc),
                    onClick = actions.onReindexAll,
                    modifier = Modifier.testTag("kb_debug_reindex_row"),
                )
                NexaraSettingsItem(
                    icon = Icons.Rounded.Delete,
                    title = stringResource(R.string.rag_config_clear_vectors),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { showClearDialog = true },
                    modifier = Modifier.testTag("rag_global_clear_action"),
                )
            }

            NexaraSettingsGroup(footer = stringResource(R.string.settings_ia_advanced_footer)) {
                NexaraSettingsExpandableRow(
                    title = stringResource(R.string.settings_ia_advanced_params),
                    subtitle = stringResource(
                        if (expanded) R.string.settings_ia_advanced_expanded_desc else R.string.settings_ia_advanced_collapsed_desc,
                    ),
                    icon = Icons.Rounded.Tune,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.testTag("kb_debug_advanced_row"),
                ) {
                    AdvancedParameterRows(
                        config = config,
                        rerankAvailable = state.rerankAvailable,
                        onConfigChanged = actions.onConfigChanged,
                        onEditSummaryPrompt = { showSummaryEditor = true },
                        onEditExtractionPrompt = { showExtractionPromptEditor = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showClearDialog) {
        ClearVectorsDialog(
            onDismiss = { showClearDialog = false },
            onConfirm = { withGraph ->
                actions.onClearVectors(withGraph)
                showClearDialog = false
            },
        )
    }

    UnifiedPromptEditor(
        show = showSummaryEditor,
        onDismiss = { showSummaryEditor = false },
        initialText = config.summaryTemplate,
        title = stringResource(R.string.rag_advanced_summary_template_title),
        onSave = { text ->
            runCatching {
                actions.onConfigChanged { copy(summaryTemplate = text.ifBlank { RagConfiguration().summaryTemplate }) }
            }
        },
        placeholder = stringResource(R.string.rag_config_summary_template_placeholder),
    )

    UnifiedPromptEditor(
        show = showExtractionPromptEditor,
        onDismiss = { showExtractionPromptEditor = false },
        initialText = config.kgExtractionPrompt ?: "",
        title = stringResource(R.string.rag_advanced_edit_prompt),
        onSave = { text ->
            runCatching { actions.onConfigChanged { copy(kgExtractionPrompt = text.ifBlank { null }) } }
        },
        placeholder = stringResource(R.string.rag_advanced_extract_prompt_placeholder),
    )
}

/** 页顶警示条：当前分块参数与已建索引不同（A2）。 */
@Composable
private fun ChunkChangedNotice(vectorCount: Int, onReindex: () -> Unit) {
    val colors = MaterialTheme.nexaraDomainColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NexaraSpacing.SectionGap)
            .background(colors.warningContainer, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)
            .testTag("kb_debug_chunk_notice"),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = colors.onWarningContainer,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = stringResource(R.string.settings_ia_chunk_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onWarningContainer,
                )
                Text(
                    text = stringResource(R.string.settings_ia_chunk_notice_body, "%,d".format(vectorCount)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onWarningContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        TextButton(
            onClick = onReindex,
            modifier = Modifier.align(Alignment.End).testTag("kb_debug_chunk_notice_reindex"),
        ) {
            Text(stringResource(R.string.settings_ia_reindex_all), color = colors.onWarningContainer)
        }
    }
}

@Composable
private fun ColumnScope.AdvancedParameterRows(
    config: RagConfiguration,
    rerankAvailable: Boolean,
    onConfigChanged: (RagConfiguration.() -> RagConfiguration) -> Unit,
    onEditSummaryPrompt: () -> Unit,
    onEditExtractionPrompt: () -> Unit,
) {
    NexaraSettingsSubheader(stringResource(R.string.settings_ia_group_chunking))
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_chunk_size),
        value = config.docChunkSize,
        spec = RetrievalSettingSpecs.globalChunkSize,
        onValueChange = { value -> onConfigChanged { copy(docChunkSize = value.toInt()) } },
        description = stringResource(R.string.settings_ia_chunk_desc),
        modifier = Modifier.testTag("kb_debug_chunk_size"),
    )
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_chunk_overlap),
        value = config.chunkOverlap,
        spec = RetrievalSettingSpecs.globalChunkOverlap,
        onValueChange = { value -> onConfigChanged { copy(chunkOverlap = value.toInt()) } },
        description = stringResource(R.string.settings_ia_chunk_desc),
    )
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_memory_chunk_size),
        value = config.memoryChunkSize,
        spec = RetrievalSettingSpecs.globalMemoryChunkSize,
        onValueChange = { value -> onConfigChanged { copy(memoryChunkSize = value.toInt()) } },
    )
    NexaraValueRow(
        title = stringResource(R.string.settings_ia_summary_prompt),
        value = stringResource(
            if (config.summaryTemplate == RagConfiguration().summaryTemplate) R.string.settings_ia_prompt_default
            else R.string.settings_ia_prompt_custom,
        ),
        onClick = onEditSummaryPrompt,
    )

    NexaraSettingsSubheader(stringResource(R.string.settings_ia_group_counts))
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_memory_count),
        value = config.memoryLimit,
        spec = RetrievalSettingSpecs.globalMemoryLimit,
        onValueChange = { value -> onConfigChanged { copy(memoryLimit = value.toInt()) } },
    )
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_document_count),
        value = config.docLimit,
        spec = RetrievalSettingSpecs.globalDocLimit,
        onValueChange = { value -> onConfigChanged { copy(docLimit = value.toInt()) } },
    )
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_rerank_candidates),
        value = config.rerankTopK,
        spec = RetrievalSettingSpecs.globalRerankTopK,
        onValueChange = { value -> onConfigChanged { copy(rerankTopK = value.toInt()) } },
    )
    RetrievalStepperSetting(
        title = stringResource(R.string.settings_ia_final_results),
        value = config.rerankFinalK,
        range = 1..20,
        onValueChange = { value -> onConfigChanged { copy(rerankFinalK = value) } },
    )
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_rerank_batch),
        value = config.rerankMaxPerCall,
        spec = RetrievalSettingSpecs.rerankMaxPerCall,
        onValueChange = { value -> onConfigChanged { copy(rerankMaxPerCall = value.toInt()) } },
        enabled = rerankAvailable,
        subtitle = if (rerankAvailable) null else stringResource(R.string.settings_ia_rerank_no_model),
    )

    NexaraSettingsSubheader(stringResource(R.string.settings_ia_group_thresholds))
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_memory_threshold),
        value = config.memoryThreshold,
        spec = RetrievalSettingSpecs.globalMemoryThreshold,
        onValueChange = { value -> onConfigChanged { copy(memoryThreshold = value.toFloat()) } },
    )
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_document_threshold),
        value = config.docThreshold,
        spec = RetrievalSettingSpecs.globalDocThreshold,
        onValueChange = { value -> onConfigChanged { copy(docThreshold = value.toFloat()) } },
    )

    NexaraSettingsSubheader(stringResource(R.string.settings_ia_group_hybrid))
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_vector_weight),
        value = config.hybridAlpha.toString().toDouble() * 100.0,
        spec = RetrievalSettingSpecs.globalVectorWeight,
        onValueChange = { value -> onConfigChanged { copy(hybridAlpha = (value / 100).toFloat()) } },
        enabled = config.enableHybridSearch,
        modifier = Modifier.testTag("kb_debug_vector_weight"),
    )
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_keyword_weight),
        value = config.hybridBM25Boost,
        spec = RetrievalSettingSpecs.globalBm25Boost,
        onValueChange = { value -> onConfigChanged { copy(hybridBM25Boost = value.toFloat()) } },
        enabled = config.enableHybridSearch,
        description = stringResource(R.string.settings_ia_keyword_weight_desc),
    )

    NexaraSettingsSubheader(stringResource(R.string.settings_ia_group_rewrite))
    RetrievalStrategySetting(
        strategy = config.queryRewriteStrategy,
        onValueChange = { value -> onConfigChanged { copy(queryRewriteStrategy = value) } },
        enabled = config.enableQueryRewrite,
    )
    RetrievalStepperSetting(
        title = stringResource(R.string.settings_ia_rewrite_variants),
        value = config.queryRewriteCount,
        range = 2..5,
        onValueChange = { value -> onConfigChanged { copy(queryRewriteCount = value) } },
        enabled = rewriteVariantsEnabled(config),
        modifier = Modifier.testTag("kb_debug_rewrite_variants"),
        description = stringResource(R.string.settings_ia_rewrite_variants_desc),
    )

    NexaraSettingsSubheader(stringResource(R.string.settings_ia_group_graph_retrieval))
    KgRetrievalRows(config, onConfigChanged)

    NexaraSettingsSubheader(stringResource(R.string.settings_ia_group_graph_extraction))
    NexaraNumericSetting(
        title = stringResource(R.string.settings_ia_extraction_timeout),
        value = config.kgExtractionTimeoutSeconds,
        spec = RetrievalSettingSpecs.extractionTimeout,
        onValueChange = { value -> onConfigChanged { copy(kgExtractionTimeoutSeconds = value.toInt()) } },
    )
    NexaraValueRow(
        title = stringResource(R.string.settings_ia_extraction_prompt),
        value = stringResource(
            if (config.kgExtractionPrompt.isNullOrBlank()) R.string.settings_ia_prompt_default else R.string.settings_ia_prompt_custom,
        ),
        onClick = onEditExtractionPrompt,
    )
}

/** 清除向量：只清向量，或连同知识图谱一起清。 */
@Composable
private fun ClearVectorsDialog(onDismiss: () -> Unit, onConfirm: (withGraph: Boolean) -> Unit) {
    var clearWithGraph by remember { mutableStateOf(false) }
    NexaraConfirmDialog(
        modifier = Modifier.testTag("rag_global_clear_dialog"),
        onDismissRequest = onDismiss,
        title = stringResource(R.string.rag_config_clear_vectors),
        message = stringResource(R.string.rag_config_clear_message),
        confirmText = stringResource(R.string.rag_config_clear_all),
        cancelText = stringResource(R.string.common_btn_cancel),
        onConfirm = { onConfirm(clearWithGraph) },
        onCancel = onDismiss,
        isDestructive = true,
        confirmButtonModifier = Modifier.testTag("rag_global_clear_confirm"),
        content = {
            Column(
                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    Triple(false, R.string.rag_config_clear_only_vectors, "rag_global_clear_vectors"),
                    Triple(true, R.string.rag_config_clear_with_kg, "rag_global_clear_graph"),
                ).forEach { (withGraph, label, tag) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("${tag}_row")
                            .selectable(selected = clearWithGraph == withGraph, onClick = { clearWithGraph = withGraph }, role = Role.RadioButton)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = clearWithGraph == withGraph, onClick = null, modifier = Modifier.testTag("${tag}_radio"))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
    )
}
