package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.params.sampling
import com.promenar.nexara.ui.common.params.ParameterSchemeSection
import com.promenar.nexara.ui.common.params.SAMPLING_PARAM_ORDER
import com.promenar.nexara.ui.common.params.SamplingEditorActions
import com.promenar.nexara.ui.common.params.SamplingEditorState
import com.promenar.nexara.ui.common.params.SamplingParamLayout
import com.promenar.nexara.ui.common.params.SamplingParamRows
import com.promenar.nexara.ui.common.params.samplingParamLayout
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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import com.promenar.nexara.ui.common.network.NetworkLightsRow
import com.promenar.nexara.ui.common.network.NetworkLightsState
import com.promenar.nexara.ui.common.network.badgeFor
import com.promenar.nexara.ui.common.network.rememberNetworkLightsState
import com.promenar.nexara.ui.common.network.rememberTailscaleRetry
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onOpenTailscaleSettings: (() -> Unit)? = null,
    /** 打开时所在的标签页：0 模型、1 参数、2 工具、3 设置。 */
    initialPage: Int = 0,
) {
    if (!show) return

    val visibleProviderModels = rememberVisibleProviderModels()
    
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val compressionState by chatViewModel.compressionState.collectAsStateWithLifecycle()
    val session = uiState.session
    
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, 3), pageCount = { 4 })
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
                        networkLights = rememberNetworkLightsState(),
                        onOpenTailscaleSettings = onOpenTailscaleSettings?.let { open -> { onDismiss(); open() } },
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

/** 会话可切换的服务商模型；本机推理不可用时去掉本地服务商的模型。 */
@Composable
private fun rememberVisibleProviderModels(): List<ModelInfo> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val providerModels by settingsViewModel.providerModels.collectAsStateWithLifecycle()
    val providers by settingsViewModel.providers.collectAsStateWithLifecycle()
    return remember(providerModels, providers) {
        filterLocalInferenceModels(
            models = providerModels,
            localProviderIds = providers.filter { it.protocolType is ProtocolType.Local }.mapTo(mutableSetOf()) { it.id },
            localInferenceAvailable = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
        )
    }
}

/**
 * 宽窗口下点输入区模型按钮弹出的模型速选：与会话设置“模型”页同一列表，选中即关闭；
 * 底部“更多会话设置”打开完整会话设置（参数页）。
 */
