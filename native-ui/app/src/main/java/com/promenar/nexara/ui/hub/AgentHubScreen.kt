package com.promenar.nexara.ui.hub

import androidx.compose.animation.Crossfade
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import com.promenar.nexara.ui.common.GenerationDots
import com.promenar.nexara.ui.theme.NexaraMotion

import androidx.compose.runtime.key

import androidx.compose.animation.core.tween

import androidx.compose.animation.animateContentSize

import androidx.compose.animation.AnimatedContent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LocalTextStyle
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.PresetAgentDisplay
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.common.toModelSelectionUiModel
import com.promenar.nexara.data.model.catalog.ModelCatalogRuntime
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.nexaraDomainColors
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.forInput
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.promenar.nexara.ui.common.NexaraMenuItem
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.common.rememberNexaraMenuTouch
import com.promenar.nexara.ui.common.nexaraMenuTouch
import com.promenar.nexara.ui.common.nexaraMenuHost
import com.promenar.nexara.ui.common.NexaraMenuDivider

// =====================================================================================
// Route —— 拥有 ViewModel、localized preset display 映射与 overlay 状态
// =====================================================================================

@Composable
fun AgentHubScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToAgentEdit: (String) -> Unit,
    onOpenLinuxEnvironment: () -> Unit = {},
    /** 内屏三列：点会话改为在右侧会话列选中；为 null 时打开整页会话。 */
    threePane: HubThreePaneHost? = null,
) {
    val context = LocalContext.current
    val viewModel: AgentHubViewModel = viewModel(
        factory = AgentHubViewModel.factory(context.applicationContext as android.app.Application),
    )
    val sessionListViewModel: SessionListViewModel = viewModel(
        factory = SessionListViewModel.factory(context.applicationContext as android.app.Application),
    )
    LaunchedEffect(Unit) {
        // 全量刷新会话目录，供“置顶”“最近”、各助手会话计数与展开列表使用
        sessionListViewModel.loadSessions()
    }
    val operationFailed by sessionListViewModel.operationFailed.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val operationFailedMessage = stringResource(R.string.sessions_operation_failed)
    LaunchedEffect(operationFailed) {
        if (operationFailed) {
            snackbarHostState.showSnackbar(operationFailedMessage)
            sessionListViewModel.dismissOperationFailure()
        }
    }
    val agents by viewModel.agents.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val allSessions by sessionListViewModel.sessions.collectAsState()
    val activeGeneration by (context.applicationContext as com.promenar.nexara.NexaraApplication)
        .generationCoordinator.sessions.collectAsState()
    val sessionsByAgent = remember(allSessions) {
        allSessions.groupBy { it.agentId }
    }
    val displayAgents = agents.map { agent ->
        val (title, subtitle) = resolveAgentDisplay(agent)
        AgentDisplayItem(agent, title, subtitle)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    val selectedSessionId = threePane?.state?.hubSessionId
    val sessionActivity = hubSessionActivities(activeGeneration, allSessions)
    val createSession: (String, (String) -> Unit) -> Unit = { agentId, open ->
        sessionListViewModel.createSession(agentId) { sessionId -> open(sessionId) }
    }
    // 列表本身与它的弹窗：三列时放在列表列里，点会话在会话列选中。
    @Composable
    fun HubListContent(openChat: (String) -> Unit, selectedSessionId: String?, closeSessions: (Set<String>) -> Unit = {}) {
        var agentToDelete by remember { mutableStateOf<String?>(null) }
        var pendingDeleteSessionId by remember { mutableStateOf<String?>(null) }
        var searchActive by rememberSaveable { mutableStateOf(false) }

        val state = AgentHubScreenState(
            displayAgents = displayAgents,
            searchQuery = searchQuery,
            searchActive = searchActive,
            showAddDialog = showAddDialog,
            pendingDeleteAgentId = agentToDelete,
            pendingDeleteSessionId = pendingDeleteSessionId,
            sessionsByAgent = sessionsByAgent,
            sessionActivity = sessionActivity,
            selectedSessionId = selectedSessionId,
        )
        val actions = AgentHubScreenActions(
            onSearch = viewModel::updateSearchQuery,
            onSearchActiveChange = { searchActive = it },
            onRequestAdd = { showAddDialog = true },
            onCancelAdd = { showAddDialog = false },
            onCreateAgent = { name, desc, model, systemPrompt ->
                viewModel.createAgent(name, desc, model, systemPrompt)
                showAddDialog = false
            },
            onRequestDelete = { agentToDelete = it },
            onCancelDelete = { agentToDelete = null },
            onConfirmDelete = { id ->
                closeSessions(sessionsByAgent[id].orEmpty().mapTo(HashSet()) { it.id })
                viewModel.deleteAgent(id)
                agentToDelete = null
            },
            onTogglePin = viewModel::togglePin,
            onEdit = onNavigateToAgentEdit,
            onOpenChat = openChat,
            onCreateSession = { agentId -> createSession(agentId, openChat) },
            onToggleSessionPin = sessionListViewModel::pinSession,
            onRequestDeleteSession = { sessionId ->
                pendingDeleteSessionId = sessionId
            },
            onCancelDeleteSession = {
                pendingDeleteSessionId = null
            },
            onDeleteSession = { sessionId, keepFiles ->
                closeSessions(setOf(sessionId))
                sessionListViewModel.deleteSession(sessionId, keepFiles)
                pendingDeleteSessionId = null
            },
            loadWorkspaceSummary = (LocalContext.current.applicationContext as? com.promenar.nexara.NexaraApplication)
                ?.let { app -> app::privateWorkspaceSummary },
        )

        // 启动自检问题：照常进入，列表顶部提示受影响的范围，点开处置或交给 Agent 诊断。
        val app = LocalContext.current.applicationContext as? com.promenar.nexara.NexaraApplication
        val startupIssues = app?.startupIssues?.collectAsState()?.value.orEmpty()
        var showStartupIssues by remember { mutableStateOf(false) }
        // 启动时的非阻断提示：Linux 不可用时列出受影响的 Linux 会话数；环境状态变化后重新统计。
        val linuxState = app?.linux?.runtime?.state?.collectAsState()?.value
        var linuxAffected by remember { mutableStateOf(0) }
        LaunchedEffect(app, linuxState) {
            linuxAffected = if (app == null || !com.promenar.nexara.distribution.DistributionCapabilities.linuxRuntimeAvailable) 0 else runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.sessionRunModes.startupCheck().affectedSessionCount }
            }.getOrDefault(0)
        }
        AgentHubScreenContent(
            state = state,
            actions = actions,
            snackbarHostState = snackbarHostState,
            startupBanner = {
                if (startupIssues.isNotEmpty()) {
                    com.promenar.nexara.ui.startup.StartupIssuesBanner(startupIssues.size, onOpen = { showStartupIssues = true })
                }
                if (linuxAffected > 0) {
                    com.promenar.nexara.ui.startup.LinuxUnavailableBanner(linuxAffected, onOpen = onOpenLinuxEnvironment)
                }
            },
        )
        if (showStartupIssues && app != null) {
            com.promenar.nexara.ui.startup.StartupIssuesSheet(
                onDismiss = { showStartupIssues = false },
                diagnosisAgentId = { issues ->
                    issues.firstNotNullOfOrNull { issue ->
                        allSessions.firstOrNull { it.id == issue.scope.id }?.agentId
                    } ?: displayAgents.firstOrNull()?.agent?.id
                },
                onOpenSession = openChat,
            )
        }

        if (state.showAddDialog) {
            AddAgentDialog(
                onDismiss = actions.onCancelAdd,
                onConfirm = actions.onCreateAgent,
            )
        }
    }

    if (threePane != null) {
        HubThreePane(
            host = threePane,
            strip = hubStripEntries(displayAgents, sessionsByAgent, selectedSessionId),
            activity = sessionActivity,
            empty = { open ->
                HubPaneEmpty(
                    agents = displayAgents,
                    onCreateSession = { agentId -> createSession(agentId, open) },
                    onRequestAdd = { showAddDialog = true },
                )
            },
            list = { open, close -> HubListContent(open, selectedSessionId, close) },
        )
    } else {
        HubListContent(onNavigateToChat, null)
    }
}

