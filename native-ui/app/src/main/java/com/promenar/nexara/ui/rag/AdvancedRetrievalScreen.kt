package com.promenar.nexara.ui.rag

import androidx.compose.foundation.layout.heightIn

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraNumericSetting
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraStepperSetting
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.NumericSettingKind
import com.promenar.nexara.ui.common.NumericSettingSpec
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.common.NexaraPopoverSpec
import com.promenar.nexara.ui.common.nexaraPopoverAnchor
import com.promenar.nexara.ui.common.rememberNexaraPopoverAnchor

@Composable
fun AdvancedRetrievalScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val presetRerankModel by ProviderManager.getInstance().rerankModelId.collectAsState()
    AdvancedRetrievalScreenContent(
        state = AdvancedRetrievalScreenState(
            config = config,
            isRerankAvailable = presetRerankModel.isNotBlank(),
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

@Composable
internal fun AdvancedRetrievalScreenContent(
    state: AdvancedRetrievalScreenState,
    actions: AdvancedRetrievalScreenActions = AdvancedRetrievalScreenActions(),
) {
    val config = state.config
    val rerankHint = if (config.enableRerank) stringResource(R.string.settings_retrieval_rerank_managed) else null

    NexaraSettingsPageLayout(
        title = stringResource(R.string.retrieval_title),
        onBack = actions.onBack,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            NexaraSettingsSection(
                title = stringResource(R.string.retrieval_memory_section),
            ) {
                NexaraNumericSetting(
                    title = stringResource(R.string.retrieval_memory_limit),
                    value = config.memoryLimit,
                    spec = RetrievalSettingSpecs.globalMemoryLimit,
                    onValueChange = { value -> actions.onConfigChanged { copy(memoryLimit = value.toInt()) } },
                    enabled = !config.enableRerank,
                    modifier = Modifier.testTag("advanced_retrieval_memory_limit_slider"),
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.retrieval_similarity_threshold),
                    value = config.memoryThreshold,
                    spec = RetrievalSettingSpecs.globalMemoryThreshold,
                    onValueChange = { value -> actions.onConfigChanged { copy(memoryThreshold = value.toFloat()) } },
                    enabled = !config.enableRerank,
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.retrieval_doc_section),
                footer = rerankHint,
            ) {
                NexaraNumericSetting(
                    title = stringResource(R.string.retrieval_doc_limit),
                    value = config.docLimit,
                    spec = RetrievalSettingSpecs.globalDocLimit,
                    onValueChange = { value -> actions.onConfigChanged { copy(docLimit = value.toInt()) } },
                    enabled = !config.enableRerank,
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.retrieval_similarity_threshold),
                    value = config.docThreshold,
                    spec = RetrievalSettingSpecs.globalDocThreshold,
                    onValueChange = { value -> actions.onConfigChanged { copy(docThreshold = value.toFloat()) } },
                    enabled = !config.enableRerank,
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.retrieval_hybrid_section),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.retrieval_hybrid_enable),
                    description = stringResource(R.string.settings_retrieval_hybrid_short),
                    checked = config.enableHybridSearch,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(enableHybridSearch = value) } },
                    modifier = Modifier.testTag("advanced_retrieval_hybrid_toggle"),
                    switchModifier = Modifier.testTag("advanced_retrieval_hybrid_switch"),
                )
                if (config.enableHybridSearch) {
                    NexaraNumericSetting(
                        title = stringResource(R.string.retrieval_vector_weight),
                        value = config.hybridAlpha.toString().toDouble() * 100.0,
                        spec = RetrievalSettingSpecs.globalVectorWeight,
                        onValueChange = { value -> actions.onConfigChanged { copy(hybridAlpha = (value / 100).toFloat()) } },
                    )
                    NexaraNumericSetting(
                        title = stringResource(R.string.retrieval_bm25_boost),
                        value = config.hybridBM25Boost,
                        spec = RetrievalSettingSpecs.globalBm25Boost,
                        onValueChange = { value -> actions.onConfigChanged { copy(hybridBM25Boost = value.toFloat()) } },
                    )
                }
            }

            NexaraSettingsSection(
                title = stringResource(R.string.retrieval_rerank_section),
                footer = if (!state.isRerankAvailable) stringResource(R.string.retrieval_rerank_model_unavailable_message) else null,
            ) {
                NexaraNumericSetting(
                    title = stringResource(R.string.retrieval_rerank_top_n),
                    value = config.rerankTopK,
                    spec = RetrievalSettingSpecs.globalRerankTopK,
                    onValueChange = { value -> actions.onConfigChanged { copy(rerankTopK = value.toInt()) } },
                    enabled = state.isRerankAvailable,
                    subtitle = if (!state.isRerankAvailable) stringResource(R.string.settings_retrieval_model_unavailable_short) else null,
                )
                RetrievalStepperSetting(
                    title = stringResource(R.string.retrieval_rerank_final),
                    value = config.rerankFinalK,
                    range = 1..20,
                    onValueChange = { value -> actions.onConfigChanged { copy(rerankFinalK = value) } },
                    enabled = state.isRerankAvailable,
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.settings_retrieval_rerank_batch_title),
                    value = config.rerankMaxPerCall,
                    spec = RetrievalSettingSpecs.rerankMaxPerCall,
                    onValueChange = { value -> actions.onConfigChanged { copy(rerankMaxPerCall = value.toInt()) } },
                    enabled = state.isRerankAvailable,
                    description = stringResource(R.string.rag_config_rerank_max_per_call_desc),
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.retrieval_rewrite_section),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.retrieval_rewrite_enable),
                    description = stringResource(R.string.settings_retrieval_rewrite_short),
                    checked = config.enableQueryRewrite,
                    onCheckedChange = { value -> actions.onConfigChanged { copy(enableQueryRewrite = value) } },
                    modifier = Modifier.testTag("advanced_retrieval_rewrite_toggle"),
                    switchModifier = Modifier.testTag("advanced_retrieval_rewrite_switch"),
                )
                if (config.enableQueryRewrite) {
                    RetrievalStrategySetting(
                        strategy = config.queryRewriteStrategy,
                        onValueChange = { value -> actions.onConfigChanged { copy(queryRewriteStrategy = value) } },
                    )
                }
            }

            KgRetrievalSettings(config, actions.onConfigChanged)
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
internal fun RetrievalStepperSetting(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    NexaraStepperSetting(
        title = title,
        value = value.toString(),
        onDecrease = { onValueChange((value - 1).coerceIn(range)) },
        onIncrease = { onValueChange((value + 1).coerceIn(range)) },
        modifier = modifier,
        canDecrease = value > range.first,
        canIncrease = value < range.last,
        enabled = enabled,
    )
}

