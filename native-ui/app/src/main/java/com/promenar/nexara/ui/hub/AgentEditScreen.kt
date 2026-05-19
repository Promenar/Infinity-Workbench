package com.promenar.nexara.ui.hub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.ui.common.NexaraBackButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
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

    var showSystemPromptEditor by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val parsedColor = try {
        Color(android.graphics.Color.parseColor(selectedColor))
    } catch (_: Exception) {
        NexaraColors.Primary
    }

    val currentIconVector = presetIcons.find { it.id == selectedIcon }?.icon ?: Icons.Rounded.AutoAwesome

    LaunchedEffect(agentId) {
        viewModel.loadAgent(agentId)
    }

    UnifiedPromptEditor(
        show = showSystemPromptEditor,
        onDismiss = { showSystemPromptEditor = false },
        onSave = {
            viewModel.setSystemPrompt(it)
            showSystemPromptEditor = false
        },
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
        confirmColor = NexaraColors.Error,
        destructive = true
    )

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_edit_title), style = NexaraTypography.headlineLarge) },
                navigationIcon = {
                    NexaraBackButton(onClick = onNavigateBack)
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
            item {
                SettingsSectionHeader(stringResource(R.string.agent_edit_section_basic))
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
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

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSectionHeader(stringResource(R.string.agent_edit_section_appearance))
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    var isExpanded by remember { mutableStateOf(false) }
                    val avatarPath by viewModel.avatarPath.collectAsState()
                    
                    val imagePickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        uri?.let { viewModel.setAvatarPath(it.toString()) }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Avatar Preview & Main Action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(contentAlignment = Alignment.BottomEnd) {
                                AgentAvatar(
                                    icon = if (avatarPath == null) currentIconVector else null,
                                    customImageUri = avatarPath,
                                    backgroundColor = parsedColor,
                                    size = 100.dp,
                                    onClick = { imagePickerLauncher.launch("image/*") }
                                )
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(NexaraColors.Primary)
                                        .border(2.dp, NexaraColors.SurfaceContainer, CircleShape)
                                        .clickable { imagePickerLauncher.launch("image/*") },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AddAPhoto,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

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
                                color = NexaraColors.OnSurface
                            )
                            
                            Row(
                                modifier = Modifier.clickable { isExpanded = !isExpanded },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isExpanded) "收起" else "查看全部",
                                    style = NexaraTypography.labelMedium.copy(color = NexaraColors.Primary, fontSize = 12.sp)
                                )
                                Icon(
                                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = NexaraColors.Primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Dynamic Folding Grid/Row
                        AnimatedContent(
                            targetState = isExpanded,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                            label = "IconSelection"
                        ) { expanded ->
                            if (expanded) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    presetIcons.chunked(4).forEach { rowIcons ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSectionHeader(stringResource(R.string.agent_edit_section_personality))
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                NexaraGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSystemPromptEditor = true },
                    shape = RoundedCornerShape(12.dp)
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
                        Text(
                            text = stringResource(R.string.agent_edit_prompt_label),
                            style = NexaraTypography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = NexaraColors.OnSurface
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (systemPrompt.isNotBlank()) NexaraColors.Primary.copy(alpha = 0.15f)
                                    else NexaraColors.GlassSurface
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (systemPrompt.isNotBlank()) stringResource(R.string.agent_edit_prompt_configured) else stringResource(R.string.agent_edit_prompt_not_set),
                                    style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                    color = if (systemPrompt.isNotBlank()) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = systemPrompt.ifBlank { stringResource(R.string.agent_edit_prompt_hint) },
                            style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                            color = if (systemPrompt.isNotBlank()) NexaraColors.OnSurfaceVariant else NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 2
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSectionHeader(stringResource(R.string.agent_edit_section_knowledge))
                Spacer(modifier = Modifier.height(8.dp))
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

            item {
                Spacer(modifier = Modifier.height(32.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .background(NexaraColors.Error.copy(alpha = 0.05f))
                        .clickable { showDeleteConfirm = true }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = NexaraColors.Error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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
}

@Composable
private fun RowScope.IconSelectionItem(
    option: AgentIconOption,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(NexaraColors.GlassSurface)
            .then(
                if (isSelected) Modifier.border(2.dp, activeColor, RoundedCornerShape(12.dp))
                else Modifier.border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(12.dp))
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = option.icon,
            contentDescription = option.label,
            tint = if (isSelected) activeColor else NexaraColors.OnSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
    }
}