// =====================================================================================
// AddAgentDialog —— overlay，依赖 ProviderManager / SettingsViewModel，由 Route 持有
// =====================================================================================

@Composable
private fun AddAgentDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit
) {
    val pm = ProviderManager.getInstance()
    val defaultModel by pm.summaryModelId.collectAsState()

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(defaultModel) }
    var systemPrompt by remember { mutableStateOf("") }
    var showPromptEditor by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(context.applicationContext as android.app.Application)
    )
    val allModels by settingsViewModel.providerModels.collectAsState()
    val resolver = ModelCatalogRuntime.resolver
    val modelItems = remember(allModels, resolver) {
        allModels.filter { it.enabled }.map { it.toModelSelectionUiModel(resolver) }
    }

    UnifiedPromptEditor(
        show = showPromptEditor,
        onDismiss = { showPromptEditor = false },
        onSave = {
            systemPrompt = it
            Result.success(Unit)
        },
        title = stringResource(R.string.hub_dialog_label_prompt),
        initialText = systemPrompt,
        placeholder = stringResource(R.string.agent_edit_prompt_placeholder),
        mode = EditorMode.DIALOG
    )

    ModelPicker(
        show = showModelPicker,
        onDismiss = { showModelPicker = false },
        filterTag = "chat",
        models = modelItems,
        onSelect = { modelId, modelName ->
            model = modelId
            showModelPicker = false
        },
        currentModelId = model
    )

    NexaraConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.hub_dialog_add_title),
        message = "",
        confirmText = stringResource(R.string.shared_btn_add),
        cancelText = stringResource(R.string.common_btn_cancel),
        onConfirm = { onConfirm(name, description, model, systemPrompt) },
        onCancel = onDismiss,
        confirmEnabled = name.isNotBlank(),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.hub_dialog_label_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.hub_dialog_label_desc)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showModelPicker = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.hub_dialog_label_model),
                                style = NexaraTypography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = model.ifBlank { stringResource(R.string.hub_dialog_placeholder_select_model) },
                                style = NexaraTypography.bodyMedium,
                                color = if (model.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPromptEditor = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.hub_dialog_label_prompt),
                            style = NexaraTypography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = systemPrompt.ifBlank { stringResource(R.string.agent_edit_prompt_hint) },
                            style = NexaraTypography.bodyMedium,
                            color = if (systemPrompt.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
    )
}

