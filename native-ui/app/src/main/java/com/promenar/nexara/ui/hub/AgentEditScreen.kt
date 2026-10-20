package com.promenar.nexara.ui.hub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.PresetAgents
import com.promenar.nexara.data.model.catalog.ModelCatalogRuntime
import com.promenar.nexara.ui.avatar.rememberAvatarCropLauncher
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forInput

data class AgentIconOption(
    val id: String,
    val icon: ImageVector,
    val labelRes: Int
)

private val presetIcons = listOf(
    AgentIconOption("✨", Icons.Rounded.AutoAwesome, R.string.settings_retrieval_icon_sparkles),
    AgentIconOption("💻", Icons.Rounded.Code, R.string.settings_retrieval_icon_code),
    AgentIconOption("📝", Icons.Rounded.EditNote, R.string.settings_retrieval_icon_note),
    AgentIconOption("🤖", Icons.Rounded.SmartToy, R.string.settings_retrieval_icon_robot),
    AgentIconOption("🧠", Icons.Rounded.Psychology, R.string.settings_retrieval_icon_brain),
    AgentIconOption("⭐", Icons.Rounded.Star, R.string.settings_retrieval_icon_star),
    AgentIconOption("🔥", Icons.Rounded.LocalFireDepartment, R.string.settings_retrieval_icon_fire),
    AgentIconOption("💡", Icons.Rounded.Lightbulb, R.string.settings_retrieval_icon_light),
    AgentIconOption("⚡", Icons.Rounded.Bolt, R.string.settings_retrieval_icon_bolt),
    AgentIconOption("🎯", Icons.Rounded.TrackChanges, R.string.settings_retrieval_icon_target)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(
    agentId: String,
    onNavigateBack: () -> Unit,
    onNavigateToRagConfig: (String) -> Unit,
    onNavigateToAdvancedRetrieval: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: AgentEditViewModel = viewModel(
        factory = AgentEditViewModel.factory(context.applicationContext as android.app.Application)
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(context.applicationContext as android.app.Application)
    )

    val name by viewModel.name.collectAsState()
    val description by viewModel.description.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val selectedIcon by viewModel.selectedIcon.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val avatarPath by viewModel.avatarPath.collectAsState()
    val defaultWorkspaceId by viewModel.defaultWorkspaceId.collectAsState()
    val defaultWorkspace by viewModel.defaultWorkspace.collectAsState()

    val allModels by settingsViewModel.providerModels.collectAsState()
    val resolver = ModelCatalogRuntime.resolver
    val resolvedModels = remember(allModels, resolver) {
        allModels.map { it.toModelSelectionUiModel(resolver) }
    }
    val modelItems = remember(allModels, resolvedModels) {
        resolvedModels.filter { resolved ->
            allModels.firstOrNull { it.id == resolved.selectionId }?.enabled == true
        }
    }
    val currentModelDisplayName = remember(resolvedModels, selectedModel) {
        if (selectedModel.isBlank()) ""
        else resolvedModels.firstOrNull { it.selectionId == selectedModel }?.displayName ?: selectedModel
    }

    val localizedPresetName = if (PresetAgents.isPreset(agentId)) {
        stringResource(PresetAgents.nameRes(agentId))
    } else null
    val localizedPresetDescription = if (PresetAgents.isPreset(agentId)) {
        stringResource(PresetAgents.descRes(agentId))
    } else null

    var showSystemPromptEditor by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }

    val defaultPrimary = MaterialTheme.colorScheme.primary
    val parsedColor = remember(selectedColor, defaultPrimary) {
        try {
            Color(android.graphics.Color.parseColor(selectedColor))
        } catch (_: Exception) {
            defaultPrimary
        }
    }

    val currentIconVector = presetIcons.find { it.id == selectedIcon }?.icon ?: Icons.Rounded.AutoAwesome
    val avatarCropLauncher = rememberAvatarCropLauncher(
        onCropped = viewModel::importAvatar,
        onFailure = viewModel::reportAvatarImportFailure,
    )

    LaunchedEffect(agentId) {
        viewModel.loadAgent(agentId, localizedPresetName, localizedPresetDescription)
    }

    UnifiedPromptEditor(
        show = showSystemPromptEditor,
        onDismiss = { showSystemPromptEditor = false },
        onSave = viewModel::saveSystemPrompt,
        title = stringResource(R.string.agent_edit_prompt_label),
        initialText = systemPrompt,
        placeholder = stringResource(R.string.agent_edit_prompt_placeholder),
        mode = EditorMode.DIALOG
    )

    ConfirmDialog(
        show = showDeleteConfirm,
        onDismiss = { showDeleteConfirm = false },
        onConfirm = {
            viewModel.deleteAgent(agentId) {
                showDeleteConfirm = false
                onNavigateBack()
            }
        },
        title = stringResource(R.string.agent_edit_delete_title),
        description = stringResource(R.string.agent_edit_delete_message),
        confirmLabel = stringResource(R.string.agent_edit_delete_confirm),
        confirmColor = MaterialTheme.colorScheme.error,
        destructive = true
    )

    if (showModelPicker) {
        ModelPicker(
            show = true,
            title = stringResource(R.string.common_model_picker_title),
            filterTag = "chat",
            currentModelId = selectedModel,
            models = modelItems,
            onDismiss = { showModelPicker = false },
            onSelect = { id, _ ->
                viewModel.setModel(id)
                showModelPicker = false
            }
        )
    }

    com.promenar.nexara.ui.workspace.WorkspacePickerSheet(
        show = showWorkspacePicker,
        onDismiss = { showWorkspacePicker = false },
        currentWorkspaceId = defaultWorkspaceId,
        onPicked = { picked ->
            showWorkspacePicker = false
            viewModel.setDefaultWorkspace(picked.id)
        },
    )

    NexaraBottomSheet(
        show = showIconPicker,
        onDismiss = { showIconPicker = false },
        title = stringResource(R.string.agent_edit_label_icon),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            presetIcons.chunked(4).forEach { rowIcons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowIcons.forEach { option ->
                        IconSelectionItem(
                            option = option,
                            isSelected = selectedIcon == option.id && avatarPath == null,
                            activeColor = parsedColor,
                            onClick = {
                                viewModel.setIcon(option.id)
                                showIconPicker = false
                            },
                        )
                    }
                    repeat(4 - rowIcons.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.agent_edit_title),
        onBack = onNavigateBack,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            saveError?.let { error ->
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(
                                when (error) {
                                    AgentEditErrorCode.NOT_FOUND -> R.string.agent_edit_error_load
                                    AgentEditErrorCode.LOAD_FAILED -> R.string.agent_edit_error_load
                                    AgentEditErrorCode.SAVE_FAILED -> R.string.agent_edit_error_save
                                    AgentEditErrorCode.DELETE_FAILED -> R.string.agent_edit_error_delete
                                    AgentEditErrorCode.AVATAR_IMPORT_FAILED -> R.string.agent_edit_error_avatar
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = viewModel::retryLastFailure) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            // ─── 1. Hero 角色舞台：大头像 + 柔和光晕 + 实时身份预览 ───
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Ambient Radial Glow
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            parsedColor.copy(alpha = 0.35f),
                                            parsedColor.copy(alpha = 0.10f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        // Main Circular Avatar
                        AgentAvatar(
                            icon = if (avatarPath == null) currentIconVector else null,
                            customImageUri = avatarPath,
                            backgroundColor = parsedColor,
                            size = 72.dp,
                            onClick = avatarCropLauncher::launch,
                        )

                        IconButton(
                            onClick = avatarCropLauncher::launch,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 2.dp, y = 2.dp)
                                .size(48.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AddAPhoto,
                                    contentDescription = stringResource(R.string.agent_edit_icon_upload),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = name.ifBlank { stringResource(R.string.agent_edit_placeholder_name) },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (name.isNotBlank()) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }

            // ─── 2. 基本信息：纯净卡片式无框输入 ───
            item {
                NexaraSettingsSection(
                    title = stringResource(R.string.agent_edit_section_basic)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        InCardInputField(
                            label = stringResource(R.string.agent_edit_label_name),
                            value = name,
                            onValueChange = viewModel::setName,
                            placeholder = stringResource(R.string.agent_edit_placeholder_name),
                            singleLine = true,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )
                        InCardInputField(
                            label = stringResource(R.string.agent_edit_label_desc),
                            value = description,
                            onValueChange = viewModel::setDescription,
                            placeholder = stringResource(R.string.agent_edit_placeholder_desc),
                            singleLine = false,
                            maxLines = 3,
                        )
                    }
                }
            }

            // 图标选择在弹层中完成，颜色选择盘保留独立编辑区。
            item {
                NexaraSettingsSection(title = stringResource(R.string.agent_edit_section_appearance)) {
                    // 行首直接展示当前图标，与下方其它设置行的图标列对齐。
                    NexaraSettingsItem(
                        title = stringResource(R.string.agent_edit_label_icon),
                        subtitle = if (avatarPath != null) stringResource(R.string.agent_edit_icon_custom)
                        else stringResource(presetIcons.find { it.id == selectedIcon }?.labelRes ?: R.string.settings_retrieval_icon_sparkles),
                        onClick = { showIconPicker = true },
                        showChevron = true,
                        leadingContent = {
                            Icon(
                                imageVector = if (avatarPath != null) Icons.Rounded.Image else currentIconVector,
                                contentDescription = null,
                                tint = parsedColor,
                                modifier = Modifier.size(NexaraSpacing.RowIconSize),
                            )
                        },
                    )
                    Column(modifier = Modifier.padding(16.dp)) {
                        ColorPickerPanel(
                            selectedColor = parsedColor,
                            onColorSelected = { color ->
                                val hex = String.format("#%02X%02X%02X", (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
                                viewModel.setColor(hex)
                            },
                        )
                    }
                }
            }

            // ─── 4. 模型配置：助手专属默认模型 ───
            item {
                NexaraSettingsSection(title = stringResource(R.string.agent_edit_section_model)) {
                    NexaraSettingsItem(
                        icon = Icons.Rounded.SmartToy,
                        title = stringResource(R.string.agent_edit_current_model),
                        subtitle = currentModelDisplayName.ifBlank { stringResource(R.string.settings_not_set) },
                        onClick = { showModelPicker = true },
                        showChevron = true,
                        modifier = Modifier.testTag("agent_edit_model"),
                    )
                }
            }

            // 系统提示词以短状态呈现，完整内容由编辑器承载。
            item {
                NexaraSettingsSection(title = stringResource(R.string.agent_edit_section_personality)) {
                    // 副标题预览提示词开头两行，完整内容在编辑器中查看。
                    NexaraSettingsItem(
                        icon = Icons.Rounded.Psychology,
                        title = stringResource(R.string.agent_edit_prompt_label),
                        subtitle = systemPromptPreview(systemPrompt) ?: stringResource(R.string.agent_edit_prompt_not_set),
                        subtitleMaxLines = 2,
                        modifier = Modifier.testTag("agent_edit_prompt"),
                        onClick = { showSystemPromptEditor = true },
                        showChevron = true,
                    )
                }
            }

            item {
                com.promenar.nexara.ui.workspace.AgentDefaultWorkspaceSection(
                    workspaceId = defaultWorkspaceId,
                    workspace = defaultWorkspace,
                    onChoose = { showWorkspacePicker = true },
                    onClear = { viewModel.setDefaultWorkspace(null) },
                )
            }

            // ─── 6. 知识库与检索配置 ───
            item {
                NexaraSettingsSection(title = stringResource(R.string.agent_edit_section_knowledge)) {
                    NexaraSettingsItem(
                        icon = Icons.Rounded.Storage,
                        title = stringResource(R.string.agent_edit_rag_config),
                        subtitle = stringResource(R.string.settings_retrieval_agent_rag_short),
                        onClick = { onNavigateToRagConfig(agentId) },
                        showChevron = true,
                    )
                    NexaraSettingsItem(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.agent_edit_advanced_retrieval),
                        subtitle = stringResource(R.string.settings_retrieval_agent_advanced_short),
                        onClick = { onNavigateToAdvancedRetrieval(agentId) },
                        showChevron = true,
                    )
                }
            }

            item {
                NexaraSettingsSection(title = stringResource(R.string.settings_retrieval_agent_actions_section)) {
                    NexaraSettingsItem(
                        icon = Icons.Rounded.DeleteOutline,
                        title = stringResource(R.string.agent_edit_delete_btn),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showDeleteConfirm = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun InCardInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 3,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ).forInput(),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RowScope.IconSelectionItem(
    option: AgentIconOption,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val animatedScale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        label = "iconScale"
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .graphicsLayer(scaleX = animatedScale, scaleY = animatedScale)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) activeColor.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) activeColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = option.icon,
            contentDescription = stringResource(option.labelRes),
            tint = if (isSelected) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 系统提示词预览：去掉空行并合并多余空白，最多取 120 个字符；空白提示词返回 null。 */
internal fun systemPromptPreview(prompt: String): String? {
    val lines = prompt.lineSequence().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
    val text = lines.joinToString(" ").takeIf { it.isNotEmpty() } ?: return null
    return if (text.length > PROMPT_PREVIEW_LENGTH) text.take(PROMPT_PREVIEW_LENGTH).trimEnd() + "…" else text
}

private const val PROMPT_PREVIEW_LENGTH = 120
