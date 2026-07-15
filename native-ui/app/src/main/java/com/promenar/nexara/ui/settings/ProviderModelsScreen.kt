package com.promenar.nexara.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.common.status.UiStatusNotice
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk

private val ModelTypes = listOf("chat", "reasoning", "image", "embedding", "rerank")
private val ModelTypeLabelResources = listOf(
    R.string.provider_models_type_chat,
    R.string.provider_models_type_reasoning,
    R.string.provider_models_type_image,
    R.string.provider_models_type_embedding,
    R.string.provider_models_type_rerank,
)

private data class CapabilityTag(
    val key: String,
    @param:StringRes val labelRes: Int,
    val icon: String,
    val color: Color,
)

private val CapabilityTags = listOf(
    CapabilityTag("vision", R.string.provider_models_capability_vision, "visibility", NexaraColors.StatusError.copy(alpha = 0.8f)),
    CapabilityTag("internet", R.string.provider_models_capability_internet, "public", NexaraColors.StatusInfo),
    CapabilityTag("audioinput", R.string.provider_models_capability_audio_input, "mic", NexaraColors.StatusSuccess),
    CapabilityTag("audiooutput", R.string.provider_models_capability_audio_output, "volume_up", NexaraColors.StatusSuccess),
    CapabilityTag("videounderstanding", R.string.provider_models_capability_video, "videocam", NexaraColors.Tertiary),
    CapabilityTag("structuredoutput", R.string.provider_models_capability_structured_output, "data_object", NexaraColors.Primary),
    CapabilityTag("promptcaching", R.string.provider_models_capability_prompt_caching, "cached", NexaraColors.StatusWarning),
    CapabilityTag("computeruse", R.string.provider_models_capability_computer_use, "computer", NexaraColors.Secondary),
)

/** type → 基础能力推导表。当用户在 UI 中切换 type 时联动刷新 capabilities。 */
private val TypeToBaseCaps = mapOf(
    "chat"      to setOf("chat"),
    "reasoning" to setOf("chat", "reasoning"),
    "image"     to setOf("image"),
    "embedding" to setOf("embedding"),
    "rerank"    to setOf("rerank"),
)

/** 所有由 type 决定的基础能力键（用于剥离旧基础能力） */
private val AllBaseCapKeys = TypeToBaseCaps.values.flatten().toSet()

internal data class ProviderModelsScreenState(
    val providerName: String,
    val providerId: String,
    val isFetching: Boolean,
    val syncNotice: UiStatusNotice?,
    val models: List<ModelInfo>,
    val modelTestStates: Map<String, ModelTestState>,
)

internal data class ProviderModelsScreenActions(
    val onRefresh: () -> Unit,
    val onAdd: (id: String, name: String) -> Boolean,
    val onDisableAll: () -> Unit,
    val onDeleteAll: () -> Unit,
    val onUpdate: (ModelInfo) -> Unit,
    val onToggle: (String) -> Unit,
    val onTest: (String) -> Unit,
    val onCancelTest: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onClearNotice: () -> Unit,
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProviderModelsScreen(
    providerName: String = "",
    providerId: String = "",
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val models by viewModel.providerModels.collectAsState()
    val modelTestStates by viewModel.modelTestStates.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val isFetching by viewModel.isFetchingModels.collectAsState()
    val syncNotice by viewModel.modelSyncNotice.collectAsState()

    val provider = remember(providers, providerId) {
        providers.find { it.id == providerId }
    }
    val effectiveTitle = provider?.name
        ?: providerName.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.provider_models_default_provider)

    // 归属只依据稳定 providerId，禁止用可重名的展示名称猜测。
    val scopedModels = remember(models, providerId) {
        models.filter { it.providerId == providerId }
    }

    val state = ProviderModelsScreenState(
        providerName = effectiveTitle,
        providerId = providerId,
        isFetching = isFetching,
        syncNotice = syncNotice,
        models = scopedModels,
        modelTestStates = modelTestStates,
    )

    val actions = ProviderModelsScreenActions(
        onRefresh = { viewModel.refreshProviderModels(providerId) },
        onAdd = { id, name -> viewModel.addCustomModel(providerId, id, name) },
        onDisableAll = { viewModel.disableAllModels(providerId) },
        onDeleteAll = { viewModel.deleteAllModels(providerId) },
        onUpdate = { viewModel.updateModel(it) },
        onToggle = { modelId -> viewModel.toggleModel(modelId) },
        onTest = { modelId -> viewModel.testModel(modelId) },
        onCancelTest = { modelId -> viewModel.cancelModelTest(modelId) },
        onDelete = { modelId -> viewModel.deleteModel(modelId) },
        onClearNotice = { viewModel.clearSyncNotice() },
    )

    ProviderModelsScreenContent(
        state = state,
        actions = actions,
        onNavigateBack = onNavigateBack,
    )
}