// =====================================================================================
// 状态提升 seam —— Content 只消费显式 state/actions，不触碰 ViewModel / ProviderManager
// =====================================================================================

internal data class AgentHubScreenState(
    val displayAgents: List<AgentDisplayItem> = emptyList(),
    val searchQuery: String = "",
    val searchActive: Boolean = false,
    val showAddDialog: Boolean = false,
    val pendingDeleteAgentId: String? = null,
    val pendingDeleteSessionId: String? = null,
    val sessionsByAgent: Map<String, List<Session>> = emptyMap(),
    /** 正在生成或等待批准的会话，列表行以此替换时间显示状态。 */
    val sessionActivity: Map<String, HubSessionActivity> = emptyMap(),
    /** 内屏三列时会话列正在显示的会话，列表中高亮。 */
    val selectedSessionId: String? = null,
)

internal enum class HubSessionActivity { GENERATING, QUEUED, AWAITING_APPROVAL, AWAITING_ANSWER }

/**
 * 应用级生成任务投影到会话列表：进行中标为生成中，排队、等待批准与待回答各自标出，已结束的不标。
 * 待回答以会话持久化的 [LoopStatus.WAITING_FOR_INPUT] 为准，生成任务退出后仍保留。
 */
internal fun hubSessionActivities(
    active: Map<String, GenerationTaskSnapshot>,
    sessions: List<Session> = emptyList(),
): Map<String, HubSessionActivity> {
    val waiting = sessions.filter { it.loopStatus == LoopStatus.WAITING_FOR_INPUT }
        .associate { it.id to HubSessionActivity.AWAITING_ANSWER }
    return waiting + active.mapNotNull { (sessionId, snapshot) ->
        val activity = when (snapshot.phase) {
            GenerationPhase.COMPLETED, GenerationPhase.FAILED, GenerationPhase.CANCELLED, GenerationPhase.PERSISTENCE_FAILED -> null
            GenerationPhase.WAITING_APPROVAL -> HubSessionActivity.AWAITING_APPROVAL
            GenerationPhase.WAITING_INPUT -> HubSessionActivity.AWAITING_ANSWER
            GenerationPhase.QUEUED -> HubSessionActivity.QUEUED
            else -> HubSessionActivity.GENERATING
        }
        activity?.let { sessionId to it }
    }
}

internal data class AgentHubScreenActions(
    val onSearch: (String) -> Unit = {},
    val onSearchActiveChange: (Boolean) -> Unit = {},
    val onRequestAdd: () -> Unit = {},
    val onCancelAdd: () -> Unit = {},
    val onCreateAgent: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    val onRequestDelete: (String) -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: (String) -> Unit = {},
    val onTogglePin: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onOpenChat: (String) -> Unit = {},
    val onCreateSession: (String) -> Unit = {},
    val onToggleSessionPin: (String) -> Unit = {},
    val onRequestDeleteSession: (String) -> Unit = {},
    val onCancelDeleteSession: () -> Unit = {},
    val onDeleteSession: (sessionId: String, keepFiles: Boolean) -> Unit = { _, _ -> },
    val loadWorkspaceSummary: (suspend (String) -> com.promenar.nexara.domain.workspace.PrivateWorkspaceSummary?)? = null,
)

internal data class HubSessionEntry(val session: Session, val agentTitle: String)

internal data class HubAgentEntry(val item: AgentDisplayItem, val sessionCount: Int)

