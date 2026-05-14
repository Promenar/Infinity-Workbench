package com.promenar.nexara.ui.hub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.theme.NexaraColors
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
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val selectedIcon by viewModel.selectedIcon.collectAsState()
    val temperature by viewModel.temperature.collectAsState()
    val topP by viewModel.topP.collectAsState()

    var showSystemPromptEditor by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showColorSheet by remember { mutableStateOf(false) }

    val currentPresetId by remember(temperature, topP) {
        derivedStateOf {
            when {
                temperature == 0.2f && topP == 0.8f -> "precise"
                temperature == 1.0f && topP == 0.95f -> "creative"
                else -> "balanced"
            }
        }
    }

    val parsedColor = try {
        Color(android.graphics.Color.parseColor(selectedColor))
    } catch (_: Exception) {
        NexaraColors.Primary
    }

    val currentIconVector = presetIcons.find { it.id == selectedIcon }?.icon ?: Icons.Rounded.AutoAwesome

    LaunchedEffect(agentId) {
        viewModel.loadAgent(agentId)
    }

    FloatingTextEditor(
        show = showSystemPromptEditor,
        onDismiss = { showSystemPromptEditor = false },
        onSave = {
            viewModel.setSystemPrompt(it)
            showSystemPromptEditor = false
        },
        title = stringResource(R.string.agent_edit_prompt_label),
        initialText = systemPrompt,
        placeholder = stringResource(R.string.agent_edit_prompt_placeholder)
    )

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(context.applicationContext as android.app.Application)
    )

    val allModels by settingsViewModel.providerModels.collectAsState()
    val modelItems = remember(allModels) {
        allModels.filter { it.enabled }.map { info ->
            ModelItem(
                id = info.id,
                name = info.name,
                providerName = "",
                capabilities = info.capabilities.mapNotNull { capStr ->
                    try { ModelCapability.valueOf(capStr.uppercase()) } catch (_: Exception) { null }
                },
                contextLength = info.contextLength
            )
        }
    }

    ModelPicker(
        show = showModelPicker,
        onDismiss = { showModelPicker = false },
        filterTag = "chat",
        models = modelItems,
        onSelect = { modelId, modelName ->
            viewModel.setModel(modelId)
            showModelPicker = false
        },
        currentModelId = selectedModel
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
        confirmColor = NexaraColors.Error,
        destructive = true
    )

    if (showColorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showColorSheet = false },
            containerColor = NexaraColors.SurfaceContainer
        ) {
            ColorPickerPanel(
                selectedColor = parsedColor,
                onColorSelected = { color ->
                    val hex = String.format(
                        "#%02X%02X%02X",
                        (color.red * 255).toInt(),
                        (color.green * 255).toInt(),
                        (color.blue * 255).toInt()
                    )
                    viewModel.setColor(hex)
                }
            )
        }
    }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_edit_title), style = NexaraTypography.headlineLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_cd_back),
                            tint = NexaraColors.OnSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f)
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = 8.dp, bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // === Section: Basic Info ===
            item {
                SettingsSectionHeader(stringResource(R.string.agent_edit_section_basic))
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = NexaraColors.SurfaceHighest)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsInput(
                            value = name,
                            onValueChange = { viewModel.setName(it) },
                            label = stringResource(R.string.agent_edit_label_name),
                            placeholder = stringResource(R.string.agent_edit_placeholder_name)
                        )
                        SettingsInput(
                            value = description,
                            onValueChange = { viewModel.setDescription(it) },
                            label = stringResource(R.string.agent_edit_label_desc),
                            placeholder = stringResource(R.string.agent_edit_placeholder_desc),
                            singleLine = false,
                            maxLines = 3
                        )
                    }
                }
            }

            // === Section: Appearance ===
            item {
                HorizontalDivider(color = NexaraColors.OutlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                SettingsSectionHeader(stringResource(R.string.agent_edit_section_appearance))
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = NexaraColors.SurfaceHighest)
                ) {
                    val avatarPath by viewModel.avatarPath.collectAsState()

                    val imagePickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        uri?.let { viewModel.setAvatarPath(it.toString()) }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            AgentAvatar(
                                icon = if (avatarPath == null) currentIconVector else null,
                                customImageUri = avatarPath,
                                backgroundColor = parsedColor,
                                size = 48.dp,
                                onClick = { imagePickerLauncher.launch("image/*") }
                            )
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(NexaraColors.Primary)
                                    .border(1.5.dp, NexaraColors.SurfaceContainer, CircleShape)
                                    .clickable { imagePickerLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AddAPhoto,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(16.dp))

                        Column(Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.agent_edit_label_icon),
                                    style = NexaraTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = NexaraColors.OnSurface
                                )
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showColorSheet = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(parsedColor)
                                            .border(1.dp, NexaraColors.OutlineVariant, CircleShape)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Palette,
                                        contentDescription = null,
                                        tint = NexaraColors.OnSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                presetIcons.forEach { option ->
                                    FilterChip(
                                        selected = selectedIcon == option.id && avatarPath == null,
                                        onClick = { viewModel.setIcon(option.id) },
                                        label = {
                                            Icon(
                                                option.icon,
                                                contentDescription = option.label,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = NexaraColors.Primary.copy(alpha = 0.15f),
                                            selectedLabelColor = NexaraColors.Primary,
                                            containerColor = NexaraColors.SurfaceContainer
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // === Section: Personality ===
            item {
                HorizontalDivider(color = NexaraColors.OutlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                SettingsSectionHeader(stringResource(R.string.agent_edit_section_personality))
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = NexaraColors.SurfaceHighest)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.agent_edit_prompt_label),
                                    style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = NexaraColors.OnSurface
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (systemPrompt.isNotBlank()) NexaraColors.Primary.copy(alpha = 0.15f)
                                            else NexaraColors.SurfaceContainer
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${systemPrompt.length} chars",
                                        style = NexaraTypography.labelSmall,
                                        color = if (systemPrompt.isNotBlank()) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { showSystemPromptEditor = true }) {
                                Text(
                                    text = stringResource(R.string.shared_btn_edit),
                                    style = NexaraTypography.labelMedium,
                                    color = NexaraColors.Primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = systemPrompt.ifBlank { stringResource(R.string.agent_edit_prompt_hint) },
                            style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                            color = if (systemPrompt.isNotBlank()) NexaraColors.OnSurfaceVariant else NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 5
                        )
                    }
                }
            }

            // === Section: Model ===
            item {
                HorizontalDivider(color = NexaraColors.OutlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                SettingsSectionHeader(stringResource(R.string.agent_edit_section_model))
            }

            item {
                Card(
                    onClick = { showModelPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = NexaraColors.SurfaceHighest)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.agent_edit_current_model),
                                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = NexaraColors.OnSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = selectedModel,
                                style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = NexaraColors.Outline
                        )
                    }
                }
            }

            // Inference Presets as FilterChips
            item {
                val presets = remember {
                    listOf(
                        InferencePreset("precise", R.string.common_preset_precise, Icons.Rounded.Code, Color(0xFFA78BFA), 0.2f, 0.8f),
                        InferencePreset("balanced", R.string.common_preset_balanced, Icons.Rounded.AutoFixHigh, Color(0xFF22D3EE), 0.7f, 0.9f),
                        InferencePreset("creative", R.string.common_preset_creative, Icons.AutoMirrored.Rounded.MenuBook, Color(0xFFFBBF24), 1.0f, 0.95f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = currentPresetId == preset.id,
                            onClick = {
                                viewModel.setTemperature(preset.temperature)
                                viewModel.setTopP(preset.topP)
                            },
                            label = {
                                Text(
                                    text = stringResource(preset.labelRes),
                                    style = NexaraTypography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    preset.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = preset.iconTint
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NexaraColors.Primary.copy(alpha = 0.15f),
                                selectedLabelColor = NexaraColors.Primary,
                                containerColor = NexaraColors.SurfaceContainer
                            )
                        )
                    }
                }
            }

            // === Section: Knowledge ===
            item {
                HorizontalDivider(color = NexaraColors.OutlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                SettingsSectionHeader(stringResource(R.string.agent_edit_section_knowledge))
            }

            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Storage,
                    title = stringResource(R.string.agent_edit_rag_config),
                    subtitle = stringResource(R.string.agent_edit_rag_desc),
                    onClick = { onNavigateToRagConfig(agentId) }
                )
            }

            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.agent_edit_advanced_retrieval),
                    subtitle = stringResource(R.string.agent_edit_retrieval_desc),
                    onClick = { onNavigateToAdvancedRetrieval(agentId) }
                )
            }

            // Delete
            item {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = NexaraColors.Error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.agent_edit_delete_btn),
                        style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = NexaraColors.Error
                    )
                }
            }
        }
    }
}
