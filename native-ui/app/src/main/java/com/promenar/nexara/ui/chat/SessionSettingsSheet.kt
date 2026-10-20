package com.promenar.nexara.ui.chat

import com.promenar.nexara.domain.usecase.withDefaults
import com.promenar.nexara.domain.usecase.resolveKeepRecentTurns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Build
import com.promenar.nexara.ui.skills.SessionSkillsSections
import com.promenar.nexara.ui.skills.SkillCatalogActions
import com.promenar.nexara.ui.skills.SkillCatalogViewModel
import androidx.compose.material.icons.rounded.Token
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.promenar.nexara.ui.common.NexaraCapsuleTabRow
import com.promenar.nexara.ui.common.NexaraTabItem
import com.promenar.nexara.ui.common.ModelSelectionUiModel
import com.promenar.nexara.ui.common.toModelSelectionUiModel
import com.promenar.nexara.ui.common.ModelSelectionListItem
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.promenar.nexara.data.manager.ProviderManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.forReading
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.remote.mcp.McpEndpointPolicy
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class ThinkingLevelOption(
    val id: String,
    val icon: ImageVector
)

private val thinkingLevels = listOf(
    ThinkingLevelOption("minimal", Icons.Rounded.Bolt),
    ThinkingLevelOption("low", Icons.Rounded.Psychology),
    ThinkingLevelOption("medium", Icons.Rounded.AutoFixHigh),
    ThinkingLevelOption("high", Icons.Rounded.School)
)