/**
 * 对话页分区：未搜索时为“置顶”“最近”“助手”，搜索时 [sessions] 为匹配的会话。
 * 只收录列表中存在的助手的会话，系统助手名下的资料会话等不会出现。
 */
internal data class HubSections(
    val pinnedSessions: List<HubSessionEntry>,
    val sessions: List<HubSessionEntry>,
    val agents: List<HubAgentEntry>,
) {
    val isEmpty: Boolean get() = pinnedSessions.isEmpty() && sessions.isEmpty() && agents.isEmpty()
}

internal const val HUB_RECENT_LIMIT = 5
internal const val HUB_PINNED_LIMIT = 20
internal const val HUB_SEARCH_SESSION_LIMIT = 50

internal fun buildHubSections(
    agents: List<AgentDisplayItem>,
    sessionsByAgent: Map<String, List<Session>>,
    query: String,
): HubSections {
    val agentTitles = agents.associate { it.agent.id to it.title }
    val sessions = agents.flatMap { sessionsByAgent[it.agent.id].orEmpty() }
    fun List<Session>.entries() = map { HubSessionEntry(it, agentTitles.getValue(it.agentId)) }
    val needle = query.trim()
    if (needle.isNotEmpty()) {
        val matches = sessions
            .filter {
                it.title.contains(needle, ignoreCase = true) ||
                    it.lastMessage?.contains(needle, ignoreCase = true) == true
            }
            .sortedWith(compareByDescending<Session> { it.isPinned }.thenByDescending { it.updatedAt })
            .take(HUB_SEARCH_SESSION_LIMIT)
        return HubSections(
            pinnedSessions = emptyList(),
            sessions = matches.entries(),
            agents = filterAgentDisplays(agents, needle).map { HubAgentEntry(it, sessionsByAgent[it.agent.id]?.size ?: 0) },
        )
    }
    val (pinned, others) = sessions.sortedByDescending { it.updatedAt }.partition { it.isPinned }
    return HubSections(
        pinnedSessions = pinned.take(HUB_PINNED_LIMIT).entries(),
        sessions = others.take(HUB_RECENT_LIMIT).entries(),
        agents = agents.map { HubAgentEntry(it, sessionsByAgent[it.agent.id]?.size ?: 0) },
    )
}

