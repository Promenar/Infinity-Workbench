package com.promenar.nexara.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.catalog.ModelMetadataResolver
import com.promenar.nexara.ui.common.ModelPicker
import com.promenar.nexara.ui.common.ModelSelectionUiModel
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.toModelSelectionUiModel

enum class DefaultModelRole(
    val type: String,
    val filter: String,
    val labelRes: Int,
) {
    SUMMARY("summary", "chat", R.string.settings_model_summary),
    IMAGE("image", "image", R.string.settings_model_image),
    EMBEDDING("embedding", "embedding", R.string.settings_model_embedding),
    RERANK("rerank", "rerank", R.string.settings_model_rerank)
}

@Composable
fun DefaultModelsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))

    val allModels by viewModel.providerModels.collectAsState()
    val summaryModelId by viewModel.summaryModelId.collectAsState()
    val imageModelId by viewModel.imageModelId.collectAsState()
    val embeddingModelId by viewModel.embeddingModelId.collectAsState()
    val rerankModelId by viewModel.rerankModelId.collectAsState()

    var activePickerRole by remember { mutableStateOf<DefaultModelRole?>(null) }

    val resolver = remember { ModelMetadataResolver() }
    val resolvedModels = remember(allModels, resolver) {
        allModels.map { model -> model.toModelSelectionUiModel(resolver) }
    }
    val modelItems = remember(allModels, resolvedModels) {
        resolvedModels.filter { resolved ->
            allModels.firstOrNull { it.id == resolved.selectionId }?.enabled == true
        }
    }

    val state = remember(resolvedModels, summaryModelId, imageModelId, embeddingModelId, rerankModelId) {
        DefaultModelsScreenState(
            summaryModelName = resolveDefaultModelName(resolvedModels, summaryModelId),
            imageModelName = resolveDefaultModelName(resolvedModels, imageModelId),
            embeddingModelName = resolveDefaultModelName(resolvedModels, embeddingModelId),
            rerankModelName = resolveDefaultModelName(resolvedModels, rerankModelId)
        )
    }

    val actions = remember(viewModel, onNavigateBack) {
        DefaultModelsScreenActions(
            onNavigateBack = onNavigateBack,
            onShowModelPicker = { role ->
                activePickerRole = role
            }
        )
    }

    DefaultModelsScreenContent(
        state = state,
        actions = actions
    )

    if (activePickerRole != null) {
        val role = activePickerRole!!
        val currentId = when (role) {
            DefaultModelRole.SUMMARY -> summaryModelId
            DefaultModelRole.IMAGE -> imageModelId
            DefaultModelRole.EMBEDDING -> embeddingModelId
            DefaultModelRole.RERANK -> rerankModelId
        }

        ModelPicker(
            show = true,
            title = stringResource(role.labelRes),
            filterTag = role.filter,
            currentModelId = currentId,
            models = modelItems,
            onDismiss = { activePickerRole = null },
            onSelect = { id, _ ->
                viewModel.setPresetModel(role.type, id)
                activePickerRole = null
            }
        )
    }
}

internal fun resolveDefaultModelName(models: List<ModelSelectionUiModel>, id: String): String {
    if (id.isEmpty()) return ""
    return models.firstOrNull { it.selectionId == id }?.displayName ?: id
}

internal data class DefaultModelsScreenState(
    val summaryModelName: String = "",
    val imageModelName: String = "",
    val embeddingModelName: String = "",
    val rerankModelName: String = ""
)

internal data class DefaultModelsScreenActions(
    val onNavigateBack: () -> Unit = {},
    val onShowModelPicker: (DefaultModelRole) -> Unit = {}
)

@Composable
internal fun DefaultModelsScreenContent(
    state: DefaultModelsScreenState,
    actions: DefaultModelsScreenActions
) {
    val notSet = stringResource(R.string.settings_not_set)
    val selectModelLabel = stringResource(R.string.common_model_picker_title)
    NexaraSettingsPageLayout(
        title = stringResource(R.string.settings_default_models),
        onBack = actions.onNavigateBack,
    ) { contentPadding ->
        LazyColumn(contentPadding = contentPadding) {
            item("models") {
                NexaraSettingsSection(title = stringResource(R.string.settings_section_ai_models)) {
                    DefaultModelRole.entries.forEach { role ->
                        val subtitle = when (role) {
                            DefaultModelRole.SUMMARY -> state.summaryModelName
                            DefaultModelRole.IMAGE -> state.imageModelName
                            DefaultModelRole.EMBEDDING -> state.embeddingModelName
                            DefaultModelRole.RERANK -> state.rerankModelName
                        }.ifEmpty { notSet }

                        NexaraSettingsItem(
                            icon = null,
                            title = stringResource(role.labelRes),
                            subtitle = subtitle,
                            onClick = { actions.onShowModelPicker(role) },
                            modifier = Modifier
                                .testTag("default_model_role:${role.type}"),
                            onClickLabel = selectModelLabel,
                        )
                    }
                }
            }
        }
    }
}
