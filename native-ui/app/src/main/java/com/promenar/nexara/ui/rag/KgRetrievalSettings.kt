package com.promenar.nexara.ui.rag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.NexaraNumericSetting
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SettingsToggle

@Composable
internal fun KgRetrievalSettings(
    config: RagConfiguration,
    onChanged: (RagConfiguration.() -> RagConfiguration) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        NexaraSettingsSection(
            title = stringResource(R.string.kg_retrieval_section),
            footer = stringResource(R.string.kg_jit_fallback_desc),
        ) {
            SettingsToggle(
                title = stringResource(R.string.kg_jit_fallback_title),
                description = stringResource(R.string.settings_retrieval_jit_short),
                checked = config.kgJitFallbackEnabled,
                onCheckedChange = { value -> onChanged { copy(kgJitFallbackEnabled = value) } },
                modifier = Modifier.testTag("kg_jit_fallback_toggle"),
                switchModifier = Modifier.testTag("kg_jit_fallback_switch"),
            )
            RetrievalStepperSetting(
                title = stringResource(R.string.kg_retrieval_hops),
                value = config.kgRetrievalHops,
                range = 1..2,
                onValueChange = { value -> onChanged { copy(kgRetrievalHops = value) } },
            )
            KgIntegerSetting(R.string.kg_seed_limit, config.kgMaxSeeds, 1, 32, 12, listOf(1, 8, 12, 16, 32)) { value ->
                onChanged { copy(kgMaxSeeds = value) }
            }
            KgIntegerSetting(R.string.kg_node_limit, config.kgMaxNodes, 2, 256, 64, listOf(2, 32, 64, 128, 256)) { value ->
                onChanged { copy(kgMaxNodes = value) }
            }
            KgIntegerSetting(R.string.kg_path_limit, config.kgMaxEdges, 1, 32, 24, listOf(1, 8, 16, 24, 32)) { value ->
                onChanged { copy(kgMaxEdges = value) }
            }
            KgIntegerSetting(R.string.settings_retrieval_kg_context_title, config.kgContextMaxTokens, 128, 16384, 2048, listOf(128, 1024, 2048, 8192, 16384)) { value ->
                onChanged { copy(kgContextMaxTokens = value) }
            }
            KgIntegerSetting(R.string.kg_jit_seed_threshold, config.kgJitMinSeeds, 1, 32, 2, listOf(1, 2, 8, 16, 32)) { value ->
                onChanged { copy(kgJitMinSeeds = value) }
            }
        }

        NexaraSettingsSection(
            title = stringResource(R.string.kg_extraction_budget_section),
            footer = stringResource(R.string.kg_budget_partial_hint),
        ) {
            KgIntegerSetting(R.string.settings_retrieval_kg_calls_title, config.kgMaxCalls, 1, 1024, 32, listOf(1, 16, 32, 128, 1024)) { value ->
                onChanged { copy(kgMaxCalls = value) }
            }
            KgIntegerSetting(R.string.settings_retrieval_kg_output_title, config.kgMaxOutputTokens, 256, 1048576, 32768, listOf(256, 8192, 32768, 131072, 1048576)) { value ->
                onChanged { copy(kgMaxOutputTokens = value) }
            }
            KgIntegerSetting(R.string.settings_retrieval_kg_request_output_title, config.kgRequestMaxOutputTokens, 128, 16384, 2048, listOf(128, 1024, 2048, 8192, 16384)) { value ->
                onChanged { copy(kgRequestMaxOutputTokens = value) }
            }
            KgIntegerSetting(R.string.settings_retrieval_kg_duration_title, config.kgMaxDurationSeconds, 5, 3600, 180, listOf(5, 60, 180, 600, 3600)) { value ->
                onChanged { copy(kgMaxDurationSeconds = value) }
            }
            KgIntegerSetting(R.string.settings_retrieval_kg_confirm_title, config.kgLargeDocumentConfirmCalls, 1, 128, 16, listOf(1, 8, 16, 32, 128)) { value ->
                onChanged { copy(kgLargeDocumentConfirmCalls = value) }
            }
        }
    }
}

@Composable
private fun KgIntegerSetting(
    label: Int,
    value: Int,
    min: Int,
    max: Int,
    defaultValue: Int,
    presets: List<Int>,
    onChanged: (Int) -> Unit,
) {
    NexaraNumericSetting(
        title = stringResource(label),
        value = value,
        spec = retrievalIntegerSpec(min, max, defaultValue, presets),
        description = when (label) {
            R.string.settings_retrieval_kg_context_title -> stringResource(R.string.settings_retrieval_kg_context_footer)
            R.string.settings_retrieval_kg_calls_title, R.string.settings_retrieval_kg_output_title,
            R.string.settings_retrieval_kg_request_output_title, R.string.settings_retrieval_kg_duration_title,
            R.string.settings_retrieval_kg_confirm_title -> stringResource(R.string.settings_retrieval_kg_budget_footer)
            else -> null
        },
        onValueChange = { onChanged(it.toInt()) },
    )
}