@Composable
internal fun ProviderModelsScreenContent(
    state: ProviderModelsScreenState,
    actions: ProviderModelsScreenActions,
    onNavigateBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredModels = remember(state.models, searchQuery) {
        if (searchQuery.isBlank()) state.models
        else state.models.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    NexaraPageLayout(
        title = state.providerName,
        onBack = onNavigateBack,
        scrollable = false,
        modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_SCREEN_ROOT),
    ) {
        NexaraSearchBar(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = stringResource(R.string.provider_models_search),
            modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD),
        )

        Spacer(modifier = Modifier.height(12.dp))

        val rotation by animateFloatAsState(
            targetValue = if (state.isFetching) 360f else 0f,
            animationSpec = if (state.isFetching) {
                tween(1000, easing = androidx.compose.animation.core.LinearEasing)
            } else {
                tween(0)
            },
            label = "syncRotation",
        )

        ProviderModelsActionsGrid(
            isFetching = state.isFetching,
            rotation = rotation,
            onRefresh = actions.onRefresh,
            onAdd = { showAddDialog = true },
            onDisableAll = actions.onDisableAll,
            onDeleteAll = { showDeleteAllDialog = true },
        )

        // 同步反馈消息：据 severity 着色，据 code+args 经 stringResource 双语格式化
        val effectiveSyncNotice = state.syncNotice ?: if (state.isFetching) ModelSyncNotice.loading() else null
        AnimatedVisibility(
            visible = effectiveSyncNotice != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_NOTICE),
        ) {
            effectiveSyncNotice?.let { notice ->
                ModelSyncNoticeBanner(
                    notice = notice,
                    dismissEnabled = !state.isFetching,
                    onDismiss = actions.onClearNotice,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 40.dp),
            modifier = Modifier
                .weight(1f)
                .testTag(UiTags.PROVIDER_MODELS_LIST),
        ) {
            when (
                reduceProviderModelsListState(
                    isLoading = state.isFetching,
                    modelCount = state.models.size,
                    filteredCount = filteredModels.size,
                    searchQuery = searchQuery,
                    notice = state.syncNotice,
                )
            ) {
                ProviderModelsListState.Loading -> item("loading") {
                    ProviderModelsListMessage(
                        message = stringResource(R.string.shared_loading),
                        tag = UiTags.PROVIDER_MODELS_STATE_LOADING,
                    )
                }

                ProviderModelsListState.Error -> item("error") {
                    ProviderModelsListMessage(
                        message = stringResource(R.string.provider_models_sync_failed),
                        tag = UiTags.PROVIDER_MODELS_STATE_ERROR,
                    )
                }

                ProviderModelsListState.Empty -> item("empty") {
                    ProviderModelsListMessage(
                        message = stringResource(R.string.provider_models_sync_no_models),
                        tag = UiTags.PROVIDER_MODELS_STATE_EMPTY,
                    )
                }

                ProviderModelsListState.SearchEmpty -> item("search-empty") {
                    ProviderModelsListMessage(
                        message = stringResource(R.string.common_model_picker_empty),
                        tag = UiTags.PROVIDER_MODELS_STATE_EMPTY,
                    )
                }

                ProviderModelsListState.Content -> items(
                    filteredModels,
                    key = { it.id },
                ) { model ->
                    EnhancedModelCard(
                        model = model,
                        testState = state.modelTestStates[model.id] ?: ModelTestState.Idle,
                        onUpdate = { actions.onUpdate(it) },
                        onToggle = { actions.onToggle(model.id) },
                        onTest = {
                            if (state.modelTestStates[model.id] == ModelTestState.Testing) {
                                actions.onCancelTest(model.id)
                            } else {
                                actions.onTest(model.id)
                            }
                        },
                        onDelete = { actions.onDelete(model.id) },
                        modifier = Modifier.testTag(UiTags.providerModelsModelCard(model.id)),
                    )
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showDeleteAllDialog = false }) {
            Box(modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_DELETE_ALL_CONFIRM_DIALOG)) {
                NexaraConfirmDialog(
                    title = stringResource(R.string.provider_models_delete_all_title),
                    message = stringResource(R.string.provider_models_delete_all_message),
                    confirmText = stringResource(R.string.shared_btn_delete),
                    confirmButtonModifier = Modifier.testTag(UiTags.PROVIDER_MODELS_DELETE_ALL_CONFIRM_BUTTON),
                    isDestructive = true,
                    onConfirm = {
                        actions.onDeleteAll()
                        showDeleteAllDialog = false
                    },
                    onCancel = { showDeleteAllDialog = false },
                )
            }
        }
    }

    if (showAddDialog) {
        NexaraBottomSheet(
            show = showAddDialog,
            onDismiss = { showAddDialog = false },
            title = stringResource(R.string.provider_models_add),
        ) {
            Box(modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ADD_SHEET)) {
                AddCustomModelForm(
                    onSubmit = { id, name ->
                        val added = actions.onAdd(id, name)
                        if (added) {
                            showAddDialog = false
                        }
                        added
                    },
                    onAdded = {
                        showAddDialog = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun AddCustomModelForm(
    onSubmit: (id: String, name: String) -> Boolean,
    onAdded: () -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var submissionFailed by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.provider_models_field_model_id),
                style = NexaraTypography.labelMedium,
                color = NexaraColors.OnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            NexaraSearchBar(
                value = modelId,
                onValueChange = {
                    modelId = it
                    submissionFailed = false
                },
                placeholder = stringResource(R.string.provider_models_placeholder_model_id),
                modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ADD_ID_FIELD),
            )
        }
        Column {
            Text(
                text = stringResource(R.string.provider_models_field_display_name),
                style = NexaraTypography.labelMedium,
                color = NexaraColors.OnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            NexaraSearchBar(
                value = modelName,
                onValueChange = {
                    modelName = it
                    submissionFailed = false
                },
                placeholder = stringResource(R.string.provider_models_placeholder_optional),
                modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ADD_NAME_FIELD),
            )
        }
        if (submissionFailed) {
            val failureMessage = stringResource(R.string.generation_failure_invalid_request)
            Text(
                text = failureMessage,
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.StatusError,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Assertive
                    stateDescription = failureMessage
                },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.material3.Button(
            onClick = {
                if (modelId.isNotBlank()) {
                    if (onSubmit(modelId, modelName)) {
                        onAdded()
                    } else {
                        submissionFailed = true
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .testTag(UiTags.PROVIDER_MODELS_ADD_SUBMIT_BUTTON),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = NexaraColors.Primary,
                contentColor = NexaraColors.OnPrimary,
            ),
            shape = NexaraShapes.medium,
        ) {
            Text(stringResource(R.string.shared_btn_add))
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun ProviderModelsActionsGrid(
    isFetching: Boolean,
    rotation: Float,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onDisableAll: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionChip(
                icon = Icons.Rounded.Sync,
                label = stringResource(R.string.provider_models_auto_fetch),
                onClick = onRefresh,
                enabled = !isFetching,
                iconModifier = Modifier.rotate(rotation),
                modifier = Modifier
                    .weight(1f)
                    .testTag(UiTags.PROVIDER_MODELS_ACTION_SYNC),
            )
            ActionChip(
                icon = Icons.Rounded.Add,
                label = stringResource(R.string.provider_models_add),
                isPrimary = true,
                onClick = onAdd,
                modifier = Modifier
                    .weight(1f)
                    .testTag(UiTags.PROVIDER_MODELS_ACTION_ADD),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionChip(
                icon = Icons.Rounded.Block,
                label = stringResource(R.string.provider_models_disable_all),
                onClick = onDisableAll,
                modifier = Modifier
                    .weight(1f)
                    .testTag(UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL),
            )
            ActionChip(
                icon = Icons.Rounded.Delete,
                label = stringResource(R.string.provider_models_delete_all),
                isDanger = true,
                onClick = onDeleteAll,
                modifier = Modifier
                    .weight(1f)
                    .testTag(UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL),
            )
        }
    }
}

@Composable
internal fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isPrimary: Boolean = false,
    isDanger: Boolean = false,
    iconModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bgColor = when {
        isDanger -> NexaraColors.ErrorContainer.copy(alpha = 0.3f)
        isPrimary -> NexaraColors.InversePrimary
        else -> NexaraColors.SurfaceHigh
    }
    val contentColor = when {
        isDanger -> NexaraColors.Error
        isPrimary -> NexaraColors.OnSurface
        else -> NexaraColors.OnSurface
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(NexaraShapes.medium)
                .background(bgColor)
                .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier
                        .size(16.dp)
                        .then(iconModifier),
                )
                Text(
                    text = label,
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = contentColor,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun EnhancedModelCard(
    model: ModelInfo,
    testState: ModelTestState,
    onUpdate: (ModelInfo) -> Unit,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTesting = testState == ModelTestState.Testing
    var selectedType by remember(model.id) { mutableStateOf(model.type) }
    var editName by remember(model.id) { mutableStateOf(model.name) }
    val editId = model.id
    var editContext by remember(model.id) { mutableStateOf(model.contextLength.toString()) }
    var activeCaps by remember(model.id) { mutableStateOf(model.capabilities.toSet()) }

    LaunchedEffect(
        model.id,
        model.type,
        model.name,
        model.contextLength,
        model.capabilities,
    ) {
        selectedType = model.type
        editName = model.name
        editContext = model.contextLength.toString()
        activeCaps = model.capabilities.toSet()
    }

    // type 变更时联动刷新基础能力（chat/reasoning/image/embedding/rerank），
    // 同时保留用户手动选择的修饰能力（vision, internet）
    LaunchedEffect(selectedType) {
        val oldBase = TypeToBaseCaps[model.type] ?: setOf("chat")
        val newBase = TypeToBaseCaps[selectedType] ?: setOf("chat")
        if (oldBase != newBase) {
            activeCaps = (activeCaps - AllBaseCapKeys) + newBase
        }
    }

    LaunchedEffect(selectedType, editName, editContext, activeCaps) {
        val updated = model.copy(
            type = selectedType,
            name = editName,
            contextLength = editContext.toIntOrNull() ?: model.contextLength,
            capabilities = activeCaps.toList(),
        )
        if (updated != model) {
            onUpdate(updated)
        }
    }

    val rotation by animateFloatAsState(
        targetValue = if (isTesting) 360f else 0f,
        animationSpec = tween(1000),
        label = "testRotation",
    )
    val resultColor = when (testState) {
        is ModelTestState.Success -> NexaraColors.StatusSuccess
        is ModelTestState.Error -> NexaraColors.StatusError
        else -> NexaraColors.Primary
    }
    val testStatusMessage = when (testState) {
        ModelTestState.Testing -> stringResource(R.string.shared_loading)
        is ModelTestState.Success -> stringResource(
            R.string.provider_models_test_success,
            "${testState.latencyMs}ms",
        )
        is ModelTestState.Error -> modelTestErrorMessage(testState)
        else -> null
    }

    NexaraGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NexaraColors.SurfaceLow.copy(alpha = 0.5f))
                        .border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .sizeIn(minHeight = 48.dp)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        singleLine = false,
                        maxLines = 2,
                        textStyle = NexaraTypography.headlineSmall.copy(
                            color = NexaraColors.OnSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        cursorBrush = SolidColor(NexaraColors.Primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (editName.isEmpty()) {
                        Text(
                            text = model.name,
                            style = NexaraTypography.headlineSmall.copy(fontSize = 15.sp),
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = editId,
                    style = NexaraTypography.bodyMedium.copy(
                        fontSize = 11.sp,
                        fontFamily = SpaceGrotesk,
                        color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (testStatusMessage != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = testStatusMessage,
                        style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                        color = resultColor,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = testStatusMessage
                        },
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onTest,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag(UiTags.providerModelsTestAction(model.id)),
                    ) {
                        Icon(
                            imageVector = if (isTesting) Icons.Rounded.Sync else Icons.Rounded.Bolt,
                            contentDescription = if (isTesting) {
                                stringResource(R.string.settings_btn_cancel)
                            } else {
                                stringResource(R.string.provider_models_cd_test)
                            },
                            tint = resultColor,
                            modifier = Modifier
                                .size(16.dp)
                                .then(if (isTesting) Modifier.rotate(rotation) else Modifier),
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag(UiTags.providerModelsDeleteAction(model.id)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.shared_btn_delete),
                            tint = NexaraColors.StatusError.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    Switch(
                        checked = model.enabled,
                        onCheckedChange = { onToggle() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(48.dp)
                            .testTag(UiTags.providerModelsToggleAction(model.id)),
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = NexaraColors.Primary,
                            checkedThumbColor = NexaraColors.OnPrimary,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(NexaraColors.GlassBorder),
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Model Type & Capabilities
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(NexaraColors.SurfaceLow)
                            .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(6.dp))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ModelTypeLabelResources.forEachIndexed { index, labelRes ->
                            val type = ModelTypes[index]
                            val isSelected = selectedType == type
                            val chipBg by animateColorAsState(
                                targetValue = if (isSelected) NexaraColors.SurfaceHighest else NexaraColors.SurfaceLow.copy(alpha = 0f),
                                animationSpec = tween(200),
                                label = "typeChipBg",
                            )

                            Box(
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics { selected = isSelected }
                                    .clickable(role = Role.RadioButton) { selectedType = type },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(chipBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = stringResource(labelRes),
                                        style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                        color = if (isSelected) NexaraColors.OnSurface else NexaraColors.Outline,
                                    )
                                }
                            }
                        }
                    }
                }

                // Capabilities Row (Multi-select)
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CapabilityTags.filter { 
                        it.key !in listOf("reasoning", "image", "embedding", "rerank")
                    }.forEach { cap ->
                        val isActive = activeCaps.contains(cap.key)
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { selected = isActive }
                                .clickable(role = Role.Checkbox) {
                                    activeCaps = if (isActive) activeCaps - cap.key
                                    else activeCaps + cap.key
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isActive) cap.color.copy(alpha = 0.15f)
                                        else NexaraColors.GlassSurface,
                                    )
                                    .border(
                                        0.5.dp,
                                        if (isActive) cap.color.copy(alpha = 0.3f) else NexaraColors.GlassBorder,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = stringResource(cap.labelRes),
                                    style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                    color = if (isActive) cap.color else NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.provider_models_context_label),
                    style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                    color = NexaraColors.OnSurfaceVariant,
                )

                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NexaraColors.SurfaceLow)
                        .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(4.dp))
                        .sizeIn(minHeight = 48.dp)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    BasicTextField(
                        value = editContext,
                        onValueChange = { editContext = it },
                        singleLine = true,
                        textStyle = NexaraTypography.bodyMedium.copy(
                            fontSize = 11.sp,
                            fontFamily = SpaceGrotesk,
                            color = NexaraColors.OnSurface,
                        ),
                        cursorBrush = SolidColor(NexaraColors.Primary),
                    )
                }

                Text(
                    text = stringResource(R.string.provider_models_tokens_unit),
                    style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            // 最大输出 / 知识截止日期（来自 ModelSpec 数据库自动匹配）
            if (model.maxOutputTokens > 0 || model.knowledgeCutoff != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (model.maxOutputTokens > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NexaraColors.Primary.copy(alpha = 0.08f))
                                .border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.provider_models_output_tokens,
                                    formatTokens(model.maxOutputTokens),
                                ),
                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                color = NexaraColors.Primary,
                            )
                        }
                    }
                    if (model.knowledgeCutoff != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NexaraColors.StatusWarning.copy(alpha = 0.08f))
                                .border(0.5.dp, NexaraColors.StatusWarning.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.provider_models_knowledge_cutoff,
                                    model.knowledgeCutoff!!,
                                ),
                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                color = NexaraColors.StatusWarning,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTokens(tokens: Int): String = when {
    tokens >= 128000 -> "${tokens / 1000}K"
    tokens >= 1000 -> "${"%.1f".format(tokens / 1000.0)}k"
    else -> "$tokens"
}

@Composable
private fun modelTestErrorMessage(error: ModelTestState.Error): String = when (error.code) {
    com.promenar.nexara.domain.generation.GenerationFailureCode.NETWORK ->
        stringResource(R.string.generation_failure_network)
    com.promenar.nexara.domain.generation.GenerationFailureCode.AUTH ->
        stringResource(R.string.generation_failure_auth)
    com.promenar.nexara.domain.generation.GenerationFailureCode.RATE_LIMIT ->
        error.retryAfterSeconds?.takeIf { it > 0 }?.let {
            stringResource(R.string.generation_failure_rate_limit_retry, it)
        } ?: stringResource(R.string.generation_failure_rate_limit)
    com.promenar.nexara.domain.generation.GenerationFailureCode.QUOTA ->
        stringResource(R.string.generation_failure_quota)
    com.promenar.nexara.domain.generation.GenerationFailureCode.TIMEOUT ->
        stringResource(R.string.generation_failure_timeout)
    com.promenar.nexara.domain.generation.GenerationFailureCode.INVALID_REQUEST ->
        stringResource(R.string.generation_failure_invalid_request)
    com.promenar.nexara.domain.generation.GenerationFailureCode.SERVER ->
        stringResource(R.string.generation_failure_server)
    else -> stringResource(R.string.generation_failure_unknown)
}

@Composable
internal fun ModelSyncNoticeBanner(
    notice: UiStatusNotice,
    dismissEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    val color = noticeColor(notice.severity)
    val message = notice.formatSyncMessage(
        unknownFallback = stringResource(R.string.provider_models_sync_failed),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.container)
            .border(0.5.dp, color.border, RoundedCornerShape(8.dp))
            .semantics {
                liveRegion = if (notice.severity == NoticeSeverity.Error) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
                stateDescription = message
            }
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = message,
                style = NexaraTypography.labelMedium.copy(fontSize = 12.sp),
                color = color.foreground,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                enabled = dismissEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(UiTags.PROVIDER_MODELS_NOTICE_DISMISS),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.shared_btn_close),
                    tint = color.foreground.copy(alpha = if (dismissEnabled) 0.8f else 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderModelsListMessage(
    message: String,
    tag: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp)
            .testTag(tag)
            .semantics(mergeDescendants = true) { stateDescription = message },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant,
        )
    }
}

/**
 * 据 [NoticeSeverity] 派生反馈条颜色，**不依赖任何展示字符串**。
 * Success→绿、Warning→琥珀、Error→红、Info→主题色。
 */
private fun noticeColor(severity: NoticeSeverity): NoticeColor = when (severity) {
    NoticeSeverity.Success -> NoticeColor(
        NexaraColors.StatusSuccess.copy(alpha = 0.1f),
        NexaraColors.StatusSuccess.copy(alpha = 0.3f),
        NexaraColors.StatusSuccess,
    )
    NoticeSeverity.Warning -> NoticeColor(
        NexaraColors.StatusWarning.copy(alpha = 0.1f),
        NexaraColors.StatusWarning.copy(alpha = 0.3f),
        NexaraColors.StatusWarning,
    )
    NoticeSeverity.Error -> NoticeColor(
        NexaraColors.StatusError.copy(alpha = 0.1f),
        NexaraColors.StatusError.copy(alpha = 0.3f),
        NexaraColors.StatusError,
    )
    NoticeSeverity.Info -> NoticeColor(
        NexaraColors.Primary.copy(alpha = 0.1f),
        NexaraColors.Primary.copy(alpha = 0.3f),
        NexaraColors.Primary,
    )
}

private data class NoticeColor(
    val container: Color,
    val border: Color,
    val foreground: Color,
)

/**
 * 用 stringResource 把结构化 notice 格式化为本地化文案。
 * 未知 code 回落到 [unknownFallback] 安全通用文案，**不**直接泄漏 code 或裸串。
 */
@Composable
private fun UiStatusNotice.formatSyncMessage(unknownFallback: String): String {
    val template = ModelSyncNotice.template(this) ?: return unknownFallback
    return if (template.args.isEmpty()) {
        stringResource(template.resourceId)
    } else {
        stringResource(template.resourceId, *template.args.toTypedArray())
    }
}