@Composable
private fun getThinkingLevelColor(id: String): Color {
    return when (id) {
        "minimal" -> MaterialTheme.colorScheme.secondary
        "low" -> MaterialTheme.colorScheme.primary
        "medium" -> MaterialTheme.colorScheme.tertiary
        "high" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun thinkingLevelTitle(id: String): String = when (id) {
    "minimal" -> stringResource(R.string.sheet_thinking_minimal)
    "low" -> stringResource(R.string.sheet_thinking_low)
    "medium" -> stringResource(R.string.sheet_thinking_medium)
    "high" -> stringResource(R.string.sheet_thinking_high)
    else -> id
}

@Composable
private fun thinkingLevelDesc(id: String): String = when (id) {
    "minimal" -> stringResource(R.string.sheet_thinking_minimal_desc)
    "low" -> stringResource(R.string.sheet_thinking_low_desc)
    "medium" -> stringResource(R.string.sheet_thinking_medium_desc)
    "high" -> stringResource(R.string.sheet_thinking_high_desc)
    else -> ""
}


internal fun filterLocalInferenceModels(
    models: List<ModelInfo>,
    localProviderIds: Set<String>,
    localInferenceAvailable: Boolean,
): List<ModelInfo> {
    if (localInferenceAvailable || localProviderIds.isEmpty()) return models
    return models.filterNot { model ->
        val providerId = model.providerId ?: model.id.substringBefore("::").takeIf { "::" in model.id }
        providerId in localProviderIds
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionSettingsSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    chatViewModel: ChatViewModel,
) {
    if (!show) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val providerModels by settingsViewModel.providerModels.collectAsStateWithLifecycle()
    val providers by settingsViewModel.providers.collectAsStateWithLifecycle()
    val visibleProviderModels = filterLocalInferenceModels(
        models = providerModels,
        localProviderIds = providers.filter { it.protocolType is ProtocolType.Local }.mapTo(mutableSetOf()) { it.id },
        localInferenceAvailable = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
    )
    
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val compressionState by chatViewModel.compressionState.collectAsStateWithLifecycle()
    val session = uiState.session
    
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val tabTitles = listOf(
        stringResource(R.string.sheet_tab_model),
        stringResource(R.string.sheet_tab_thinking),
        stringResource(R.string.sheet_tab_tools),
        stringResource(R.string.sheet_tab_settings)
    )

    NexaraModalSheet(onDismissRequest = onDismiss, height = NexaraSheetHeight.Full) {
        Text(
            stringResource(R.string.sheet_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val sessionTabs = remember(tabTitles) {
                tabTitles.map { NexaraTabItem(title = it) }
            }
            NexaraCapsuleTabRow(
                tabs = sessionTabs,
                selectedIndex = pagerState.currentPage,
                onTabSelected = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NexaraSpacing.XLarge, vertical = NexaraSpacing.Small),
            )

            Spacer(modifier = Modifier.height(NexaraSpacing.Small))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> ModelPanel(
                        selectedModelId = session?.modelId ?: "",
                        onSelect = { modelId ->
                            chatViewModel.updateModelId(modelId)
                            onDismiss()
                        },
                        allModels = visibleProviderModels,
                    )
                    1 -> ParamsPanel(
                        session = session,
                        chatViewModel = chatViewModel
                    )
                    2 -> ToolsPanel(chatViewModel = chatViewModel, session = session)
                    3 -> SettingsPanel(
                        session = session,
                        chatViewModel = chatViewModel,
                        isGenerating = uiState.isGenerating,
                        isCompressing = compressionState.isCompressing,
                        onCompactNow = {
                            chatViewModel.compactNow()
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ModelPanel(
    selectedModelId: String,
    onSelect: (String) -> Unit,
    allModels: List<ModelInfo>,
) {
    var searchQuery by remember { mutableStateOf("") }
    val modelItems = remember(allModels, selectedModelId) {
        sessionSelectableModels(allModels, selectedModelId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NexaraSpacing.ScreenHorizontal)
            .padding(top = 16.dp)
    ) {
        NexaraSearchBar(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = stringResource(R.string.common_model_picker_search)
        )

        Spacer(modifier = Modifier.height(12.dp))

        val filtered = remember(searchQuery, modelItems) {
            if (searchQuery.isBlank()) modelItems else modelItems.filter {
                it.displayName.contains(searchQuery, true)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiTags.CHAT_MODEL_LIST),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(filtered, key = { _, it -> it.selectionId }) { index, model ->
                val isSelected = model.selectionId == selectedModelId
                ModelSelectionListItem(
                    model = model,
                    selected = isSelected,
                    onClick = { onSelect(model.selectionId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.chatModelOption(model.selectionId))
                )
            }
        }
    }
}

/** 会话可选模型：已启用、可走对话接口，或是当前已选模型。 */
internal fun sessionSelectableModels(
    models: List<ModelInfo>,
    selectedModelId: String,
): List<ModelSelectionUiModel> = filterSessionSelectionModels(
    models = models.filter { it.enabled }.map { it.toModelSelectionUiModel() },
    selectedModelId = selectedModelId,
)

internal fun filterSessionSelectionModels(
    models: List<ModelSelectionUiModel>,
    selectedModelId: String,
): List<ModelSelectionUiModel> = models.filter { model ->
    model.selectionId == selectedModelId ||
        model.isChatSelectionCandidate()
}

@Composable
private fun ParamsPanel(
    session: com.promenar.nexara.data.model.Session?,
    chatViewModel: ChatViewModel,
) {
    val params = session?.inferenceParams ?: com.promenar.nexara.data.model.InferenceParams()
    val agentDefaults by androidx.compose.runtime.produceState<com.promenar.nexara.data.model.InferenceParams?>(
        initialValue = null, session?.agentId,
    ) { value = chatViewModel.agentInferenceDefaults() }
    SessionParamsContent(params, agentDefaults, chatViewModel::updateInferenceParams)
}

@Composable
internal fun SessionParamsContent(
    params: com.promenar.nexara.data.model.InferenceParams,
    agentDefaults: com.promenar.nexara.data.model.InferenceParams? = null,
    onParamsChange: (com.promenar.nexara.data.model.InferenceParams) -> Unit = {},
) {
    val shown = params.withDefaults(agentDefaults)
    val currentTemperature = (shown.temperature ?: 0.7).toFloat()
    val selectedLevel = when {
        currentTemperature <= 0.3f -> "minimal"
        currentTemperature <= 0.6f -> "low"
        currentTemperature <= 0.9f -> "medium"
        else -> "high"
    }
    val onLevelClick: (String) -> Unit = { levelId ->
        val (temperature, topP, topK) = when (levelId) {
            "minimal" -> Triple(0.1, 0.5, 10)
            "low" -> Triple(0.4, 0.8, 30)
            "medium" -> Triple(0.7, 0.9, 50)
            else -> Triple(1.0, 1.0, 100)
        }
        onParamsChange(params.copy(temperature = temperature, topP = topP, topK = topK))
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = NexaraSpacing.ScreenHorizontal),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 24.dp)) {
        item {
            NexaraSettingsSection(title = stringResource(R.string.session_thinking_style)) {
                Column(Modifier.padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in listOf(thinkingLevels.take(2), thinkingLevels.drop(2))) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { level ->
                            val isSelected = selectedLevel == level.id
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                        else Modifier
                                    )
                                    .clickable { onLevelClick(level.id) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else Modifier)
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val levelColor = getThinkingLevelColor(level.id)
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(levelColor.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(level.icon, null, tint = levelColor, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(thinkingLevelTitle(level.id), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(thinkingLevelDesc(level.id), style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

                }
            }
        }
        item {
            NexaraSettingsSection(title = stringResource(R.string.sheet_settings_section_inference)) {
                NexaraNumericSetting(
                    title = stringResource(R.string.sheet_settings_stream_timeout), value = params.streamTimeout ?: 120,
                    spec = NumericSettingSpec(30.0, 300.0, step = 10.0, defaultValue = 120.0,
                        unit = stringResource(R.string.session_numeric_seconds),
                        presets = listOf(60.0, 120.0, 180.0, 300.0).map { NumericSettingSpec.Preset(it) }),
                    onValueChange = { onParamsChange(params.copy(streamTimeout = it.toInt())) },
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.sheet_settings_top_p), value = shown.topP ?: 1.0,
                    spec = sessionContinuousSpec(0.0, 1.0, 1.0, listOf(0.5, 0.8, 0.95, 1.0)),
                    onValueChange = { onParamsChange(params.copy(topP = it)) },
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.sheet_settings_max_tokens), value = shown.maxTokens ?: 0,
                    spec = NumericSettingSpec(0.0, 8192.0, defaultValue = 0.0, unlimitedValue = 0.0,
                        presets = listOf(0.0, 1024.0, 2048.0, 4096.0, 8192.0).map { NumericSettingSpec.Preset(it) }),
                    onValueChange = { onParamsChange(params.copy(maxTokens = it.toInt().takeIf { value -> value != 0 })) },
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.sheet_settings_top_k), value = params.topK ?: 0,
                    spec = NumericSettingSpec(0.0, 100.0, defaultValue = 0.0, unlimitedValue = 0.0,
                        presets = listOf(0.0, 20.0, 40.0, 100.0).map { NumericSettingSpec.Preset(it) }),
                    onValueChange = { onParamsChange(params.copy(topK = it.toInt().takeIf { value -> value != 0 })) },
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.sheet_settings_repetition_penalty), value = params.repetitionPenalty ?: 1.0,
                    spec = sessionContinuousSpec(0.5, 2.0, 1.0, listOf(0.5, 1.0, 1.5, 2.0)),
                    onValueChange = { onParamsChange(params.copy(repetitionPenalty = it)) },
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.sheet_settings_presence_penalty), value = params.presencePenalty ?: 0.0,
                    spec = sessionContinuousSpec(-2.0, 2.0, 0.0, listOf(-2.0, -1.0, 0.0, 1.0, 2.0)),
                    onValueChange = { onParamsChange(params.copy(presencePenalty = it)) },
                )
                NexaraNumericSetting(
                    title = stringResource(R.string.sheet_settings_frequency_penalty), value = params.frequencyPenalty ?: 0.0,
                    spec = sessionContinuousSpec(-2.0, 2.0, 0.0, listOf(-2.0, -1.0, 0.0, 1.0, 2.0)),
                    onValueChange = { onParamsChange(params.copy(frequencyPenalty = it)) },
                )
            }
        }
    }
}

internal fun sessionContinuousSpec(min: Double, max: Double, defaultValue: Double, presets: List<Double>) =
    NumericSettingSpec(min, max, step = 0.0, defaultValue = defaultValue,
        presets = presets.map { NumericSettingSpec.Preset(it) }, kind = NumericSettingKind.Continuous, decimalPlaces = 2)

@Composable
private fun ToolsPanel(chatViewModel: ChatViewModel, session: com.promenar.nexara.data.model.Session?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val skillsViewModel: SkillCatalogViewModel = viewModel(
        factory = SkillCatalogViewModel.factory(context.applicationContext as android.app.Application),
    )
    LaunchedEffect(session?.id) { skillsViewModel.bind(session?.id) }
    val skillsState by skillsViewModel.state.collectAsStateWithLifecycle()
    val skillsError by skillsViewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(skillsError) {
        skillsError?.let { message ->
            android.widget.Toast.makeText(context, context.getString(R.string.skills_runtime_error, message), android.widget.Toast.LENGTH_SHORT).show()
            skillsViewModel.consumeError()
        }
    }
    val skillActions = remember(skillsViewModel) {
        SkillCatalogActions(
            onToggle = { skill, enabled -> skillsViewModel.setEnabled(skill, enabled) },
            onTrust = skillsViewModel::trust,
            onAcknowledge = skillsViewModel::acknowledgeModified,
            onDelete = skillsViewModel::delete,
            loadDocument = skillsViewModel::loadDocument,
        )
    }
    val customSkills by chatViewModel.availableSessionCustomSkills.collectAsStateWithLifecycle()
    val mcpServers by chatViewModel.availableSessionMcpServers.collectAsStateWithLifecycle()
    val mcpSettings = (context.applicationContext as? NexaraApplication)?.mcpConnectionSettings
    val mcpConnectionRevision = mcpSettings?.revision?.collectAsStateWithLifecycle()?.value
    SessionToolsContent(
        options = session?.options ?: com.promenar.nexara.data.model.SessionOptions(),
        executionMode = session?.executionMode?.ifEmpty { "semi" } ?: "semi",
        isGeminiModel = session?.modelId?.contains("gemini", ignoreCase = true) == true,
        onToolToggle = chatViewModel::toggleTool, onExecutionModeChange = chatViewModel::updateExecutionMode,
        skillsSection = { SessionSkillsSections(skillsState, skillActions) },
        additionalSections = {
        if (customSkills.isNotEmpty()) {
            NexaraSettingsSection(title = stringResource(R.string.sheet_tool_session_skills),
                footer = stringResource(R.string.sheet_tool_custom_unsandboxed)) {
                customSkills.forEach { skill ->
                    SettingsToggle(
                        title = skill.name,
                        icon = Icons.Rounded.AutoFixHigh,
                        checked = skill.id in session?.activeSkillIds.orEmpty(),
                        onCheckedChange = { chatViewModel.toggleSessionSkill(skill.id) },
                    )
                }
            }
        }

        val selectableMcpServers = remember(mcpServers, mcpConnectionRevision) {
            mcpServers.filter { server ->
                server.enabled && (mcpSettings?.isAllowed(server.id, server.type, server.url)
                    ?: McpEndpointPolicy.isAllowed(server.type, server.url))
            }
        }
        if (selectableMcpServers.isNotEmpty()) {
            NexaraSettingsSection(title = stringResource(R.string.sheet_tool_mcp_servers)) {
                selectableMcpServers.forEach { server ->
                    SettingsToggle(
                        title = server.name,
                        icon = Icons.Rounded.Sync,
                        checked = server.id in session?.activeMcpServerIds.orEmpty(),
                        onCheckedChange = { chatViewModel.toggleSessionMcpServer(server.id) },
                    )
                }
            }
        }

        },
    )
}

/**
 * “工具”标签：工具使用（总开关与执行模式）置顶，其后是技能、自定义工具与 MCP，最后是会话选项。
 */
@Composable
internal fun SessionToolsContent(
    options: com.promenar.nexara.data.model.SessionOptions = com.promenar.nexara.data.model.SessionOptions(),
    executionMode: String = "semi",
    isGeminiModel: Boolean = false,
    onToolToggle: (String, Boolean) -> Unit = { _, _ -> },
    onExecutionModeChange: (String) -> Unit = {},
    skillsSection: @Composable () -> Unit = {},
    additionalSections: @Composable () -> Unit = {},
) {
    val selectedExecutionMode = when (executionMode) {
        "auto" -> ExecutionMode.AUTO
        "manual" -> ExecutionMode.MANUAL
        else -> ExecutionMode.SEMI
    }
    val modeDesc = stringResource(when (selectedExecutionMode) {
        ExecutionMode.AUTO -> R.string.sheet_execution_mode_auto
        ExecutionMode.MANUAL -> R.string.sheet_execution_mode_manual
        ExecutionMode.SEMI -> R.string.sheet_execution_mode_semi
    })
    var showModePicker by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = NexaraSpacing.ScreenHorizontal, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
    ) {
        NexaraSettingsSection(title = stringResource(R.string.sheet_tool_section_usage)) {
            SettingsToggle(
                stringResource(R.string.sheet_tool_tools_enabled),
                description = stringResource(R.string.sheet_tool_tools_enabled_desc),
                checked = options.toolsEnabled,
                icon = Icons.Rounded.Build,
                onCheckedChange = { onToolToggle("toolsEnabled", it) },
                modifier = Modifier.testTag("session_tools_enabled"),
            )
            NexaraValueRow(
                title = stringResource(R.string.sheet_tool_execution_mode),
                value = stringResource(selectedExecutionMode.labelRes),
                onClick = { showModePicker = true },
                modifier = Modifier.testTag("session_execution_mode_picker"),
            )
        }
        skillsSection()
        additionalSections()
        NexaraSettingsSection(title = stringResource(R.string.agent_panels_session_options)) {
            SettingsToggle(
                stringResource(R.string.sheet_tool_time_injection),
                description = stringResource(R.string.sheet_tool_time_injection_desc),
                checked = options.enableTimeInjection,
                icon = Icons.Rounded.Timer,
                onCheckedChange = { onToolToggle("timeInjection", it) },
            )
            if (isGeminiModel) SettingsToggle(stringResource(R.string.sheet_tool_gemini_grounding), checked = options.enableGeminiSearch,
                icon = Icons.Rounded.Language, onCheckedChange = { onToolToggle("enableGeminiSearch", it) })
        }
    }
    NexaraBottomSheet(show = showModePicker, onDismiss = { showModePicker = false },
        title = stringResource(R.string.sheet_tool_execution_mode), scrollContent = true) {
        Text(modeDesc, style = MaterialTheme.typography.bodySmall.forReading(),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        ExecutionMode.entries.forEach { mode ->
            val label = stringResource(mode.labelRes)
            NexaraSettingsItem(
                horizontalPadding = 0.dp,
                title = if (mode == ExecutionMode.SEMI) stringResource(R.string.numeric_default_value, label) else label,
                role = Role.RadioButton,
                selected = mode == selectedExecutionMode,
                modifier = Modifier.testTag("session_execution_mode_${mode.name.lowercase()}"),
                onClick = { onExecutionModeChange(mode.name.lowercase()); showModePicker = false },
                trailingContent = {
                    if (mode == selectedExecutionMode) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                },
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    session: com.promenar.nexara.data.model.Session?,
    chatViewModel: ChatViewModel,
    isGenerating: Boolean,
    isCompressing: Boolean,
    onCompactNow: () -> Unit,
) {
    val presetRerankModel by ProviderManager.getInstance().rerankModelId.collectAsStateWithLifecycle()
    SessionContextPanelContent(
        params = session?.inferenceParams ?: com.promenar.nexara.data.model.InferenceParams(),
        ragOptions = session?.ragOptions ?: com.promenar.nexara.data.model.RagOptions(),
        fontSize = session?.options?.fontSize ?: 15,
        isRerankAvailable = presetRerankModel.isNotBlank(), isGenerating = isGenerating, isCompressing = isCompressing,
        onParamsChange = chatViewModel::updateInferenceParams,
        onRagOptionsChange = { update -> chatViewModel.updateRagOptions(update(chatViewModel.currentRagOptions.value)) },
        onFontSizeChange = { chatViewModel.updateFontSizeLocally(it); chatViewModel.updateFontSize(it) },
        onCompactNow = onCompactNow,
    )
}

@Composable
internal fun SessionContextPanelContent(
    params: com.promenar.nexara.data.model.InferenceParams = com.promenar.nexara.data.model.InferenceParams(),
    ragOptions: com.promenar.nexara.data.model.RagOptions = com.promenar.nexara.data.model.RagOptions(),
    fontSize: Int = 15,
    isRerankAvailable: Boolean = true,
    isGenerating: Boolean = false,
    isCompressing: Boolean = false,
    onParamsChange: (com.promenar.nexara.data.model.InferenceParams) -> Unit = {},
    onRagOptionsChange: ((com.promenar.nexara.data.model.RagOptions) -> com.promenar.nexara.data.model.RagOptions) -> Unit = {},
    onFontSizeChange: (Int) -> Unit = {},
    onCompactNow: () -> Unit = {},
    /** 会话工作区项，位于上下文设置之前；为空时不显示。 */
) {
    val retainedTurns = resolveKeepRecentTurns(params.keepRecentTurns, params.activeContextWindow)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = NexaraSpacing.ScreenHorizontal, vertical = 16.dp)) {
        SessionContextSettingsSection(params.autoSummaryThreshold, retainedTurns, isGenerating, isCompressing,
            onThresholdChange = { onParamsChange(params.copy(autoSummaryThreshold = it)) },
            onKeepRecentTurnsChange = { onParamsChange(params.copy(keepRecentTurns = it)) }, onCompactNow = onCompactNow)
        val fullyDisabled = !ragOptions.enableMemory && !ragOptions.isGlobal && !ragOptions.enableDocs && ragOptions.enableKnowledgeGraph != true
        val retrievalFooter = buildList {
            if (!isRerankAvailable) add(stringResource(R.string.sheet_settings_rerank_unavailable))
            if (fullyDisabled) {
                add(stringResource(R.string.sheet_settings_rag_disabled))
                add(stringResource(R.string.sheet_settings_rag_disabled_hint))
            }
        }.joinToString("\n").ifBlank { null }
        NexaraSettingsSection(title = stringResource(R.string.session_retrieval_section), footer = retrievalFooter) {
            SettingsToggle(stringResource(R.string.sheet_settings_memory), checked = ragOptions.enableMemory, icon = Icons.Rounded.History,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.Memory, it) } })
            SettingsToggle(stringResource(R.string.sheet_settings_global_memory), checked = ragOptions.isGlobal, icon = Icons.Rounded.Share,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.GlobalMemory, it) } })
            SettingsToggle(stringResource(R.string.sheet_settings_docs), checked = ragOptions.enableDocs, icon = Icons.Rounded.Description,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.Documents, it) } })
            SettingsToggle(stringResource(R.string.sheet_tool_rerank), checked = isRerankAvailable && ragOptions.enableRerank,
                icon = Icons.Rounded.Sync, enabled = isRerankAvailable,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.Rerank, it) } })
            SettingsToggle(stringResource(R.string.sheet_settings_kg), checked = ragOptions.enableKnowledgeGraph ?: false, icon = Icons.Rounded.Psychology,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.KnowledgeGraph, it) } })
        }
        val memoryScope = when (ragOptions.memorySource()) {
            SessionMemorySource.Disabled -> R.string.session_memory_scope_off
            SessionMemorySource.Global -> R.string.session_memory_scope_both
            SessionMemorySource.Session -> R.string.sheet_settings_memory
        }
        NexaraSettingsSection(title = stringResource(R.string.sheet_settings_section_retrieval),
            footer = stringResource(R.string.session_settings_memory_desc)) {
            NexaraSettingsRowLayout(title = stringResource(R.string.session_memory_scope)) {
                Text(stringResource(memoryScope), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        NexaraSettingsSection(title = stringResource(R.string.sheet_settings_ui_title)) {
            NexaraStepperSetting(title = stringResource(R.string.sheet_settings_font_size), value = fontSize.toString(),
                canDecrease = fontSize > 10, canIncrease = fontSize < 18,
                onDecrease = { onFontSizeChange((fontSize - 1).coerceAtLeast(10)) },
                onIncrease = { onFontSizeChange((fontSize + 1).coerceAtMost(18)) })
        }
    }
}

@Composable
internal fun SessionContextSettingsSection(
    autoCompactionThreshold: Double,
    keepRecentTurns: Int,
    isGenerating: Boolean,
    isCompressing: Boolean,
    onThresholdChange: (Double) -> Unit,
    onKeepRecentTurnsChange: (Int) -> Unit,
    onCompactNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thresholdPercent = (autoCompactionThreshold * 100).roundToInt().coerceIn(50, 95)
    val retainedTurns = keepRecentTurns.coerceIn(1, 20)
    val compressionUnavailableReason = when {
        isCompressing -> stringResource(R.string.chat_context_compress_unavailable_running)
        isGenerating -> stringResource(R.string.chat_context_compress_unavailable_generating)
        else -> null
    }
    NexaraSettingsSection(
        title = stringResource(R.string.sheet_settings_section_context),
        modifier = modifier.testTag("session_context_management"),
        footer = stringResource(R.string.settings_batch1_context_footer, retainedTurns) +
            (compressionUnavailableReason?.let { "\n$it" } ?: ""),
    ) {
        NexaraNumericSetting(
            title = stringResource(R.string.sheet_settings_summary_threshold), value = thresholdPercent,
            description = stringResource(R.string.sheet_settings_context_description, retainedTurns),
            spec = NumericSettingSpec(50.0, 95.0, step = 5.0, defaultValue = 80.0, unit = "%",
                presets = listOf(70.0, 80.0, 90.0).map { NumericSettingSpec.Preset(it) }),
            onValueChange = { onThresholdChange(it / 100.0) },
        )
        NexaraStepperSetting(
            title = stringResource(R.string.sheet_settings_keep_recent_turns), value = retainedTurns.toString(),
            canDecrease = retainedTurns > 1, canIncrease = retainedTurns < 20,
            onDecrease = { onKeepRecentTurnsChange((retainedTurns - 1).coerceAtLeast(1)) },
            onIncrease = { onKeepRecentTurnsChange((retainedTurns + 1).coerceAtMost(20)) },
        )
        NexaraSettingsItem(
            title = stringResource(R.string.chat_context_btn_compress),
            subtitle = when {
                isCompressing -> stringResource(R.string.session_compaction_running)
                isGenerating -> stringResource(R.string.session_compaction_generating)
                else -> null
            },
            onClick = onCompactNow, enabled = compressionUnavailableReason == null,
            modifier = Modifier.testTag("session_context_compact_now"),
        )
    }
}
