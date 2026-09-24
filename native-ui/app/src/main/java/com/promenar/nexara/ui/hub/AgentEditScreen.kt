package com.promenar.nexara.ui.hub

import androidx.compose.animation.*
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
import androidx.compose.material.icons.automirrored.rounded.*
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.PresetAgents
import com.promenar.nexara.ui.avatar.rememberAvatarCropLauncher
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography

data class AgentIconOption(
    val id: String,
    val icon: ImageVector,
    val label: String
)

private val presetIcons = listOf(
    AgentIconOption("✨", Icons.Rounded.AutoAwesome, "Sparkles"),
    AgentIconOption("💻", Icons.Rounded.Code, "Code"),
    AgentIconOption("📝", Icons.Rounded.EditNote, "Note"),
    AgentIconOption("🤖", Icons.Rounded.SmartToy, "Robot"),
    AgentIconOption("🧠", Icons.Rounded.Psychology, "Brain"),
    AgentIconOption("⭐", Icons.Rounded.Star, "Star"),
    AgentIconOption("🔥", Icons.Rounded.LocalFireDepartment, "Fire"),
    AgentIconOption("💡", Icons.Rounded.Lightbulb, "Light"),
    AgentIconOption("⚡", Icons.Rounded.Bolt, "Bolt"),
    AgentIconOption("🎯", Icons.Rounded.TrackChanges, "Target")
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

    val name by viewModel.name.collectAsState()
    val description by viewModel.description.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val selectedIcon by viewModel.selectedIcon.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val avatarPath by viewModel.avatarPath.collectAsState()

    val localizedPresetName = if (PresetAgents.isPreset(agentId)) {
        stringResource(PresetAgents.nameRes(agentId))
    } else null
    val localizedPresetDescription = if (PresetAgents.isPreset(agentId)) {
        stringResource(PresetAgents.descRes(agentId))
    } else null

    var showSystemPromptEditor by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_edit_title), style = NexaraTypography.headlineLarge) },
                navigationIcon = {
                    NexaraBackButton(onClick = onNavigateBack)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f)
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = NexaraSpacing.ScreenHorizontal,
                end = NexaraSpacing.ScreenHorizontal,
                top = NexaraSpacing.Small,
                bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium)
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
                            style = NexaraTypography.bodyMedium,
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
                                .size(116.dp)
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
                            size = 88.dp,
                            onClick = avatarCropLauncher::launch,
                        )

                        // Camera floating badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 2.dp, y = 2.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                                .clickable(onClick = avatarCropLauncher::launch),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddAPhoto,
                                contentDescription = stringResource(R.string.agent_edit_icon_upload),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = name.ifBlank { stringResource(R.string.agent_edit_placeholder_name) },
                        style = NexaraTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (name.isNotBlank()) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = description,
                            style = NexaraTypography.bodyMedium,
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

            // ─── 3. 视觉与色彩工坊：图标选择 + 调色盘 + 彩虹滑块 ───
            item {
                var isExpanded by remember { mutableStateOf(false) }

                NexaraSettingsSection(
                    title = stringResource(R.string.agent_edit_section_appearance)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Icon Selection Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.agent_edit_label_icon),
                                style = NexaraTypography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { isExpanded = !isExpanded }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isExpanded) stringResource(R.string.agent_edit_icon_toggle_collapse) else stringResource(R.string.agent_edit_icon_toggle_expand),
                                    style = NexaraTypography.labelMedium.copy(color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Animated folding icon grid
                        AnimatedContent(
                            targetState = isExpanded,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "IconSelection"
                        ) { expanded ->
                            if (expanded) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    presetIcons.chunked(4).forEach { rowIcons ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            rowIcons.forEach { option ->
                                                IconSelectionItem(
                                                    option = option,
                                                    isSelected = selectedIcon == option.id && avatarPath == null,
                                                    activeColor = parsedColor,
                                                    onClick = { viewModel.setIcon(option.id) }
                                                )
                                            }
                                            repeat(4 - rowIcons.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    presetIcons.take(4).forEach { option ->
                                        IconSelectionItem(
                                            option = option,
                                            isSelected = selectedIcon == option.id && avatarPath == null,
                                            activeColor = parsedColor,
                                            onClick = { viewModel.setIcon(option.id) }
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )

                        // Color Picker (Presets + Rainbow Slider + Swatch)
                        ColorPickerPanel(
                            selectedColor = parsedColor,
                            onColorSelected = { color ->
                                val hex = String.format("#%02X%02X%02X", (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
                                viewModel.setColor(hex)
                            }
                        )
                    }
                }
            }

            // ─── 4. 人格与设定：系统提示词卡片 ───
            item {
                NexaraSettingsSection(
                    title = stringResource(R.string.agent_edit_section_personality)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSystemPromptEditor = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Category Icon Badge
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.agent_edit_prompt_label),
                                    style = NexaraTypography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(
                                            if (systemPrompt.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceContainerHigh
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (systemPrompt.isNotBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = if (systemPrompt.isNotBlank()) stringResource(R.string.agent_edit_prompt_configured) else stringResource(R.string.agent_edit_prompt_not_set),
                                            style = NexaraTypography.labelSmall.copy(fontSize = 10.sp),
                                            color = if (systemPrompt.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = systemPrompt.ifBlank { stringResource(R.string.agent_edit_prompt_hint) },
                                style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                                color = if (systemPrompt.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // ─── 5. 知识库与检索配置 ───
            item {
                NexaraSettingsSection(
                    title = stringResource(R.string.agent_edit_section_knowledge)
                ) {
                    NexaraSettingsItem(
                        icon = Icons.Rounded.Storage,
                        title = stringResource(R.string.agent_edit_rag_config),
                        subtitle = stringResource(R.string.agent_edit_rag_desc),
                        onClick = { onNavigateToRagConfig(agentId) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )
                    NexaraSettingsItem(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.agent_edit_advanced_retrieval),
                        subtitle = stringResource(R.string.agent_edit_retrieval_desc),
                        onClick = { onNavigateToAdvancedRetrieval(agentId) }
                    )
                }
            }

            // ─── 6. 危险操作：删除助手 ───
            item {
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f))
                        .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .clickable { showDeleteConfirm = true }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.agent_edit_delete_btn),
                            style = NexaraTypography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = maxLines,
            textStyle = NexaraTypography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = NexaraTypography.bodyLarge,
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
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) activeColor.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) activeColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = option.icon,
            contentDescription = option.label,
            tint = if (isSelected) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp)
        )
    }
}
