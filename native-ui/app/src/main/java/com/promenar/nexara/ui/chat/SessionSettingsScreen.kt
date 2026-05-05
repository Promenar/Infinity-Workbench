package com.promenar.nexara.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.FloatingTextEditor
import com.promenar.nexara.ui.common.InferencePreset
import com.promenar.nexara.ui.common.InferencePresets
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.SettingsInput
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionSettingsScreen(
    sessionId: String,
    onNavigateBack: () -> Unit,
    onNavigateToAgentEdit: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(context.applicationContext as android.app.Application))
    val uiState by chatViewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) {
        chatViewModel.loadSession(sessionId)
    }

    val session = uiState.session
    val agentName = session?.agentId?.let { aid ->
        aid.substringAfter("agent_").replaceFirstChar { it.uppercase() }
    } ?: "Agent"

    val defaultTitle = stringResource(R.string.chat_title_new)
    var sessionTitle by remember(session?.title) { mutableStateOf(session?.title ?: defaultTitle) }
    var customPrompt by remember(session?.customPrompt) { mutableStateOf(session?.customPrompt ?: "") }
    var temperature by remember(session?.inferenceParams?.temperature) { mutableStateOf((session?.inferenceParams?.temperature ?: 0.7).toFloat()) }
    var topP by remember(session?.inferenceParams?.topP) { mutableStateOf((session?.inferenceParams?.topP ?: 0.9).toFloat()) }
    var maxTokens by remember(session?.inferenceParams?.maxTokens) { mutableStateOf((session?.inferenceParams?.maxTokens ?: 2048)) }
    var enableMemory by remember { mutableStateOf(session?.ragOptions?.enableMemory ?: true) }
    var enableKG by remember { mutableStateOf(session?.ragOptions?.enableKnowledgeGraph ?: false) }
    var enableDocs by remember { mutableStateOf(session?.ragOptions?.enableDocs ?: false) }
    val attachedDocs = remember { mutableStateListOf("Q3_Report_Final.pdf", "Revenue_Data.csv") }
    var showPromptEditor by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    FloatingTextEditor(
        show = showPromptEditor,
        onDismiss = { showPromptEditor = false },
        onSave = {
            customPrompt = it
            chatViewModel.updateCustomPrompt(it)
        },
        title = stringResource(R.string.session_settings_prompt_editor_title),
        initialText = customPrompt,
        placeholder = stringResource(R.string.session_settings_prompt_placeholder)
    )

    if (showDeleteDialog) {
        NexaraConfirmDialog(
            title = stringResource(R.string.session_settings_delete_title),
            message = stringResource(R.string.session_settings_delete_message),
            confirmText = stringResource(R.string.shared_btn_delete),
            onConfirm = {
                showDeleteDialog = false
                onNavigateBack()
            },
            onCancel = { showDeleteDialog = false },
            isDestructive = true
        )
    }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.session_settings_title), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                        if (session != null) {
                            Text(session.title, style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_cd_back), tint = NexaraColors.OnSurface)
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
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(NexaraColors.SurfaceHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.SmartToy, null, tint = NexaraColors.Primary, modifier = Modifier.size(24.dp))
                                }
                                Column {
                                    Text(stringResource(R.string.session_settings_active_agent), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurfaceVariant)
                                    Text(agentName, style = NexaraTypography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = NexaraColors.OnSurface)
                                }
                            }
                            Row {
                                IconButton(onClick = { }) {
                                    Icon(Icons.Rounded.SwapHoriz, null, tint = NexaraColors.Primary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(NexaraColors.GlassBorder))

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Download, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.session_settings_export), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurface)
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader(stringResource(R.string.session_settings_section_info))
                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            SettingsInput(
                                value = sessionTitle,
                                onValueChange = {
                                    sessionTitle = it
                                    chatViewModel.updateSessionTitle(it)
                                },
                                label = stringResource(R.string.session_settings_title_label),
                                placeholder = stringResource(R.string.session_settings_title_placeholder)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        val aiTitle = stringResource(R.string.session_settings_ai_title)
                        IconButton(
                            onClick = { sessionTitle = aiTitle },
                            modifier = Modifier
                                .padding(top = 20.dp)
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(NexaraColors.SurfaceHighest)
                        ) {
                            Icon(Icons.Rounded.AutoFixHigh, null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader(stringResource(R.string.session_settings_section_inference))
                Spacer(modifier = Modifier.height(12.dp))

                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        InferenceSlider(stringResource(R.string.session_settings_temperature), temperature, 0f, 2f, 0.1f, stringResource(R.string.common_preset_precise), stringResource(R.string.common_preset_creative)) { temperature = it }
                        Spacer(modifier = Modifier.height(20.dp))
                        InferenceSlider(stringResource(R.string.session_settings_top_p), topP, 0f, 1f, 0.05f, stringResource(R.string.session_settings_label_focused), stringResource(R.string.session_settings_label_diverse)) { topP = it }
                        Spacer(modifier = Modifier.height(20.dp))
                        InferenceSliderInt(stringResource(R.string.session_settings_max_tokens), maxTokens, 100, 4096, 100, stringResource(R.string.session_settings_label_short), stringResource(R.string.session_settings_label_long)) { maxTokens = it }

                        Spacer(modifier = Modifier.height(16.dp))
                        val selectedPreset = when {
                            temperature <= 0.3f -> "precise"
                            temperature <= 0.8f -> "balanced"
                            else -> "creative"
                        }
                        InferencePresets(selected = selectedPreset) { preset ->
                            temperature = preset.temperature
                            topP = preset.topP
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader(stringResource(R.string.session_settings_section_rag))
                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggle(
                        title = stringResource(R.string.session_settings_memory),
                        description = stringResource(R.string.session_settings_memory_desc),
                        checked = enableMemory,
                        onCheckedChange = {
                            enableMemory = it
                            chatViewModel.updateRagOptions(
                                (session?.ragOptions ?: com.promenar.nexara.data.model.RagOptions()).copy(enableMemory = it)
                            )
                        }
                    )
                    SettingsToggle(
                        title = stringResource(R.string.session_settings_kg),
                        description = stringResource(R.string.session_settings_kg_desc),
                        checked = enableKG,
                        onCheckedChange = {
                            enableKG = it
                            chatViewModel.updateRagOptions(
                                (session?.ragOptions ?: com.promenar.nexara.data.model.RagOptions()).copy(enableKnowledgeGraph = it)
                            )
                        }
                    )
                    SettingsToggle(
                        title = stringResource(R.string.session_settings_kb),
                        description = stringResource(R.string.session_settings_kb_desc),
                        checked = enableDocs,
                        onCheckedChange = {
                            enableDocs = it
                            chatViewModel.updateRagOptions(
                                (session?.ragOptions ?: com.promenar.nexara.data.model.RagOptions()).copy(enableDocs = it)
                            )
                        }
                    )

                    if (enableDocs) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.session_settings_attached_docs_label), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold), color = NexaraColors.OnSurface)

                        NexaraGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    attachedDocs.forEach { doc ->
                                        DocumentChip(
                                            name = doc,
                                            onRemove = { attachedDocs.remove(doc) }
                                        )
                                    }

                                    Surface(
                                        onClick = { },
                                        shape = RoundedCornerShape(50),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, NexaraColors.GlassBorder.copy(alpha = 0.2f)),
                                        color = Color.Transparent
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Add, null, tint = NexaraColors.Primary, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.session_settings_add_docs), style = NexaraTypography.labelMedium.copy(fontSize = 12.sp), color = NexaraColors.Primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader(stringResource(R.string.session_settings_section_prompt))
                Spacer(modifier = Modifier.height(12.dp))

                NexaraGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPromptEditor = true },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.session_settings_system_prompt_label), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                            Spacer(modifier = Modifier.height(4.dp))
                            if (customPrompt.isNotBlank()) {
                                Text(
                                    customPrompt.take(80) + if (customPrompt.length > 80) "..." else "",
                                    style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                                    color = NexaraColors.OnSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (customPrompt.isNotBlank()) NexaraColors.Primary.copy(alpha = 0.15f)
                                    else NexaraColors.SurfaceHighest
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (customPrompt.isNotBlank()) stringResource(R.string.session_settings_configured) else stringResource(R.string.session_settings_not_set),
                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                color = if (customPrompt.isNotBlank()) NexaraColors.Primary else NexaraColors.Outline
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader(stringResource(R.string.session_settings_section_danger))
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
                        Text(stringResource(R.string.session_settings_delete_btn), style = NexaraTypography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = NexaraColors.Error)
                    }
                }
            }
        }
    }
}

