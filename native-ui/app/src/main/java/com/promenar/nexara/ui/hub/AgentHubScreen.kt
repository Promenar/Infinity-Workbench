package com.promenar.nexara.ui.hub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.PresetAgentDisplay
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.ui.common.toModelSelectionUiModel
import com.promenar.nexara.data.model.catalog.ModelMetadataResolver
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.settings.SettingsViewModel
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography

// =====================================================================================
// Route —— 拥有 ViewModel、localized preset display 映射与 overlay 状态
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AgentHubScreen(
    onNavigateToSessionList: (String) -> Unit,
    onNavigateToAgentEdit: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: AgentHubViewModel = viewModel(
        factory = AgentHubViewModel.factory(context.applicationContext as android.app.Application),
    )
    val agents by viewModel.agents.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val displayAgents = agents.map { agent ->
        val (title, subtitle) = resolveAgentDisplay(agent)
        AgentDisplayItem(agent, title, subtitle)
    }
    val visibleAgents = filterAgentDisplays(displayAgents, searchQuery)

    var showAddDialog by remember { mutableStateOf(false) }
    var agentToDelete by remember { mutableStateOf<String?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }

    val state = AgentHubScreenState(
        displayAgents = visibleAgents,
        searchQuery = searchQuery,
        searchActive = searchActive,
        showAddDialog = showAddDialog,
        pendingDeleteAgentId = agentToDelete,
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
            viewModel.deleteAgent(id)
            agentToDelete = null
        },
        onTogglePin = viewModel::togglePin,
        onEdit = onNavigateToAgentEdit,
        onOpenSession = onNavigateToSessionList,
    )

    AgentHubScreenContent(state = state, actions = actions)

    if (state.showAddDialog) {
        AddAgentDialog(
            onDismiss = actions.onCancelAdd,
            onConfirm = actions.onCreateAgent,
        )
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
    val resolver = remember { ModelMetadataResolver() }
    val modelItems = remember(allModels) {
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_dialog_add_title), style = NexaraTypography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.hub_dialog_label_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
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

        confirmButton = {
            Button(
                onClick = { onConfirm(name, description, model, systemPrompt) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.shared_btn_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_btn_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
)

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
    val onOpenSession: (String) -> Unit = {},
)

private data class HubSearchScrollAnchor(
    val agentId: String?,
    val fallbackIndex: Int,
    val offset: Int,
    val originalItemCount: Int,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AgentHubScreenContent(
    state: AgentHubScreenState,
    actions: AgentHubScreenActions,
) {
    val listState = rememberLazyListState()
    var searchScrollAnchor by remember { mutableStateOf<HubSearchScrollAnchor?>(null) }
    LaunchedEffect(state.searchActive, state.searchQuery, state.displayAgents.size) {
        if (state.searchActive && searchScrollAnchor == null) {
            val index = listState.firstVisibleItemIndex
            searchScrollAnchor = HubSearchScrollAnchor(
                agentId = state.displayAgents.getOrNull(index)?.agent?.id,
                fallbackIndex = index,
                offset = listState.firstVisibleItemScrollOffset,
                originalItemCount = state.displayAgents.size,
            )
        } else if (!state.searchActive && state.searchQuery.isBlank()) {
            val anchor = searchScrollAnchor ?: return@LaunchedEffect
            if (state.displayAgents.isNotEmpty()) {
                val stableIndex = anchor.agentId
                    ?.let { id -> state.displayAgents.indexOfFirst { it.agent.id == id } }
                    ?.takeIf { it >= 0 }
                val targetIndex = stableIndex
                    ?: anchor.fallbackIndex.coerceIn(0, state.displayAgents.lastIndex)
                listState.scrollToItem(targetIndex, anchor.offset)
                searchScrollAnchor = null
            } else if (anchor.originalItemCount == 0) {
                searchScrollAnchor = null
            }
        }
    }
    Scaffold(
        modifier = Modifier.testTag(UiTags.HUB_ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            Box(
                modifier = Modifier
                    .testTag(UiTags.HUB_SEARCH)
                    .semantics(mergeDescendants = true) {},
            ) {
                NexaraSearchTopBar(
                    title = null,
                    query = state.searchQuery,
                    searchActive = state.searchActive,
                    onQueryChange = actions.onSearch,
                    onSearchActiveChange = { active ->
                        if (!active) actions.onSearch("")
                        actions.onSearchActiveChange(active)
                    },
                    actions = {
                        IconButton(
                            onClick = actions.onRequestAdd,
                            modifier = Modifier.testTag(UiTags.HUB_ADD_AGENT)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.hub_btn_add_agent),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                )
            }
        }
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

        when {
            state.displayAgents.isEmpty() && state.searchQuery.isBlank() -> EmptyAgentState(
                onCreateAgent = actions.onRequestAdd,
                modifier = Modifier.padding(paddingValues),
            )

            state.displayAgents.isEmpty() -> HubSearchEmptyState(
                modifier = Modifier.padding(paddingValues),
            )

            else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.HUB_AGENT_LIST)
                    .padding(paddingValues)
                    .padding(horizontal = NexaraSpacing.ScreenHorizontal)
                    .bettboxListGroup(),
            ) {
                items(state.displayAgents, key = { item -> item.agent.id }) { item ->
                    val agent = item.agent
                    val parsedColor = try {
                        Color(agent.color.toColorInt())
                    } catch (_: Exception) {
                        MaterialTheme.colorScheme.primary
                    }

                    val iconVector = agentIconVector(agent.icon)

                    AgentCardItem(
                        agentId = agent.id,
                        icon = iconVector,
                        customImageUri = agent.avatarPath,
                        title = item.title,
                        subtitle = item.subtitle,
                        iconContainerColor = parsedColor,
                        isPinned = agent.isPinned,
                        onPin = { actions.onTogglePin(agent.id) },
                        onDelete = { actions.onRequestDelete(agent.id) },
                        onEdit = { actions.onEdit(agent.id) },
                        onClick = { actions.onOpenSession(agent.id) }
                    )
                }
            }
            }
        }
    }
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
// Agent Card —— 始终可见的动作入口 + DropdownMenu
// =====================================================================================

@Composable
fun AgentCardItem(
    agentId: String,
    icon: ImageVector,
    customImageUri: String? = null,
    title: String,
    subtitle: String,
    iconContainerColor: Color,
    isPinned: Boolean = false,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val pinnedStateDescription = stringResource(R.string.sessions_tag_pinned)
    val largeFont = LocalConfiguration.current.fontScale >= 1.5f

    SwipeableItem(
        onPin = onPin,
        onDelete = onDelete,
        onEdit = onEdit,
        isPinned = isPinned,
        shape = RectangleShape,
    ) {
        ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .testTag(UiTags.hubAgentCard(agentId))
                    .semantics {
                        if (isPinned) stateDescription = pinnedStateDescription
                    }
                    .clickable(onClick = onClick),
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                ),
                leadingContent = {
                    Box(
                        modifier = if (customImageUri != null) {
                            Modifier.testTag(UiTags.hubAgentCustomAvatar(agentId))
                        } else {
                            Modifier
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        AgentAvatar(
                            icon = icon,
                            customImageUri = customImageUri,
                            backgroundColor = iconContainerColor,
                            size = 56.dp,
                        )
                    }
                },
                headlineContent = {
                    Text(
                        text = title,
                        style = NexaraTypography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (largeFont) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = if (subtitle.isNotBlank()) {
                    {
                        Text(
                            text = subtitle,
                            style = NexaraTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (largeFont) 4 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else null,
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isPinned) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.testTag(UiTags.hubAgentActions(agentId))
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(R.string.hub_cd_agent_actions),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (isPinned) R.string.common_cd_unpin else R.string.common_cd_pin
                                            )
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onPin()
                                    },
                                    modifier = Modifier.testTag(UiTags.HUB_AGENT_MENU_PIN)
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.shared_btn_edit)) },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit()
                                    },
                                    modifier = Modifier.testTag(UiTags.HUB_AGENT_MENU_EDIT)
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.shared_btn_delete)) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    },
                                    modifier = Modifier.testTag(UiTags.HUB_AGENT_MENU_DELETE)
                                )
                            }
                        }
                    }
                }
            )
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

private fun agentIconVector(icon: String): ImageVector = when (icon) {
    "\uD83D\uDCBB" -> Icons.Rounded.Code
    "\uD83D\uDCDD" -> Icons.Rounded.EditNote
    "\uD83C\uDF10", "A" -> Icons.Rounded.Translate
    else -> Icons.Rounded.SmartToy
}
