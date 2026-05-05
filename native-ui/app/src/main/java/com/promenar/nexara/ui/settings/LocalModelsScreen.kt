package com.promenar.nexara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk

private data class LocalModelInfo(
    val id: String,
    val name: String,
    val size: String,
    val quantization: String,
    val memoryWarning: Boolean = false,
    val slotMain: Boolean = false,
    val slotEmb: Boolean = false,
    val slotRerank: Boolean = false
)

private val PlaceholderModels = listOf(
    LocalModelInfo("1", "Mistral-7B-Instruct-v0.2", "7.2 GB", "Q8_0", memoryWarning = true),
    LocalModelInfo("2", "Llama-3-8B-Instruct.Q5_K_M", "5.7 GB", "Q5_K_M", slotMain = true),
    LocalModelInfo("3", "nomic-embed-text-v1.5.f16", "274 MB", "F16", slotEmb = true)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(
    onNavigateBack: () -> Unit
) {
    var engineEnabled by remember { mutableStateOf(true) }
    var models by remember { mutableStateOf(PlaceholderModels) }

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
                            onCheckedChange = { engineEnabled = it },
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
                        .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                        .border(
                            1.dp,
                            NexaraColors.OutlineVariant,
                            NexaraShapes.large
                        )
                        .clickable { }
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.UploadFile,
                            contentDescription = null,
                            tint = NexaraColors.Primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.local_models_import_title),
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurface
                        )
                        Text(
                            text = stringResource(R.string.local_models_import_subtitle),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_active_slots)) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SlotCard(
                        label = stringResource(R.string.local_models_slot_main),
                        slotType = "GPU",
                        modelName = "Llama-3-8B-Instruct.Q5_K_M",
                        size = "5.7 GB",
                        active = true,
                        color = NexaraColors.Primary,
                        modifier = Modifier.weight(1f)
                    )
                    SlotCard(
                        label = stringResource(R.string.local_models_slot_embeddings),
                        slotType = "CPU",
                        modelName = "nomic-embed-text-v1.5.f16",
                        size = "274 MB",
                        active = true,
                        color = NexaraColors.Tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    SlotCard(
                        label = stringResource(R.string.local_models_slot_reranker),
                        slotType = "Idle",
                        modelName = null,
                        size = null,
                        active = false,
                        color = NexaraColors.Outline,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_imported)) }

            items(models) { model ->
                ModelCard(
                    model = model,
                    onLoad = { },
                    onDelete = {
                        models = models.filter { it.id != model.id }
                    }
                )
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
                            text = "llama.cpp · v3.2.1",
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurface
                        )
                        EngineSlotStatus("Main", "Llama-3-8B-Instruct.Q5_K_M", active = true, badge = "GPU")
                        EngineSlotStatus("Embedding", "nomic-embed-text-v1.5.f16", active = true, badge = "CPU")
                        EngineSlotStatus("Reranker", stringResource(R.string.local_models_not_loaded), active = false, badge = "Idle")
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCard(
    label: String,
    slotType: String,
    modelName: String?,
    size: String?,
    active: Boolean,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    NexaraGlassCard(
        modifier = modifier,
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
                        text = slotType,
                        style = NexaraTypography.labelMedium.copy(fontSize = 9.sp),
                        color = color
                    )
                }
            }

            if (modelName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = modelName,
                        style = NexaraTypography.headlineMedium.copy(
                            fontSize = 14.sp,
                            fontFamily = SpaceGrotesk
                        ),
                        color = NexaraColors.OnSurface,
                        maxLines = 2
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddCircle,
                        contentDescription = null,
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.local_models_load_model),
                        style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }

            if (size != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = size,
                        style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = SpaceGrotesk),
                        color = NexaraColors.OnSurfaceVariant
                    )
                    if (active) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(NexaraColors.StatusSuccess, CircleShape())
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: LocalModelInfo,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
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
                        text = model.name,
                        style = NexaraTypography.headlineMedium.copy(fontSize = 16.sp),
                        color = NexaraColors.OnSurface
                    )
                    if (model.memoryWarning) {
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
                    if (model.slotMain) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NexaraColors.Primary.copy(alpha = 0.2f))
                                .border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.local_models_active_main),
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
                            text = model.size,
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
                            text = model.quantization,
                            style = NexaraTypography.bodyMedium.copy(
                                fontSize = 11.sp,
                                fontFamily = SpaceGrotesk
                            ),
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .clip(NexaraShapes.medium)
                        .background(NexaraColors.SurfaceContainer)
                        .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
                        .clickable(onClick = onLoad)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (model.slotMain) stringResource(R.string.local_models_loaded) else stringResource(R.string.local_models_load),
                        style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                        color = if (model.slotMain) NexaraColors.OnSurfaceVariant else NexaraColors.OnSurface
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
    modelName: String,
    active: Boolean,
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
                text = modelName,
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
                    text = badge,
                    style = NexaraTypography.labelMedium.copy(fontSize = 9.sp),
                    color = if (active) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                )
            }
        }
    }
}

private fun CircleShape() = RoundedCornerShape(50)
