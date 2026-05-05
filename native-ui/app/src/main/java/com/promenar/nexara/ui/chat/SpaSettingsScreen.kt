package com.promenar.nexara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.CollapsibleSection
import com.promenar.nexara.ui.common.ColorPickerPanel
import com.promenar.nexara.ui.common.InferencePresets
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.SettingsInput
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SpaSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRagConfig: () -> Unit = {},
    onNavigateToAdvancedRetrieval: () -> Unit = {}
) {
    var assistantTitle by remember { mutableStateOf("Nexara Prime") }
    var selectedIcon by remember { mutableStateOf(Icons.Rounded.SmartToy) }
    var selectedFabColor by remember { mutableStateOf(Color(0xFF6366F1)) }
    var rotateAnimation by remember { mutableStateOf(true) }
    var glowEffect by remember { mutableStateOf(true) }
    var enableKG by remember { mutableStateOf(true) }
    var contextWindow by remember { mutableFloatStateOf(0.7f) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val fabIcons = remember {
        listOf(
            Icons.Rounded.SmartToy,
            Icons.Rounded.Bolt,
            Icons.Rounded.ChatBubble,
            Icons.Rounded.AutoFixHigh,
            Icons.Rounded.AutoFixHigh
        )
    }

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
                    onValueChange = { assistantTitle = it },
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
                                fabIcons.forEach { icon ->
                                    val isSelected = icon == selectedIcon
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
                                            .clickable { selectedIcon = icon },
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
                                onColorSelected = { selectedFabColor = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        SettingsToggle(stringResource(R.string.spa_fab_rotation), checked = rotateAnimation, onCheckedChange = { rotateAnimation = it })
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsToggle(stringResource(R.string.spa_fab_glow), checked = glowEffect, onCheckedChange = { glowEffect = it })
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(NexaraColors.SurfaceLow)
                                .border(0.5.dp, NexaraColors.OutlineVariant, RoundedCornerShape(12.dp))
                                .clickable { }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Memory, null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.spa_default_model), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurfaceVariant)
                                Text("GPT-4o", style = NexaraTypography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = NexaraColors.OnSurface)
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = NexaraColors.Outline, modifier = Modifier.size(18.dp))
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
                    checked = enableKG,
                    onCheckedChange = { enableKG = it }
                )

                if (enableKG) {
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
                                "${(contextWindow * 100).toInt()}%",
                                style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                                color = NexaraColors.Primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = contextWindow,
                            onValueChange = { contextWindow = it },
                            colors = SliderDefaults.colors(
                                thumbColor = NexaraColors.Primary,
                                activeTrackColor = NexaraColors.Primary,
                                inactiveTrackColor = NexaraColors.SurfaceHighest
                            )
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
                    StatCard("1,204", stringResource(R.string.spa_stat_documents), Icons.Rounded.Description, Modifier.weight(1f))
                    StatCard("8,432", stringResource(R.string.spa_stat_sessions), Icons.Rounded.History, Modifier.weight(1f))
                    StatCard("2.4M", stringResource(R.string.spa_stat_vectors), Icons.Rounded.Cloud, Modifier.weight(1f))
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = NexaraColors.StatusWarning, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.spa_clean_ghost), style = NexaraTypography.labelMedium, color = NexaraColors.StatusWarning)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { },
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