@Composable
internal fun ModelQuickPickPopover(
    anchor: NexaraPopoverAnchor,
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onMoreSettings: () -> Unit,
    onOpenTailscaleSettings: (() -> Unit)? = null,
) {
    val models = rememberVisibleProviderModels()
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    NexaraPopover(
        show = true,
        anchor = anchor,
        onDismissRequest = onDismiss,
        side = NexaraPopoverSide.Above,
        align = NexaraPopoverAlign.Start,
        width = ModelQuickPickWidth,
        title = stringResource(R.string.chat_model_quick_pick_title),
        modifier = Modifier.testTag(UiTags.CHAT_MODEL_QUICK_PICK),
    ) {
        Box(modifier = Modifier.weight(1f, fill = false).heightIn(max = ModelQuickPickListMaxHeight)) {
            ModelPanel(
                selectedModelId = uiState.session?.modelId ?: "",
                onSelect = { modelId ->
                    chatViewModel.updateModelId(modelId)
                    onDismiss()
                },
                allModels = models,
                networkLights = rememberNetworkLightsState(),
                onOpenTailscaleSettings = onOpenTailscaleSettings?.let { open -> { onDismiss(); open() } },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .clickable(role = Role.Button, onClick = onMoreSettings)
                .padding(horizontal = NexaraSpacing.ScreenHorizontal, vertical = NexaraSpacing.Small)
                .testTag(UiTags.CHAT_MODEL_QUICK_PICK_MORE),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.chat_model_quick_pick_more),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ModelQuickPickWidth = 360.dp
private val ModelQuickPickListMaxHeight = 480.dp

@Composable
internal fun ModelPanel(
    selectedModelId: String,
    onSelect: (String) -> Unit,
    allModels: List<ModelInfo>,
    networkLights: NetworkLightsState? = null,
    onOpenTailscaleSettings: (() -> Unit)? = null,
    onRetryTailscale: (() -> Unit)? = null,
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

        if (networkLights != null) {
            NetworkLightsRow(
                state = networkLights,
                onOpenTailscaleSettings = onOpenTailscaleSettings,
                onRetryTailscale = onRetryTailscale ?: rememberTailscaleRetry(),
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }

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
                        .testTag(UiTags.chatModelOption(model.selectionId)),
                    networkBadge = networkLights?.badgeFor(model),
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
    val providerManager = ProviderManager.getInstance()
    val providers by providerManager.providers.collectAsStateWithLifecycle()
    val providerModels by providerManager.providerModels.collectAsStateWithLifecycle()
    val modelKey = session?.modelId?.takeIf(String::isNotBlank)
    val schemesFlow = remember(modelKey) {
        modelKey?.let(chatViewModel::parameterSchemes) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val schemes by schemesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val profile = remember(modelKey, providers, providerModels) {
        sessionModelParameterProfile(modelKey, providers, providerModels)
    }
    val storedEffort = com.promenar.nexara.data.model.params.ReasoningEffort.fromWireName(
        params.reasoningEffort ?: agentDefaults?.reasoningEffort,
    )
    val editor = sessionSamplingEditorState(params, agentDefaults, samplingParamLayout(profile, storedEffort), schemes)
    val actions = remember(chatViewModel, modelKey, editor) {
        SamplingEditorActions(
            onApplyModelDefault = chatViewModel::applyModelDefaultSampling,
            onApplyScheme = chatViewModel::applyParameterScheme,
            onParamChange = chatViewModel::updateSamplingParam,
            onSaveScheme = { name -> modelKey?.let { chatViewModel.saveParameterScheme(it, name, editor.savableValues) } },
            onOverwriteScheme = { scheme -> chatViewModel.overwriteParameterScheme(scheme, editor.savableValues) },
            onRenameScheme = chatViewModel::renameParameterScheme,
            onDeleteScheme = chatViewModel::deleteParameterScheme,
        )
    }
    SessionParamsContent(
        params = params,
        editor = editor,
        modelName = modelKey?.let { resolveModelDisplayName(it, providerModels) },
        actions = actions,
        onParamsChange = chatViewModel::updateInferenceParams,
    )
}

/** 会话参数页的编辑器数据；未识别的模型显示全部参数。 */
internal fun sessionSamplingEditorState(
    params: com.promenar.nexara.data.model.InferenceParams,
    agentDefaults: com.promenar.nexara.data.model.InferenceParams?,
    layout: SamplingParamLayout,
    schemes: List<com.promenar.nexara.data.model.params.ParameterScheme>,
) = SamplingEditorState(
    own = params.sampling,
    inherited = agentDefaults?.sampling ?: com.promenar.nexara.data.model.params.SamplingValues(),
    followsScheme = params.parameterSchemeId != null,
    baseSchemeId = params.parameterSchemeId,
    layout = layout,
    schemes = schemes,
)

@Composable
internal fun SessionParamsContent(
    params: com.promenar.nexara.data.model.InferenceParams,
    editor: SamplingEditorState = sessionSamplingEditorState(params, null, SamplingParamLayout(SAMPLING_PARAM_ORDER), emptyList()),
    modelName: String? = null,
    actions: SamplingEditorActions = SamplingEditorActions(),
    onParamsChange: (com.promenar.nexara.data.model.InferenceParams) -> Unit = {},
    initialExpanded: com.promenar.nexara.data.model.params.SamplingParam? = null,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = NexaraSpacing.ScreenHorizontal), state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 24.dp)) {
        item {
            ParameterSchemeSection(editor, actions, modelName, scrollState = listState)
        }
        item {
            NexaraSettingsSection(title = stringResource(R.string.params_section_sampling)) {
                SamplingParamRows(editor, actions, ownLabel = stringResource(R.string.params_source_session), initialExpanded = initialExpanded)
            }
        }
        item {
            val timeout = params.streamTimeout ?: STREAM_TIMEOUT_DEFAULT_SECONDS
            NexaraSettingsSection(title = stringResource(R.string.params_section_request)) {
                NexaraStepperSetting(
                    title = stringResource(R.string.sheet_settings_stream_timeout),
                    value = stringResource(R.string.session_numeric_seconds_value, timeout),
                    onDecrease = { onParamsChange(params.copy(streamTimeout = (timeout - STREAM_TIMEOUT_STEP_SECONDS).coerceAtLeast(STREAM_TIMEOUT_MIN_SECONDS))) },
                    onIncrease = { onParamsChange(params.copy(streamTimeout = (timeout + STREAM_TIMEOUT_STEP_SECONDS).coerceAtMost(STREAM_TIMEOUT_MAX_SECONDS))) },
                    canDecrease = timeout > STREAM_TIMEOUT_MIN_SECONDS,
                    canIncrease = timeout < STREAM_TIMEOUT_MAX_SECONDS,
                )
            }
        }
    }
}

private const val STREAM_TIMEOUT_DEFAULT_SECONDS = 120
private const val STREAM_TIMEOUT_MIN_SECONDS = 30
private const val STREAM_TIMEOUT_MAX_SECONDS = 300
private const val STREAM_TIMEOUT_STEP_SECONDS = 10

@Composable
private fun ToolsPanel(chatViewModel: ChatViewModel, session: com.promenar.nexara.data.model.Session?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val skillsViewModel: SkillCatalogViewModel = viewModel(
        factory = SkillCatalogViewModel.factory(context.applicationContext as android.app.Application),
    )
    LaunchedEffect(session?.id) { skillsViewModel.bind(session?.id) }
    val skillsState by skillsViewModel.state.collectAsStateWithLifecycle()
    val skillsError by skillsViewModel.error.collectAsStateWithLifecycle()
    val resources = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(skillsError) {
        skillsError?.let { message ->
            android.widget.Toast.makeText(context, resources.getString(R.string.skills_runtime_error, message), android.widget.Toast.LENGTH_SHORT).show()
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
        executionMode = session?.executionMode?.ifEmpty { "workspace" } ?: "workspace",
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
    executionMode: String = "workspace",
    isGeminiModel: Boolean = false,
    onToolToggle: (String, Boolean) -> Unit = { _, _ -> },
    onExecutionModeChange: (String) -> Unit = {},
    skillsSection: @Composable () -> Unit = {},
    additionalSections: @Composable () -> Unit = {},
) {
    val selectedExecutionMode = ExecutionMode.valueOf(
        com.promenar.nexara.domain.model.ExecutionModeCodec.parseOrWorkspace(executionMode).name,
    )
    var showModePicker by remember { mutableStateOf(false) }
    val modePickerAnchor = rememberNexaraPopoverAnchor()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = NexaraSpacing.ScreenHorizontal, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
    ) {
        NexaraSettingsSection(title = stringResource(R.string.sheet_tool_section_usage)) {
            SettingsToggle(
                stringResource(R.string.sheet_tool_tools_enabled),
                checked = options.toolsEnabled,
                icon = Icons.Rounded.Build,
                onCheckedChange = { onToolToggle("toolsEnabled", it) },
                modifier = Modifier.testTag("session_tools_enabled"),
            )
            NexaraValueRow(
                title = stringResource(R.string.sheet_tool_execution_mode),
                value = stringResource(selectedExecutionMode.labelRes),
                onClick = { if (!modePickerAnchor.consumeRecentDismiss()) showModePicker = true },
                modifier = Modifier.nexaraPopoverAnchor(modePickerAnchor).testTag("session_execution_mode_picker"),
            )
        }
        skillsSection()
        additionalSections()
        NexaraSettingsSection(title = stringResource(R.string.agent_panels_session_options)) {
            SettingsToggle(
                stringResource(R.string.sheet_tool_time_injection),
                checked = options.enableTimeInjection,
                icon = Icons.Rounded.Timer,
                onCheckedChange = { onToolToggle("timeInjection", it) },
            )
            if (isGeminiModel) SettingsToggle(stringResource(R.string.sheet_tool_gemini_grounding), checked = options.enableGeminiSearch,
                icon = Icons.Rounded.Language, onCheckedChange = { onToolToggle("enableGeminiSearch", it) })
        }
    }
    NexaraBottomSheet(show = showModePicker, onDismiss = { showModePicker = false },
        title = stringResource(R.string.sheet_tool_execution_mode), scrollContent = true,
        popover = NexaraPopoverSpec(modePickerAnchor)) {
        ExecutionMode.entries.forEach { mode ->
            val label = stringResource(mode.labelRes)
            NexaraSettingsItem(
                horizontalPadding = 0.dp,
                title = if (mode == ExecutionMode.WORKSPACE) stringResource(R.string.numeric_default_value, label) else label,
                subtitle = stringResource(mode.descriptionRes),
                subtitleMaxLines = 3,
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
    val memoryPolicy by androidx.compose.runtime.produceState(
        initialValue = com.promenar.nexara.data.agent.AgentMemoryPolicy(), session?.agentId,
    ) { value = chatViewModel.agentMemoryPolicy() }
    val reachStats by androidx.compose.runtime.produceState(
        initialValue = emptyMap<com.promenar.nexara.data.model.ConversationScope, com.promenar.nexara.data.rag.ConversationReachStats>(),
        session?.id, memoryPolicy,
    ) { value = runCatching { chatViewModel.conversationReachStats() }.getOrDefault(emptyMap()) }
    val graphQueue by chatViewModel.sessionGraphQueue.collectAsStateWithLifecycle()
    SessionContextPanelContent(
        params = session?.inferenceParams ?: com.promenar.nexara.data.model.InferenceParams(),
        ragOptions = session?.ragOptions ?: com.promenar.nexara.data.model.RagOptions(),
        memoryPolicy = memoryPolicy,
        reachStats = reachStats,
        graphQueue = graphQueue,
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
    memoryPolicy: com.promenar.nexara.data.agent.AgentMemoryPolicy = com.promenar.nexara.data.agent.AgentMemoryPolicy(),
    /** 三档对话范围各覆盖的会话数；为空时不显示。 */
    reachStats: Map<com.promenar.nexara.data.model.ConversationScope, com.promenar.nexara.data.rag.ConversationReachStats> = emptyMap(),
    /** 会话图谱后台抽取队列；全局开关关闭时为 null，不显示。 */
    graphQueue: com.promenar.nexara.data.rag.SessionGraphQueueStatus? = null,
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
        val fullyDisabled = !ragOptions.enableMemory && !ragOptions.enableDocs && ragOptions.enableKnowledgeGraph != true
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
            SettingsToggle(stringResource(R.string.sheet_settings_docs), checked = ragOptions.enableDocs, icon = Icons.Rounded.Description,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.Documents, it) } })
            SettingsToggle(stringResource(R.string.sheet_tool_rerank), checked = isRerankAvailable && ragOptions.enableRerank,
                icon = Icons.Rounded.Sync, enabled = isRerankAvailable,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.Rerank, it) } })
            SettingsToggle(stringResource(R.string.sheet_settings_kg), checked = ragOptions.enableKnowledgeGraph ?: false, icon = Icons.Rounded.Psychology,
                onCheckedChange = { onRagOptionsChange { current -> current.withRetrievalToggle(SessionRetrievalToggle.KnowledgeGraph, it) } })
        }
        SessionScopeSection(ragOptions, memoryPolicy, onRagOptionsChange, reachStats, graphQueue)
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
