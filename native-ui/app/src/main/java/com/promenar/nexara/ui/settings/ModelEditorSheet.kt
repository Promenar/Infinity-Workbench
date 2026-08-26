package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.withRecordedUserEdits
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.testing.UiTags

private val EditorModelTypes = listOf("chat", "reasoning", "image", "embedding", "rerank")
private val EditorModelTypeLabelResources = listOf(
    R.string.provider_models_type_chat,
    R.string.provider_models_type_reasoning,
    R.string.provider_models_type_image,
    R.string.provider_models_type_embedding,
    R.string.provider_models_type_rerank,
)
private val EditorTypeToBaseCaps = mapOf(
    "chat" to setOf("chat"),
    "reasoning" to setOf("chat", "reasoning"),
    "image" to setOf("image"),
    "embedding" to setOf("embedding"),
    "rerank" to setOf("rerank"),
)
private val EditorAllBaseCapKeys = EditorTypeToBaseCaps.values.flatten().toSet()
private val EditorCapabilityTags = listOf(
    "vision" to R.string.provider_models_capability_vision,
    "internet" to R.string.provider_models_capability_internet,
    "audioinput" to R.string.provider_models_capability_audio_input,
    "audiooutput" to R.string.provider_models_capability_audio_output,
    "videounderstanding" to R.string.provider_models_capability_video,
    "structuredoutput" to R.string.provider_models_capability_structured_output,
    "promptcaching" to R.string.provider_models_capability_prompt_caching,
    "computeruse" to R.string.provider_models_capability_computer_use,
)

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
internal fun ModelEditorSheet(
    model: ModelInfo,
    testState: ModelTestState,
    onDismissRequest: () -> Unit,
    onUpdate: (ModelInfo) -> Unit,
    onTest: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    renderAsModal: Boolean = true,
) {
    var showDeleteConfirmation by remember(model.id) { mutableStateOf(false) }
    var selectedType by remember(model.id) { mutableStateOf(model.type) }
    var editName by remember(model.id) { mutableStateOf(model.name) }
    var editRemoteModelId by remember(model.id) { mutableStateOf(model.remoteModelId) }
    var editContext by remember(model.id) { mutableStateOf(model.contextLength.toString()) }
    var activeCaps by remember(model.id) { mutableStateOf(model.capabilities.toSet()) }

    LaunchedEffect(
        model.id,
        model.type,
        model.name,
        model.remoteModelId,
        model.contextLength,
        model.capabilities,
    ) {
        selectedType = model.type
        editName = model.name
        editRemoteModelId = model.remoteModelId
        editContext = model.contextLength.toString()
        activeCaps = model.capabilities.toSet()
    }

    LaunchedEffect(selectedType, editName, editRemoteModelId, editContext, activeCaps) {
        val updated = model.copy(
            remoteModelId = editRemoteModelId.trim(),
            type = selectedType,
            name = editName,
            contextLength = editContext.toIntOrNull() ?: model.contextLength,
            capabilities = activeCaps.toList(),
        )
        if (updated != model) {
            onUpdate(updated.withRecordedUserEdits(model))
        }
    }

    val isTesting = testState == ModelTestState.Testing
    val status = modelTestStatus(testState)
    val statusColor = when (testState) {
        is ModelTestState.Success -> MaterialTheme.colorScheme.tertiary
        is ModelTestState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val editedFieldLabels = listOfNotNull(
        if ("remoteModelId" in model.userEditedFields) {
            stringResource(R.string.provider_models_field_model_id)
        } else {
            null
        },
        if ("name" in model.userEditedFields) {
            stringResource(R.string.provider_models_field_display_name)
        } else {
            null
        },
        if ("type" in model.userEditedFields) stringResource(R.string.provider_models_editor_type) else null,
        if ("capabilities" in model.userEditedFields) {
            stringResource(R.string.provider_models_editor_capabilities)
        } else {
            null
        },
        if ("contextLength" in model.userEditedFields) {
            stringResource(R.string.provider_models_context_label)
        } else {
            null
        },
        if ("maxOutputTokens" in model.userEditedFields) {
            stringResource(R.string.provider_models_editor_output_limit)
        } else {
            null
        },
    )
    val remoteModelId = model.remoteModelId.ifBlank { model.id.substringAfter("::", model.id) }

    val editorContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.provider_models_editor_title),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(UiTags.providerModelsEditorCloseAction(model.id)),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.shared_btn_close),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .imePadding()
                .testTag(UiTags.providerModelsEditorList(model.id)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("identity") {
                ModelEditorIdentity(
                    model = model,
                    remoteModelId = remoteModelId,
                    editedFieldLabels = editedFieldLabels,
                )
            }
            item("remote-model-id") {
                OutlinedTextField(
                    value = editRemoteModelId,
                    onValueChange = { editRemoteModelId = it },
                    label = { Text(stringResource(R.string.provider_models_field_model_id)) },
                    placeholder = { Text(stringResource(R.string.provider_models_placeholder_model_id)) },
                    supportingText = {
                        Text(stringResource(R.string.provider_models_remote_id_supporting_text))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item("name") {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text(stringResource(R.string.provider_models_field_display_name)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.providerModelsNameField(model.id)),
                    maxLines = 2,
                )
            }
            item("type") {
                SettingsSectionHeader(stringResource(R.string.provider_models_editor_type))
                Column(modifier = Modifier.fillMaxWidth()) {
                    EditorModelTypeLabelResources.forEachIndexed { index, labelRes ->
                        val type = EditorModelTypes[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp)
                                .clickable(role = Role.RadioButton) {
                                activeCaps = (activeCaps - EditorAllBaseCapKeys) +
                                    (EditorTypeToBaseCaps[type] ?: setOf("chat"))
                                selectedType = type
                            }
                                .testTag(UiTags.providerModelsTypeAction(model.id, type)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(labelRes), modifier = Modifier.weight(1f))
                            RadioButton(
                                selected = selectedType == type,
                                onClick = null,
                            )
                        }
                    }
                }
            }
            item("capabilities") {
                SettingsSectionHeader(stringResource(R.string.provider_models_editor_capabilities))
                Column(modifier = Modifier.fillMaxWidth()) {
                    EditorCapabilityTags.forEach { (key, labelRes) ->
                        val selected = key in activeCaps
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp)
                                .clickable(role = Role.Switch) {
                                activeCaps = if (selected) activeCaps - key else activeCaps + key
                            }
                                .testTag(UiTags.providerModelsCapabilityAction(model.id, key)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(labelRes), modifier = Modifier.weight(1f))
                            Switch(checked = selected, onCheckedChange = null)
                        }
                    }
                }
            }
            item("context") {
                OutlinedTextField(
                    value = editContext,
                    onValueChange = { value ->
                        if (value.all(Char::isDigit)) editContext = value
                    },
                    label = { Text(stringResource(R.string.provider_models_context_label)) },
                    suffix = { Text(stringResource(R.string.provider_models_tokens_unit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.providerModelsContextField(model.id)),
                )
            }
            if (model.maxOutputTokens > 0 || model.knowledgeCutoff != null) {
                item("limits") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (model.maxOutputTokens > 0) {
                            Text(
                                text = stringResource(
                                    R.string.provider_models_output_tokens,
                                    formatModelEditorTokens(model.maxOutputTokens),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        model.knowledgeCutoff?.let { cutoff ->
                            Text(
                                text = stringResource(R.string.provider_models_knowledge_cutoff, cutoff),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            status?.let { message ->
                item("test-status") {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = message
                        },
                    )
                }
            }
            item("test") {
                FilledTonalButton(
                    onClick = if (isTesting) onCancel else onTest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag(UiTags.providerModelsEditorTestAction(model.id)),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Bolt, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (isTesting) R.string.settings_btn_cancel else R.string.provider_models_cd_test,
                        ),
                    )
                }
            }
            item("delete") {
                TextButton(
                    onClick = { showDeleteConfirmation = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag(UiTags.providerModelsEditorDeleteAction(model.id)),
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.shared_btn_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        }
    }

    if (renderAsModal) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
            modifier = modifier.testTag(UiTags.providerModelsEditorSheet(model.id)),
        ) {
            editorContent()
        }
    } else {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .testTag(UiTags.providerModelsEditorSheet(model.id)),
            color = MaterialTheme.colorScheme.background,
        ) {
            editorContent()
        }
    }

    if (showDeleteConfirmation) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showDeleteConfirmation = false }) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.testTag(UiTags.providerModelsEditorDeleteConfirmDialog(model.id)),
            ) {
                NexaraConfirmDialog(
                    title = stringResource(R.string.shared_btn_delete),
                    message = model.name,
                    confirmText = stringResource(R.string.shared_btn_delete),
                    confirmButtonModifier = Modifier.testTag(
                        UiTags.providerModelsEditorDeleteConfirmButton(model.id),
                    ),
                    isDestructive = true,
                    onConfirm = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    onCancel = { showDeleteConfirmation = false },
                )
            }
        }
    }
}

