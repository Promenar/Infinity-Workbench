package com.promenar.nexara.ui.settings

import com.promenar.nexara.data.model.ModelInfo
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.common.status.UiStatusNotice
import com.promenar.nexara.ui.testing.UiTags

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
)

private val CapabilityTags = listOf(
    CapabilityTag("vision", R.string.provider_models_capability_vision),
    CapabilityTag("internet", R.string.provider_models_capability_internet),
    CapabilityTag("audioinput", R.string.provider_models_capability_audio_input),
    CapabilityTag("audiooutput", R.string.provider_models_capability_audio_output),
    CapabilityTag("videounderstanding", R.string.provider_models_capability_video),
    CapabilityTag("structuredoutput", R.string.provider_models_capability_structured_output),
    CapabilityTag("promptcaching", R.string.provider_models_capability_prompt_caching),
    CapabilityTag("computeruse", R.string.provider_models_capability_computer_use),
)

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
        onUpdate = { viewModel.updateUserModel(it) },
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var searchQuery by rememberSaveable(state.providerId) { mutableStateOf("") }
    var selectedModelId by rememberSaveable(state.providerId) { mutableStateOf<String?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    // 选中项始终从最新同步结果派生，删除或同步移除后不会继续编辑旧快照。
    val selectedModel = remember(state.models, selectedModelId) {
        state.models.firstOrNull { it.id == selectedModelId }
    }
    LaunchedEffect(selectedModelId, selectedModel) {
        if (selectedModelId != null && selectedModel == null) {
            selectedModelId = null
        }
    }

    val filteredModels = remember(state.models, searchQuery) {
        if (searchQuery.isBlank()) state.models
        else state.models.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.remoteModelId.contains(searchQuery, ignoreCase = true) ||
            it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    NexaraSettingsPageLayout(
        title = state.providerName,
        onBack = onNavigateBack,
        modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_SCREEN_ROOT),
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
        ) {
        NexaraSearchBar(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = stringResource(R.string.provider_models_search),
            modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD),
        )

        Spacer(modifier = Modifier.height(12.dp))

        ProviderModelsTopActions(
            isFetching = state.isFetching,
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
            verticalArrangement = Arrangement.spacedBy(0.dp),
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
                        assertive = true,
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
                        message = stringResource(R.string.provider_models_search) +
                            " · " + stringResource(R.string.common_model_picker_empty),
                        tag = UiTags.PROVIDER_MODELS_STATE_SEARCH_EMPTY,
                    )
                }

                ProviderModelsListState.Content -> itemsIndexed(
                    filteredModels,
                    key = { _, model -> model.id },
                ) { index, model ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    ProviderModelsModelRow(
                        model = model,
                        onRowClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            selectedModelId = model.id
                        },
                        onToggle = { actions.onToggle(model.id) },
                    )
                }
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
        ProviderModelsAddSheet(
            onDismiss = { showAddDialog = false },
            onSubmit = { id, name ->
                val added = actions.onAdd(id, name)
                if (added) {
                    showAddDialog = false
                }
                added
            },
            onAdded = { showAddDialog = false },
        )
    }

    selectedModel?.let { model ->
        ModelEditorSheet(
            model = model,
            testState = state.modelTestStates[model.id] ?: ModelTestState.Idle,
            onDismissRequest = { selectedModelId = null },
            onUpdate = actions.onUpdate,
            onTest = { actions.onTest(model.id) },
            onCancel = { actions.onCancelTest(model.id) },
            onDelete = {
                actions.onDelete(model.id)
                selectedModelId = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelsAddSheet(
    onDismiss: () -> Unit,
    onSubmit: (id: String, name: String) -> Boolean,
    onAdded: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ADD_SHEET),
    ) {
        Text(
            text = stringResource(R.string.provider_models_add_custom),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        AddCustomModelForm(onSubmit = onSubmit, onAdded = onAdded)
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

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .testTag(UiTags.PROVIDER_MODELS_ADD_FORM_LIST),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("model-id") {
            OutlinedTextField(
                value = modelId,
                onValueChange = {
                    modelId = it
                    submissionFailed = false
                },
                label = { Text(stringResource(R.string.provider_models_field_model_id)) },
                placeholder = { Text(stringResource(R.string.provider_models_placeholder_model_id)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.PROVIDER_MODELS_ADD_ID_FIELD),
            )
        }
        item("model-name") {
            OutlinedTextField(
                value = modelName,
                onValueChange = {
                    modelName = it
                    submissionFailed = false
                },
                label = { Text(stringResource(R.string.provider_models_field_display_name)) },
                placeholder = { Text(stringResource(R.string.provider_models_placeholder_optional)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.PROVIDER_MODELS_ADD_NAME_FIELD),
            )
        }
        if (submissionFailed) {
            item("submission-error") {
                val failureMessage = stringResource(R.string.generation_failure_invalid_request)
                Text(
                    text = failureMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Assertive
                        stateDescription = failureMessage
                    },
                )
            }
        }
        item("submit") {
            Button(
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
            ) {
                Text(stringResource(R.string.shared_btn_add))
            }
        }
    }
}

@Composable
internal fun ProviderModelsTopActions(
    isFetching: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onDisableAll: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = onRefresh,
            enabled = !isFetching,
            modifier = Modifier
                .weight(1f)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag(UiTags.PROVIDER_MODELS_ACTION_SYNC),
        ) {
            if (isFetching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = null,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.provider_models_auto_fetch), maxLines = 2)
        }
        Button(
            onClick = onAdd,
            modifier = Modifier
                .weight(1f)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag(UiTags.PROVIDER_MODELS_ACTION_ADD),
        ) {
            Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.provider_models_add), maxLines = 2)
        }
        Box {
            IconButton(
                onClick = { overflowExpanded = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag(UiTags.PROVIDER_MODELS_ACTION_OVERFLOW),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.chat_cd_options),
                )
            }
            DropdownMenu(
                expanded = overflowExpanded,
                onDismissRequest = { overflowExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.provider_models_disable_all)) },
                    leadingIcon = { Icon(Icons.Rounded.Block, contentDescription = null) },
                    onClick = {
                        overflowExpanded = false
                        onDisableAll()
                    },
                    modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL),
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.provider_models_delete_all),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        overflowExpanded = false
                        onDeleteAll()
                    },
                    modifier = Modifier.testTag(UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ProviderModelsModelRow(
    model: ModelInfo,
    onRowClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remoteModelId = remember(model.id, model.remoteModelId) {
        model.remoteModelId.ifBlank { model.id.substringAfter("::", model.id) }
    }
    val summaryCapabilities = remember(model.capabilities, model.type) {
        model.capabilities
            .filterNot { it == "chat" }
            .ifEmpty { listOf(model.type) }
            .distinct()
    }
    val localizedCapabilities = mutableListOf<String>()
    for (capability in summaryCapabilities) {
        val labelResource = capabilityLabelResource(capability)
        localizedCapabilities += if (labelResource != null) stringResource(labelResource) else capability
    }
    val capabilitySummary = localizedCapabilities.joinToString(" · ")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onRowClick)
            .testTag(UiTags.providerModelsModelCard(model.id))
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = model.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = remoteModelId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .testTag(UiTags.providerModelsRemoteId(model.id))
                .semantics { contentDescription = remoteModelId },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = capabilitySummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = model.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .testTag(UiTags.providerModelsToggleAction(model.id)),
            )
        }
    }
}