/** 当天显示时刻，当年显示月/日，更早的带年份。 */
internal fun formatHubSessionTime(
    epochMillis: Long,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val pattern = when {
        time.toLocalDate() == now.toLocalDate() -> "HH:mm"
        time.year == now.year -> "M/d"
        else -> "yyyy/M/d"
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(time)
}

@Composable
internal fun AgentHubScreenContent(
    state: AgentHubScreenState,
    actions: AgentHubScreenActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initialExpandedAgentId: String? = null,
    /** 标题栏下方的启动自检提示；没有问题时不占位。 */
    startupBanner: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val searching = state.searchActive && state.searchQuery.isNotBlank()
    val sections = remember(state.displayAgents, state.sessionsByAgent, searching, state.searchQuery) {
        buildHubSections(state.displayAgents, state.sessionsByAgent, if (searching) state.searchQuery else "")
    }
    // 会话目录晚于助手加载：会话分组插到助手分组之前时，列表会按原首项锚定而停在中途，停在顶部附近时回到顶部。
    val sessionGroupsShown = sections.pinnedSessions.isNotEmpty() to sections.sessions.isNotEmpty()
    // 同一时间只展开一个助手，就地列出它的会话与“新建会话”。
    var expandedAgentId by rememberSaveable { mutableStateOf(initialExpandedAgentId) }
    val haptics = LocalNexaraHaptics.current
    LaunchedEffect(sessionGroupsShown) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    Scaffold(
        modifier = Modifier.testTag(UiTags.HUB_ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                NexaraTabHeader(
                    title = stringResource(R.string.nav_tab_chat),
                    searchActive = state.searchActive,
                    query = state.searchQuery,
                    onQueryChange = actions.onSearch,
                    onSearchActiveChange = actions.onSearchActiveChange.takeIf { state.displayAgents.isNotEmpty() },
                    searchFieldTag = UiTags.HUB_SEARCH,
                ) {
                    HubNewChatAction(
                        agents = state.displayAgents,
                        onCreateSession = actions.onCreateSession,
                        onRequestAdd = actions.onRequestAdd,
                    )
                }
                startupBanner()
            }
        },
    ) { paddingValues ->
        ConfirmDialog(
            show = state.pendingDeleteAgentId != null,
            onDismiss = actions.onCancelDelete,
            onConfirm = {
                state.pendingDeleteAgentId?.let(actions.onConfirmDelete)
            },
            title = stringResource(R.string.agent_edit_delete_title),
            description = stringResource(R.string.agent_edit_delete_message),
            confirmLabel = stringResource(R.string.agent_edit_delete_confirm),
            destructive = true
        )
        state.pendingDeleteSessionId?.let { sessionId ->
            com.promenar.nexara.ui.workspace.DeleteSessionDialog(
                sessionId = sessionId,
                loadSummary = actions.loadWorkspaceSummary,
                onConfirm = { keepFiles -> actions.onDeleteSession(sessionId, keepFiles) },
                onCancel = actions.onCancelDeleteSession,
            )
        }

        // 无助手、搜索无结果与列表之间交叉淡入。
        AnimatedContent(
            targetState = when {
                state.displayAgents.isEmpty() -> 0
                searching && sections.isEmpty -> 1
                else -> 2
            },
            transitionSpec = { NexaraMotion.fadeThrough() },
            label = "hub_content_phase",
        ) { phase ->
            when (phase) {
                0 -> EmptyAgentState(
                    onCreateAgent = actions.onRequestAdd,
                    modifier = Modifier.padding(paddingValues),
                )

                1 -> HubSearchEmptyState(modifier = Modifier.padding(paddingValues))

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(UiTags.HUB_AGENT_LIST),
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding() + NexaraSpacing.Small,
                        bottom = paddingValues.calculateBottomPadding() + NexaraSpacing.XLarge,
                    ),
                ) {
                    if (sections.pinnedSessions.isNotEmpty()) {
                        item(key = "pinned") {
                            HubSessionSection(
                                modifier = Modifier.animateItem(),
                                title = stringResource(R.string.hub_section_pinned),
                                entries = sections.pinnedSessions,
                                activity = state.sessionActivity,
                                selectedSessionId = state.selectedSessionId,
                                actions = actions,
                            )
                        }
                    }
                    if (sections.sessions.isNotEmpty()) {
                        item(key = "sessions") {
                            HubSessionSection(
                                modifier = Modifier.animateItem(),
                                title = stringResource(if (searching) R.string.hub_section_sessions else R.string.hub_section_recent),
                                entries = sections.sessions,
                                activity = state.sessionActivity,
                                selectedSessionId = state.selectedSessionId,
                                actions = actions,
                            )
                        }
                    }
                    if (sections.agents.isNotEmpty() || !searching) {
                        item(key = "agents") {
                            NexaraSettingsSection(
                                title = stringResource(R.string.hub_section_agents),
                                headerStartPadding = NexaraSpacing.ScreenHorizontal,
                                modifier = Modifier.animateItem(),
                            ) {
                                sections.agents.forEach { entry ->
                                    val agentId = entry.item.agent.id
                                    key(agentId) {
                                    val expanded = expandedAgentId == agentId
                                    HubAgentRow(
                                        entry = entry,
                                        expanded = expanded,
                                        onToggleExpand = {
                                            // 展开与收起分别使用开/关两档轻振，与开关控件的触感一致。
                                            haptics.toggle(!expanded)
                                            expandedAgentId = if (expanded) null else agentId
                                        },
                                        onTogglePin = { actions.onTogglePin(agentId) },
                                        onEdit = { actions.onEdit(agentId) },
                                        onDelete = { actions.onRequestDelete(agentId) },
                                    )
                                    AnimatedVisibility(visible = expanded, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                                        HubAgentSessions(
                                            agentId = agentId,
                                            sessions = state.sessionsByAgent[agentId].orEmpty(),
                                            activity = state.sessionActivity,
                                            selectedSessionId = state.selectedSessionId,
                                            actions = actions,
                                        )
                                    }
                                    }
                                }
                                if (!searching) HubAddAgentRow(onClick = actions.onRequestAdd)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubSessionSection(
    title: String,
    entries: List<HubSessionEntry>,
    activity: Map<String, HubSessionActivity>,
    actions: AgentHubScreenActions,
    modifier: Modifier = Modifier,
    selectedSessionId: String? = null,
) {
    NexaraSettingsSection(
        title = title,
        headerStartPadding = NexaraSpacing.ScreenHorizontal,
        modifier = modifier,
    ) {
        // 置顶、删除或新会话进入时，分组高度平滑变化而不是整块跳动。
        Column(Modifier.animateContentSize(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate))) {
            entries.forEach { entry ->
                val sessionId = entry.session.id
                key(sessionId) {
                    HubSessionRow(
                        session = entry.session,
                        subtitle = entry.agentTitle,
                        activity = activity[sessionId],
                        selected = sessionId == selectedSessionId,
                        onOpen = { actions.onOpenChat(sessionId) },
                        onTogglePin = { actions.onToggleSessionPin(sessionId) },
                        onRequestDelete = { actions.onRequestDeleteSession(sessionId) },
                    )
                }
            }
        }
    }
}

/** 页头“新对话”：选择助手后直接新建会话；还没有助手时转为添加助手。 */
@Composable
private fun HubNewChatAction(
    agents: List<AgentDisplayItem>,
    onCreateSession: (String) -> Unit,
    onRequestAdd: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { if (agents.isEmpty()) onRequestAdd() else expanded = true },
            modifier = Modifier
                .size(NexaraSpacing.MinimumTouchTarget)
                .testTag(UiTags.HUB_NEW_CHAT),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.hub_new_chat),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HubNewChatMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            agents = agents,
            onCreateSession = onCreateSession,
            onRequestAdd = onRequestAdd,
        )
    }
}

/** “新对话”的助手选择菜单：选择助手后直接新建会话，末项添加助手。页头与内屏会话列的新建入口共用。 */
@Composable
internal fun HubNewChatMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    agents: List<AgentDisplayItem>,
    onCreateSession: (String) -> Unit,
    onRequestAdd: () -> Unit,
) {
    NexaraDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        minWidth = 200.dp,
    ) {
        Text(
            text = stringResource(R.string.hub_new_chat),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = NexaraSpacing.Medium, vertical = NexaraSpacing.Small),
        )
        agents.forEach { item ->
            NexaraMenuItem(
                leadingIcon = {
                    AgentAvatar(
                        icon = agentIconVector(item.agent.icon),
                        customImageUri = item.agent.avatarPath,
                        backgroundColor = agentAvatarColor(item.agent),
                        size = 28.dp,
                    )
                },
                text = {
                    Text(
                        text = item.title,
                        style = NexaraTypography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                onClick = {
                    onDismiss()
                    onCreateSession(item.agent.id)
                },
                modifier = Modifier.testTag(UiTags.hubNewChatAgent(item.agent.id)),
            )
        }
        NexaraMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            text = { Text(stringResource(R.string.hub_btn_add_agent), style = NexaraTypography.labelLarge) },
            onClick = {
                onDismiss()
                onRequestAdd()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubSessionRow(
    session: Session,
    subtitle: String?,
    activity: HubSessionActivity?,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRequestDelete: () -> Unit,
    selected: Boolean = false,
    tag: String = UiTags.hubSession(session.id),
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val menuTouch = rememberNexaraMenuTouch()
    val pinLabel = stringResource(if (session.isPinned) R.string.common_cd_unpin else R.string.common_cd_pin)
    val deleteLabel = stringResource(R.string.shared_btn_delete)
    val pinnedState = stringResource(R.string.sessions_tag_pinned)
    val activityState = activity?.let { stringResource(hubSessionActivityLabel(it)) }
    val time = remember(session.updatedAt) {
        formatHubSessionTime(session.updatedAt, System.currentTimeMillis())
    }
    Box {
        NexaraSettingsRowLayout(
            title = session.title.ifBlank { stringResource(R.string.hub_session_untitled) },
            subtitle = subtitle,
            modifier = Modifier
                .testTag(tag)
                // 内屏三列：会话列正在显示的会话与设置目录的选中项同样高亮。
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .semantics {
                    if (selected) this.selected = true
                    listOfNotNull(pinnedState.takeIf { session.isPinned }, activityState)
                        .takeIf { it.isNotEmpty() }?.let { stateDescription = it.joinToString("，") }
                    customActions = listOf(
                        CustomAccessibilityAction(pinLabel) { onTogglePin(); true },
                        CustomAccessibilityAction(deleteLabel) { onRequestDelete(); true },
                    )
                }
                .nexaraMenuHost(menuExpanded)
                .nexaraMenuTouch(menuTouch)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuExpanded = true },
                    onLongClickLabel = stringResource(R.string.hub_cd_session_actions),
                ),
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(NexaraSpacing.XSmall))
                    }
                    // 生成中以三点波浪、等待批准以文字替换时间，回到首页一眼看出哪个会话还在跑。
                    Crossfade(targetState = activity, label = "hub_session_activity") { current ->
                        when (current) {
                            HubSessionActivity.GENERATING -> GenerationDots(
                                dotSize = 5.dp, gap = 4.dp, lift = 3.dp,
                                modifier = Modifier.testTag(UiTags.hubSessionGenerating(tag)),
                            )
                            HubSessionActivity.AWAITING_APPROVAL -> Text(
                                text = stringResource(R.string.hub_session_awaiting_approval),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                            // 待回答与提问卡同用琥珀色；排队用中性色，表示尚未开始。
                            HubSessionActivity.AWAITING_ANSWER -> Text(
                                text = stringResource(R.string.ask_card_status_waiting),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                color = MaterialTheme.nexaraDomainColors.warning,
                                maxLines = 1,
                                modifier = Modifier.testTag(UiTags.hubSessionAwaitingAnswer(tag)),
                            )
                            HubSessionActivity.QUEUED -> Text(
                                text = stringResource(R.string.ask_card_status_queued),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            null -> Text(
                                text = time,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            },
        )
        NexaraDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = androidx.compose.ui.unit.DpOffset(NexaraSpacing.RowHorizontal, 0.dp),
            touch = menuTouch,
        ) {
            HubPinMenuItem(isPinned = session.isPinned) {
                menuExpanded = false
                onTogglePin()
            }
            NexaraMenuDivider()
            HubDeleteMenuItem {
                menuExpanded = false
                onRequestDelete()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubAgentRow(
    entry: HubAgentEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val agent = entry.item.agent
    var menuExpanded by remember { mutableStateOf(false) }
    val menuTouch = rememberNexaraMenuTouch()
    val pinLabel = stringResource(if (agent.isPinned) R.string.common_cd_unpin else R.string.common_cd_pin)
    val editLabel = stringResource(R.string.shared_btn_edit)
    val deleteLabel = stringResource(R.string.shared_btn_delete)
    val rowState = listOfNotNull(
        stringResource(R.string.sessions_tag_pinned).takeIf { agent.isPinned },
        stringResource(if (expanded) R.string.common_state_expanded else R.string.common_state_collapsed),
    ).joinToString(", ")
    Box {
        HubRowLayout(
            modifier = Modifier
                .testTag(UiTags.hubAgentCard(agent.id))
                .semantics {
                    stateDescription = rowState
                    customActions = listOf(
                        CustomAccessibilityAction(pinLabel) { onTogglePin(); true },
                        CustomAccessibilityAction(editLabel) { onEdit(); true },
                        CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                }
                .nexaraMenuHost(menuExpanded)
                .nexaraMenuTouch(menuTouch)
                .combinedClickable(
                    onClick = onToggleExpand,
                    onLongClick = { menuExpanded = true },
                    onLongClickLabel = stringResource(R.string.hub_cd_agent_actions),
                ),
            leading = {
                Box(
                    modifier = if (agent.avatarPath != null) {
                        Modifier.testTag(UiTags.hubAgentCustomAvatar(agent.id))
                    } else {
                        Modifier
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    AgentAvatar(
                        icon = agentIconVector(agent.icon),
                        customImageUri = agent.avatarPath,
                        backgroundColor = agentAvatarColor(agent),
                        size = 40.dp,
                    )
                }
            },
            title = entry.item.title,
            // 会话数放在副标题开头，右侧只留展开箭头，长名称与大字号下标题不被挤压。
            subtitle = listOf(
                pluralStringResource(R.plurals.hub_agent_session_count, entry.sessionCount, entry.sessionCount),
                entry.item.subtitle,
            ).filter { it.isNotBlank() }.joinToString(" · "),
            pinned = agent.isPinned,
            trailing = {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            },
        )
        NexaraDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = androidx.compose.ui.unit.DpOffset(NexaraSpacing.RowHorizontal, 0.dp),
            touch = menuTouch,
        ) {
            HubPinMenuItem(isPinned = agent.isPinned, modifier = Modifier.testTag(UiTags.HUB_AGENT_MENU_PIN)) {
                menuExpanded = false
                onTogglePin()
            }
            NexaraMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = { Text(editLabel, style = NexaraTypography.labelLarge) },
                onClick = {
                    menuExpanded = false
                    onEdit()
                },
                modifier = Modifier.testTag(UiTags.HUB_AGENT_MENU_EDIT)
            )
            NexaraMenuDivider()
            HubDeleteMenuItem(modifier = Modifier.testTag(UiTags.HUB_AGENT_MENU_DELETE)) {
                menuExpanded = false
                onDelete()
            }
        }
    }
}

/** 助手展开区：该助手的全部会话（长按置顶/删除）与“新建会话”。 */
@Composable
private fun HubAgentSessions(
    agentId: String,
    sessions: List<Session>,
    activity: Map<String, HubSessionActivity>,
    actions: AgentHubScreenActions,
    selectedSessionId: String? = null,
) {
    Column(Modifier.fillMaxWidth().animateContentSize(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate))) {
        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.hub_sessions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = NexaraSpacing.RowHorizontal,
                    end = NexaraSpacing.RowHorizontal,
                    top = NexaraSpacing.Small,
                    bottom = NexaraSpacing.Small,
                ),
            )
        }
        sessions.forEach { session ->
            HubSessionRow(
                session = session,
                subtitle = null,
                activity = activity[session.id],
                selected = session.id == selectedSessionId,
                onOpen = { actions.onOpenChat(session.id) },
                onTogglePin = { actions.onToggleSessionPin(session.id) },
                onRequestDelete = { actions.onRequestDeleteSession(session.id) },
                tag = UiTags.hubAgentSession(session.id),
            )
        }
        NexaraSettingsRowLayout(
            title = stringResource(R.string.sessions_btn_new),
            titleColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .testTag(UiTags.hubAgentNewSession(agentId))
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { actions.onCreateSession(agentId) }
                // 加号与下方“添加助手”的加号竖向对齐（后者居中在 40dp 头像位里）。
                .padding(start = (40.dp - NexaraSpacing.RowIconSize) / 2),
            leadingContent = {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
    }
}

@Composable
private fun HubAddAgentRow(onClick: () -> Unit) {
    HubRowLayout(
        modifier = Modifier
            .testTag(UiTags.HUB_ADD_AGENT)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        leading = {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        },
        title = stringResource(R.string.hub_btn_add_agent),
        titleColor = MaterialTheme.colorScheme.primary,
    )
}

/** 带头像的列表行：几何与设置行一致，头像位放大到 40dp。 */
@Composable
private fun HubRowLayout(
    modifier: Modifier,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    pinned: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    // 放大字号时标题与副标题各放宽到两行，与设置行的大字号处理一致。
    val maxLines = if (LocalDensity.current.fontScale > 1f) 2 else 1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.RowTwoLineHeight)
            .padding(horizontal = NexaraSpacing.RowHorizontal, vertical = NexaraSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(NexaraSpacing.Medium))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(NexaraSpacing.XSmall))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Spacer(Modifier.height(NexaraSpacing.RowTwoLineGap))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(NexaraSpacing.Medium))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
                content = trailing,
            )
        }
    }
}

