package com.promenar.nexara.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk

private val ModelTypes = listOf("chat", "reasoning", "image", "embedding", "rerank")
private val ModelTypeLabels = listOf("Chat", "Reasoning", "Image", "Embed", "Rerank")

private data class CapabilityTag(
    val key: String,
    val label: String,
    val icon: String,
    val color: androidx.compose.ui.graphics.Color
)

private val CapabilityTags = listOf(
    CapabilityTag("vision", "Vision", "visibility", NexaraColors.StatusError.copy(alpha = 0.8f)),
    CapabilityTag("internet", "Internet", "public", NexaraColors.StatusInfo),
    CapabilityTag("reasoning", "Reasoning", "psychology", NexaraColors.PrimaryContainer)
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProviderModelsScreen(
    providerName: String = "Provider",
    providerId: String = "",
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val models by viewModel.providerModels.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var testingModel by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    var newModelId by remember { mutableStateOf("") }
    var newModelName by remember { mutableStateOf("") }

    val filteredModels = remember(models, searchQuery) {
        if (searchQuery.isBlank()) models
        else models.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    val providers by viewModel.providers.collectAsState()
    val provider = remember(providers, providerId) {
        providers.find { it.id == providerId }
    }
    val effectiveTitle = provider?.name ?: providerName

    NexaraPageLayout(
        title = effectiveTitle,
        onBack = onNavigateBack,
        scrollable = false,
        modifier = Modifier.imePadding()
    ) {
        NexaraSearchBar(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = stringResource(R.string.provider_models_search),
        )

        Spacer(modifier = Modifier.height(12.dp))

        val isFetching by viewModel.isFetchingModels.collectAsState()
        val rotation by animateFloatAsState(
            targetValue = if (isFetching) 360f else 0f,
            animationSpec = if (isFetching) tween(1000, easing = androidx.compose.animation.core.LinearEasing) else tween(0),
            label = "syncRotation"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionChip(
                icon = Icons.Rounded.Sync,
                label = stringResource(R.string.provider_models_auto_fetch),
                onClick = { viewModel.refreshModels() },
                iconModifier = Modifier.rotate(rotation),
                modifier = Modifier.weight(1f)
            )
            ActionChip(
                icon = Icons.Rounded.Add, 
                label = stringResource(R.string.provider_models_add), 
                isPrimary = true, 
                onClick = { showAddDialog = true },
                modifier = Modifier.weight(1f)
            )
            ActionChip(
                icon = Icons.Rounded.Block, 
                label = stringResource(R.string.provider_models_disable_all), 
                onClick = { viewModel.disableAllModels() },
                modifier = Modifier.weight(1f)
            )
            ActionChip(
                icon = Icons.Rounded.Delete, 
                label = stringResource(R.string.provider_models_delete_all), 
                isDanger = true, 
                onClick = { showDeleteAllDialog = true },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 40.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filteredModels) { model ->
                EnhancedModelCard(
                    model = model,
                    isTesting = testingModel == model.id,
                    testResult = if (testingModel == model.id) testResult else null,
                    onToggle = { viewModel.toggleModel(model.id) },
                    onTest = {
                        testingModel = model.id
                        testResult = null
                    },
                    onDelete = { viewModel.deleteModel(model.id) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                AddCustomModelButton(onClick = { showAddDialog = true })
            }
        }
    }

    if (showDeleteAllDialog) {
        NexaraConfirmDialog(
            title = stringResource(R.string.provider_models_delete_all_title),
            message = stringResource(R.string.provider_models_delete_all_message),
            confirmText = stringResource(R.string.shared_btn_delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteAllModels()
                showDeleteAllDialog = false
            },
            onCancel = { showDeleteAllDialog = false }
        )
    }

    NexaraBottomSheet(
        show = showAddDialog,
        onDismiss = { showAddDialog = false },
        title = stringResource(R.string.provider_models_add)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    text = "Model ID",
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                NexaraSearchBar(
                    value = newModelId,
                    onValueChange = { newModelId = it },
                    placeholder = "e.g. gpt-4o"
                )
            }
            Column {
                Text(
                    text = "Display Name",
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                NexaraSearchBar(
                    value = newModelName,
                    onValueChange = { newModelName = it },
                    placeholder = "Optional"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.material3.Button(
                onClick = {
                    if (newModelId.isNotBlank()) {
                        viewModel.addCustomModel(newModelId, newModelName)
                        newModelId = ""
                        newModelName = ""
                        showAddDialog = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = NexaraColors.Primary,
                    contentColor = NexaraColors.OnPrimary
                ),
                shape = NexaraShapes.medium
            ) {
                Text(stringResource(R.string.shared_btn_add))
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isPrimary: Boolean = false,
    isDanger: Boolean = false,
    iconModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = when {
        isDanger -> NexaraColors.ErrorContainer.copy(alpha = 0.3f)
        isPrimary -> NexaraColors.InversePrimary
        else -> NexaraColors.SurfaceHigh
    }
    val contentColor = when {
        isDanger -> NexaraColors.Error
        isPrimary -> NexaraColors.OnPrimary
        else -> NexaraColors.OnSurface
    }

    Box(
        modifier = modifier
            .height(30.dp)
            .clip(NexaraShapes.medium)
            .background(bgColor)
            .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(12.dp).then(iconModifier)
            )
            Text(
                text = label,
                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                color = contentColor
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EnhancedModelCard(
    model: ModelInfo,
    isTesting: Boolean,
    testResult: String?,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    var selectedType by remember { mutableStateOf(model.type) }
    var editName by remember { mutableStateOf(model.name) }
    var editId by remember { mutableStateOf(model.id) }
    var editContext by remember { mutableStateOf(model.contextLength.toString()) }
    var activeCaps by remember { mutableStateOf(model.capabilities.toSet()) }

    val rotation by animateFloatAsState(
        targetValue = if (isTesting) 360f else 0f,
        animationSpec = tween(1000),
        label = "testRotation"
    )
    val resultColor = when (testResult) {
        "success" -> NexaraColors.StatusSuccess
        "error" -> NexaraColors.StatusError
        else -> NexaraColors.Primary
    }

    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BasicTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            singleLine = true,
                            textStyle = NexaraTypography.headlineSmall.copy(
                                color = NexaraColors.OnSurface,
                                fontSize = 16.sp
                            ),
                            cursorBrush = SolidColor(NexaraColors.Primary)
                        )
                        if (editName.isEmpty()) {
                            Text(
                                text = model.name,
                                style = NexaraTypography.headlineSmall.copy(fontSize = 16.sp),
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = editId,
                            style = NexaraTypography.bodyMedium.copy(
                                fontSize = 11.sp,
                                fontFamily = SpaceGrotesk,
                                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f)
                            )
                        )
                    }

                    if (testResult != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (testResult == "success") stringResource(R.string.provider_models_test_success, "128ms")
                            else stringResource(R.string.provider_models_test_error),
                            style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                            color = resultColor
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onTest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isTesting) Icons.Rounded.Sync else Icons.Rounded.Bolt,
                            contentDescription = stringResource(R.string.provider_models_cd_test),
                            tint = resultColor,
                            modifier = Modifier
                                .size(16.dp)
                                .then(if (isTesting) Modifier.rotate(rotation) else Modifier)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.shared_btn_delete),
                            tint = NexaraColors.StatusError.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Switch(
                        checked = model.enabled,
                        onCheckedChange = { onToggle() },
                        modifier = Modifier.scale(0.7f),
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = NexaraColors.Primary,
                            checkedThumbColor = NexaraColors.OnPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(NexaraColors.GlassBorder)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Model Type & Capabilities
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Type Selector Row
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(NexaraColors.SurfaceLow)
                            .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(6.dp))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ModelTypeLabels.forEachIndexed { index, label ->
                            val type = ModelTypes[index]
                            val isSelected = selectedType == type
                            val chipBg by animateColorAsState(
                                targetValue = if (isSelected) NexaraColors.SurfaceHighest
                                else NexaraColors.SurfaceLow.copy(alpha = 0f),
                                animationSpec = tween(200),
                                label = "typeChipBg"
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(chipBg)
                                    .clickable { selectedType = type }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                    color = if (isSelected) NexaraColors.OnSurface else NexaraColors.Outline
                                )
                            }
                        }
                    }
                }

                // Capabilities Row (Multi-select)
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CapabilityTags.forEach { cap ->
                        val isActive = activeCaps.contains(cap.key)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isActive) cap.color.copy(alpha = 0.15f)
                                    else NexaraColors.GlassSurface
                                )
                                .border(
                                    0.5.dp,
                                    if (isActive) cap.color.copy(alpha = 0.3f) else NexaraColors.GlassBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    activeCaps = if (isActive) activeCaps - cap.key
                                    else activeCaps + cap.key
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = cap.label,
                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                color = if (isActive) cap.color else NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.provider_models_context_label),
                    style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
                
                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NexaraColors.SurfaceLow)
                        .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    BasicTextField(
                        value = editContext,
                        onValueChange = { editContext = it },
                        singleLine = true,
                        textStyle = NexaraTypography.bodyMedium.copy(
                            fontSize = 11.sp,
                            fontFamily = SpaceGrotesk,
                            color = NexaraColors.OnSurface
                        ),
                        cursorBrush = SolidColor(NexaraColors.Primary)
                    )
                }
                
                Text(
                    text = "tokens",
                    style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun AddCustomModelButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceHigh)
            .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = NexaraColors.Primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.provider_models_add_custom),
                style = NexaraTypography.labelMedium,
                color = NexaraColors.Primary
            )
        }
    }
}