@Composable
private fun InferenceSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    minLabel: String,
    maxLabel: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Rounded.Info, null, tint = NexaraColors.Outline, modifier = Modifier.size(14.dp))
            }
            Text(
                String.format("%.1f", value),
                style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                color = NexaraColors.Primary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            steps = ((max - min) / step).toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = NexaraColors.Primary,
                activeTrackColor = NexaraColors.Primary,
                inactiveTrackColor = NexaraColors.SurfaceHighest
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(minLabel, style = NexaraTypography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.05.sp), color = NexaraColors.Outline)
            Text(maxLabel, style = NexaraTypography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.05.sp), color = NexaraColors.Outline)
        }
    }
}

@Composable
private fun InferenceSliderInt(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    minLabel: String,
    maxLabel: String,
    onChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Rounded.Info, null, tint = NexaraColors.Outline, modifier = Modifier.size(14.dp))
            }
            Text(
                value.toString(),
                style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                color = NexaraColors.Primary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / step) - 1,
            colors = SliderDefaults.colors(
                thumbColor = NexaraColors.Primary,
                activeTrackColor = NexaraColors.Primary,
                inactiveTrackColor = NexaraColors.SurfaceHighest
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(minLabel, style = NexaraTypography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.05.sp), color = NexaraColors.Outline)
            Text(maxLabel, style = NexaraTypography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.05.sp), color = NexaraColors.Outline)
        }
    }
}

@Composable
private fun DocumentChip(
    name: String,
    onRemove: () -> Unit
) {
    val icon = if (name.endsWith(".pdf")) Icons.Rounded.Description else Icons.Rounded.TableChart

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(NexaraColors.SurfaceContainer)
            .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(name, style = NexaraTypography.labelMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurface, maxLines = 1)
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Rounded.Close,
            null,
            tint = NexaraColors.Outline,
            modifier = Modifier
                .size(14.dp)
                .clickable { onRemove() }
        )
    }
}