@Composable
private fun HubPinMenuItem(isPinned: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    NexaraMenuItem(
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = null,
                tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        text = {
            Text(
                stringResource(if (isPinned) R.string.common_cd_unpin else R.string.common_cd_pin),
                style = NexaraTypography.labelLarge,
            )
        },
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun HubDeleteMenuItem(modifier: Modifier = Modifier, onClick: () -> Unit) {
    NexaraMenuItem(
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        },
        text = {
            Text(
                stringResource(R.string.shared_btn_delete),
                style = NexaraTypography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        },
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun HubSearchEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.common_search_no_results),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// =====================================================================================
// Empty State —— 使用 Material Icon，不使用 emoji
// =====================================================================================

@Composable
private fun EmptyAgentState(
    onCreateAgent: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(UiTags.HUB_EMPTY_STATE),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.hub_empty_title),
                style = NexaraTypography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.hub_empty_subtitle),
                style = NexaraTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(
                onClick = onCreateAgent,
                modifier = Modifier.testTag(UiTags.HUB_EMPTY_ADD_AGENT),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.hub_btn_add_agent))
            }
        }
    }
}

// =====================================================================================
// 辅助函数
// =====================================================================================

/**
 * 显示叠加层解析：预置未定制 → 当前 Locale 资源；已定制/非预置 → DB 原文。
 * 实时求值，跟随语言切换重组，不写 DB。
 */
