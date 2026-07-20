package com.promenar.nexara.ui.hub

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraPageLayout

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentAdvancedRetrievalScreenContent(
    state: AgentAdvancedRetrievalScreenState,
    actions: AgentAdvancedRetrievalScreenActions = AgentAdvancedRetrievalScreenActions(),
) {
    val scopeLabel = state.scopeLabel
    val useInherited = state.useInheritedConfig
    val retrievalConfig = state.retrievalConfig
    val isRerankAvailable = state.isRerankAvailable
    var showResetConfirm by remember { mutableStateOf(false) }

    val memoryLimit = retrievalConfig.memoryLimit
    val memoryThreshold = retrievalConfig.memoryThreshold
    val docLimit = retrievalConfig.docLimit
    val docThreshold = retrievalConfig.docThreshold
    val enableRerank = retrievalConfig.enableRerank
    val rerankTopK = retrievalConfig.rerankTopK
    val rerankFinalK = retrievalConfig.rerankFinalK
    val enableQueryRewrite = retrievalConfig.enableQueryRewrite
    val queryRewriteStrategy = retrievalConfig.queryRewriteStrategy
    val queryRewriteCount = retrievalConfig.queryRewriteCount
    val enableHybridSearch = retrievalConfig.enableHybridSearch
    val hybridAlpha = retrievalConfig.hybridAlpha
    val hybridBM25Boost = retrievalConfig.hybridBM25Boost

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
            }
        )
    }

    NexaraPageLayout(
        title = stringResource(R.string.agent_retrieval_title),
        onBack = actions.onBack
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = scopeLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            ListItem(
                headlineContent = {
                    Text(
                        text = if (useInherited) stringResource(R.string.agent_rag_status_inherited) else stringResource(R.string.agent_rag_status_custom),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                trailingContent = {
                    if (!useInherited) {
                        TextButton(
                            onClick = { showResetConfirm = true },
                            modifier = Modifier
                                .sizeIn(minHeight = 48.dp)
                                .testTag("agent_retrieval_reset_action"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.shared_btn_reset)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 记忆检索 ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.agent_retrieval_section_memory),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                RetrievalParamSlider(
                    label = stringResource(R.string.agent_retrieval_memory_limit),
                    value = memoryLimit.toFloat(),
                    valueRange = 3f..10f,
                    step = 1f,
                    displayValue = "${memoryLimit}",
                    onValueChange = { newVal -> actions.onRetrievalConfigChanged { copy(memoryLimit = newVal.toInt()) } },
                    sliderModifier = Modifier.testTag("agent_retrieval_memory_limit_slider"),
                )
                RetrievalParamSlider(
                    label = stringResource(R.string.agent_retrieval_memory_threshold),
                    value = memoryThreshold,
                    valueRange = 0.5f..0.95f,
                    step = 0.05f,
                    displayValue = "${(memoryThreshold * 100).toInt()}%",
                    onValueChange = { newVal -> actions.onRetrievalConfigChanged { copy(memoryThreshold = newVal) } }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 文档检索 ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.agent_retrieval_section_document),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                RetrievalParamSlider(
                    label = stringResource(R.string.agent_retrieval_doc_limit),
                    value = docLimit.toFloat(),
                    valueRange = 5f..15f,
                    step = 1f,
                    displayValue = "${docLimit}",
                    onValueChange = { newVal -> actions.onRetrievalConfigChanged { copy(docLimit = newVal.toInt()) } }
                )
                RetrievalParamSlider(
                    label = stringResource(R.string.agent_retrieval_doc_threshold),
                    value = docThreshold,
                    valueRange = 0.3f..0.8f,
                    step = 0.05f,
                    displayValue = "${(docThreshold * 100).toInt()}%",
                    onValueChange = { newVal -> actions.onRetrievalConfigChanged { copy(docThreshold = newVal) } }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 重排序 ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_retrieval_section_rerank),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    supportingContent = if (!isRerankAvailable) {
                        {
                            Text(
                                text = stringResource(R.string.agent_retrieval_rerank_model_unavailable_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else null,
                    trailingContent = {
                        Switch(
                            checked = isRerankAvailable && enableRerank,
                            enabled = isRerankAvailable,
                            onCheckedChange = null,
                            modifier = Modifier.testTag("agent_retrieval_rerank_switch"),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_retrieval_rerank_toggle")
                        .toggleable(
                            value = isRerankAvailable && enableRerank,
                            enabled = isRerankAvailable,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                actions.onRetrievalConfigChanged { copy(enableRerank = checked) }
                            },
                        )
                )

                if (isRerankAvailable && enableRerank) {
                    RetrievalParamSlider(
                        label = stringResource(R.string.agent_retrieval_recall_count),
                        value = rerankTopK.toFloat(),
                        valueRange = 10f..100f,
                        step = 5f,
                        displayValue = "${rerankTopK}",
                        onValueChange = { newVal -> actions.onRetrievalConfigChanged { copy(rerankTopK = newVal.toInt()) } }
                    )
                    RetrievalParamSlider(
                        label = stringResource(R.string.agent_retrieval_final_count),
                        value = rerankFinalK.toFloat(),
                        valueRange = 3f..20f,
                        step = 1f,
                        displayValue = "${rerankFinalK}",
                        onValueChange = { newVal -> actions.onRetrievalConfigChanged { copy(rerankFinalK = newVal.toInt()) } }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 查询改写 ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_retrieval_section_rewrite),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = enableQueryRewrite,
                            onCheckedChange = null,
                            modifier = Modifier.testTag("agent_retrieval_rewrite_switch"),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_retrieval_rewrite_toggle")
                        .toggleable(
                            value = enableQueryRewrite,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                actions.onRetrievalConfigChanged { copy(enableQueryRewrite = checked) }
                            },
                        )
                )

                if (enableQueryRewrite) {
                    Text(
                        text = stringResource(R.string.agent_retrieval_strategy_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "hyde" to stringResource(R.string.agent_retrieval_strategy_hyde),
                            "multi-query" to stringResource(R.string.agent_retrieval_strategy_multi),
                            "expansion" to stringResource(R.string.agent_retrieval_strategy_expansion)
                        ).forEach { (id, label) ->
                            val isSelected = queryRewriteStrategy == id
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    actions.onRetrievalConfigChanged { copy(queryRewriteStrategy = id) }
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    RetrievalParamSlider(
                        label = stringResource(R.string.agent_retrieval_variant_count),
                        value = queryRewriteCount.toFloat(),
                        valueRange = 2f..5f,
                        step = 1f,
                        displayValue = "${queryRewriteCount}",
                        onValueChange = { newVal -> actions.onRetrievalConfigChanged { copy(queryRewriteCount = newVal.toInt()) } }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 混合检索 ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_retrieval_section_hybrid),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = enableHybridSearch,
                            onCheckedChange = null,
                            modifier = Modifier.testTag("agent_retrieval_hybrid_switch"),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_retrieval_hybrid_toggle")
                        .toggleable(
                            value = enableHybridSearch,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                actions.onRetrievalConfigChanged { copy(enableHybridSearch = checked) }
                            },
                        )
                )
                if (enableHybridSearch) {
                    RetrievalParamSlider(
                        label = stringResource(R.string.agent_retrieval_vector_weight),
                        value = hybridAlpha,
                        valueRange = 0f..1f,
                        step = 0.05f,
                        displayValue = "${(hybridAlpha * 100).toInt()}%",
                        onValueChange = { newVal -> actions.onRetrievalConfigChanged { copy(hybridAlpha = newVal) } }
                    )
                    RetrievalParamSlider(
                        label = stringResource(R.string.agent_retrieval_bm25_boost),
                        value = hybridBM25Boost,
                        valueRange = 0.5f..2.0f,
                        step = 0.1f,
                        displayValue = String.format("%.1fx", hybridBM25Boost),
                        onValueChange = { newVal -> actions.onRetrievalConfigChanged { copy(hybridBM25Boost = newVal) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun RetrievalParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    sliderModifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            modifier = sliderModifier,
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = ((valueRange.endInclusive - valueRange.start) / step).toInt() - 1
        )
    }
}
