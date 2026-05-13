package com.promenar.nexara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.CollapsibleSection
import com.promenar.nexara.ui.common.ColorPickerPanel
import com.promenar.nexara.ui.common.InferencePresets
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.rag.RagViewModel
import com.promenar.nexara.ui.common.SettingsInput
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SpaSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRagConfig: () -> Unit = {},
    onNavigateToAdvancedRetrieval: () -> Unit = {}
) {
    val context = LocalContext.current
    val spaViewModel: SpaViewModel = viewModel()
    val ragViewModel: RagViewModel = viewModel(factory = RagViewModel.factory(context.applicationContext as android.app.Application))
    val ragConfig by ragViewModel.config.collectAsState()
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(context.applicationContext as android.app.Application)
    )

    val assistantTitle by spaViewModel.assistantTitle.collectAsState()
    val fabColorHex by spaViewModel.fabColor.collectAsState()
    val fabIconIndex by spaViewModel.fabIconIndex.collectAsState()
    val rotateAnimation by spaViewModel.rotateAnimation.collectAsState()
    val glowEffect by spaViewModel.glowEffect.collectAsState()
    val uiContextRatio by spaViewModel.uiContextRatio.collectAsState()
    val defaultModelId by spaViewModel.defaultModelId.collectAsState()

    val allModels by settingsViewModel.providerModels.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    val fabIcons = remember {
        listOf(
            Icons.Rounded.SmartToy,
            Icons.Rounded.Bolt,
            Icons.Rounded.ChatBubble,
            Icons.Rounded.AutoFixHigh,
            Icons.Rounded.AutoFixHigh
        )
    }

    val selectedFabColor = remember(fabColorHex) {
        try { Color(android.graphics.Color.parseColor(fabColorHex)) } catch (_: Exception) { Color(0xFF6366F1) }
    }

    val selectedIcon = fabIcons.getOrElse(fabIconIndex) { fabIcons[0] }

    if (showDeleteDialog) {
        NexaraConfirmDialog(
            title = stringResource(R.string.spa_delete_title),
            message = stringResource(R.string.spa_delete_message),
            confirmText = stringResource(R.string.shared_btn_delete),
            onConfirm = { showDeleteDialog = false; onNavigateBack() },
            onCancel = { showDeleteDialog = false },
            isDestructive = true
        )
    }

    val modelItems = remember(allModels) {
        allModels.map { info ->
            ModelItem(
                id = info.id,
                name = info.name,
                providerName = info.providerName,
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
        filterTag = "multimodal",
        models = modelItems,
        currentModelId = defaultModelId,
        onSelect = { id, _ ->
            spaViewModel.updateDefaultModel(id)
            showModelPicker = false
        }
    )

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spa_title), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = NexaraColors.OnSurface)
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
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                SettingsInput(
                    value = assistantTitle,
                    onValueChange = { spaViewModel.updateAssistantTitle(it) },
                    label = stringResource(R.string.spa_assistant_title_label),
                    placeholder = stringResource(R.string.spa_assistant_title_placeholder)
                )
            }

            item {
                SettingsSectionHeader(stringResource(R.string.spa_section_fab))
                Spacer(modifier = Modifier.height(12.dp))

                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CollapsibleSection(stringResource(R.string.spa_icon_style), defaultExpanded = true) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                fabIcons.forEachIndexed { index, icon ->
                                    val isSelected = index == fabIconIndex
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) NexaraColors.Primary.copy(alpha = 0.2f)
                                                else NexaraColors.SurfaceHigh
                                            )
                                            .then(
                                                if (isSelected) Modifier.border(1.dp, NexaraColors.Primary, RoundedCornerShape(12.dp))
                                                else Modifier.border(0.5.dp, NexaraColors.OutlineVariant, RoundedCornerShape(12.dp))
                                            )
                                            .clickable { spaViewModel.updateFabIcon(index) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(icon, null, tint = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        CollapsibleSection(stringResource(R.string.spa_color_label), defaultExpanded = false) {
                            Spacer(modifier = Modifier.height(12.dp))
                            ColorPickerPanel(
                                selectedColor = selectedFabColor,
                                onColorSelected = { color ->
                                    val hex = "#" + color.value.toString(16).takeLast(8).uppercase()
                                    spaViewModel.updateFabColor(hex)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        SettingsToggle(stringResource(R.string.spa_fab_rotation), checked = rotateAnimation, onCheckedChange = { spaViewModel.updateRotateAnimation(it) })
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsToggle(stringResource(R.string.spa_fab_glow), checked = glowEffect, onCheckedChange = { spaViewModel.updateGlowEffect(it) })
                    }
                }
            }

            item {
                SettingsSectionHeader(stringResource(R.string.spa_section_model))
                Spacer(modifier = Modifier.height(12.dp))

                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val selectedModelName = allModels.find { it.id == defaultModelId }?.name ?: (allModels.filter { it.enabled }.firstOrNull()?.name ?: "No model configured")

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(NexaraColors.SurfaceLow)
                                .border(0.5.dp, NexaraColors.OutlineVariant, RoundedCornerShape(12.dp))
                                .clickable { showModelPicker = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Memory, null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.spa_default_model), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurfaceVariant)
                                Text(selectedModelName, style = NexaraTypography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = NexaraColors.OnSurface)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = NexaraColors.Outline, modifier = Modifier.size(18.dp))
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        InferencePresets(selected = "balanced") {}
                    }
                }
            }

            item {
                SettingsSectionHeader(stringResource(R.string.spa_section_kg))
                Spacer(modifier = Modifier.height(12.dp))

                SettingsToggle(
                    title = stringResource(R.string.spa_kg_enable),
                    description = stringResource(R.string.spa_kg_enable_desc),
                    checked = ragConfig.enableKnowledgeGraph,
                    onCheckedChange = { enabled -> ragViewModel.updateConfig { it.copy(enableKnowledgeGraph = enabled) } }
                )

                if (ragConfig.enableKnowledgeGraph) {
                    Spacer(modifier = Modifier.height(8.dp))
                    NexaraGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Memory, null, tint = NexaraColors.Primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.spa_kg_view), style = NexaraTypography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = NexaraColors.OnSurface)
                                Text(stringResource(R.string.spa_kg_browse), style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader(stringResource(R.string.spa_section_knowledge))
                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NexaraSettingsItem(
                        icon = Icons.Rounded.Search,
                        title = stringResource(R.string.spa_rag_config),
                        subtitle = stringResource(R.string.spa_rag_config_desc),
                        onClick = onNavigateToRagConfig
                    )
                    NexaraSettingsItem(
                        icon = Icons.Rounded.AutoFixHigh,
                        title = stringResource(R.string.spa_advanced_retrieval),
                        subtitle = stringResource(R.string.spa_retrieval_desc),
                        onClick = onNavigateToAdvancedRetrieval
                    )
                }
            }

            item {
                SettingsSectionHeader(stringResource(R.string.spa_section_context))
                Spacer(modifier = Modifier.height(12.dp))

                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.spa_context_window), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                            Text(
                                "${(uiContextRatio * 100).toInt()}%",
                                style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                                color = NexaraColors.Primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        NexaraSlider(
                            value = uiContextRatio,
                            onValueChange = { spaViewModel.updateUiContextRatio(it) }
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(stringResource(R.string.spa_section_stats))
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // TODO: Replace with real data from DocRepository.countDocuments(), SessionRepository.countSessions(), VectorDao.count()
                    StatCard("1,204", stringResource(R.string.spa_stat_documents), Icons.Rounded.Description, Modifier.weight(1f))
                    StatCard("8,432", stringResource(R.string.spa_stat_sessions), Icons.Rounded.History, Modifier.weight(1f))
                    StatCard("2.4M", stringResource(R.string.spa_stat_vectors), Icons.Rounded.Cloud, Modifier.weight(1f))
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        android.widget.Toast.makeText(context, "Ghost data cleanup is coming soon", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = NexaraColors.StatusWarning, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.spa_clean_ghost), style = NexaraTypography.labelMedium, color = NexaraColors.StatusWarning)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        android.widget.Toast.makeText(context, "History export is coming soon", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Download, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.spa_export_history), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader(stringResource(R.string.spa_section_danger))
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    onClick = { showDeleteDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    color = NexaraColors.ErrorContainer.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, NexaraColors.Error.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Delete, null, tint = NexaraColors.Error, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.spa_delete_btn), style = NexaraTypography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = NexaraColors.Error)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    NexaraGlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = NexaraColors.Primary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = NexaraTypography.headlineLarge.copy(fontSize = 22.sp), color = NexaraColors.OnSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurfaceVariant)
        }
    }
}
