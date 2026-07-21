package com.promenar.nexara.ui.hub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.common.ConfirmDialog
import com.promenar.nexara.ui.common.NexaraSearchTopBar
import com.promenar.nexara.ui.common.SwipeableItem
import com.promenar.nexara.ui.theme.NexaraSpacing
import java.text.SimpleDateFormat
import java.util.Date

// Route 持有 ViewModel 与瞬时 UI 状态，Content 只消费显式 state/actions。
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AgentSessionsScreen(
    agentId: String,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    onNavigateToAgentEdit: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SessionListViewModel = viewModel(
        factory = SessionListViewModel.factory(context.applicationContext as android.app.Application),
    )
    val sessions by viewModel.sessions.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val agentName by viewModel.agentName.collectAsState()
    val agentColor by viewModel.agentColor.collectAsState()
    val operationFailed by viewModel.operationFailed.collectAsState()
    var pendingDeleteSessionId by remember { mutableStateOf<String?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val operationFailedMessage = stringResource(R.string.sessions_operation_failed)

    LaunchedEffect(agentId) {
        viewModel.loadSessions(agentId)
    }
    LaunchedEffect(operationFailed) {
        if (operationFailed) {
            snackbarHostState.showSnackbar(operationFailedMessage)
            viewModel.dismissOperationFailure()
        }
    }

    val parsedAgentColor = runCatching {
        Color(android.graphics.Color.parseColor(agentColor))
    }.getOrElse { MaterialTheme.colorScheme.primary }

    AgentSessionsScreenContent(
        state = AgentSessionsScreenState(
            sessions = sessions,
            agentName = agentName,
            agentColor = parsedAgentColor,
            searchQuery = searchQuery,
            searchActive = searchActive,
            pendingDeleteSessionId = pendingDeleteSessionId,
        ),
        actions = AgentSessionsScreenActions(
            onSearch = viewModel::searchSessions,
            onSearchActiveChange = { searchActive = it },
            onNavigateBack = onNavigateBack,
            onNavigateToAgentEdit = onNavigateToAgentEdit,
            onCreateSession = {
                viewModel.createSession(agentId, onNavigateToChat)
            },
            onOpenSession = { sessionId ->
                viewModel.selectSession(sessionId)
                onNavigateToChat(sessionId)
            },
            onPinSession = viewModel::pinSession,
            onRequestDelete = { pendingDeleteSessionId = it },
            onCancelDelete = { pendingDeleteSessionId = null },
            onConfirmDelete = { sessionId ->
                viewModel.deleteSession(sessionId)
                pendingDeleteSessionId = null
            },
        ),
        snackbarHostState = snackbarHostState,
    )
}

internal data class AgentSessionsScreenState(
    val sessions: List<Session> = emptyList(),
    val agentName: String = "",
    val agentColor: Color = Color.Unspecified,
    val searchQuery: String = "",
    val searchActive: Boolean = false,
    val pendingDeleteSessionId: String? = null,
)

internal data class AgentSessionsScreenActions(
    val onSearch: (String) -> Unit = {},
    val onSearchActiveChange: (Boolean) -> Unit = {},
    val onNavigateBack: () -> Unit = {},
    val onNavigateToAgentEdit: () -> Unit = {},
    val onCreateSession: () -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
    val onPinSession: (String) -> Unit = {},
    val onRequestDelete: (String) -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: (String) -> Unit = {},
)

private data class SessionSearchScrollAnchor(
    val sessionId: String?,
    val fallbackIndex: Int,
    val offset: Int,
    val originalItemCount: Int,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSessionsScreenContent(
    state: AgentSessionsScreenState,
    actions: AgentSessionsScreenActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val listState = rememberLazyListState()
    var searchScrollAnchor by remember { mutableStateOf<SessionSearchScrollAnchor?>(null) }
    LaunchedEffect(state.searchActive, state.searchQuery, state.sessions.size) {
        if (state.searchActive && searchScrollAnchor == null) {
            val index = listState.firstVisibleItemIndex
            searchScrollAnchor = SessionSearchScrollAnchor(
                sessionId = state.sessions.getOrNull(index)?.id,
                fallbackIndex = index,
                offset = listState.firstVisibleItemScrollOffset,
                originalItemCount = state.sessions.size,
            )
        } else if (!state.searchActive && state.searchQuery.isBlank()) {
            val anchor = searchScrollAnchor ?: return@LaunchedEffect
            if (state.sessions.isNotEmpty()) {
                val stableIndex = anchor.sessionId
                    ?.let { id -> state.sessions.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 }
                val targetIndex = stableIndex
                    ?: anchor.fallbackIndex.coerceIn(0, state.sessions.lastIndex)
                listState.scrollToItem(targetIndex, anchor.offset)
                searchScrollAnchor = null
            } else if (anchor.originalItemCount == 0) {
                searchScrollAnchor = null
            }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            NexaraSearchTopBar(
                title = state.agentName,
                query = state.searchQuery,
                searchActive = state.searchActive,
                onQueryChange = actions.onSearch,
                onSearchActiveChange = { active ->
                    if (!active) actions.onSearch("")
                    actions.onSearchActiveChange(active)
                },
                onBack = actions.onNavigateBack,
                actions = {
                    IconButton(
                        onClick = actions.onNavigateToAgentEdit,
                        modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.sessions_cd_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = actions.onCreateSession,
                containerColor = if (state.agentColor != Color.Unspecified) state.agentColor else MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.sessions_cd_new),
                )
            }
        },
    ) { paddingValues ->
        ConfirmDialog(
            show = state.pendingDeleteSessionId != null,
            onDismiss = actions.onCancelDelete,
            onConfirm = {
                state.pendingDeleteSessionId?.let(actions.onConfirmDelete)
            },
            title = stringResource(R.string.session_settings_delete_title),
            description = stringResource(R.string.session_settings_delete_message),
            confirmLabel = stringResource(R.string.shared_btn_delete),
            destructive = true,
        )

        when {
            state.sessions.isEmpty() && state.searchQuery.isNotBlank() -> {
                SearchEmptyState(modifier = Modifier.padding(paddingValues))
            }

            state.sessions.isEmpty() -> {
                EmptySessionsState(
                    onCreateSession = actions.onCreateSession,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(
                        start = NexaraSpacing.ScreenHorizontal,
                        end = NexaraSpacing.ScreenHorizontal,
                        top = NexaraSpacing.Small,
                        bottom = 96.dp,
                    ),
                ) {
                    itemsIndexed(state.sessions, key = { _, session -> session.id }) { index, session ->
                        SessionListItem(
                            session = session,
                            showDivider = index < state.sessions.lastIndex,
                            actions = actions,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionListItem(
    session: Session,
    showDivider: Boolean,
    actions: AgentSessionsScreenActions,
) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val largeFont = configuration.fontScale >= 1.5f
    val formattedTime = remember(session.updatedAt, locale) {
        SimpleDateFormat("MMM d\nHH:mm", locale).format(Date(session.updatedAt))
    }
    val pinnedStateDescription = stringResource(R.string.sessions_tag_pinned)
    val pinActionLabel = stringResource(
        if (session.isPinned) R.string.common_cd_unpin else R.string.common_cd_pin,
    )
    val deleteActionLabel = stringResource(R.string.common_cd_delete)

    SwipeableItem(
        onPin = { actions.onPinSession(session.id) },
        onDelete = { actions.onRequestDelete(session.id) },
        isPinned = session.isPinned,
        shape = RectangleShape,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                modifier = Modifier.clickable(
                    onClick = { actions.onOpenSession(session.id) },
                )
                    .fillMaxWidth()
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .semantics(mergeDescendants = true) {
                        if (session.isPinned) stateDescription = pinnedStateDescription
                        customActions = listOf(
                            CustomAccessibilityAction(pinActionLabel) {
                                actions.onPinSession(session.id)
                                true
                            },
                            CustomAccessibilityAction(deleteActionLabel) {
                                actions.onRequestDelete(session.id)
                                true
                            },
                        )
                    },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                ),
                headlineContent = {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (largeFont) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = if (session.lastMessage.isNullOrBlank()) {
                    null
                } else {
                    {
                        Text(
                            text = session.lastMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (largeFont) 4 else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                trailingContent = {
                    Column(
                        modifier = Modifier.widthIn(max = 96.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        if (session.isPinned) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = NexaraSpacing.ScreenHorizontal),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(NexaraSpacing.XXLarge),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(NexaraSpacing.Large))
            Text(
                text = stringResource(R.string.common_search_no_results),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptySessionsState(
    onCreateSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(NexaraSpacing.XXLarge),
        ) {
            Icon(
                imageVector = Icons.Rounded.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(NexaraSpacing.Large))
            Text(
                text = stringResource(R.string.sessions_empty_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(NexaraSpacing.Small))
            Text(
                text = stringResource(R.string.sessions_empty_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(NexaraSpacing.XLarge))
            FilledTonalButton(
                onClick = onCreateSession,
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(NexaraSpacing.Small))
                Text(stringResource(R.string.sessions_btn_new))
            }
        }
    }
}