@Composable
private fun resolveAgentDisplay(
    agent: Agent,
): Pair<String, String> {
    val fields = PresetAgentDisplay.resolve(agent)
    @Composable
    fun resolve(source: PresetAgentDisplay.TextSource): String = when (source) {
        is PresetAgentDisplay.TextSource.Resource -> stringResource(source.res)
        is PresetAgentDisplay.TextSource.Literal -> source.value
    }
    return resolve(fields.name) to resolve(fields.description)
}

internal data class AgentDisplayItem(val agent: Agent, val title: String, val subtitle: String)

internal fun filterAgentDisplays(items: List<AgentDisplayItem>, query: String): List<AgentDisplayItem> =
    if (query.isBlank()) items else items.filter {
        it.title.contains(query, ignoreCase = true) ||
            it.subtitle.contains(query, ignoreCase = true)
    }

/** 助手主题色向次要文字色靠拢 35%，头像底色不抢标题。 */
@Composable
internal fun agentAvatarColor(agent: Agent): Color {
    val parsed = try {
        Color(agent.color.toColorInt())
    } catch (_: Exception) {
        MaterialTheme.colorScheme.primary
    }
    return lerp(parsed, MaterialTheme.colorScheme.onSurfaceVariant, 0.35f)
}

internal fun agentIconVector(icon: String): ImageVector = when (icon) {
    "💻" -> Icons.Rounded.Code
    "📝" -> Icons.Rounded.EditNote
    "🌐", "A" -> Icons.Rounded.Translate
    else -> Icons.Rounded.SmartToy
}