private fun capabilityLabelResource(capability: String): Int? {
    val typeIndex = ModelTypes.indexOf(capability)
    return if (typeIndex >= 0) {
        ModelTypeLabelResources[typeIndex]
    } else {
        CapabilityTags.firstOrNull { it.key == capability }?.labelRes
    }
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics {
                liveRegion = if (notice.severity == NoticeSeverity.Error) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
                stateDescription = message
            },
        color = color.container,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
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
    assertive: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics(mergeDescendants = true) {
                liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                stateDescription = message
            },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 据 [NoticeSeverity] 派生反馈条颜色，**不依赖任何展示字符串**。
 * Success→绿、Warning→琥珀、Error→红、Info→主题色。
 */
@Composable
private fun noticeColor(severity: NoticeSeverity): NoticeColor {
    val colors = MaterialTheme.colorScheme
    return when (severity) {
        NoticeSeverity.Success -> NoticeColor(
            colors.primaryContainer,
            colors.primary.copy(alpha = 0.3f),
            colors.onPrimaryContainer,
        )
        NoticeSeverity.Warning -> NoticeColor(
            colors.tertiaryContainer,
            colors.tertiary.copy(alpha = 0.3f),
            colors.onTertiaryContainer,
        )
        NoticeSeverity.Error -> NoticeColor(
            colors.errorContainer,
            colors.error.copy(alpha = 0.3f),
            colors.onErrorContainer,
        )
        NoticeSeverity.Info -> NoticeColor(
            colors.secondaryContainer,
            colors.secondary.copy(alpha = 0.3f),
            colors.onSecondaryContainer,
        )
    }
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