@Composable
private fun ModelEditorIdentity(
    model: ModelInfo,
    remoteModelId: String,
    editedFieldLabels: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSectionHeader(stringResource(R.string.provider_models_editor_identity))
        Text(
            text = remoteModelId,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model.canonicalModelId?.let { canonicalId ->
            Text(
                text = stringResource(R.string.provider_models_editor_canonical_id, canonicalId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (editedFieldLabels.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(
                    R.string.provider_models_editor_user_overrides,
                    editedFieldLabels.joinToString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.providerModelsEditorSource(model.id)),
            )
        }
    }
}

@Composable
private fun modelTestStatus(testState: ModelTestState): String? = when (testState) {
    ModelTestState.Idle -> null
    ModelTestState.Testing -> stringResource(R.string.shared_loading)
    is ModelTestState.Success -> stringResource(
        R.string.provider_models_test_success,
        "${testState.latencyMs}ms",
    )
    is ModelTestState.Error -> when (testState.code) {
        GenerationFailureCode.NETWORK -> stringResource(R.string.generation_failure_network)
        GenerationFailureCode.AUTH -> stringResource(R.string.generation_failure_auth)
        GenerationFailureCode.RATE_LIMIT -> testState.retryAfterSeconds?.takeIf { it > 0 }?.let {
            stringResource(R.string.generation_failure_rate_limit_retry, it)
        } ?: stringResource(R.string.generation_failure_rate_limit)
        GenerationFailureCode.QUOTA -> stringResource(R.string.generation_failure_quota)
        GenerationFailureCode.TIMEOUT -> stringResource(R.string.generation_failure_timeout)
        GenerationFailureCode.INVALID_REQUEST -> stringResource(R.string.generation_failure_invalid_request)
        GenerationFailureCode.SERVER -> stringResource(R.string.generation_failure_server)
        else -> stringResource(R.string.generation_failure_unknown)
    }
}

private fun formatModelEditorTokens(tokens: Int): String = when {
    tokens >= 128000 -> "${tokens / 1000}K"
    tokens >= 1000 -> "${"%.1f".format(tokens / 1000.0)}k"
    else -> "$tokens"
}
