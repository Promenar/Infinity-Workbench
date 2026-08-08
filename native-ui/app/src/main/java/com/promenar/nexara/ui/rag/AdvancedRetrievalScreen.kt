package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.data.manager.ProviderManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedRetrievalScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit
) {
    val config by viewModel.config.collectAsState()
    val presetRerankModel by ProviderManager.getInstance().rerankModelId.collectAsState()
    val isRerankAvailable = presetRerankModel.isNotBlank()

    AdvancedRetrievalScreenContent(
        state = AdvancedRetrievalScreenState(
            config = config,
            isRerankAvailable = isRerankAvailable,
        ),
        actions = AdvancedRetrievalScreenActions(
            onBack = onNavigateBack,
            onConfigChanged = { transform -> viewModel.updateConfig(transform) },
        ),
    )
}

internal data class AdvancedRetrievalScreenState(
    val config: RagConfiguration,
    val isRerankAvailable: Boolean,
)

internal data class AdvancedRetrievalScreenActions(
    val onBack: () -> Unit = {},
    val onConfigChanged: (RagConfiguration.() -> RagConfiguration) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedRetrievalScreenContent(
    state: AdvancedRetrievalScreenState,
    actions: AdvancedRetrievalScreenActions = AdvancedRetrievalScreenActions(),
) {
    val config = state.config
    val isRerankAvailable = state.isRerankAvailable

    NexaraSettingsPageLayout(
        title = stringResource(R.string.retrieval_title),
        onBack = actions.onBack
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {

            Text(
                text = stringResource(R.string.retrieval_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === Memory Section ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.retrieval_memory_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AdaptiveSlider(
                    label = stringResource(R.string.retrieval_memory_limit),
                    value = config.memoryLimit.toFloat(),
                    valueRange = 1f..50f,
                    displayValue = "${config.memoryLimit}",
                    enabled = !config.enableRerank,
                    rerankBadge = config.enableRerank,
                    onValueChange = { v -> actions.onConfigChanged { copy(memoryLimit = v.toInt()) } },
                    sliderModifier = Modifier.testTag("advanced_retrieval_memory_limit_slider"),
                )
                AdaptiveSlider(
                    label = stringResource(R.string.retrieval_similarity_threshold),
                    value = config.memoryThreshold,
                    valueRange = 0f..1f,
                    displayValue = "%.2f".format(config.memoryThreshold),
                    enabled = !config.enableRerank,
                    rerankBadge = config.enableRerank,
                    onValueChange = { v -> actions.onConfigChanged { copy(memoryThreshold = v) } }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === Doc Section ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.retrieval_doc_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AdaptiveSlider(
                    label = stringResource(R.string.retrieval_doc_limit),
                    value = config.docLimit.toFloat(),
                    valueRange = 1f..50f,
                    displayValue = "${config.docLimit}",
                    enabled = !config.enableRerank,
                    rerankBadge = config.enableRerank,
                    onValueChange = { v -> actions.onConfigChanged { copy(docLimit = v.toInt()) } }
                )
                AdaptiveSlider(
                    label = stringResource(R.string.retrieval_similarity_threshold),
                    value = config.docThreshold,
                    valueRange = 0f..1f,
                    displayValue = "%.2f".format(config.docThreshold),
                    enabled = !config.enableRerank,
                    rerankBadge = config.enableRerank,
                    onValueChange = { v -> actions.onConfigChanged { copy(docThreshold = v) } }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === Hybrid Section ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.retrieval_hybrid_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.retrieval_hybrid_enable),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.retrieval_hybrid_desc),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = config.enableHybridSearch,
                            onCheckedChange = null,
                            modifier = Modifier.testTag("advanced_retrieval_hybrid_switch"),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("advanced_retrieval_hybrid_toggle")
                        .toggleable(
                            value = config.enableHybridSearch,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                actions.onConfigChanged { copy(enableHybridSearch = enabled) }
                            },
                        )
                )

                if (config.enableHybridSearch) {
                    AdaptiveSlider(
                        label = stringResource(R.string.retrieval_vector_weight),
                        value = config.hybridAlpha,
                        valueRange = 0f..1f,
                        displayValue = "${(config.hybridAlpha * 100).toInt()}%",
                        enabled = true,
                        rerankBadge = false,
                        onValueChange = { v -> actions.onConfigChanged { copy(hybridAlpha = v) } }
                    )
                    AdaptiveSlider(
                        label = stringResource(R.string.retrieval_bm25_boost),
                        value = config.hybridBM25Boost,
                        valueRange = 0.5f..2f,
                        displayValue = "%.1fx".format(config.hybridBM25Boost),
                        enabled = true,
                        rerankBadge = false,
                        onValueChange = { v -> actions.onConfigChanged { copy(hybridBM25Boost = v) } }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === Rerank Section ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.retrieval_rerank_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!isRerankAvailable) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                stringResource(R.string.retrieval_rerank_model_unconfigured),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                if (!isRerankAvailable) {
                    Text(
                        text = stringResource(R.string.retrieval_rerank_model_unavailable_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                AdaptiveSlider(
                    label = stringResource(R.string.retrieval_rerank_top_n),
                    value = config.rerankTopK.toFloat(),
                    valueRange = 5f..100f,
                    displayValue = "${config.rerankTopK}",
                    enabled = isRerankAvailable,
                    rerankBadge = false,
                    onValueChange = { v -> actions.onConfigChanged { copy(rerankTopK = v.toInt()) } }
                )
                AdaptiveSlider(
                    label = stringResource(R.string.retrieval_rerank_final),
                    value = config.rerankFinalK.toFloat(),
                    valueRange = 1f..20f,
                    displayValue = "${config.rerankFinalK}",
                    enabled = isRerankAvailable,
                    rerankBadge = false,
                    onValueChange = { v -> actions.onConfigChanged { copy(rerankFinalK = v.toInt()) } }
                )
                AdaptiveSlider(
                    label = stringResource(R.string.rag_config_rerank_max_per_call),
                    value = config.rerankMaxPerCall.toFloat(),
                    valueRange = 8f..200f,
                    displayValue = "${config.rerankMaxPerCall}",
                    enabled = isRerankAvailable,
                    rerankBadge = false,
                    onValueChange = { v -> actions.onConfigChanged { copy(rerankMaxPerCall = v.toInt()) } }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === Rewrite Section ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.retrieval_rewrite_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.retrieval_rewrite_enable),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.retrieval_rewrite_desc),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = config.enableQueryRewrite,
                            onCheckedChange = null,
                            modifier = Modifier.testTag("advanced_retrieval_rewrite_switch"),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("advanced_retrieval_rewrite_toggle")
                        .toggleable(
                            value = config.enableQueryRewrite,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                actions.onConfigChanged { copy(enableQueryRewrite = enabled) }
                            },
                        )
                )

                if (config.enableQueryRewrite) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val strategies = listOf("hyde" to "HyDE", "multi-query" to "Multi-Query", "expansion" to "Expansion")
                        strategies.forEachIndexed { index, (value, label) ->
                            val isSelected = config.queryRewriteStrategy == value
                            SegmentedButton(
                                selected = isSelected,
                                onClick = {
                                    actions.onConfigChanged { copy(queryRewriteStrategy = value) }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = strategies.size),
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun AdaptiveSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    enabled: Boolean,
    rerankBadge: Boolean,
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
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (rerankBadge) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            stringResource(R.string.retrieval_rerank_badge),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Slider(
            modifier = sliderModifier,
            value = value,
            onValueChange = if (enabled) onValueChange else { {} },
            valueRange = valueRange,
            enabled = enabled
        )
    }
}
