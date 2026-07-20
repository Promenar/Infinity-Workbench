package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.ModelWorkload
import com.promenar.nexara.data.model.catalog.SupportState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    show: Boolean,
    onDismiss: () -> Unit,
    onSelect: (modelId: String, modelName: String) -> Unit,
    currentModelId: String = "",
    models: List<ModelSelectionUiModel> = emptyList(),
    title: String? = null,
    filterTag: String? = null
) {
    if (!show) return

    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        delay(150)
        debouncedQuery = searchQuery
    }

    val filteredModels = remember(debouncedQuery, models, filterTag) {
        val baseList = if (filterTag == null) models
        else models.filter { item -> item.matchesModelPickerFilter(filterTag) }

        if (debouncedQuery.isBlank()) baseList
        else baseList.filter {
            it.displayName.contains(debouncedQuery, ignoreCase = true) ||
                    it.providerName.contains(debouncedQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ModelPickerSheetContent(
            title = title ?: stringResource(R.string.common_model_picker_title),
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            models = filteredModels,
            currentModelId = currentModelId,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
        )
    }
}

@Composable
internal fun ModelPickerSheetContent(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    models: List<ModelSelectionUiModel>,
    currentModelId: String,
    onSelect: (modelId: String, modelName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))

        NexaraSearchBar(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = stringResource(R.string.common_model_picker_search),
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (models.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.DeveloperBoard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.common_model_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(models, key = { _, model -> model.selectionId }) { index, model ->
                    val isSelected = model.selectionId == currentModelId

                    ModelSelectionListItem(
                        model = model,
                        selected = isSelected,
                        onClick = { onSelect(model.selectionId, model.displayName) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (index < models.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

internal fun ModelSelectionUiModel.matchesModelPickerFilter(filterTag: String): Boolean = when (filterTag) {
    "chat" -> isChatSelectionCandidate()
    "multimodal" -> capabilityStates[ModelCapability.VISION_INPUT] == SupportState.SUPPORTED
    "reasoning" -> capabilityStates[ModelCapability.REASONING] == SupportState.SUPPORTED
    "image" -> workload == ModelWorkload.IMAGE_GENERATION
    "embedding" -> workload == ModelWorkload.EMBEDDING
    "rerank" -> workload == ModelWorkload.RERANK
    "audio" -> workload == ModelWorkload.AUDIO ||
        capabilityStates[ModelCapability.AUDIO_INPUT] == SupportState.SUPPORTED ||
        capabilityStates[ModelCapability.AUDIO_OUTPUT] == SupportState.SUPPORTED
    "video" -> workload == ModelWorkload.VIDEO ||
        capabilityStates[ModelCapability.VIDEO_INPUT] == SupportState.SUPPORTED
    "vision" -> capabilityStates[ModelCapability.VISION_INPUT] == SupportState.SUPPORTED
    else -> true
}
