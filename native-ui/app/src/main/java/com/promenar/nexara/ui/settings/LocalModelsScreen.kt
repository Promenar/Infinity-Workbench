package com.promenar.nexara.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.local.inference.SlotState
import com.promenar.nexara.data.local.inference.SlotType
import com.promenar.nexara.data.local.inference.StoredModel
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LocalModelsViewModel = viewModel(
        factory = LocalModelsViewModel.factory(LocalContext.current.applicationContext as android.app.Application)
    )
) {
    val context = LocalContext.current
    val availableModels by viewModel.availableModels.collectAsState()
    val engineEnabled by viewModel.isEngineEnabled.collectAsState()
    val mainSlot by viewModel.mainSlot.collectAsState()
    val embeddingSlot by viewModel.embeddingSlot.collectAsState()
    val rerankSlot by viewModel.rerankSlot.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()

    val ggufPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.local_models_title), style = NexaraTypography.headlineLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_cd_back),
                            tint = NexaraColors.OnSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 24.dp, bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.local_models_desc),
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(NexaraColors.PrimaryContainer.copy(alpha = 0.2f), CircleShape())
                                    .border(0.5.dp, NexaraColors.PrimaryContainer.copy(alpha = 0.3f), CircleShape()),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Dns,
                                    contentDescription = null,
                                    tint = NexaraColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.local_models_enable_engine),
                                    style = NexaraTypography.headlineMedium,
                                    color = NexaraColors.OnSurface
                                )
                                Text(
                                    text = stringResource(R.string.local_models_engine_subtitle),
                                    style = NexaraTypography.labelMedium,
                                    color = NexaraColors.OnSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = engineEnabled,
                            onCheckedChange = { viewModel.setEngineEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = NexaraColors.Primary,
                                checkedThumbColor = NexaraColors.OnPrimary
                            )
                        )
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(NexaraShapes.large)
                        .background(
                            if (isImporting) NexaraColors.PrimaryContainer.copy(alpha = 0.1f)
                            else NexaraColors.SurfaceContainer.copy(alpha = 0.3f)
                        )
                        .border(
                            1.dp,
                            if (isImporting) NexaraColors.Primary.copy(alpha = 0.5f)
                            else NexaraColors.OutlineVariant,
                            NexaraShapes.large
                        )
                        .clickable(enabled = !isImporting) {
                            ggufPickerLauncher.launch(arrayOf("*/*"))
                        }
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.UploadFile,
                            contentDescription = null,
                            tint = if (isImporting) NexaraColors.Primary.copy(alpha = 0.5f) else NexaraColors.Primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isImporting) "Importing..." else stringResource(R.string.local_models_import_title),
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurface
                        )
                        Text(
                            text = if (isImporting) "Please wait" else stringResource(R.string.local_models_import_subtitle),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_active_slots)) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SlotCard(
                        label = stringResource(R.string.local_models_slot_main),
                        slotState = mainSlot,
                        color = NexaraColors.Primary,
                        enabled = engineEnabled,
                        onLoadClick = { path -> viewModel.loadModel(SlotType.MAIN, path) },
                        onUnloadClick = { viewModel.unloadModel(SlotType.MAIN) },
                        models = availableModels,
                        formatFileSize = { viewModel.formatFileSize(it) },
                        modifier = Modifier.weight(1f)
                    )
                    SlotCard(
                        label = stringResource(R.string.local_models_slot_embeddings),
                        slotState = embeddingSlot,
                        color = NexaraColors.Tertiary,
                        enabled = engineEnabled,
                        onLoadClick = { path -> viewModel.loadModel(SlotType.EMBEDDING, path) },
                        onUnloadClick = { viewModel.unloadModel(SlotType.EMBEDDING) },
                        models = availableModels,
                        formatFileSize = { viewModel.formatFileSize(it) },
                        modifier = Modifier.weight(1f)
                    )
                    SlotCard(
                        label = stringResource(R.string.local_models_slot_reranker),
                        slotState = rerankSlot,
                        color = NexaraColors.Outline,
                        enabled = engineEnabled,
                        onLoadClick = { path -> viewModel.loadModel(SlotType.RERANK, path) },
                        onUnloadClick = { viewModel.unloadModel(SlotType.RERANK) },
                        models = availableModels,
                        formatFileSize = { viewModel.formatFileSize(it) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_imported)) }

            if (availableModels.isEmpty()) {
                item {
                    NexaraGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NexaraShapes.large as RoundedCornerShape
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No models imported yet",
                                style = NexaraTypography.bodyMedium,
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
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
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_engine_status)) }

            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "llama.cpp · ${if (viewModel.gpuAvailable) "Vulkan GPU" else "CPU"}",
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurface
                        )
                        EngineSlotStatus(
                            label = "Main",
                            modelName = mainSlot.modelName.ifEmpty { null },
                            active = mainSlot.isLoaded,
                            isLoading = mainSlot.isLoading,
                            badge = mainSlot.backendType.displayName
                        )
                        EngineSlotStatus(
                            label = "Embedding",
                            modelName = embeddingSlot.modelName.ifEmpty { null },
                            active = embeddingSlot.isLoaded,
                            isLoading = embeddingSlot.isLoading,
                            badge = embeddingSlot.backendType.displayName
                        )
                        EngineSlotStatus(
                            label = "Reranker",
                            modelName = rerankSlot.modelName.ifEmpty { null },
                            active = rerankSlot.isLoaded,
                            isLoading = rerankSlot.isLoading,
                            badge = if (rerankSlot.isLoaded) rerankSlot.backendType.displayName else "Idle"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCard(
    label: String,
    slotState: SlotState,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onLoadClick: (String) -> Unit,
    onUnloadClick: () -> Unit,
    models: List<StoredModel>,
    formatFileSize: (Long) -> String,
    modifier: Modifier = Modifier
) {
    var showModelPicker by remember { mutableStateOf(false) }

    NexaraGlassCard(
        modifier = modifier.fillMaxHeight(),
        shape = NexaraShapes.large as RoundedCornerShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(NexaraColors.SurfaceContainer)
                        .border(0.5.dp, color.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (slotState.isLoading) "Loading" else slotState.backendType.displayName,
                        style = NexaraTypography.labelMedium.copy(fontSize = 9.sp),
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
                    color = NexaraColors.Primary,
                    trackColor = NexaraColors.SurfaceContainer
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
                        style = NexaraTypography.headlineMedium.copy(
                            fontSize = 14.sp,
                            fontFamily = SpaceGrotesk
                        ),
                        color = NexaraColors.OnSurface,
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
                        style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = SpaceGrotesk),
                        color = NexaraColors.OnSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(NexaraColors.StatusSuccess, RoundedCornerShape(50))
                    )
                }
            } else if (slotState.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = NexaraColors.Error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = slotState.error.take(30),
                        style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                        color = NexaraColors.Error,
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
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp)
                    )
                    Text(
                        text = if (models.isEmpty()) "No models" else stringResource(R.string.local_models_load_model),
                        style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                        color = NexaraColors.OnSurfaceVariant
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
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Select Model", style = NexaraTypography.headlineMedium)
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
                                style = NexaraTypography.bodyMedium,
                                color = NexaraColors.OnSurface,
                                maxLines = 2
                            )
                            Text(
                                text = formatFileSize(model.sizeBytes),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                text = "Cancel",
                modifier = Modifier.clickable { onDismiss() },
                color = NexaraColors.Primary
            )
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

    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = model.fileName,
                        style = NexaraTypography.headlineMedium.copy(fontSize = 16.sp),
                        color = NexaraColors.OnSurface
                    )
                    if (isHighMemory) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NexaraColors.ErrorContainer.copy(alpha = 0.3f))
                                .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = NexaraColors.Error,
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = stringResource(R.string.local_models_memory_heavy),
                                    style = NexaraTypography.labelMedium.copy(fontSize = 9.sp),
                                    color = NexaraColors.OnErrorContainer
                                )
                            }
                        }
                    }
                    if (isLoadedInSlot && loadedSlot != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NexaraColors.Primary.copy(alpha = 0.2f))
                                .border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = when (loadedSlot) {
                                    SlotType.MAIN -> stringResource(R.string.local_models_active_main)
                                    SlotType.EMBEDDING -> "Active Emb"
                                    SlotType.RERANK -> "Active Rerank"
                                },
                                style = NexaraTypography.labelMedium.copy(fontSize = 9.sp),
                                color = NexaraColors.Primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.SdCard,
                            contentDescription = null,
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = formatFileSize(model.sizeBytes),
                            style = NexaraTypography.bodyMedium.copy(
                                fontSize = 11.sp,
                                fontFamily = SpaceGrotesk
                            ),
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = model.quantization.ifEmpty { "N/A" },
                            style = NexaraTypography.bodyMedium.copy(
                                fontSize = 11.sp,
                                fontFamily = SpaceGrotesk
                            ),
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                    if (model.architecture.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Memory,
                                contentDescription = null,
                                tint = NexaraColors.OnSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = model.architecture,
                                style = NexaraTypography.bodyMedium.copy(
                                    fontSize = 11.sp,
                                    fontFamily = SpaceGrotesk
                                ),
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .clip(NexaraShapes.medium)
                        .background(NexaraColors.SurfaceContainer)
                        .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
                        .clickable(enabled = engineEnabled) {
                            if (isLoadedInSlot && loadedSlot != null) {
                                onLoad(loadedSlot)
                            } else {
                                onLoad(SlotType.MAIN)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isLoadedInSlot) stringResource(R.string.local_models_loaded) else stringResource(R.string.local_models_load),
                        style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                        color = if (isLoadedInSlot) NexaraColors.OnSurfaceVariant else NexaraColors.OnSurface
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.local_models_cd_delete),
                        tint = NexaraColors.Error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineSlotStatus(
    label: String,
    modelName: String?,
    active: Boolean,
    isLoading: Boolean,
    badge: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceHigh)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = NexaraTypography.labelMedium,
            color = NexaraColors.OnSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    isLoading -> "Loading..."
                    modelName != null -> modelName
                    else -> stringResource(R.string.local_models_not_loaded)
                },
                style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp, fontFamily = SpaceGrotesk),
                color = if (active) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (active) NexaraColors.Primary.copy(alpha = 0.15f)
                        else NexaraColors.SurfaceContainer
                    )
                    .border(
                        0.5.dp,
                        if (active) NexaraColors.Primary.copy(alpha = 0.25f) else NexaraColors.GlassBorder,
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
                    style = NexaraTypography.labelMedium.copy(fontSize = 9.sp),
                    color = if (active) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                )
            }
        }
    }
}

private fun CircleShape() = RoundedCornerShape(50)
