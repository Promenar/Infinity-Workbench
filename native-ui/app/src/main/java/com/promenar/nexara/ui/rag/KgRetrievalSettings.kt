package com.promenar.nexara.ui.rag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraSpacing

@Composable
internal fun KgRetrievalSettings(config: RagConfiguration, onChanged: (RagConfiguration.() -> RagConfiguration) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        NexaraSettingsSection(title = stringResource(R.string.kg_retrieval_section)) {
            SettingsToggle(
                title = stringResource(R.string.kg_jit_fallback_title),
                description = stringResource(R.string.kg_jit_fallback_desc),
                checked = config.kgJitFallbackEnabled,
                onCheckedChange = { value -> onChanged { copy(kgJitFallbackEnabled = value) } },
                modifier = Modifier.testTag("kg_jit_fallback_toggle"),
                switchModifier = Modifier.testTag("kg_jit_fallback_switch"),
            )
            Column(
                modifier = Modifier.padding(NexaraSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
            ) {
                KgIntegerSetting(R.string.kg_retrieval_hops, config.kgRetrievalHops, 1f..2f) { value -> onChanged { copy(kgRetrievalHops = value) } }
                KgIntegerSetting(R.string.kg_seed_limit, config.kgMaxSeeds, 1f..32f) { value -> onChanged { copy(kgMaxSeeds = value) } }
                KgIntegerSetting(R.string.kg_node_limit, config.kgMaxNodes, 2f..256f) { value -> onChanged { copy(kgMaxNodes = value) } }
                KgIntegerSetting(R.string.kg_path_limit, config.kgMaxEdges, 1f..32f) { value -> onChanged { copy(kgMaxEdges = value) } }
                KgIntegerSetting(R.string.kg_context_budget, config.kgContextMaxTokens, 128f..16384f) { value -> onChanged { copy(kgContextMaxTokens = value) } }
                KgIntegerSetting(R.string.kg_jit_seed_threshold, config.kgJitMinSeeds, 1f..32f) { value -> onChanged { copy(kgJitMinSeeds = value) } }
            }
        }
        NexaraSettingsSection(title = stringResource(R.string.kg_extraction_budget_section)) {
            Column(
                modifier = Modifier.padding(NexaraSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
            ) {
                Text(
                    stringResource(R.string.kg_budget_partial_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KgIntegerSetting(R.string.kg_budget_calls, config.kgMaxCalls, 1f..1024f) { value -> onChanged { copy(kgMaxCalls = value) } }
                KgIntegerSetting(R.string.kg_budget_output, config.kgMaxOutputTokens, 256f..1048576f) { value -> onChanged { copy(kgMaxOutputTokens = value) } }
                KgIntegerSetting(R.string.kg_budget_request_output, config.kgRequestMaxOutputTokens, 128f..16384f) { value -> onChanged { copy(kgRequestMaxOutputTokens = value) } }
                KgIntegerSetting(R.string.kg_budget_duration, config.kgMaxDurationSeconds, 5f..3600f) { value -> onChanged { copy(kgMaxDurationSeconds = value) } }
                KgIntegerSetting(R.string.kg_budget_confirm_threshold, config.kgLargeDocumentConfirmCalls, 1f..128f) { value -> onChanged { copy(kgLargeDocumentConfirmCalls = value) } }
            }
        }
    }
}

@Composable
private fun KgIntegerSetting(label: Int, value: Int, range: ClosedFloatingPointRange<Float>, onChanged: (Int) -> Unit) {
    AdaptiveSlider(label = stringResource(label), value = value.toFloat(), valueRange = range,
        displayValue = value.toString(), enabled = true, rerankBadge = false, onValueChange = { onChanged(it.toInt()) })
}