@Composable
internal fun RetrievalStrategySetting(
    strategy: String,
    onValueChange: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val anchor = rememberNexaraPopoverAnchor()
    val options = listOf(
        "hyde" to stringResource(R.string.agent_retrieval_strategy_hyde),
        "multi-query" to stringResource(R.string.agent_retrieval_strategy_multi),
        "expansion" to stringResource(R.string.agent_retrieval_strategy_expansion),
    )
    NexaraValueRow(
        title = stringResource(R.string.agent_retrieval_strategy_label),
        value = options.firstOrNull { it.first == strategy }?.second ?: strategy,
        onClick = { if (!anchor.consumeRecentDismiss()) showPicker = true },
        modifier = Modifier.nexaraPopoverAnchor(anchor),
    )
    NexaraBottomSheet(
        show = showPicker,
        scrollContent = true,
        onDismiss = { showPicker = false },
        title = stringResource(R.string.agent_retrieval_strategy_label),
        popover = NexaraPopoverSpec(anchor),
    ) {
        options.forEach { (id, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = strategy == id,
                        role = Role.RadioButton,
                        onClick = {
                            onValueChange(id)
                            showPicker = false
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (strategy == id) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 各页保留自身的数值范围和档位；连续参数的 0 步长表示原始无档位滑块。 */
internal object RetrievalSettingSpecs {
    val globalChunkSize = retrievalIntegerSpec(100, 2000, 800, listOf(100, 400, 800, 1200, 1600, 2000), step = 100)
    val globalChunkOverlap = retrievalIntegerSpec(0, 500, 100, listOf(0, 50, 100, 200, 300, 500), step = 50)
    val extractionTimeout = retrievalIntegerSpec(5, 120, 120, listOf(5, 30, 60, 90, 120))
    val globalMemoryLimit = retrievalIntegerSpec(1, 50, 5, listOf(1, 5, 10, 20, 30, 50))
    val globalDocLimit = retrievalIntegerSpec(1, 50, 8, listOf(1, 5, 8, 10, 20, 50))
    val globalRerankTopK = retrievalIntegerSpec(5, 100, 30, listOf(5, 10, 30, 50, 100))
    val rerankMaxPerCall = retrievalIntegerSpec(8, 200, 100, listOf(8, 32, 64, 100, 200))
    val globalMemoryThreshold = continuousSpec(0.0, 1.0, 0.7, listOf(0.0, 0.5, 0.7, 0.9, 1.0))
    val globalDocThreshold = continuousSpec(0.0, 1.0, 0.45, listOf(0.0, 0.3, 0.45, 0.7, 1.0))
    val globalVectorWeight = continuousSpec(0.0, 100.0, 60.0, listOf(0.0, 25.0, 50.0, 60.0, 100.0), unit = "%", decimalPlaces = 0)
    val globalBm25Boost = continuousSpec(0.5, 2.0, 1.0, listOf(0.5, 1.0, 1.5, 2.0), unit = "×", decimalPlaces = 1)
    val agentChunkSize = retrievalIntegerSpec(100, 2000, 800, listOf(100, 400, 800, 1200, 1600, 2000), step = 50)
    val agentChunkOverlap = retrievalIntegerSpec(0, 500, 100, listOf(0, 50, 100, 200, 300, 500), step = 10)
    val agentMemoryChunk = retrievalIntegerSpec(500, 3000, 1000, listOf(500, 1000, 1500, 2000, 3000), step = 100)
    val agentMemoryThreshold = continuousSpec(50.0, 95.0, 70.0, listOf(50.0, 60.0, 70.0, 80.0, 95.0), step = 5.0, unit = "%", decimalPlaces = 0)
    val agentDocThreshold = continuousSpec(30.0, 80.0, 45.0, listOf(30.0, 45.0, 60.0, 70.0, 80.0), step = 5.0, unit = "%", decimalPlaces = 0)
    val agentRerankTopK = retrievalIntegerSpec(10, 100, 30, listOf(10, 20, 30, 50, 100), step = 5)
    val agentVectorWeight = continuousSpec(0.0, 100.0, 60.0, listOf(0.0, 25.0, 50.0, 60.0, 100.0), step = 5.0, unit = "%", decimalPlaces = 0)
    val agentBm25Boost = continuousSpec(0.5, 2.0, 1.0, listOf(0.5, 1.0, 1.5, 2.0), step = 0.1, unit = "×", decimalPlaces = 1)
}

internal fun retrievalIntegerSpec(
    min: Int,
    max: Int,
    defaultValue: Int,
    presets: List<Int>,
    step: Int = 1,
): NumericSettingSpec = NumericSettingSpec(
    min = min.toDouble(),
    max = max.toDouble(),
    step = step.toDouble(),
    defaultValue = defaultValue.toDouble(),
    presets = presets.map { NumericSettingSpec.Preset(it.toDouble()) },
)

private fun continuousSpec(
    min: Double,
    max: Double,
    defaultValue: Double,
    presets: List<Double>,
    step: Double = 0.0,
    unit: String = "",
    decimalPlaces: Int = 2,
): NumericSettingSpec = NumericSettingSpec(
    min = min,
    max = max,
    step = step,
    defaultValue = defaultValue,
    presets = presets.map { NumericSettingSpec.Preset(it) },
    kind = NumericSettingKind.Continuous,
    unit = unit,
    decimalPlaces = decimalPlaces,
)
