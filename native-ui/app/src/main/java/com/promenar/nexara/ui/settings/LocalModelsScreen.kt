package com.promenar.nexara.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.local.inference.SlotState
import com.promenar.nexara.data.local.inference.SlotType
import com.promenar.nexara.data.local.inference.StoredModel
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LocalModelsViewModel = viewModel(
        factory = LocalModelsViewModel.factory(LocalContext.current.applicationContext as android.app.Application)
    )
) {
    val availableModels by viewModel.availableModels.collectAsState()
    val engineEnabled by viewModel.isEngineEnabled.collectAsState()
    val mainSlot by viewModel.mainSlot.collectAsState()
    val embeddingSlot by viewModel.embeddingSlot.collectAsState()
    val rerankSlot by viewModel.rerankSlot.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val configuration = LocalConfiguration.current
    val stackActiveSlots = configuration.screenWidthDp < 600 || LocalDensity.current.fontScale >= 1.5f

    val ggufPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.local_models_title),
        onBack = onNavigateBack,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = paddingValues,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.local_models_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                SettingsToggle(
                    title = stringResource(R.string.local_models_enable_engine),
                    description = stringResource(R.string.local_models_engine_subtitle),
                    checked = engineEnabled,
                    onCheckedChange = viewModel::setEngineEnabled,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item {
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isImporting) {
                            ggufPickerLauncher.launch(arrayOf("*/*"))
                        },
                    headlineContent = {
                        Text(
                            text = if (isImporting) stringResource(R.string.local_models_importing) else stringResource(R.string.local_models_import_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    supportingContent = {
                        Text(
                            text = if (isImporting) stringResource(R.string.local_models_please_wait) else stringResource(R.string.local_models_import_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        if (isImporting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.UploadFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_active_slots)) }

            item {
                if (stackActiveSlots) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SlotCard(
                            label = stringResource(R.string.local_models_slot_main),
                            slotState = mainSlot,
                            color = MaterialTheme.colorScheme.primary,
                            enabled = engineEnabled,
                            onLoadClick = { path -> viewModel.loadModel(SlotType.MAIN, path) },
                            onUnloadClick = { viewModel.unloadModel(SlotType.MAIN) },
                            models = availableModels,
                            formatFileSize = { viewModel.formatFileSize(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SlotCard(
                            label = stringResource(R.string.local_models_slot_embeddings),
                            slotState = embeddingSlot,
                            color = MaterialTheme.colorScheme.tertiary,
                            enabled = engineEnabled,
                            onLoadClick = { path -> viewModel.loadModel(SlotType.EMBEDDING, path) },
                            onUnloadClick = { viewModel.unloadModel(SlotType.EMBEDDING) },
                            models = availableModels,
                            formatFileSize = { viewModel.formatFileSize(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SlotCard(
                            label = stringResource(R.string.local_models_slot_reranker),
                            slotState = rerankSlot,
                            color = MaterialTheme.colorScheme.outline,
                            enabled = engineEnabled,
                            onLoadClick = { path -> viewModel.loadModel(SlotType.RERANK, path) },
                            onUnloadClick = { viewModel.unloadModel(SlotType.RERANK) },
                            models = availableModels,
                            formatFileSize = { viewModel.formatFileSize(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    SlotCard(
                        label = stringResource(R.string.local_models_slot_main),
                        slotState = mainSlot,
                        color = MaterialTheme.colorScheme.primary,
                        enabled = engineEnabled,
                        onLoadClick = { path -> viewModel.loadModel(SlotType.MAIN, path) },
                        onUnloadClick = { viewModel.unloadModel(SlotType.MAIN) },
                        models = availableModels,
                        formatFileSize = { viewModel.formatFileSize(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    SlotCard(
                        label = stringResource(R.string.local_models_slot_embeddings),
                        slotState = embeddingSlot,
                        color = MaterialTheme.colorScheme.tertiary,
                        enabled = engineEnabled,
                        onLoadClick = { path -> viewModel.loadModel(SlotType.EMBEDDING, path) },
                        onUnloadClick = { viewModel.unloadModel(SlotType.EMBEDDING) },
                        models = availableModels,
                        formatFileSize = { viewModel.formatFileSize(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    SlotCard(
                        label = stringResource(R.string.local_models_slot_reranker),
                        slotState = rerankSlot,
                        color = MaterialTheme.colorScheme.outline,
                        enabled = engineEnabled,
                        onLoadClick = { path -> viewModel.loadModel(SlotType.RERANK, path) },
                        onUnloadClick = { viewModel.unloadModel(SlotType.RERANK) },
                        models = availableModels,
                        formatFileSize = { viewModel.formatFileSize(it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_imported)) }

            if (availableModels.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.local_models_none_imported),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            } else {
                items(availableModels, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        isLoadedInSlot = viewModel.isModelLoadedInSlot(model.filePath),
                        loadedSlot = viewModel.findSlotForModel(model.filePath),
                        engineEnabled = engineEnabled,
                        formatFileSize = { viewModel.formatFileSize(it) },
                        onLoad = { slot ->
                            if (viewModel.isModelLoadedInSlot(model.filePath)) {
                                val s = viewModel.findSlotForModel(model.filePath)
                                if (s != null) viewModel.unloadModel(s)
                            } else {
                                viewModel.loadModel(slot, model.filePath)
                            }
                        },
                        onDelete = { viewModel.deleteModel(model.filePath) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_engine_status)) }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "llama.cpp · ${if (viewModel.gpuAvailable) "Vulkan GPU" else "CPU"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    EngineSlotStatus(
                        label = stringResource(R.string.local_models_slot_main),
                        modelName = mainSlot.modelName.ifEmpty { null },
                        active = mainSlot.isLoaded,
                        isLoading = mainSlot.isLoading,
                        badge = mainSlot.backendType.displayName
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EngineSlotStatus(
                        label = stringResource(R.string.local_models_slot_embeddings),
                        modelName = embeddingSlot.modelName.ifEmpty { null },
                        active = embeddingSlot.isLoaded,
                        isLoading = embeddingSlot.isLoading,
                        badge = embeddingSlot.backendType.displayName
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EngineSlotStatus(
                        label = stringResource(R.string.local_models_slot_reranker),
                        modelName = rerankSlot.modelName.ifEmpty { null },
                        active = rerankSlot.isLoaded,
                        isLoading = rerankSlot.isLoading,
                        badge = if (rerankSlot.isLoaded) rerankSlot.backendType.displayName else stringResource(R.string.local_models_slot_idle)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun SlotCard(
    label: String,
    slotState: SlotState,
    color: Color,
    enabled: Boolean,
    onLoadClick: (String) -> Unit,
    onUnloadClick: () -> Unit,
    models: List<StoredModel>,
    formatFileSize: (Long) -> String,
    modifier: Modifier = Modifier
) {
    var showModelPicker by remember { mutableStateOf(false) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (slotState.isLoading) stringResource(R.string.shared_loading) else slotState.backendType.displayName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = color
                    )
                }
            }

            if (slotState.isLoading) {
                LinearProgressIndicator(
                    progress = { slotState.loadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            if (slotState.isLoaded && slotState.modelName.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = slotState.modelName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = slotState.modelSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            } else if (slotState.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = slotState.error.take(30),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2
                    )
                }
            } else if (!slotState.isLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled && models.isNotEmpty()) {
                            if (models.size == 1) {
                                onLoadClick(models.first().filePath)
                            } else {
                                showModelPicker = true
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (models.isEmpty()) "No models" else stringResource(R.string.local_models_load_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            models = models,
            formatFileSize = formatFileSize,
            onDismiss = { showModelPicker = false },
            onSelect = { model ->
                showModelPicker = false
                onLoadClick(model.filePath)
            }
        )
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<StoredModel>,
    formatFileSize: (Long) -> String,
    onDismiss: () -> Unit,
    onSelect: (StoredModel) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.common_model_picker_title), style = MaterialTheme.typography.titleLarge)
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(models) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(model) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2
                            )
                            Text(
                                text = formatFileSize(model.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_btn_cancel))
            }
        }
    )
}

@Composable
private fun ModelCard(
    model: StoredModel,
    isLoadedInSlot: Boolean,
    loadedSlot: SlotType?,
    engineEnabled: Boolean,
    formatFileSize: (Long) -> String,
    onLoad: (SlotType) -> Unit,
    onDelete: () -> Unit
) {
    val isHighMemory = model.quantization.contains("Q8", ignoreCase = true)

    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isHighMemory) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                            .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = stringResource(R.string.local_models_memory_heavy),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                if (isLoadedInSlot && loadedSlot != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = when (loadedSlot) {
                                SlotType.MAIN -> stringResource(R.string.local_models_active_main)
                                SlotType.EMBEDDING -> "Active Emb"
                                SlotType.RERANK -> "Active Rerank"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        supportingContent = {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.SdCard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = formatFileSize(model.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = model.quantization.ifEmpty { "N/A" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (model.architecture.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = model.architecture,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (isLoadedInSlot && loadedSlot != null) {
                            onLoad(loadedSlot)
                        } else {
                            onLoad(SlotType.MAIN)
                        }
                    },
                    enabled = engineEnabled
                ) {
                    Text(
                        text = if (isLoadedInSlot) stringResource(R.string.local_models_loaded) else stringResource(R.string.local_models_load),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.local_models_cd_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}

@Composable
private fun EngineSlotStatus(
    label: String,
    modelName: String?,
    active: Boolean,
    isLoading: Boolean,
    badge: String
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        supportingContent = {
            Text(
                text = when {
                    isLoading -> "Loading..."
                    modelName != null -> modelName
                    else -> stringResource(R.string.local_models_not_loaded)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        0.5.dp,
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = when {
                        isLoading -> "..."
                        active -> badge
                        else -> stringResource(R.string.local_models_slot_idle)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
