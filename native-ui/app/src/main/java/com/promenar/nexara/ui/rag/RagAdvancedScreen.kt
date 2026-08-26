package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.ModelPicker
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.bettboxListGroup
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.UnifiedPromptEditor
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.common.toModelSelectionUiModel
import com.promenar.nexara.data.model.catalog.ModelMetadataResolver

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
                .padding(contentPadding)
                .bettboxListGroup()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.rag_advanced_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 知识图谱提取配置 ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsSectionHeader(stringResource(R.string.rag_advanced_extract_config))

                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.rag_advanced_extract_model), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        val selectedModelName = allModels.find { it.id == config.kgExtractionModel }?.name
                        Text(
                            selectedModelName ?: stringResource(R.string.rag_advanced_select_model_placeholder),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    trailingContent = {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rag_advanced_model_row")
                        .clickable { showModelPicker = true }
                )

                ConfigSlider(
                    label = stringResource(R.string.rag_advanced_kg_timeout),
                    value = config.kgExtractionTimeoutSeconds.toFloat(),
                    valueRange = 5f..120f,
                    onValueChange = { v -> actions.onConfigChanged { copy(kgExtractionTimeoutSeconds = v.toInt()) } },
                    sliderModifier = Modifier.testTag("rag_advanced_timeout_slider"),
                )
                Text(
                    text = stringResource(R.string.rag_advanced_kg_timeout_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 提示词模板 ===
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsSectionHeader(stringResource(R.string.rag_advanced_prompt_section))

                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPromptEditor = true },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.rag_advanced_active_prompt),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    supportingContent = {
                        Text(
                            text = config.kgExtractionPrompt ?: stringResource(R.string.rag_advanced_default_prompt),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingContent = {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { actions.onConfigChanged { copy(kgExtractionPrompt = null) } },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.rag_advanced_reset_default))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // === 查看知识图谱 ===
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(stringResource(R.string.rag_advanced_view_graph), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rag_advanced_graph_row")
                    .clickable { actions.onNavigateToGraph() }
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    val resolver = remember { ModelMetadataResolver() }
    val modelItems = remember(allModels) {
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

@Composable
private fun ConfigSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    sliderModifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${value.toInt()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            modifier = sliderModifier,
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
