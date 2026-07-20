package com.promenar.nexara.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.catalog.ModelMetadataResolver
import com.promenar.nexara.ui.common.ModelPicker
import com.promenar.nexara.ui.common.ModelSelectionUiModel
import com.promenar.nexara.ui.common.toModelSelectionUiModel
import com.promenar.nexara.ui.theme.NexaraSpacing

enum class DefaultModelRole(
    val type: String,
    val filter: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    SUMMARY("summary", "chat", R.string.settings_model_summary, Icons.Rounded.Psychology),
    IMAGE("image", "image", R.string.settings_model_image, Icons.Rounded.Image),
    EMBEDDING("embedding", "embedding", R.string.settings_model_embedding, Icons.Rounded.Link),
    RERANK("rerank", "rerank", R.string.settings_model_rerank, Icons.Rounded.Route)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefaultModelsScreenContent(
    state: DefaultModelsScreenState,
    actions: DefaultModelsScreenActions
) {
    val notSet = stringResource(R.string.settings_not_set)
    val selectModelLabel = stringResource(R.string.common_model_picker_title)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_default_models),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = NexaraSpacing.ScreenHorizontal)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(DefaultModelRole.entries, key = { it.type }) { role ->
                    val subtitle = when (role) {
                        DefaultModelRole.SUMMARY -> state.summaryModelName
                        DefaultModelRole.IMAGE -> state.imageModelName
                        DefaultModelRole.EMBEDDING -> state.embeddingModelName
                        DefaultModelRole.RERANK -> state.rerankModelName
                    }.ifEmpty { notSet }

                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                            .testTag("default_model_role:${role.type}")
                            .clickable(
                                role = Role.Button,
                                onClickLabel = selectModelLabel,
                                onClick = { actions.onShowModelPicker(role) },
                            ),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(
                                imageVector = role.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(role.labelRes),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        supportingContent = {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
