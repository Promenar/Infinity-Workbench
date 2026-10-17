package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.catalog.ModelCatalogRuntime
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.ModelPicker
import com.promenar.nexara.ui.common.NexaraNumericSetting
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.UnifiedPromptEditor
import com.promenar.nexara.ui.common.toModelSelectionUiModel
import com.promenar.nexara.ui.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagAdvancedScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGraph: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(context.applicationContext as Application))
    val config by viewModel.config.collectAsState()
    val settingsViewModel: SettingsViewModel = viewModel(
        modelClass = SettingsViewModel::class.java,
        factory = SettingsViewModel.factory(context.applicationContext as Application)
    )
    val allModels by settingsViewModel.providerModels.collectAsState()

    RagAdvancedScreenContent(
        state = RagAdvancedScreenState(config = config, allModels = allModels),
        actions = RagAdvancedScreenActions(
            onBack = onNavigateBack,
            onNavigateToGraph = onNavigateToGraph,
            onConfigChanged = { transform -> viewModel.updateConfig(transform) },
        ),
    )
}

internal data class RagAdvancedScreenState(
    val config: RagConfiguration,
    val allModels: List<ModelInfo>,
)

internal data class RagAdvancedScreenActions(
    val onBack: () -> Unit = {},
    val onNavigateToGraph: () -> Unit = {},
    val onConfigChanged: (RagConfiguration.() -> RagConfiguration) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RagAdvancedScreenContent(
    state: RagAdvancedScreenState,
    actions: RagAdvancedScreenActions = RagAdvancedScreenActions(),
) {
    val config = state.config
    val allModels = state.allModels
    var showPromptEditor by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.rag_advanced_title),
        onBack = actions.onBack
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            NexaraSettingsSection(
                title = stringResource(R.string.rag_advanced_extract_config),
            ) {
                NexaraValueRow(
                    title = stringResource(R.string.rag_advanced_extract_model),
                    value = allModels.find { it.id == config.kgExtractionModel }?.name
                        ?: stringResource(R.string.rag_advanced_select_model_placeholder),
                    onClick = { showModelPicker = true },
                    modifier = Modifier.testTag("rag_advanced_model_row"),
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.rag_advanced_kg_timeout),
                    value = config.kgExtractionTimeoutSeconds,
                    spec = RetrievalSettingSpecs.extractionTimeout,
                    onValueChange = { value -> actions.onConfigChanged { copy(kgExtractionTimeoutSeconds = value.toInt()) } },
                    modifier = Modifier.testTag("rag_advanced_timeout_slider"),
                    description = stringResource(R.string.rag_advanced_kg_timeout_desc),
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.rag_advanced_prompt_section),
                footer = stringResource(R.string.settings_retrieval_default_prompt_footer, stringResource(R.string.rag_advanced_default_prompt)),
            ) {
                NexaraSettingsItem(
                    title = stringResource(R.string.rag_advanced_active_prompt),
                    subtitle = stringResource(
                        if (config.kgExtractionPrompt.isNullOrBlank()) R.string.settings_retrieval_template_default
                        else R.string.settings_retrieval_template_custom,
                    ),
                    onClick = { showPromptEditor = true },
                    showChevron = true,
                )
                NexaraSettingsItem(
                    title = stringResource(R.string.rag_advanced_reset_default),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { actions.onConfigChanged { copy(kgExtractionPrompt = null) } },
                )
            }

            NexaraSettingsSection(
                title = stringResource(R.string.settings_retrieval_graph_section),
                footer = stringResource(R.string.rag_advanced_desc),
            ) {
                NexaraSettingsItem(
                    icon = Icons.Rounded.AccountTree,
                    title = stringResource(R.string.rag_advanced_view_graph),
                    onClick = actions.onNavigateToGraph,
                    showChevron = true,
                    modifier = Modifier.testTag("rag_advanced_graph_row"),
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    val resolver = ModelCatalogRuntime.resolver
    val modelItems = remember(allModels, resolver) {
        allModels.map { it.toModelSelectionUiModel(resolver) }
    }

    ModelPicker(
        show = showModelPicker,
        onDismiss = { showModelPicker = false },
        filterTag = "chat",
        models = modelItems,
        currentModelId = config.kgExtractionModel ?: "",
        onSelect = { id, _ ->
            actions.onConfigChanged { copy(kgExtractionModel = id) }
            showModelPicker = false
        }
    )

    UnifiedPromptEditor(
        show = showPromptEditor,
        onDismiss = { showPromptEditor = false },
        initialText = config.kgExtractionPrompt ?: "",
        title = stringResource(R.string.rag_advanced_edit_prompt),
        onSave = { text ->
            runCatching {
                actions.onConfigChanged { copy(kgExtractionPrompt = text.ifBlank { null }) }
            }
        },
        placeholder = stringResource(R.string.rag_advanced_extract_prompt_placeholder)
    )
}
